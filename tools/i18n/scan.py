import re, os, sys
root = sys.argv[1]
verbose = len(sys.argv) > 2
pat = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
counts = {}
total = 0
for dp, dn, fn in os.walk(root):
    for f in fn:
        if not f.endswith('.kt'): continue
        p = os.path.join(dp, f)
        n = 0
        for i, line in enumerate(open(p, encoding='utf-8')):
            s = line.strip()
            if s.startswith('//') or s.startswith('*') or s.startswith('/*'): continue
            if re.search(r'\bLog\.[dviwe]\(', line) or 'BandLog.' in line: continue
            for m in pat.finditer(line):
                t = m.group(1)
                if re.search(r'[A-Za-zÁÉÍÓÚáéíóúñÑ¿¡]{2,}', t) and (' ' in t or re.search(r'[áéíóúñ¿¡]', t)):
                    n += 1
                    if verbose:
                        print(f'{os.path.relpath(p, root)}:{i+1}: {t}')
        if n:
            counts[os.path.relpath(p, root)] = n
            total += n
if not verbose:
    for k, v in sorted(counts.items(), key=lambda x: -x[1]):
        print(v, k)
print('TOTAL', total)
