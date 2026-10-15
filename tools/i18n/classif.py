import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
m = {'"Bajo"': 'Level.LOW', '"Normal"': 'Level.NORMAL', '"Sobrepeso"': 'Level.OVERWEIGHT', '"Obesidad"': 'Level.OBESE',
     '"Muy bajo"': 'Level.VERY_LOW', '"Alto"': 'Level.HIGH', '"Muy alto"': 'Level.VERY_HIGH', '"Excelente"': 'Level.EXCELLENT',
     '"Óptimo"': 'Level.OPTIMAL', '"Más joven"': 'Level.YOUNGER', '"Mayor que tu edad"': 'Level.OLDER_THAN_AGE',
     '"En rango"': 'Level.IN_RANGE', '"Fuera de rango"': 'Level.OUT_OF_RANGE'}
for k, v in m.items():
    s = s.replace('Classification(' + k + ',', 'Classification(' + v + ',')
s = s.replace('''data class Classification(val label: String, val status: Status)

data class BodyType(val index: Int, val name: String, val description: String)
''', '''/** Classification chip text; the UI maps it to a string resource. */
enum class Level {
    VERY_LOW, LOW, NORMAL, HIGH, VERY_HIGH, OVERWEIGHT, OBESE, EXCELLENT, OPTIMAL, YOUNGER, OLDER_THAN_AGE, IN_RANGE, OUT_OF_RANGE,
}

data class Classification(val level: Level, val status: Status)

/** Mi Fit body types, by index: fat level (high, normal, low) x 3 + muscle level (low, normal, high). */
enum class BodyType {
    OBESE, OVERWEIGHT, SOLIDLY_BUILT, LACKS_EXERCISE, BALANCED, BALANCED_MUSCULAR, SKINNY, BALANCED_SKINNY, SKINNY_MUSCULAR,
}
''')
s = re.sub(r'    private val BODY_TYPES = listOf\(.*?\n    \)\n\n    fun bodyTypeByIndex\(index: Int\): BodyType \{\n.*?\n    \}\n',
           '''    fun bodyTypeByIndex(index: Int): BodyType = BodyType.entries[index.coerceIn(0, 8)]
''', s, flags=re.S)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
