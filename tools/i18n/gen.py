"""Generates the Android string resources (app/src/main/res/values*/strings.xml) from the sources.

src/master/*.txt : `key = English ||| Spanish` (plurals: `key{one} = ...`), `!key = text` for
                   non-translatable strings, `# comment` lines become XML comments in values/.
src/<locale>/*.txt (or src/<locale>.txt): `key = text` / `key{cat} = text` for every translatable key.

Texts are written unescaped (apostrophes and quotes as they are, `\\n` for a line break, `%%` for a
literal percent sign in strings with `%1$s` placeholders). The script checks that every locale has
every key, the same placeholders and the plural categories its language needs, and escapes the XML.

Usage: python tools/i18n/gen.py app/src/main/res [--check]
       python tools/i18n/gen.py --only <locale>   (validates one locale, writes nothing)
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'src')

# locale file -> resource folder qualifier
LOCALES = {
    'es': 'es', 'pt-BR': 'pt-rBR', 'pt-PT': 'pt-rPT', 'fr': 'fr', 'de': 'de', 'it': 'it', 'ru': 'ru',
    'zh-CN': 'zh-rCN', 'ja': 'ja', 'ko': 'ko', 'tr': 'tr', 'pl': 'pl', 'hi': 'hi', 'ar': 'ar',
    'id': 'in', 'vi': 'vi',
}

# CLDR plural categories (integers and decimals) per language.
PLURALS = {
    'en': ['one', 'other'],
    'es': ['one', 'many', 'other'],
    'pt-BR': ['one', 'many', 'other'],
    'pt-PT': ['one', 'many', 'other'],
    'fr': ['one', 'many', 'other'],
    'de': ['one', 'other'],
    'it': ['one', 'many', 'other'],
    'ru': ['one', 'few', 'many', 'other'],
    'zh-CN': ['other'],
    'ja': ['other'],
    'ko': ['other'],
    'tr': ['one', 'other'],
    'pl': ['one', 'few', 'many', 'other'],
    'hi': ['one', 'other'],
    'ar': ['zero', 'one', 'two', 'few', 'many', 'other'],
    'id': ['other'],
    'vi': ['other'],
}

LINE = re.compile(r'^(!?)([a-z0-9_]+)(?:\{(zero|one|two|few|many|other)\})?\s=\s?(.*)$')
PH = re.compile(r'%(\d+)\$([sdf]|\.\d+f)')


def read_lines(path):
    if os.path.isdir(path):
        for name in sorted(os.listdir(path)):
            if name.endswith('.txt'):
                with open(os.path.join(path, name), encoding='utf-8') as f:
                    for n, raw in enumerate(f, 1):
                        yield f'{name}:{n}', raw
    else:
        with open(path, encoding='utf-8') as f:
            for n, raw in enumerate(f, 1):
                yield n, raw


def parse(path, master=False):
    """Returns (entries, order, comments). entries: key -> {'text'|cat: str, 'nt': bool}."""
    entries = {}
    order = []
    items = []  # ('comment', text) or ('key', key)
    if True:
        for n, raw in read_lines(path):
            line = raw.rstrip('\n').rstrip('\r')
            if not line.strip():
                continue
            if line.startswith('#'):
                items.append(('comment', line[1:].strip()))
                continue
            m = LINE.match(line)
            if not m:
                raise SystemExit(f'{path}:{n}: bad line: {line!r}')
            nt, key, cat, text = m.groups()
            text = text.strip()
            if master:
                if nt:
                    en, es = text, None
                else:
                    if ' ||| ' not in text:
                        raise SystemExit(f'{path}:{n}: missing Spanish: {line!r}')
                    en, es = (t.strip() for t in text.split(' ||| ', 1))
                value = {'en': en, 'es': es}
            else:
                value = text
            e = entries.get(key)
            if e is None:
                e = {'nt': bool(nt), 'plural': cat is not None, 'forms': {}}
                entries[key] = e
                order.append(key)
                items.append(('key', key))
            elif e['plural'] != (cat is not None) or not cat:
                raise SystemExit(f'{path}:{n}: duplicate key {key}')
            if cat in e['forms']:
                raise SystemExit(f'{path}:{n}: duplicate plural {key}{{{cat}}}')
            e['forms'][cat or ''] = value
    return entries, order, items


def esc(text):
    s = text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace('\\n', '\x00')  # keep \n escapes
    s = s.replace('\\', '\\\\')
    s = s.replace('\x00', '\\n')
    s = s.replace("'", "\\'").replace('"', '\\"')
    if s.startswith('@') or s.startswith('?'):
        s = '\\' + s
    return s


def attrs(text):
    if '%' in text and not PH.search(text):
        return ' formatted="false"'
    return ''


def placeholders(text):
    return sorted(set(m.group(0) for m in PH.finditer(text)))


def check_format(key, text, where):
    if "\\'" in text or '\\"' in text:
        raise SystemExit(f'{where}: {key}: write quotes unescaped: {text!r}')
    # Every % must be a positional placeholder or %%.
    stripped = PH.sub('', text).replace('%%', '')
    if PH.search(text) and '%' in stripped:
        raise SystemExit(f'{where}: {key}: stray % in formatted string: {text!r}')


def write_xml(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    out += rows
    out.append('</resources>')
    data = '\n'.join(out) + '\n'
    if os.path.exists(path) and open(path, encoding='utf-8').read() == data:
        return
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(data)


def render(entries, order, lang, pick, items=None):
    rows = []
    seq = items if items is not None else [('key', k) for k in order]
    for kind, val in seq:
        if kind == 'comment':
            rows.append(f'    <!-- {val} -->')
            continue
        key = val
        e = entries[key]
        if e['nt'] and lang != 'en':
            continue
        if e['plural']:
            rows.append(f'    <plurals name="{key}">')
            for cat in PLURALS[lang]:
                text = pick(key, cat)
                rows.append(f'        <item quantity="{cat}">{esc(text)}</item>')
            rows.append('    </plurals>')
        else:
            text = pick(key, '')
            nt = ' translatable="false"' if e['nt'] else ''
            rows.append(f'    <string name="{key}"{nt}{attrs(text)}>{esc(text)}</string>')
    return rows


def main():
    only = sys.argv[sys.argv.index('--only') + 1] if '--only' in sys.argv else None
    res = None if only else sys.argv[1]
    master, order, items = parse(os.path.join(SRC, 'master'), master=True)
    errors = []

    for key in order:
        e = master[key]
        for cat, v in e['forms'].items():
            check_format(key, v['en'], 'en')
            if v['es'] is not None:
                check_format(key, v['es'], 'es')
        if e['plural'] and set(e['forms']) != {'one', 'other'}:
            errors.append(f'master: {key}: plurals need one/other')

    def en_pick(key, cat):
        return master[key]['forms'][cat]['en']

    def es_pick(key, cat):
        forms = master[key]['forms']
        if cat == 'many':
            cat = 'other'
        return forms[cat]['es']

    def en_ref(key):
        forms = master[key]['forms']
        return forms.get('other', forms.get(''))['en']

    # Spanish placeholders.
    for key in order:
        e = master[key]
        if e['nt']:
            continue
        ref = placeholders(en_ref(key))
        for cat, v in e['forms'].items():
            got = placeholders(v['es'])
            if got != ref and not (e['plural'] and cat == 'one' and set(got) <= set(ref)):
                errors.append(f'es: {key}{{{cat}}}: placeholders {got} != {ref}')

    if res:
        write_xml(os.path.join(res, 'values', 'strings.xml'), render(master, order, 'en', en_pick, items))
        write_xml(os.path.join(res, 'values-es', 'strings.xml'), render(master, order, 'es', es_pick))

    translatable = [k for k in order if not master[k]['nt']]
    done = ['en', 'es']
    for loc, folder in LOCALES.items():
        if loc == 'es' or (only and loc != only):
            continue
        path = os.path.join(SRC, loc)
        if not os.path.isdir(path):
            path = os.path.join(SRC, f'{loc}.txt')
        if not os.path.exists(path):
            errors.append(f'{loc}: missing file')
            continue
        tr, _, _ = parse(path)
        missing = [k for k in translatable if k not in tr]
        extra = [k for k in tr if k not in master or master[k]['nt']]
        if missing:
            errors.append(f'{loc}: {len(missing)} missing: {missing[:12]}')
        if extra:
            errors.append(f'{loc}: {len(extra)} extra: {extra[:12]}')
        cats = PLURALS[loc]
        for k in translatable:
            if k not in tr:
                continue
            e = master[k]
            t = tr[k]
            if e['plural'] != t['plural']:
                errors.append(f'{loc}: {k}: plural mismatch')
                continue
            ref = placeholders(en_ref(k))
            for cat, v in t['forms'].items():
                check_format(k, v, loc)
                if not v.strip() or '|||' in v:
                    errors.append(f'{loc}: {k}{{{cat}}}: empty or contains |||')
                got = placeholders(v)
                if got != ref and not (e['plural'] and set(got) <= set(ref)):
                    errors.append(f'{loc}: {k}{{{cat}}}: placeholders {got} != {ref}')
            if e['plural']:
                have = set(t['forms'])
                if not set(cats) <= have:
                    errors.append(f'{loc}: {k}: plural categories {sorted(have)} need {cats}')
        if missing or any(e.startswith(loc + ':') for e in errors):
            continue

        def pick(key, cat, tr=tr, cats=cats):
            forms = tr[key]['forms']
            return forms[cat] if cat in forms else forms['other']

        if res:
            write_xml(os.path.join(res, f'values-{folder}', 'strings.xml'), render(master, order, loc, pick))
        done.append(loc)

    n_str = sum(1 for k in order if not master[k]['plural'])
    n_pl = sum(1 for k in order if master[k]['plural'])
    print(f'{n_str} strings, {n_pl} plurals, {len(translatable)} translatable; generated: {", ".join(done)}')
    if errors:
        print('\n'.join(errors[:80]))
        if len(errors) > 80:
            print(f'... {len(errors) - 80} more')
        if '--check' in sys.argv or only:
            sys.exit(1)


if __name__ == '__main__':
    main()
