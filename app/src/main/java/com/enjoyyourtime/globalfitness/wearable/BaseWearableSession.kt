package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Lifecycle shared by every session: a single-threaded scope (incoming data, timers and requests
 * form one serialized queue), the event channel, and closing exactly once with a reason.
 * Functions the driver does not implement throw [WearableFailure.NOT_SUPPORTED].
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseWearableSession(protected val env: WearableSessionEnv) : WearableSession {
    protected val serial = Dispatchers.Default.limitedParallelism(1)
    private val job = SupervisorJob(env.scope.coroutineContext[Job])
    protected val scope = CoroutineScope(env.scope.coroutineContext + job + serial)

    private val eventChannel = Channel<WearableEvent>(Channel.UNLIMITED)
    override val events: Flow<WearableEvent> = eventChannel.receiveAsFlow()

    @Volatile
    private var closeReason: WearableException? = null

    @Volatile
    protected var closing = false
        private set

    private val closedSignal = CompletableDeferred<WearableException>()

    override val isOpen: Boolean get() = closeReason == null

    override suspend fun awaitClosed(): WearableException = closedSignal.await()

    override fun close() {
        closing = true
        releaseLinks()
        if (job.isActive) scope.launch { fail(WearableException(WearableFailure.CLOSED)) } else fail(WearableException(WearableFailure.CLOSED))
    }

    protected fun log(text: String) = env.log(text)

    protected fun emit(event: WearableEvent) {
        eventChannel.trySend(event)
    }

    protected fun ensureOpen() {
        closeReason?.let { throw it }
    }

    /** A link failure: [WearableFailure.CLOSED] when we are closing anyway. */
    protected fun linkLost(cause: Throwable?, detail: String = "link lost"): WearableException =
        if (closing) WearableException(WearableFailure.CLOSED) else WearableException(WearableFailure.LINK_LOST, detail, cause)

    /** Releases the transport (idempotent, any thread). */
    protected abstract fun releaseLinks()

    /** Hook for subclasses: complete or fail their own waiters. Runs once, inside [fail]. */
    protected open fun onClosed(reason: WearableException) = Unit

    /** Closes everything once; returns the (first) reason so callers can `throw fail(...)`. */
    @Synchronized
    protected fun fail(reason: WearableException): WearableException {
        closeReason?.let { return it }
        closeReason = reason
        if (reason.failure != WearableFailure.CLOSED) log("Session ended: ${reason.failure} (${reason.message})")
        releaseLinks()
        try {
            onClosed(reason)
        } finally {
            eventChannel.close()
            closedSignal.complete(reason)
            scope.cancel()
        }
        return reason
    }

    protected fun unsupported(): Nothing = throw WearableException(WearableFailure.NOT_SUPPORTED)

    override suspend fun refreshStatus(includeInfo: Boolean): Unit = unsupported()

    override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean): Unit = unsupported()

    override suspend fun findDevice(start: Boolean): Unit = unsupported()

    override suspend fun setLiveHeartRate(enabled: Boolean): Unit = unsupported()

    override suspend fun sendNotification(notification: BandNotification): Unit = unsupported()

    override suspend fun dismissNotification(id: Int, packageName: String): Unit = unsupported()

    override suspend fun loadAlarms(): WearableAlarms = unsupported()

    override suspend fun saveAlarm(id: Int?, spec: AlarmSpec): Unit = unsupported()

    override suspend fun deleteAlarm(id: Int): Unit = unsupported()
}
