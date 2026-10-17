"""Checks the Google Play listing texts in docs/store-listing/*.md against the Play limits.

Each file has three sections, in this order: the app title (max. 30 characters), the short
description (max. 80) and the full description (max. 4000), each under its own `## ` heading.
Usage: python tools/i18n/check_listing.py [docs/store-listing/<locale>.md ...]
"""
import glob
import os
import sys

LIMITS = [('title', 30), ('short description', 80), ('full description', 4000)]


def sections(path):
    parts = []
    current = None
    for line in open(path, encoding='utf-8').read().split('\n'):
        if line.startswith('## '):
            current = []
            parts.append(current)
        elif current is not None:
            current.append(line)
    return ['\n'.join(p).strip() for p in parts]


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'store-listing')
    files = sys.argv[1:] or sorted(glob.glob(os.path.join(root, '*.md')))
    failed = False
    for path in files:
        texts = sections(path)
        name = os.path.basename(path)
        if len(texts) != 3:
            print(f'{name}: expected 3 sections, found {len(texts)}')
            failed = True
            continue
        report = []
        for (label, limit), text in zip(LIMITS, texts):
            n = len(text)
            ok = 0 < n <= limit and (label == 'full description' or '\n' not in text)
            failed |= not ok
            report.append(f'{label} {n}/{limit}{"" if ok else " FAIL"}')
        print(f'{name}: ' + ', '.join(report))
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
