package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.GattLink
import com.enjoyyourtime.globalfitness.wearable.standard.StandardGatt
import com.enjoyyourtime.globalfitness.wearable.standard.StandardUuids
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A session over a BLE GATT link ([GattLink], implemented by the scales' `GattSession`, which
 * already serializes operations and matches callbacks). Notifications are handed to
 * [onNotification] on the session's single thread; [expect] lets a request wait for its answer.
 *
 * Subclasses implement [onLinkReady] (subscribe, authenticate) and [onNotification].
 */
abstract class GattWearableSession(env: WearableSessionEnv, private val requestedMtu: Int = 247) : BaseWearableSession(env) {
    protected val link: GattLink = env.links.gatt(env.address)

    /** Negotiated ATT MTU (23 until the exchange succeeds). */
    protected var mtu: Int = DEFAULT_MTU
        private set

    private class Waiter(val characteristic: UUID, val predicate: (ByteArray) -> Boolean, val deferred: CompletableDeferred<ByteArray>)

    private val waiters = ArrayList<Waiter>()
    private var watchJobs = emptyList<Job>()

    override fun releaseLinks() = link.close()

    override fun onClosed(reason: WearableException) {
        watchJobs.forEach { it.cancel() }
        synchronized(waiters) {
            waiters.forEach { it.deferred.completeExceptionally(reason) }
            waiters.clear()
        }
    }

    override suspend fun open() = withContext(serial) {
        try {
            withTimeout(env.timeouts.connect) {
                link.connect()
                link.discoverServices()
            }
        } catch (e: TimeoutCancellationException) {
            throw fail(WearableException(WearableFailure.CONNECT_FAILED, "connect timeout", e))
        } catch (e: CancellationException) {
            fail(WearableException(WearableFailure.CLOSED))
            throw e
        } catch (e: Exception) {
            throw fail(WearableException(WearableFailure.CONNECT_FAILED, "connect failed", e))
        }
        log("GATT connected, ${link.characteristics().size} characteristics")
        watchJobs = listOf(
            scope.launch { link.notifications.collect { dispatch(it.characteristic, it.value) } },
            scope.launch {
                link.isConnected.first { !it }
                fail(linkLost(null, "disconnected"))
            },
        )
        if (requestedMtu > DEFAULT_MTU) negotiateMtu(requestedMtu)
        try {
            onLinkReady()
        } catch (e: WearableException) {
            throw fail(e)
        } catch (e: CancellationException) {
            fail(WearableException(WearableFailure.CLOSED))
            throw e
        } catch (e: Exception) {
            throw fail(WearableException(WearableFailure.AUTH_REJECTED, "handshake error: ${e.javaClass.simpleName}", e))
        }
    }

    /** Subscribe and authenticate; throw [WearableException] on failure. Runs on the session thread. */
    protected abstract suspend fun onLinkReady()

    /** Asks for a larger ATT MTU; keeps the current one when the exchange fails. Returns the MTU in use. */
    protected suspend fun negotiateMtu(requested: Int): Int {
        val negotiated = try {
            link.requestMtu(requested)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (negotiated != null && negotiated >= DEFAULT_MTU) {
            mtu = negotiated
            onMtuChanged(negotiated)
        }
        log("MTU $mtu")
        return mtu
    }

    /** Hook for protocols whose framing depends on the MTU. */
    protected open fun onMtuChanged(mtu: Int) = Unit

    /** Reads the standard Device Information service (missing characteristics are skipped). */
    protected suspend fun readDeviceInformation(): WearableDeviceInfo {
        val dis = StandardUuids.DEVICE_INFORMATION
        suspend fun text(characteristic: UUID): String? =
            if (hasCharacteristic(dis, characteristic)) StandardGatt.parseString(read(dis, characteristic)) else null
        return WearableDeviceInfo(
            manufacturer = text(StandardUuids.MANUFACTURER_NAME),
            model = text(StandardUuids.MODEL_NUMBER),
            serial = text(StandardUuids.SERIAL_NUMBER),
            hardware = text(StandardUuids.HARDWARE_REVISION),
            firmware = text(StandardUuids.FIRMWARE_REVISION) ?: text(StandardUuids.SOFTWARE_REVISION),
        )
    }

    /** Every notification (and indication) that no [expect] waiter consumed. Must never throw. */
    protected abstract fun onNotification(characteristic: UUID, value: ByteArray)

    private fun dispatch(characteristic: UUID, value: ByteArray) {
        val waiter = synchronized(waiters) {
            waiters.firstOrNull { it.characteristic == characteristic && safePredicate(it, value) }?.also { waiters.remove(it) }
        }
        if (waiter != null) {
            waiter.deferred.complete(value)
            return
        }
        try {
            onNotification(characteristic, value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Notification on $characteristic dropped: ${e.javaClass.simpleName}")
        }
    }

    private fun safePredicate(waiter: Waiter, value: ByteArray): Boolean = try {
        waiter.predicate(value)
    } catch (e: Exception) {
        false
    }

    /**
     * Registers interest in the next notification of [characteristic] that satisfies [predicate];
     * register before writing the request so a fast answer is not missed.
     */
    protected fun expect(characteristic: UUID, predicate: (ByteArray) -> Boolean = { true }): CompletableDeferred<ByteArray> {
        ensureOpen()
        val deferred = CompletableDeferred<ByteArray>()
        synchronized(waiters) { waiters += Waiter(characteristic, predicate, deferred) }
        return deferred
    }

    /** Waits for an [expect]ed answer; throws [WearableFailure.REQUEST_TIMEOUT] after [timeoutMs]. */
    protected suspend fun await(deferred: CompletableDeferred<ByteArray>, timeoutMs: Long = env.timeouts.request): ByteArray {
        try {
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(waiters) { waiters.removeAll { it.deferred === deferred } }
            throw WearableException(WearableFailure.REQUEST_TIMEOUT, "no answer", e)
        }
    }

    protected fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = link.hasCharacteristic(service, characteristic)

    /** The service that holds [characteristic], when the device has it. */
    protected fun serviceOf(characteristic: UUID): UUID? = link.characteristics().firstOrNull { it.characteristic == characteristic }?.service

    protected suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean {
        ensureOpen()
        return gattCall { link.enableNotifications(service, characteristic) }
    }

    /** Writes one value; false when the device refused it or the characteristic is missing. */
    protected suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean = true): Boolean {
        ensureOpen()
        return gattCall { link.write(service, characteristic, value, withResponse) }
    }

    protected suspend fun read(service: UUID, characteristic: UUID): ByteArray? {
        ensureOpen()
        return gattCall { link.read(service, characteristic) }
    }

    private suspend fun <T> gattCall(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // GattSession throws when an operation times out: the link is wedged and already closed.
        throw fail(linkLost(e, "GATT operation failed"))
    }

    companion object {
        const val DEFAULT_MTU = 23
    }
}
