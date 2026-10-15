import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
m = {'"Bajo"': 'Level.LOW', '"Normal"': 'Level.NORMAL', '"Sobrepeso"': 'Level.OVERWEIGHT', '"Obesidad"': 'Level.OBESE',
     '"Muy bajo"': 'Level.VERY_LOW', '"Alto"': 'Level.HIGH', '"Muy alto"': 'Level.VERY_HIGH', '"Excelente"': 'Level.EXCELLENT',
     '"Óptimo"': 'Level.OPTIMAL', '"Más joven"': 'Level.YOUNGER', '"Mayor que tu edad"': 'Level.OLDER_THAN_AGE',
     '"En rango"': 'Level.IN_RANGE', '"Fuera de rango"': 'Level.OUT_OF_RANGE'}
for k, v in m.items():
    s = s.replace('Classification(' + k + ',', 'Classification(' + v + ',')
    s = re.sub(r'assertEquals\(' + re.escape(k) + r', (Classifier\.[^\n]*)\.label\)', r'assertEquals(' + v + r', \1.level)', s)
s = s.replace('''        assertEquals(1, type!!.index)
        assertEquals("Sobrepeso", type.name)''', '''        assertEquals(1, type!!.ordinal)
        assertEquals(BodyType.OVERWEIGHT, type)''')
s = s.replace('assertEquals("Delgado musculoso", Classifier.bodyTypeByIndex(8).name)', 'assertEquals(BodyType.SKINNY_MUSCULAR, Classifier.bodyTypeByIndex(8))')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
