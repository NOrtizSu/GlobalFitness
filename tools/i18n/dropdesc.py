import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().split('\n')
drop = set(sys.argv[2:])
out = [l for l in lines if not any(l.startswith(k + ' =') or l.startswith(k + '{') for k in drop) and not (l.startswith('body_type_') and '_desc =' in l)]
open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
