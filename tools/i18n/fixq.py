import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().split('\n')
out = []
for l in lines:
    if l.endswith("'"):
        l = l[:-1]
    l = l.replace("\\'", "'")
    out.append(l)
open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
