import sys
base = sys.argv[1]

def sub(path, pairs):
    p = base + '/' + path
    s = open(p, encoding='utf-8').read()
    for a, b in pairs:
        if a not in s:
            raise SystemExit(f'{path}: not found: {a!r}')
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

N = 'ScaleNotice(ScaleNotice.Kind.'
sub('scale/GattSessionRunner.kt', [
    ('class GattException(message: String) : Exception(message)',
     '/** A connection failure; [notice] is what the user sees. */\nclass GattException(val notice: ScaleNotice) : Exception(notice.kind.name)'),
    ('    /** Why the link failed on its own (e.g. a wedged operation), null when it did not. */\n    val failure: String? get() = null',
     '    /** Why the link failed on its own (e.g. a wedged operation), null when it did not. */\n    val failure: ScaleNotice? get() = null'),
    ('        class End(val reason: String?) : Event', '        class End(val reason: ScaleNotice?) : Event'),
    ('send(ScaleEvent.Status(SessionStatus.FAILED, "No se pudo conectar (tiempo agotado)"))',
     f'send(ScaleEvent.Status(SessionStatus.FAILED, {N}CONNECT_TIMEOUT)))'),
    ('send(ScaleEvent.Status(SessionStatus.FAILED, e.message ?: "No se pudo conectar"))',
     f'''val notice = when {{
                e is GattException -> e.notice
                e.message != null -> {N}CONNECT_FAILED_DETAIL, listOf(e.message!!))
                else -> {N}CONNECT_FAILED)
            }}
            send(ScaleEvent.Status(SessionStatus.FAILED, notice))'''),
    ('            override fun message(text: String) {\n                trySend(ScaleEvent.Message(text))',
     '            override fun message(notice: ScaleNotice) {\n                trySend(ScaleEvent.Message(notice))'),
    ('events.send(Event.End("La báscula se desconectó"))', f'events.send(Event.End({N}SCALE_DISCONNECTED)))'),
    ('        var endReason: String? = null', '        var endReason: ScaleNotice? = null'),
    ('trySend(ScaleEvent.Message("Error del protocolo: ${e.message ?: e.javaClass.simpleName}"))',
     f'trySend(ScaleEvent.Message({N}PROTOCOL_ERROR, listOf(e.message ?: e.javaClass.simpleName))))'),
    ('        const val NO_DATA = "Sin datos nuevos de la báscula"', f'        val NO_DATA = {N}NO_NEW_DATA)'),
])

sub('scale/GattSession.kt', [
    ('    @Volatile private var failureText: String? = null\n    override val failure: String? get() = failureText',
     '    @Volatile private var failureNotice: ScaleNotice? = null\n    override val failure: ScaleNotice? get() = failureNotice'),
    ('val error = GattException(errorText(status))', 'val error = GattException(errorNotice(status))'),
    ('GattException("No se pudieron leer los servicios ($status)")', f'GattException({N}SERVICES_READ_FAILED, listOf(status)))'),
    ('throw GattException("Falta el permiso de dispositivos cercanos")\n        val adapter', f'throw GattException({N}MISSING_NEARBY_PERMISSION))\n        val adapter'),
    ('throw GattException("Bluetooth no disponible")', f'throw GattException({N}BLUETOOTH_UNAVAILABLE))'),
    ('throw GattException("Bluetooth desactivado")', f'throw GattException({N}BLUETOOTH_OFF))'),
    ('throw GattException("Dirección no válida: $address")', f'throw GattException({N}INVALID_ADDRESS, listOf(address)))'),
    ('            throw GattException("Falta el permiso de dispositivos cercanos")\n        } ?: throw GattException("Android no pudo iniciar la conexión")',
     f'            throw GattException({N}MISSING_NEARBY_PERMISSION))\n        }} ?: throw GattException({N}CONNECT_NOT_STARTED))'),
    ('throw GattException("No conectado")', f'throw GattException({N}NOT_CONNECTED))'),
    ('throw GattException("No se pudieron buscar los servicios")', f'throw GattException({N}SERVICE_DISCOVERY_FAILED))'),
    ('            failureText = "La báscula dejó de responder"\n            close()\n            throw GattException("La báscula dejó de responder")',
     f'            failureNotice = {N}SCALE_STOPPED_RESPONDING)\n            close()\n            throw GattException({N}SCALE_STOPPED_RESPONDING))'),
    ('val error = GattException("Conexión cerrada")', f'val error = GattException({N}CONNECTION_CLOSED))'),
    ('''        fun errorText(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "La báscula se desconectó"
            8 -> "Se perdió la conexión con la báscula (fuera de alcance)"
            19 -> "La báscula cerró la conexión"
            22 -> "Conexión cancelada por el teléfono"
            62 -> "No se pudo establecer la conexión"
            133 -> "Error de conexión Bluetooth (133). Reintentando en la próxima medición"
            else -> "Error de conexión Bluetooth ($status)"
        }''', f'''        fun errorNotice(status: Int): ScaleNotice = when (status) {{
            BluetoothGatt.GATT_SUCCESS -> {N}SCALE_DISCONNECTED)
            8 -> {N}CONNECTION_LOST_OUT_OF_RANGE)
            19 -> {N}SCALE_CLOSED_CONNECTION)
            22 -> {N}PHONE_CANCELLED_CONNECTION)
            62 -> {N}CONNECTION_NOT_ESTABLISHED)
            133 -> {N}GATT_ERROR_133)
            else -> {N}GATT_ERROR, listOf(status))
        }}'''),
])
