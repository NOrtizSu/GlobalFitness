import os
import sys

root = sys.argv[1]  # .../scale
D = 'DriverNameNote.'
rep = {
    '    override val displayName = "AiLink / eLink (báscula de grasa por anuncios)"':
        '    override val displayName = "AiLink / eLink"\n    override val nameNote = ' + D + 'BODY_FAT_BROADCAST',
    '    override val displayName = "OKOK / Chipsea (anuncios)"':
        '    override val displayName = "OKOK / Chipsea"\n    override val nameNote = ' + D + 'BROADCAST',
    '    override val displayName = "QN / Renpho / FITINDEX (anuncios AABB)"':
        '    override val displayName = "QN / Renpho / FITINDEX"\n    override val nameNote = ' + D + 'AABB_BROADCAST',
    '    override val displayName = "Báscula AAA002 / AAA007 / AAA013"\n    override val brand = "Genérica (AAA)"':
        '    override val displayName = "AAA002 / AAA007 / AAA013"\n    override val nameNote = ' + D + 'SCALE\n    override val brand = GENERIC_BRAND',
    '    override val displayName = "Scale Up (anuncio 0xD0/0xE0)"\n    override val brand = "Genérica (Scale Up)"':
        '    override val displayName = "Scale Up"\n    override val nameNote = ' + D + 'D0_E0_BROADCAST\n    override val brand = GENERIC_BRAND',
    '    override val displayName = "EEBBL Body Fat Scale P1 (8 electrodos)"':
        '    override val displayName = "EEBBL Body Fat Scale P1"\n    override val nameNote = ' + D + 'EIGHT_ELECTRODES',
    '    override val displayName = "SWAN / Icomon (protocolo MGB)"':
        '    override val displayName = "SWAN / Icomon"\n    override val nameNote = ' + D + 'MGB_PROTOCOL',
    '    override val displayName = "QN / Yolanda / RENPHO / FITINDEX (conexión)"':
        '    override val displayName = "QN / Yolanda / RENPHO / FITINDEX"\n    override val nameNote = ' + D + 'CONNECTION',
    '    override val displayName = "1byone (nuevo)"':
        '    override val displayName = "1byone"\n    override val nameNote = ' + D + 'NEW_MODEL',
    '    override val displayName = "Xiaomi Mi Smart Scale (1ª gen.)"':
        '    override val displayName = "Xiaomi Mi Smart Scale"\n    override val nameNote = ' + D + 'FIRST_GENERATION',
    '    override val displayName = "Báscula estándar Bluetooth (perfil Weight Scale)"\n    override val brand = "Genérica"':
        '    override val displayName = "Weight Scale"\n    override val nameNote = ' + D + 'STANDARD_PROFILE\n    override val brand = GENERIC_BRAND',
    'STANDARD_IMPEDANCE("STANDARD_IMPEDANCE", "Impedancia estándar (Sun 2003)")': 'STANDARD_IMPEDANCE("STANDARD_IMPEDANCE", "Standard impedance (Sun 2003)")',
    'TRISA_RESISTANCE("TRISA_R", "Trisa (resistencia)")': 'TRISA_RESISTANCE("TRISA_R", "Trisa (resistance)")',
    'WLA25("WLA25", "Fitdays WLA25 (10 impedancias)")': 'WLA25("WLA25", "Fitdays WLA25 (10 impedances)")',
    'ONE_BYONE_NEW("ONE_BYONE_NEW", "1byone (nuevo)")': 'ONE_BYONE_NEW("ONE_BYONE_NEW", "1byone (new)")',
    'KEEP_S3("KEEP_S3", "Keep S3 (doble frecuencia)")': 'KEEP_S3("KEEP_S3", "Keep S3 (dual frequency)")',
}
found = set()
for dp, dn, fn in os.walk(root):
    for name in fn:
        if not name.endswith('.kt'):
            continue
        p = os.path.join(dp, name)
        s = open(p, encoding='utf-8').read()
        o = s
        for a, b in rep.items():
            if a in s:
                s = s.replace(a, b)
                found.add(a)
        if s != o:
            if 'drivers' in dp:
                imports = []
                if 'DriverNameNote.' in s and 'import com.enjoyyourtime.globalfitness.scale.DriverNameNote\n' not in s:
                    imports.append('import com.enjoyyourtime.globalfitness.scale.DriverNameNote')
                if 'GENERIC_BRAND' in s and 'import com.enjoyyourtime.globalfitness.scale.GENERIC_BRAND\n' not in s:
                    imports.append('import com.enjoyyourtime.globalfitness.scale.GENERIC_BRAND')
                if imports:
                    lines = s.split('\n')
                    first = next(i for i, l in enumerate(lines) if l.startswith('import com.enjoyyourtime.globalfitness.scale.'))
                    for imp in imports:
                        lines.insert(first, imp)
                    # keep scale imports sorted
                    block_end = first
                    while block_end < len(lines) and lines[block_end].startswith('import '):
                        block_end += 1
                    start = first
                    while start > 0 and lines[start - 1].startswith('import '):
                        start -= 1
                    lines[start:block_end] = sorted(set(lines[start:block_end]))
                    s = '\n'.join(lines)
            open(p, 'w', encoding='utf-8', newline='\n').write(s)
            print('updated', name)
for a in rep:
    if a not in found:
        print('NOT FOUND', a[:60])
