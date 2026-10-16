// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.SppChannel
import com.enjoyyourtime.globalfitness.band.xiaomi.SppFrame
import com.enjoyyourtime.globalfitness.band.xiaomi.SppPacketV2
import com.enjoyyourtime.globalfitness.band.xiaomi.SppProtocolV2
import com.enjoyyourtime.globalfitness.band.xiaomi.SppReceiveBuffer
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiAuth
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCommands
import com.enjoyyourtime.globalfitness.wearable.ConfiguredWearable
import com.enjoyyourtime.globalfitness.wearable.GattWearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableAlarms
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** BLE characteristic sets of Xiaomi devices (Gadgetbridge XiaomiUuids). */
object XiaomiBleUuids {
    class V1Set(
        val service: UUID,
        val encrypted: Boolean,
        val commandRead: UUID,
        val commandWrite: UUID,
        val activityData: UUID,
        /** Not present on every model. */
        val dataUpload: UUID?,
    )

    /** Order matters: the first set whose characteristics exist wins. */
    val V1_SETS: List<V1Set> = listOf(
        // Mi Band 8, Redmi Watch 3 Active, Xiaomi Watch S1 (Active), Redmi Smart Band 2, Redmi Watch 2 Lite...
        V1Set(uuid("0000fe95-0000-1000-8000-00805f9b34fb"), true, uuid("00000051-0000-1000-8000-00805f9b34fb"), uuid("00000052-0000-1000-8000-00805f9b34fb"), uuid("00000053-0000-1000-8000-00805f9b34fb"), uuid("00000055-0000-1000-8000-00805f9b34fb")),
        // Mi Watch Lite, Redmi Watch
        V1Set(uuid("16186f00-0000-1000-8000-00807f9b34fb"), false, uuid("16186f01-0000-1000-8000-00807f9b34fb"), uuid("16186f02-0000-1000-8000-00807f9b34fb"), uuid("16186f03-0000-1000-8000-00807f9b34fb"), uuid("16186f04-0000-1000-8000-00807f9b34fb")),
        // Mi Smart Band 4C, Redmi Band
        V1Set(uuid("16187f00-0000-1000-8000-00807f9b34fb"), false, uuid("16187f02-0000-1000-8000-00807f9b34fb"), uuid("16187f01-0000-1000-8000-00807f9b34fb"), uuid("16187f03-0000-1000-8000-00807f9b34fb"), uuid("16187f04-0000-1000-8000-00807f9b34fb")),
        // Mi Watch Color Sport
        V1Set(uuid("1314f000-1000-9000-7000-301291e21220"), false, uuid("1314f005-1000-9000-7000-301291e21220"), uuid("1314f001-1000-9000-7000-301291e21220"), uuid("1314f002-1000-9000-7000-301291e21220"), uuid("1314f007-1000-9000-7000-301291e21220")),
        // Mi Watch CN
        V1Set(uuid("7495fe00-a7f3-424b-92dd-4a006a3aef56"), false, uuid("74950002-a7f3-424b-92dd-4a006a3aef56"), uuid("74950001-a7f3-424b-92dd-4a006a3aef56"), uuid("74950003-a7f3-424b-92dd-4a006a3aef56"), null),
    )

    val V2_SERVICE: UUID = uuid("0000fe95-0000-1000-8000-00805f9b34fb")
    val V2_RX: UUID = uuid("0000005e-0000-1000-8000-00805f9b34fb")
    val V2_TX: UUID = uuid("0000005f-0000-1000-8000-00805f9b34fb")

    /** Service UUIDs these devices advertise (Gadgetbridge XiaomiCoordinator.createBLEScanFilters). */
    val ADVERTISED_SERVICES: List<UUID> = V1_SETS.map { it.service }.distinct()

    private fun uuid(s: String): UUID = UUID.fromString(s)
}

/**
 * Xiaomi protobuf device over BLE (Gadgetbridge XiaomiBleSupport): the "V1" chunked
 * characteristics ([XiaomiBleChunker], encrypted or plaintext with the user id) or "V2", the SPP V2
 * framing carried over the 005e / 005f characteristics. Authentication and commands are the same
 * as over SPP.
 */
