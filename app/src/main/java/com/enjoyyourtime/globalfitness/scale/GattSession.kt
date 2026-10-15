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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Coroutine wrapper over [BluetoothGatt] with a serialized operation queue: only one GATT
 * operation is in flight at a time (Android silently drops concurrent ones). Each operation
 * waits for its callback, bounded by [opTimeoutMs].
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
    @Volatile private var pending: CompletableDeferred<Result>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var servicesWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var closed = false

    private val _connected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notifications = MutableSharedFlow<GattNotification>(extraBufferCapacity = 256)
    override val notifications: Flow<GattNotification> = _notifications.asSharedFlow()

    private class Result(val status: Int, val value: ByteArray? = null, val mtu: Int = 0)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectWaiter?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                _connected.value = false
                val error = GattException(errorText(status))
                connectWaiter?.completeExceptionally(error)
                servicesWaiter?.completeExceptionally(error)
                pending?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesWaiter?.complete(Unit)
            } else {
                servicesWaiter?.completeExceptionally(GattException("No se pudieron leer los servicios ($status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pending?.complete(Result(status, mtu = mtu))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(Result(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(Result(status))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pending?.complete(Result(status, value.copyOf()))
        }

        @Deprecated("Legacy callback for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(Result(status, characteristic.value?.copyOf()))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            _notifications.tryEmit(GattNotification(characteristic.uuid, value.copyOf()))
        }

        @Deprecated("Legacy callback for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            _notifications.tryEmit(GattNotification(characteristic.uuid, value.copyOf()))
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun connect() {
        if (!hasConnectPermission()) throw GattException("Falta el permiso de dispositivos cercanos")
        val adapter = manager?.adapter ?: throw GattException("Bluetooth no disponible")
        if (!adapter.isEnabled) throw GattException("Bluetooth desactivado")
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw GattException("Dirección no válida: $address")
        }
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw GattException("Falta el permiso de dispositivos cercanos")
        } ?: throw GattException("Android no pudo iniciar la conexión")
        gatt = g
        try {
            waiter.await()
        } finally {
            connectWaiter = null
        }
    }

    override suspend fun discoverServices() {
        val g = gatt ?: throw GattException("No conectado")
        val waiter = CompletableDeferred<Unit>()
        servicesWaiter = waiter
        try {
            if (!g.discoverServices()) throw GattException("No se pudieron buscar los servicios")
            waiter.await()
        } finally {
            servicesWaiter = null
        }
    }

    /** Runs one GATT operation: [start] must return true when the request was accepted. */
    private suspend fun op(start: (BluetoothGatt) -> Boolean): Result? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        if (!_connected.value || closed) return@withLock null
        val waiter = CompletableDeferred<Result>()
        pending = waiter
        try {
            val started = try {
                start(g)
            } catch (e: SecurityException) {
                false
            }
            if (!started) return@withLock null
            withTimeoutOrNull(opTimeoutMs) {
                try {
                    waiter.await()
                } catch (e: GattException) {
                    null
                }
            }
        } finally {
            pending = null
        }
    }

    override suspend fun requestMtu(mtu: Int): Int? =
        op { it.requestMtu(mtu) }?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS }?.mtu

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
        val result = op { gg -> writeDescriptorCompat(gg, descriptor, value) }
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
        val result = op { g -> writeCharacteristicCompat(g, ch, value, type) }
        return result?.status == BluetoothGatt.GATT_SUCCESS
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray? {
        val ch = characteristic(service, characteristic) ?: return null
        val result = op { g -> g.readCharacteristic(ch) } ?: return null
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
        val error = GattException("Conexión cerrada")
        connectWaiter?.completeExceptionally(error)
        servicesWaiter?.completeExceptionally(error)
        pending?.completeExceptionally(error)
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

        fun errorText(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "La báscula se desconectó"
            8 -> "Se perdió la conexión con la báscula (fuera de alcance)"
            19 -> "La báscula cerró la conexión"
            22 -> "Conexión cancelada por el teléfono"
            62 -> "No se pudo establecer la conexión"
            133 -> "Error de conexión Bluetooth (133). Reintentando en la próxima medición"
            else -> "Error de conexión Bluetooth ($status)"
        }
    }
}
