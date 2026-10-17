package com.enjoyyourtime.globalfitness.scale

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Coroutine wrapper over [BluetoothGatt] with a serialized operation queue: only one GATT
 * operation is in flight at a time (Android silently drops concurrent ones). Each operation
 * waits for its own callback (matched by kind and characteristic, so a late or spontaneous
 * callback never completes the wrong operation), bounded by [opTimeoutMs]. A timed-out operation
 * means the link is wedged: the connection is dropped and [failure] explains why.
 *
 * Handles both the API 33+ callback/write signatures and the legacy (deprecated) ones.
 */
@SuppressLint("MissingPermission")
class GattSession(
    private val context: Context,
    override val address: String,
    private val opTimeoutMs: Long = 5_000L,
) : GattLink {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val opMutex = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: PendingOp? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var servicesWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var closed = false

    private val _connected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()

    // Unlimited: a notification burst (history download) must never be dropped.
    private val _notifications = Channel<GattNotification>(Channel.UNLIMITED)
    override val notifications: Flow<GattNotification> = _notifications.receiveAsFlow()

    @Volatile private var failureNotice: ScaleNotice? = null
    override val failure: ScaleNotice? get() = failureNotice

    private class Result(val status: Int, val value: ByteArray? = null, val mtu: Int = 0)

    private enum class OpKind { MTU, DESCRIPTOR, WRITE, READ }

    /** The in-flight operation: only its own callback may complete it. */
    private class PendingOp(val kind: OpKind, val uuid: UUID?, val deferred: CompletableDeferred<Result>) {
        fun completeIf(kind: OpKind, uuid: UUID?, result: Result) {
            if (kind == this.kind && (this.uuid == null || this.uuid == uuid)) deferred.complete(result)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectWaiter?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                _connected.value = false
                val error = GattException(errorNotice(status))
                connectWaiter?.completeExceptionally(error)
                servicesWaiter?.completeExceptionally(error)
                pending?.deferred?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesWaiter?.complete(Unit)
            } else {
                servicesWaiter?.completeExceptionally(GattException(ScaleNotice(ScaleNotice.Kind.SERVICES_READ_FAILED, listOf(status))))
            }
        }

        // Some stacks also call this spontaneously (peer-initiated exchange): only a pending MTU
        // request takes it.
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pending?.completeIf(OpKind.MTU, null, Result(status, mtu = mtu))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.completeIf(OpKind.DESCRIPTOR, descriptor.characteristic?.uuid, Result(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.completeIf(OpKind.WRITE, characteristic.uuid, Result(status))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pending?.completeIf(OpKind.READ, characteristic.uuid, Result(status, value.copyOf()))
        }

        @Deprecated("Legacy callback for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.completeIf(OpKind.READ, characteristic.uuid, Result(status, characteristic.value?.copyOf()))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            _notifications.trySend(GattNotification(characteristic.uuid, value.copyOf()))
        }

        @Deprecated("Legacy callback for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            _notifications.trySend(GattNotification(characteristic.uuid, value.copyOf()))
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun connect() {
        if (!hasConnectPermission()) throw GattException(ScaleNotice(ScaleNotice.Kind.MISSING_NEARBY_PERMISSION))
        val adapter = manager?.adapter ?: throw GattException(ScaleNotice(ScaleNotice.Kind.BLUETOOTH_UNAVAILABLE))
        if (!adapter.isEnabled) throw GattException(ScaleNotice(ScaleNotice.Kind.BLUETOOTH_OFF))
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw GattException(ScaleNotice(ScaleNotice.Kind.INVALID_ADDRESS, listOf(address)))
        }
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw GattException(ScaleNotice(ScaleNotice.Kind.MISSING_NEARBY_PERMISSION))
        } ?: throw GattException(ScaleNotice(ScaleNotice.Kind.CONNECT_NOT_STARTED))
        gatt = g
        try {
            waiter.await()
        } finally {
            connectWaiter = null
        }
    }

    override suspend fun discoverServices() {
        val g = gatt ?: throw GattException(ScaleNotice(ScaleNotice.Kind.NOT_CONNECTED))
        val waiter = CompletableDeferred<Unit>()
        servicesWaiter = waiter
        try {
            if (!g.discoverServices()) throw GattException(ScaleNotice(ScaleNotice.Kind.SERVICE_DISCOVERY_FAILED))
            waiter.await()
        } finally {
            servicesWaiter = null
        }
    }

    /**
     * Runs one GATT operation: [start] must return true when the request was accepted. When its
     * callback does not arrive within [opTimeoutMs] the stack is wedged (it would refuse every
     * later operation): the connection is dropped and [GattException] is thrown.
     */
    private suspend fun op(kind: OpKind, uuid: UUID?, start: (BluetoothGatt) -> Boolean): Result? {
        var timedOut = false
        val result = opMutex.withLock {
            val g = gatt ?: return@withLock null
            if (!_connected.value || closed) return@withLock null
            val waiter = CompletableDeferred<Result>()
            pending = PendingOp(kind, uuid, waiter)
            try {
                val started = try {
                    start(g)
                } catch (e: SecurityException) {
                    false
                }
                if (!started) return@withLock null
                val r = withTimeoutOrNull(opTimeoutMs) {
                    try {
                        waiter.await()
                    } catch (e: GattException) {
                        null
                    }
                }
                if (r == null && !waiter.isCompleted) timedOut = true
                r
            } finally {
                pending = null
            }
        }
        if (timedOut) {
            failureNotice = ScaleNotice(ScaleNotice.Kind.SCALE_STOPPED_RESPONDING)
            close()
            throw GattException(ScaleNotice(ScaleNotice.Kind.SCALE_STOPPED_RESPONDING))
        }
        return result
    }

    override suspend fun requestMtu(mtu: Int): Int? =
        op(OpKind.MTU, null) { it.requestMtu(mtu) }?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS }?.mtu

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(characteristic)

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        characteristic(service, characteristic) != null

    override fun characteristics(): List<GattCharInfo> =
        gatt?.services.orEmpty().flatMap { s ->
            s.characteristics.orEmpty().map { c ->
                val p = c.properties
                GattCharInfo(
                    service = s.uuid,
                    characteristic = c.uuid,
                    canNotify = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                    canWrite = p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                )
            }
        }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean {
        val ch = characteristic(service, characteristic) ?: return false
        val g = gatt ?: return false
        val enabled = try {
            g.setCharacteristicNotification(ch, true)
        } catch (e: SecurityException) {
            false
        }
        if (!enabled) return false
        val descriptor = ch.getDescriptor(CCCD) ?: return true // some clones notify without a CCCD
        val value = when {
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val result = op(OpKind.DESCRIPTOR, characteristic) { gg -> writeDescriptorCompat(gg, descriptor, value) }
        return result?.status == BluetoothGatt.GATT_SUCCESS
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
        val ch = characteristic(service, characteristic) ?: return false
        val supportsNoResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val supportsResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = when {
            withResponse && supportsResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            !withResponse && supportsNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            supportsResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            supportsNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> return false
        }
        val result = op(OpKind.WRITE, characteristic) { g -> writeCharacteristicCompat(g, ch, value, type) }
        return result?.status == BluetoothGatt.GATT_SUCCESS
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray? {
        val ch = characteristic(service, characteristic) ?: return null
        val result = op(OpKind.READ, characteristic) { g -> g.readCharacteristic(ch) } ?: return null
        return result.value.takeIf { result.status == BluetoothGatt.GATT_SUCCESS }
    }

    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = type
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }

    override fun close() {
        if (closed) return
        closed = true
        _connected.value = false
        val g = gatt ?: return
        gatt = null
        val error = GattException(ScaleNotice(ScaleNotice.Kind.CONNECTION_CLOSED))
        connectWaiter?.completeExceptionally(error)
        servicesWaiter?.completeExceptionally(error)
        pending?.deferred?.completeExceptionally(error)
        _notifications.close()
        try {
            g.disconnect()
        } catch (_: SecurityException) {
        }
        try {
            g.close()
        } catch (_: SecurityException) {
        }
    }

    companion object {
        val CCCD: UUID = Bytes.uuid16(0x2902)

        fun errorNotice(status: Int): ScaleNotice = when (status) {
            BluetoothGatt.GATT_SUCCESS -> ScaleNotice(ScaleNotice.Kind.SCALE_DISCONNECTED)
            8 -> ScaleNotice(ScaleNotice.Kind.CONNECTION_LOST_OUT_OF_RANGE)
            19 -> ScaleNotice(ScaleNotice.Kind.SCALE_CLOSED_CONNECTION)
            22 -> ScaleNotice(ScaleNotice.Kind.PHONE_CANCELLED_CONNECTION)
            62 -> ScaleNotice(ScaleNotice.Kind.CONNECTION_NOT_ESTABLISHED)
            133 -> ScaleNotice(ScaleNotice.Kind.GATT_ERROR_133)
            else -> ScaleNotice(ScaleNotice.Kind.GATT_ERROR, listOf(status))
        }
    }
}
