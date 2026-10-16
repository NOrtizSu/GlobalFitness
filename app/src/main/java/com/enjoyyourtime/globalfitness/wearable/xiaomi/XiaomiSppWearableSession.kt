// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import com.enjoyyourtime.globalfitness.band.xiaomi.BandException
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiAuth
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiSession
import com.enjoyyourtime.globalfitness.wearable.BaseWearableSession
import com.enjoyyourtime.globalfitness.wearable.ConfiguredWearable
import com.enjoyyourtime.globalfitness.wearable.WearableAlarms
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Xiaomi protobuf device over Bluetooth Classic (RFCOMM / SPP): the existing [XiaomiSession]
 * (Gadgetbridge XiaomiSppSupport, SPP V1/V2, auth, request correlation) behind the generic
 * [com.enjoyyourtime.globalfitness.wearable.WearableSession] interface. The Smart Band 9 path is
 * exactly the one of the original direct band mode.
 */
class XiaomiSppWearableSession(
    env: WearableSessionEnv,
    private val model: XiaomiModel,
    private val sessionTimeouts: XiaomiSession.Timeouts = XiaomiSession.Timeouts(),
) : BaseWearableSession(env), XiaomiCommandChannel {
    @Volatile
    private var link: BandLink? = null

    @Volatile
    private var session: XiaomiSession? = null

    private val features = XiaomiFeatures(this, ::emit, ::log) { block ->
        scope.launch {
            try {
                block()
            } catch (e: WearableException) {
                log("Follow-up request failed: ${e.failure}")
            }
        }
    }

    override val label: String get() = session?.protocolLabel ?: "SPP"

    override fun releaseLinks() {
        session?.close() ?: link?.close()
    }

    override suspend fun open() = withContext(serial) {
        val key = (env.authKey?.let { AuthKey.parse(it) } as? AuthKeyResult.Valid)?.key
            ?: throw fail(WearableException(WearableFailure.NO_KEY))
        val l = try {
            env.links.spp(env.address)
        } catch (e: WearableException) {
            throw fail(e)
        }
        link = l
        if (!isOpen) {
            l.close()
            throw WearableException(WearableFailure.CLOSED)
        }
        val deviceId = env.config[ConfiguredWearable.CONFIG_XIAOMI_DEVICE_ID]?.trim()?.takeIf { model.usesDeviceId && it.isNotEmpty() }
        val s = XiaomiSession(l, XiaomiAuth(key, defaultDeviceId = deviceId), env.companion, scope, env.log, sessionTimeouts)
        session = s
        try {
            s.handshake()
        } catch (e: BandException) {
            throw fail(e.toWearable())
        } catch (e: CancellationException) {
            fail(WearableException(WearableFailure.CLOSED))
            throw e
        }
        scope.launch { s.commands.collect { features.handleCommand(it) } }
        scope.launch { fail(s.awaitClosed().toWearable()) }
        Unit
    }

    // ---- XiaomiCommandChannel ----

    override suspend fun send(command: XiaomiProto.Command) {
        val s = session ?: throw WearableException(WearableFailure.CLOSED)
        try {
            s.send(command)
        } catch (e: BandException) {
            throw e.toWearable()
        }
    }

    override suspend fun request(command: XiaomiProto.Command, responseSubtype: Int): XiaomiProto.Command {
        val s = session ?: throw WearableException(WearableFailure.CLOSED)
        try {
            return s.request(command, responseSubtype, env.timeouts.request)
        } catch (e: BandException) {
            throw e.toWearable()
        }
    }

    // ---- WearableSession ----

    override suspend fun refreshStatus(includeInfo: Boolean) = withContext(serial) { features.refreshStatus(includeInfo) }

    override suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) =
        withContext(serial) { features.setTime(nowMillis, timeZone, is24Hour) }

    override suspend fun findDevice(start: Boolean) = withContext(serial) { features.findDevice(start) }

    override suspend fun setLiveHeartRate(enabled: Boolean) = withContext(serial) { features.setLiveHeartRate(enabled) }

    override suspend fun sendNotification(notification: BandNotification) =
        withContext(serial) { features.sendNotification(notification) }

    override suspend fun dismissNotification(id: Int, packageName: String) =
        withContext(serial) { features.dismissNotification(id, packageName) }

    override suspend fun loadAlarms(): WearableAlarms = withContext(serial) { features.loadAlarms() }

    override suspend fun saveAlarm(id: Int?, spec: AlarmSpec) = withContext(serial) { features.saveAlarm(id, spec) }

    override suspend fun deleteAlarm(id: Int) = withContext(serial) { features.deleteAlarm(id) }
}

/** Same failure, generic type (the names of [com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure] are a subset). */
internal fun BandException.toWearable(): WearableException {
    val failure = WearableFailure.entries.firstOrNull { it.name == this.failure.name } ?: WearableFailure.LINK_LOST
    return WearableException(failure, failure.name, this)
}
