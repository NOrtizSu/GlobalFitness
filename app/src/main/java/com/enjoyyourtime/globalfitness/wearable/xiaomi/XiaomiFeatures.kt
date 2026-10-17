// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.wearable.WearableAlarms
import com.enjoyyourtime.globalfitness.wearable.WearableDeviceInfo
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import java.util.TimeZone

/** An authenticated Xiaomi command channel (SPP session or BLE characteristics). */
interface XiaomiCommandChannel {
    suspend fun send(command: XiaomiProto.Command)

    /** Sends [command] and waits for the device's command with the same type and [responseSubtype]. */
    suspend fun request(command: XiaomiProto.Command, responseSubtype: Int = command.subtype): XiaomiProto.Command
}

/**
 * The device functions of the Xiaomi protobuf protocol, shared by every transport: what
 * Gadgetbridge's XiaomiSystemService, XiaomiHealthService (realtime stats), XiaomiScheduleService
 * (alarms) and XiaomiNotificationService do, turned into [WearableEvent]s.
 *
 * Runs on the owning session's single thread; [launch] starts follow-up requests there.
 */
class XiaomiFeatures(
    private val channel: XiaomiCommandChannel,
    private val emit: (WearableEvent) -> Unit,
    private val log: (String) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    /** True while the realtime stats stream is wanted (Gadgetbridge failsafe stops unwanted ones). */
    var liveActive = false
        private set

    suspend fun refreshStatus(includeInfo: Boolean) {
        val requests = buildList {
            if (includeInfo) add(XiaomiCommands.getDeviceInfo())
            add(XiaomiCommands.getDeviceState())
            add(XiaomiCommands.getBattery())
        }
        for (request in requests) {
            try {
                handleCommand(channel.request(request))
            } catch (e: WearableException) {
                if (e.failure != WearableFailure.REQUEST_TIMEOUT) throw e
                log("No answer to ${request.type}/${request.subtype}")
            }
        }
    }

    suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean) =
        channel.send(XiaomiCommands.setTime(nowMillis, timeZone, is24Hour))

    suspend fun findDevice(start: Boolean) = channel.send(XiaomiCommands.findWatch(start))

    suspend fun setLiveHeartRate(enabled: Boolean) {
        // Set before sending: the first 8/47 can arrive before send() returns.
        liveActive = enabled
        try {
            channel.send(XiaomiCommands.realtimeStats(enabled))
        } catch (e: WearableException) {
            if (enabled) liveActive = false
            throw e
        }
    }

    suspend fun sendNotification(notification: BandNotification) = channel.send(XiaomiCommands.notification(notification))

    suspend fun dismissNotification(id: Int, packageName: String) =
        channel.send(XiaomiCommands.dismissNotification(id, packageName))

    suspend fun loadAlarms(): WearableAlarms {
        val reply = channel.request(XiaomiCommands.getAlarms())
        val alarms = if (reply.schedule.hasAlarms()) reply.schedule.alarms else XiaomiProto.Alarms.getDefaultInstance()
        val result = WearableAlarms(
            XiaomiCommands.parseAlarms(alarms),
            if (alarms.hasMaxAlarms()) alarms.maxAlarms else null,
        )
        emit(WearableEvent.Alarms(result.alarms, result.maxAlarms))
        return result
    }

    suspend fun saveAlarm(id: Int?, spec: AlarmSpec) {
        if (id == null) {
            try {
                channel.request(XiaomiCommands.createAlarm(spec))
            } catch (e: WearableException) {
                if (e.failure != WearableFailure.REQUEST_TIMEOUT) throw e
                log("The device did not confirm the new alarm")
            }
        } else {
            channel.send(XiaomiCommands.editAlarm(id, spec))
        }
    }

    suspend fun deleteAlarm(id: Int) = channel.send(XiaomiCommands.deleteAlarms(listOf(id)))

    /** Folds a command from the device (answer or push) into events. Never throws. */
    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.type) {
            XiaomiCommands.TYPE_SYSTEM -> handleSystem(cmd)
            XiaomiCommands.TYPE_HEALTH ->
                if (cmd.subtype == XiaomiCommands.HEALTH_REALTIME_STATS_EVENT && cmd.health.hasRealTimeStats()) {
                    onRealtimeStats(cmd.health.realTimeStats)
                }
            XiaomiCommands.TYPE_SCHEDULE -> when (cmd.subtype) {
                XiaomiCommands.SCHEDULE_ALARMS_GET -> if (cmd.schedule.hasAlarms()) {
                    val alarms = cmd.schedule.alarms
                    emit(WearableEvent.Alarms(XiaomiCommands.parseAlarms(alarms), if (alarms.hasMaxAlarms()) alarms.maxAlarms else null))
                }
                // Alarm created on the device itself: reload the list.
                XiaomiCommands.SCHEDULE_ALARMS_CREATE -> launch { loadAlarms() }
            }
            else -> Unit
        }
    }

    private fun handleSystem(cmd: XiaomiProto.Command) {
        val system = cmd.system
        when (cmd.subtype) {
            XiaomiCommands.SYSTEM_DEVICE_INFO -> if (system.hasDeviceInfo()) {
                val info = system.deviceInfo
                emit(
                    WearableEvent.Info(
                        WearableDeviceInfo(
                            model = info.model.ifEmpty { null },
                            firmware = info.firmware.ifEmpty { null },
                            serial = info.serialNumber.ifEmpty { null },
                        ),
                    ),
                )
            }
            XiaomiCommands.SYSTEM_BATTERY -> if (system.hasPower() && system.power.hasBattery()) {
                val battery = system.power.battery
                emit(
                    WearableEvent.Battery(
                        level = if (battery.hasLevel()) battery.level else null,
                        charging = if (battery.hasState()) chargingFromRaw(battery.state) else null,
                    ),
                )
            }
            XiaomiCommands.SYSTEM_DEVICE_STATE_GET -> {
                if (!system.hasBasicDeviceState()) {
                    launch { handleCommand(channel.request(XiaomiCommands.getBattery())) }
                    return
                }
                val state = system.basicDeviceState
                emit(
                    WearableEvent.DeviceState(
                        worn = state.isWorn,
                        charging = state.isCharging,
                        batteryLevel = if (state.hasBatteryLevel()) state.batteryLevel else null,
                    ),
                )
            }
            XiaomiCommands.SYSTEM_DEVICE_STATE -> {
                if (system.hasDeviceState()) {
                    val state = system.deviceState
                    emit(
                        WearableEvent.DeviceState(
                            charging = if (state.hasChargingState()) chargingFromRaw(state.chargingState) else null,
                            worn = when (state.wearingState) {
                                1 -> true
                                2 -> false
                                else -> null
                            },
                        ),
                    )
                }
                launch { handleCommand(channel.request(XiaomiCommands.getBattery())) }
            }
            XiaomiCommands.SYSTEM_FIND_WATCH ->
                if (system.hasFindDevice() && system.findDevice == XiaomiCommands.FIND_STOP) emit(WearableEvent.FindStopped)
            XiaomiCommands.SYSTEM_FIND_PHONE -> emit(WearableEvent.FindPhone)
        }
    }

    private fun onRealtimeStats(stats: XiaomiProto.RealTimeStats) {
        if (!liveActive) {
            // Gadgetbridge failsafe: nobody is listening, stop the stream.
            launch { channel.send(XiaomiCommands.realtimeStats(false)) }
            return
        }
        emit(WearableEvent.HeartRate(stats.heartRate))
        emit(
            WearableEvent.Activity(
                steps = if (stats.hasSteps()) stats.steps else null,
                calories = if (stats.hasCalories()) stats.calories else null,
                standingHours = if (stats.hasStandingHours()) stats.standingHours else null,
            ),
        )
    }

    /** XiaomiSystemService: 1 charging, 2 and 3 not charging. */
    private fun chargingFromRaw(raw: Int): Boolean? = when (raw) {
        1 -> true
        2, 3 -> false
        else -> null
    }
}
