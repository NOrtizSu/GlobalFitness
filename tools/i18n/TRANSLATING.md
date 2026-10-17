# Translating GlobalFitness

The app's texts live in `app/src/main/res/values*/strings.xml`, generated from the sources in this
folder by `gen.py`. Edit the sources, not the XML, then run:

```sh
python tools/i18n/gen.py app/src/main/res --check
```

It escapes the XML and fails when a locale misses a key, has a key that no longer exists, changes a
placeholder or lacks a plural category its language needs.

## Files

- `src/master/*.txt` — the reference texts: `key = English ||| Spanish`, one per line.
  `!key = text` marks a text that is never translated. `# ...` lines are comments.
- `src/<locale>/*.txt` — one folder per language, with the same file names as `src/master/`:
  `key = translation`, one per line, every key of the master files.

| Source folder | Android resources | Play Store locale |
|---|---|---|
| (master, English) | `values` | en-US |
| (master, Spanish) | `values-es` | es-ES |
| `pt-BR` | `values-pt-rBR` | pt-BR |
| `pt-PT` | `values-pt-rPT` | pt-PT |
| `fr` | `values-fr` | fr-FR |
| `de` | `values-de` | de-DE |
| `it` | `values-it` | it-IT |
| `ru` | `values-ru` | ru-RU |
| `zh-CN` | `values-zh-rCN` | zh-CN |
| `ja` | `values-ja` | ja-JP |
| `ko` | `values-ko` | ko-KR |
| `tr` | `values-tr` | tr-TR |
| `pl` | `values-pl` | pl-PL |
| `hi` | `values-hi` | hi-IN |
| `ar` | `values-ar` | ar |
| `id` | `values-in` | id |
| `vi` | `values-vi` | vi |

A new language also needs an entry in `gen.py` (`LOCALES`, `PLURALS`) and in
`app/src/main/res/xml/locales_config.xml`.

## Rules

- One entry per line. Write the text as it should appear: no XML escaping, no `\'`. `\n` is a line break.
- Keep every placeholder exactly as in English (`%1$s`, `%2$d`…); their order in the sentence may change.
  `%%` is a literal percent sign and must stay `%%`.
- Plurals (`key{one} = …`) need every category of the language (CLDR):
  en/de/hi/tr `one other`; es/fr/it/pt `one many other` (`many` is used for millions: copy `other`);
  ru/pl `one few many other`; ar `zero one two few many other`; ja/ko/zh/id/vi `other`.
  A `one` text may drop the number ("One measurement") when the language allows it.
- Do not translate names: GlobalFitness, Health Connect, Mi Fitness, Google, Google Play, Xiaomi,
  Gadgetbridge, openScale, Bluetooth, Firebase, scale and band model names, "bind key"
  (the Xiaomi term, kept as is), file and folder names, package names.
- Keep it short: navigation labels, chips and buttons have little room. Prefer the words Android and
  the big fitness apps use in that language.
- Units can follow local usage (`кг`, `كغ`…) as long as `unit_*` and `value_*` agree.
- Use the glossary in `docs/translations.md` so the same concept always has the same word.
