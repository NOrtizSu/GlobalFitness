package com.enjoyyourtime.globalfitness.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.enjoyyourtime.globalfitness.band.xiaomi.BandException
import com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiBand9
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RFCOMM connection to the band's Serial Port Profile service, as Gadgetbridge's BtBRQueue opens it
 * (secure socket resolved through SDP). Blocking socket calls run on [Dispatchers.IO]; [close] unblocks
 * a pending read.
 */
class RfcommLink private constructor(private val socket: BluetoothSocket) : BandLink {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val buffer = ByteArray(BUFFER_SIZE)

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val n = input.read(buffer)
        if (n < 0) throw IOException("Fin de la conexión")
        buffer.copyOf(n)
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        output.write(data)
        output.flush()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Gadgetbridge XiaomiSppSupport uses a 1024-byte read buffer. */
        private const val BUFFER_SIZE = 1024
        private const val CONNECTING = 0
        private const val CONNECTED = 1
        private const val TIMED_OUT = 2

        /**
         * Opens the socket, or throws [BandException] ([BandFailure.CONNECT_FAILED] on refusal or after
         * [timeoutMs]). Cancelling the caller closes the socket.
         */
        @SuppressLint("MissingPermission") // BandPermissions.hasConnect is checked first
        suspend fun connect(context: Context, adapter: BluetoothAdapter, address: String, timeoutMs: Long): RfcommLink {
            if (!BandPermissions.hasConnect(context)) throw BandException(BandFailure.MISSING_PERMISSION)
            if (!BluetoothAdapter.checkBluetoothAddress(address)) throw BandException(BandFailure.NOT_FOUND)
            if (BandPermissions.hasScan(context)) {
                // An ongoing discovery slows down or breaks RFCOMM connections.
                try {
                    adapter.cancelDiscovery()
                } catch (_: SecurityException) {
                }
            }
            val socket = try {
                adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(XiaomiBand9.SPP_UUID)
            } catch (e: IOException) {
                throw BandException(BandFailure.CONNECT_FAILED, cause = e)
            } catch (e: SecurityException) {
                throw BandException(BandFailure.MISSING_PERMISSION, cause = e)
            }

            val state = AtomicInteger(CONNECTING)
            try {
                coroutineScope {
                    val watchdog = launch {
                        try {
                            delay(timeoutMs)
                        } finally {
                            // Timeout or caller cancelled: closing the socket aborts the blocking connect().
                            if (state.compareAndSet(CONNECTING, TIMED_OUT)) socket.close()
                        }
                    }
                    withContext(Dispatchers.IO) { socket.connect() }
                    if (!state.compareAndSet(CONNECTING, CONNECTED)) throw IOException("Tiempo de conexión agotado")
                    watchdog.cancel()
                }
            } catch (e: IOException) {
                closeQuietly(socket)
                // Closing the socket because the caller was cancelled is not a connection failure.
                currentCoroutineContext().ensureActive()
                throw BandException(BandFailure.CONNECT_FAILED, cause = e)
            } catch (e: SecurityException) {
                closeQuietly(socket)
                currentCoroutineContext().ensureActive()
                throw BandException(BandFailure.MISSING_PERMISSION, cause = e)
            } catch (e: Throwable) {
                closeQuietly(socket)
                throw e
            }
            return RfcommLink(socket)
        }

        private fun closeQuietly(socket: BluetoothSocket) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}
