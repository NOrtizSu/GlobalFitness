"""Applies literal replacements from a spec file to a source file (used while extracting strings).

Spec format: blocks separated by lines '=====' ; each block is OLD, a line '-----', then NEW.
A one-line OLD matches a whole line when it can, a part of a line otherwise. Every OLD must match
exactly once.
Usage: python sub.py <file> <spec>
"""
import re
import sys

path, spec = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
blocks = open(spec, encoding='utf-8').read().split('\n=====\n')
for b in blocks:
    if not b.strip():
        continue
    old, new = b.split('\n-----\n')
    if old.startswith('\n'):
        old = old[1:]
    if new.endswith('\n') and not old.endswith('\n'):
        new = new[:-1]
    if '\n' not in old:
        pattern = re.compile('^' + re.escape(old) + '$', re.M)
        n = len(pattern.findall(s))
        if n == 1:
            s = pattern.sub(lambda _: new, s)
            continue
        if n > 1:
            raise SystemExit(f'{path}: expected 1 line match, got {n}: {old[:120]!r}')
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{path}: expected 1 match, got {n}: {old[:120]!r}')
    s = s.replace(old, new)
open(path, 'w', encoding='utf-8', newline='\n').write(s)
print('ok', path)