class XiaomiBleWearableSession(
    env: WearableSessionEnv,
    private val model: XiaomiModel,
    /** Ack wait before a V1 message is given up so the queue keeps moving. */
    private val ackTimeoutMs: Long = 10_000L,
) : GattWearableSession(env, requestedMtu = 512), XiaomiCommandChannel {

    private class PendingWrite(val service: UUID, val characteristic: UUID, val bytes: ByteArray)

    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)
    private var auth: XiaomiAuth? = null

    // V1
    private var v1: XiaomiBleUuids.V1Set? = null
    private val chunkers = LinkedHashMap<UUID, XiaomiBleChunker>()
    private val receiveTimers = HashMap<UUID, Job>()
    private val ackTimers = HashMap<UUID, Job>()

    // V2
    private var v2: SppProtocolV2? = null
    private val rx = SppReceiveBuffer()
    private val v2Started = CompletableDeferred<Unit>()

    private val authReplies = Channel<XiaomiProto.Command>(Channel.UNLIMITED)
    private var confirmSent = false
    private val pending = HashMap<Long, ArrayDeque<CompletableDeferred<XiaomiProto.Command>>>()

    private val features = XiaomiFeatures(this, ::emit, ::log) { block ->
        scope.launch {
            try {
                block()
            } catch (e: WearableException) {
                log("Follow-up request failed: ${e.failure}")
            }
        }
    }

    override var label: String = "BLE"
        private set

    override fun onClosed(reason: WearableException) {
        super.onClosed(reason)
        writes.close()
        authReplies.close(reason)
        v2Started.completeExceptionally(reason)
        pending.values.flatten().forEach { it.completeExceptionally(reason) }
        pending.clear()
    }

    override suspend fun onLinkReady() {
        val set = XiaomiBleUuids.V1_SETS.firstOrNull { s ->
            serviceOf(s.commandRead) != null && serviceOf(s.commandWrite) != null && serviceOf(s.activityData) != null
        }
        scope.launch { for (w in writes) writeOne(w) }
        if (set != null) {
            startV1(set)
        } else if (serviceOf(XiaomiBleUuids.V2_RX) != null && serviceOf(XiaomiBleUuids.V2_TX) != null) {
            startV2()
        } else {
            throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no known Xiaomi BLE characteristics")
        }
    }

    private suspend fun writeOne(w: PendingWrite) {
        if (!write(w.service, w.characteristic, w.bytes)) log("Write to ${w.characteristic} refused")
    }

    // ---- V1 ----

    private suspend fun startV1(set: XiaomiBleUuids.V1Set) {
        label = if (set.encrypted) "BLE v1" else "BLE v1 (plaintext)"
        v1 = set
        log("Xiaomi BLE V1 characteristics found (${if (set.encrypted) "encrypted" else "plaintext"})")
        val cipher = if (set.encrypted) newAuth()?.let { AuthCipher(it, model.checkDecryptionMac) } else null
        if (set.encrypted && cipher == null) throw WearableException(WearableFailure.WRONG_KEY, "key is not 32 hex characters")
        val chars = listOfNotNull(set.commandWrite, set.commandRead, set.activityData, set.dataUpload?.takeIf { serviceOf(it) != null })
        for (c in chars) {
            chunkers[c] = XiaomiBleChunker(set.encrypted, cipher, incrementNonce = c != set.dataUpload).also { it.setMtu(mtu) }
        }
        for (c in chars) enableNotifications(serviceOf(c) ?: set.service, c)

        if (set.encrypted) {
            encryptedHandshake(requireNotNull(auth))
        } else {
            plaintextHandshake()
        }
        // Gadgetbridge onAuthSuccess: the characteristics restart with encryption counter 1.
        chunkers.values.forEach { it.reset() }
        log("Authenticated ($label)")
    }

    private fun newAuth(): XiaomiAuth? {
        val key = (env.authKey?.let { AuthKey.parse(it) } as? AuthKeyResult.Valid)?.key ?: return null
        val deviceId = env.config[ConfiguredWearable.CONFIG_XIAOMI_DEVICE_ID]?.trim()?.takeIf { model.usesDeviceId && it.isNotEmpty() }
        return XiaomiAuth(key, defaultDeviceId = deviceId).also { auth = it }
    }

    private class AuthCipher(private val auth: XiaomiAuth, private val checkMac: Boolean) : XiaomiBleCipher {
        override val ready: Boolean get() = auth.isEncryptionInitialized
        override fun encrypt(plain: ByteArray, counter: Int): ByteArray = auth.encryptV1(plain, counter)
        override fun decrypt(cipher: ByteArray): ByteArray = if (checkMac) auth.decryptV1(cipher) else auth.decryptV1Unchecked(cipher)
    }

    private fun perform(characteristic: UUID, outputs: List<XiaomiBleChunker.Output>) {
        val service = serviceOf(characteristic) ?: v1?.service ?: return
        val chunker = chunkers[characteristic]
        for (o in outputs) {
            when (o) {
                is XiaomiBleChunker.Output.Write -> writes.trySend(PendingWrite(service, characteristic, o.bytes))
                is XiaomiBleChunker.Output.Received ->
                    if (characteristic == v1?.commandRead || characteristic == v1?.commandWrite) onCommandBytes(o.payload)
                is XiaomiBleChunker.Output.Sent -> if (!o.ok) log("Device rejected a message on $characteristic")
                XiaomiBleChunker.Output.StartTimer -> {
                    receiveTimers.remove(characteristic)?.cancel()
                    receiveTimers[characteristic] = scope.launch {
                        delay(XiaomiBleChunker.TIMEOUT_MS)
                        receiveTimers.remove(characteristic)
                        chunker?.let { perform(characteristic, it.onReceiveTimeout()) }
                    }
                }
                XiaomiBleChunker.Output.StopTimer -> receiveTimers.remove(characteristic)?.cancel()
            }
        }
        if (chunker != null) armAckTimer(characteristic, chunker)
    }

    private fun armAckTimer(characteristic: UUID, chunker: XiaomiBleChunker) {
        ackTimers.remove(characteristic)?.cancel()
        if (!chunker.busy) return
        ackTimers[characteristic] = scope.launch {
            delay(ackTimeoutMs)
            ackTimers.remove(characteristic)
            log("No ack on $characteristic: moving on")
            perform(characteristic, chunker.onAckTimeout())
        }
    }

    // ---- V2 (SPP V2 frames over BLE) ----

    private suspend fun startV2() {
        label = "BLE v2"
        log("Xiaomi BLE V2 characteristics found")
        val a = newAuth() ?: throw WearableException(WearableFailure.WRONG_KEY, "key is not 32 hex characters")
        val protocol = SppProtocolV2(a)
        v2 = protocol
        enableNotifications(serviceOf(XiaomiBleUuids.V2_RX) ?: XiaomiBleUuids.V2_SERVICE, XiaomiBleUuids.V2_RX)
        writeV2(protocol.sessionConfigFrame())
        withTimeoutOrNull(env.timeouts.auth) { v2Started.await() }
            ?: throw WearableException(WearableFailure.NO_RESPONSE, "no session config answer")
        encryptedHandshake(a)
        log("Authenticated ($label)")
    }

    /** Splits a V2 frame into MTU-sized writes (Gadgetbridge writeChunkedData). */
    private fun writeV2(frame: ByteArray) {
        val service = serviceOf(XiaomiBleUuids.V2_TX) ?: XiaomiBleUuids.V2_SERVICE
        val size = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(frame.size, offset + size)
            writes.trySend(PendingWrite(service, XiaomiBleUuids.V2_TX, frame.copyOfRange(offset, end)))
            offset = end
        }
    }

    private fun onV2Bytes(value: ByteArray) {
        val protocol = v2 ?: return
        rx.append(value)
        while (true) {
            when (val frame = rx.poll(protocol) ?: break) {
                is SppFrame.V2 -> onV2Packet(protocol, frame.packet)
                is SppFrame.V1 -> Unit
                SppFrame.Corrupt -> log("V2 frame dropped (bad checksum)")
            }
        }
    }

    private fun onV2Packet(protocol: SppProtocolV2, packet: SppPacketV2) {
        when (packet) {
            is SppPacketV2.SessionConfig -> v2Started.complete(Unit)
            is SppPacketV2.Data -> {
                val payload = try {
                    packet.decryptedPayload(requireNotNull(auth))
                } catch (e: Exception) {
                    null
                }
                writeV2(SppPacketV2.ackFrame(packet.sequenceNumber))
                if (payload != null && (packet.channel == SppChannel.ProtobufCommand || packet.channel == SppChannel.Authentication)) {
                    onCommandBytes(payload)
                }
            }
            is SppPacketV2.Ack -> protocol.onAck(packet.sequenceNumber)
            is SppPacketV2.Nack -> log("Device expects V2 packet ${packet.sequenceNumber}")
        }
    }

    // ---- shared ----

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (v2 != null) {
            if (characteristic == XiaomiBleUuids.V2_RX) onV2Bytes(value)
            return
        }
        val chunker = chunkers[characteristic] ?: return
        perform(characteristic, chunker.onNotification(value))
    }

    private fun sendRaw(command: XiaomiProto.Command) {
        ensureOpen()
        val bytes = command.toByteArray()
        val protocol = v2
        if (protocol != null) {
            val channel = if (command.type == XiaomiCommands.TYPE_AUTH) SppChannel.Authentication else SppChannel.ProtobufCommand
            writeV2(protocol.nextDataFrame(protocol.dataPayload(channel, bytes)))
            return
        }
        val set = v1 ?: throw WearableException(WearableFailure.CLOSED)
        val chunker = chunkers.getValue(set.commandWrite)
        perform(set.commandWrite, chunker.send(bytes))
    }

    private suspend fun encryptedHandshake(a: XiaomiAuth) {
        sendRaw(a.startHandshake())
        val nonceReply = awaitAuthReply()
        if (nonceReply.subtype == XiaomiCommands.AUTH_NONCE && nonceReply.auth.hasDeviceVerify()) {
            when (val result = a.handleWatchNonce(nonceReply.auth.deviceVerify, env.companion)) {
                XiaomiAuth.WatchNonceResult.WrongKey -> throw WearableException(WearableFailure.WRONG_KEY)
                is XiaomiAuth.WatchNonceResult.Confirm -> {
                    confirmSent = true
                    sendRaw(result.command)
                }
            }
            val confirm = awaitAuthReply()
            if (confirm.subtype != XiaomiCommands.AUTH_CONFIRM) throw WearableException(WearableFailure.AUTH_REJECTED)
        } else if (nonceReply.subtype == XiaomiCommands.AUTH_SEND_USERID && nonceReply.auth.status == 1) {
            a.onAuthenticated(encrypted = false)
        } else {
            throw WearableException(WearableFailure.AUTH_REJECTED, "unexpected auth answer ${nonceReply.subtype}")
        }
    }

    /** Plaintext devices: the user id as "auth key" (Gadgetbridge startClearTextHandshake). */
    private suspend fun plaintextHandshake() {
        val userId = env.authKey?.trim()?.takeIf { it.isNotEmpty() } ?: throw WearableException(WearableFailure.NO_KEY)
        sendRaw(
            XiaomiProto.Command.newBuilder()
                .setType(XiaomiCommands.TYPE_AUTH)
                .setSubtype(XiaomiCommands.AUTH_SEND_USERID)
                .setAuth(XiaomiProto.Account.newBuilder().setUserId(userId))
                .build(),
        )
        val reply = awaitAuthReply()
        if (reply.subtype != XiaomiCommands.AUTH_SEND_USERID || reply.auth.status != 1) {
            throw WearableException(WearableFailure.WRONG_KEY, "user id rejected")
        }
    }

    private suspend fun awaitAuthReply(): XiaomiProto.Command =
        withTimeoutOrNull(env.timeouts.auth) { authReplies.receive() }
            ?: throw WearableException(WearableFailure.NO_RESPONSE, "no auth answer")

    private fun onCommandBytes(bytes: ByteArray) {
        val command = try {
            XiaomiProto.Command.parseFrom(bytes)
        } catch (e: Exception) {
            log("Unreadable command (${bytes.size} bytes)")
            return
        }
        if (command.type == XiaomiCommands.TYPE_AUTH) {
            if (command.subtype == XiaomiCommands.AUTH_CONFIRM && confirmSent) auth?.onAuthenticated(encrypted = true)
            authReplies.trySend(command)
            return
        }
        val key = key(command.type, command.subtype)
        val waiter = pending[key]?.removeFirstOrNull()
        if (waiter != null) {
            if (pending[key].isNullOrEmpty()) pending.remove(key)
            waiter.complete(command)
        } else {
            features.handleCommand(command)
        }
    }

    // ---- XiaomiCommandChannel ----

    override suspend fun send(command: XiaomiProto.Command) = withContext(serial) { sendRaw(command) }

    override suspend fun request(command: XiaomiProto.Command, responseSubtype: Int): XiaomiProto.Command = withContext(serial) {
        ensureOpen()
        val key = key(command.type, responseSubtype)
        val reply = CompletableDeferred<XiaomiProto.Command>()
        pending.getOrPut(key) { ArrayDeque() }.addLast(reply)
        try {
            sendRaw(command)
            withTimeout(env.timeouts.request) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw WearableException(WearableFailure.REQUEST_TIMEOUT, "no answer", e)
        } finally {
            pending[key]?.let { queue ->
                queue.remove(reply)
                if (queue.isEmpty()) pending.remove(key)
            }
        }
    }

    private fun key(type: Int, subtype: Int): Long = (type.toLong() shl 32) or (subtype.toLong() and 0xffffffffL)

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
