import sys
sys.path.insert(0, __file__.rsplit('/', 1)[0].rsplit('\\', 1)[0])
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
sub('ble/ScaleMonitor.kt', [
    ('    data class SaveFailed(val weightKg: Double, val message: String) : LiveScale',
     '    data class SaveFailed(val weightKg: Double, val notice: ScaleNotice) : LiveScale'),
    ('    val detail: String? = null,\n    val weightKg: Double? = null,',
     '    val detail: ScaleNotice? = null,\n    val weightKg: Double? = null,'),
    ('''    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Spanish error messages when a measurement could not be stored. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Spanish informational messages (auto-configuration, missing bind key, ...). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()''',
     '''    private val _errors = MutableSharedFlow<ScaleNotice>(extraBufferCapacity = 4)

    /** Errors when a measurement could not be stored. */
    val errors: SharedFlow<ScaleNotice> = _errors.asSharedFlow()

    private val _messages = MutableSharedFlow<ScaleNotice>(extraBufferCapacity = 8)

    /** Informational messages (auto-configuration, missing bind key, ...). */
    val messages: SharedFlow<ScaleNotice> = _messages.asSharedFlow()'''),
    ('''        val extra = if (result.driver.capabilities.needsConfig) " Completa su configuración en Dispositivos." else ""
        _messages.tryEmit("Báscula detectada y configurada: ${result.driver.displayName}.$extra")''',
     f'''        val kind = if (result.driver.capabilities.needsConfig) ScaleNotice.Kind.AUTO_CONFIGURED_NEEDS_SETUP else ScaleNotice.Kind.AUTO_CONFIGURED
        _messages.tryEmit(ScaleNotice(kind, listOf(result.driver.displayName)))'''),
    ('''            val extra = if (driver.capabilities.needsConfig) " Completa su configuración." else " Súbete a la báscula para que la app se conecte."
            _messages.tryEmit("Báscula añadida: ${driver.displayName}.$extra")''',
     '''            val kind = if (driver.capabilities.needsConfig) ScaleNotice.Kind.ADDED_NEEDS_SETUP else ScaleNotice.Kind.ADDED_STEP_ON
            _messages.tryEmit(ScaleNotice(kind, listOf(driver.displayName)))'''),
    ('is ScaleEvent.Message -> _messages.tryEmit(event.text)', 'is ScaleEvent.Message -> _messages.tryEmit(event.notice)'),
    ('updateSession(cs, SessionStatus.FAILED, e.message)',
     f'updateSession(cs, SessionStatus.FAILED, {N}CONNECT_FAILED_DETAIL, listOf(e.message ?: e.javaClass.simpleName)))'),
    ('private fun updateSession(cs: ConfiguredScale, status: SessionStatus, detail: String?, weight: Double? = null)',
     'private fun updateSession(cs: ConfiguredScale, status: SessionStatus, detail: ScaleNotice?, weight: Double? = null)'),
    ('''                    val message = "No se pudo guardar la medición: ${e.message ?: e.javaClass.simpleName}"
                    _errors.tryEmit(message)
                    KeyResult.Error(message)''',
     f'''                    val notice = {N}SAVE_FAILED, listOf(e.message ?: e.javaClass.simpleName))
                    _errors.tryEmit(notice)
                    KeyResult.Error(notice)'''),
    ('is KeyResult.Error -> LiveScale.SaveFailed(r.weightKg, result.message)', 'is KeyResult.Error -> LiveScale.SaveFailed(r.weightKg, result.notice)'),
    ('import com.enjoyyourtime.globalfitness.scale.ScaleEvent\n', 'import com.enjoyyourtime.globalfitness.scale.ScaleEvent\nimport com.enjoyyourtime.globalfitness.scale.ScaleNotice\n'),
])
