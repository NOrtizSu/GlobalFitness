import os
import re
import sys

root = sys.argv[1]  # .../scale/drivers
K = 'ScaleNotice.Kind.'
simple = {
    '"Súbete a la báscula"': 'STEP_ON',
    '"Batería de la báscula baja"': 'LOW_BATTERY',
    '"Usuario creado en la báscula: súbete para la medición de referencia"': 'USER_CREATED_STEP_ON',
    '"La báscula no admite más usuarios"': 'SCALE_FULL',
    '"Medición fallida: coloca bien los pies en la báscula"': 'MEASUREMENT_FAILED_FEET',
    '"La báscula Eufy rechazó la autenticación"': 'EUFY_AUTH_REJECTED',
    '"Báscula emparejada"': 'PAIRED',
    '"Báscula no emparejada: pon la báscula en modo emparejamiento"': 'NOT_PAIRED_PAIRING_MODE',
    '"Modelo Omron no reconocido"': 'OMRON_UNKNOWN_MODEL',
    '"Mantén pulsado el botón de emparejamiento de la Omron"': 'OMRON_HOLD_PAIRING_BUTTON',
    '"Omron emparejada"': 'OMRON_PAIRED',
    '"No hay mediciones nuevas en la Omron"': 'OMRON_NO_NEW_MEASUREMENTS',
    '"La Omron dejó de responder"': 'OMRON_STOPPED_RESPONDING',
    '"Registro de la báscula no válido"': 'INVALID_RECORD',
    '"La báscula no pudo medir la grasa corporal"': 'FAT_MEASUREMENT_FAILED',
    '"La báscula no pudo crear el usuario (¿sin huecos libres?)"': 'USER_CREATE_FAILED',
    '"La báscula rechazó el usuario: vuelve a intentarlo más tarde"': 'USER_REJECTED_RETRY_LATER',
    '"Trisa emparejada. Súbete de nuevo para medir"': 'TRISA_PAIRED_STEP_ON_AGAIN',
    '"Trisa no emparejada: pulsa el botón de emparejamiento de la báscula"': 'TRISA_NOT_PAIRED',
    '"La báscula no tiene usuarios libres: indica tu usuario y PIN en Básculas"': 'NO_FREE_USERS_SET_PIN',
    '"Súbete a la báscula para la medición de referencia"': 'STEP_ON_FOR_REFERENCE',
    '"La báscula rechazó el PIN del usuario: revísalo en Básculas"': 'PIN_REJECTED',
    '"La Omron no está en modo emparejamiento"': 'OMRON_NOT_IN_PAIRING_MODE',
    '"No se pudo emparejar la Omron"': 'OMRON_PAIRING_FAILED',
    '"La Omron rechazó la sesión"': 'OMRON_SESSION_REJECTED',
    '"Error leyendo la memoria de la Omron"': 'OMRON_MEMORY_READ_ERROR',
}
withargs = {
    '"Mediciones leídas de la Omron: ${fresh.size}"': ('OMRON_MEASUREMENTS_READ', 'fresh.size'),
    '"Usuario $index registrado en la báscula (PIN $consent)"': ('USER_REGISTERED', 'index, consent'),
    '"La báscula no aceptó el registro del usuario (código $result)"': ('USER_REGISTRATION_REFUSED', 'result'),
    '"Falta el PIN del usuario $index de la báscula: introdúcelo en Básculas"': ('PIN_MISSING', 'index'),
    '"La báscula muestra el PIN del usuario $index: introdúcelo en Básculas"': ('PIN_SHOWN_ON_SCALE', 'index'),
}
env_msgs = {
    'env.messages("Báscula S400 detectada: configura su bind key en Básculas para leerla.")': 'env.messages(ScaleNotice(ScaleNotice.Kind.BIND_KEY_NEEDED, listOf("S400")))',
    'env.messages("La bind key de la S400 no descifra sus datos: revísala en Básculas.")': 'env.messages(ScaleNotice(ScaleNotice.Kind.BIND_KEY_WRONG, listOf("S400")))',
    'env.messages("Báscula S800 detectada: configura su bind key en Básculas para leerla.")': 'env.messages(ScaleNotice(ScaleNotice.Kind.BIND_KEY_NEEDED, listOf("S800")))',
    'env.messages("La bind key de la S800 no descifra sus datos: revísala en Básculas.")': 'env.messages(ScaleNotice(ScaleNotice.Kind.BIND_KEY_WRONG, listOf("S800")))',
}

for name in sorted(os.listdir(root)):
    if not name.endswith('.kt'):
        continue
    p = os.path.join(root, name)
    s = open(p, encoding='utf-8').read()
    orig = s
    for k, v in simple.items():
        s = s.replace(f'userInfo({k})', f'userInfo({K}{v})')
        s = s.replace(f'fail({k})', f'fail({K}{v})')
    for k, (kind, args) in withargs.items():
        s = s.replace(f'userInfo({k})', f'userInfo({K}{kind}, {args})')
    for k, v in env_msgs.items():
        s = s.replace(k, v)
    s = s.replace('private const val TRANSTEK_FULL = "La báscula no tiene usuarios libres: elige tu usuario (1-8) en la configuración de la báscula"',
                  'private val TRANSTEK_FULL = ScaleNotice.Kind.NO_FREE_USERS_CHOOSE_SLOT')
    s = s.replace('''        private fun fail(message: String) {
            userInfo(message)''', '''        private fun fail(kind: ScaleNotice.Kind) {
            userInfo(kind)''')
    if s != orig:
        if 'import com.enjoyyourtime.globalfitness.scale.ScaleNotice\n' not in s:
            # insert import in sorted position among scale imports
            lines = s.split('\n')
            idx = None
            for i, l in enumerate(lines):
                if l.startswith('import com.enjoyyourtime.globalfitness.scale.'):
                    if l > 'import com.enjoyyourtime.globalfitness.scale.ScaleNotice' and idx is None:
                        idx = i
                    last = i
            if idx is None:
                idx = last + 1
            lines.insert(idx, 'import com.enjoyyourtime.globalfitness.scale.ScaleNotice')
            s = '\n'.join(lines)
        open(p, 'w', encoding='utf-8', newline='\n').write(s)
        print('updated', name)
    left = [l.strip() for l in s.split('\n') if ('userInfo("' in l or 'env.messages("' in l or 'fail("' in l)]
    for l in left:
        print('  LEFT:', name, l)
