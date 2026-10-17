package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaomiFeaturesTest {
    private class FakeChannel : XiaomiCommandChannel {
        val requests = mutableListOf<String>()

        override suspend fun send(command: XiaomiProto.Command) {
            requests += "send ${command.type}/${command.subtype}"
        }

        override suspend fun request(command: XiaomiProto.Command, responseSubtype: Int): XiaomiProto.Command {
            requests += "${command.type}/${command.subtype}"
            return XiaomiProto.Command.newBuilder()
                .setType(XiaomiCommands.TYPE_SYSTEM)
                .setSubtype(XiaomiCommands.SYSTEM_BATTERY)
                .setSystem(XiaomiProto.System.newBuilder().setPower(XiaomiProto.Power.newBuilder().setBattery(XiaomiProto.Battery.newBuilder().setLevel(80))))
                .build()
        }
    }

    private val devicePush: XiaomiProto.Command = XiaomiProto.Command.newBuilder()
        .setType(XiaomiCommands.TYPE_SYSTEM)
        .setSubtype(XiaomiCommands.SYSTEM_DEVICE_STATE)
        .setSystem(XiaomiProto.System.newBuilder().setDeviceState(XiaomiProto.DeviceState.newBuilder().setChargingState(1).setWearingState(1)))
        .build()

    @Test
    fun deviceStatePushBurstsAskTheBatteryOnce() = runBlocking {
        val channel = FakeChannel()
        val events = mutableListOf<WearableEvent>()
        val launched = mutableListOf<suspend () -> Unit>()
        var now = 1_000_000L
        val features = XiaomiFeatures(channel, { events += it }, {}, { now }) { launched += it }

        repeat(5) { features.handleCommand(devicePush) }
        assertEquals(1, launched.size) // one request in flight, the rest of the burst coalesced
        launched.removeAt(0).invoke()
        assertEquals(listOf("2/1"), channel.requests)
        assertEquals(80, events.filterIsInstance<WearableEvent.Battery>().single().level)

        // Still within the interval: no new request even though the first one finished.
        now += XiaomiFeatures.BATTERY_REQUEST_INTERVAL_MS - 1
        features.handleCommand(devicePush)
        assertEquals(0, launched.size)

        now += 1
        features.handleCommand(devicePush)
        assertEquals(1, launched.size)
        assertEquals(7, events.filterIsInstance<WearableEvent.DeviceState>().size) // every push still reports its state
    }
}
