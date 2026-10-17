# Translations

GlobalFitness follows the phone's language (and the per-app language in Android 13+:
*Settings → Apps → GlobalFitness → Language*, also reachable from *Profile → Language*). English is the
default for any language without a translation.

| Language | Android resources | Play Store listing |
|---|---|---|
| English (default) | `values` | `docs/store-listing/en-US.md` |
| Spanish | `values-es` | `docs/store-listing/es-ES.md` |
| Portuguese (Brazil) | `values-pt-rBR` | `docs/store-listing/pt-BR.md` |
| Portuguese (Portugal) | `values-pt-rPT` | `docs/store-listing/pt-PT.md` |
| French | `values-fr` | `docs/store-listing/fr-FR.md` |
| German | `values-de` | `docs/store-listing/de-DE.md` |
| Italian | `values-it` | `docs/store-listing/it-IT.md` |
| Russian | `values-ru` | `docs/store-listing/ru-RU.md` |
| Chinese (Simplified) | `values-zh-rCN` | `docs/store-listing/zh-CN.md` |
| Japanese | `values-ja` | `docs/store-listing/ja-JP.md` |
| Korean | `values-ko` | `docs/store-listing/ko-KR.md` |
| Turkish | `values-tr` | `docs/store-listing/tr-TR.md` |
| Polish | `values-pl` | `docs/store-listing/pl-PL.md` |
| Hindi | `values-hi` | `docs/store-listing/hi-IN.md` |
| Arabic (right-to-left) | `values-ar` | `docs/store-listing/ar.md` |
| Indonesian | `values-in` | `docs/store-listing/id.md` |
| Vietnamese | `values-vi` | `docs/store-listing/vi.md` |

## Review by native speakers is recommended

English and Spanish are the reference texts. The other 15 languages were translated with an AI model
and checked automatically (every key present, placeholders and plural forms intact, Play limits), but
**no native speaker has reviewed them yet**. Before relying on them, and especially before publishing the
store listings, please have a native speaker check:

- the body-composition and health terms (glossary below) against what fitness apps use in that language;
- the cautious medical wording (estimates, "not a medical device");
- texts that quote other screens' labels (they must match those labels exactly);
- short labels in the bottom bar, chips and buttons, on a small phone.

Corrections go into `tools/i18n/src/<locale>/*.txt`; see `tools/i18n/TRANSLATING.md` for the format, the
plural categories of each language and how to regenerate and check the resources
(`python tools/i18n/gen.py app/src/main/res --check`). Store listings are checked with
`python tools/i18n/check_listing.py`.

## What stays untranslated on purpose

- Brand, model and protocol names (Xiaomi, Mi Body Composition Scale 2, Health Connect, Bluetooth LE,
  GATT, MGB…), and Xiaomi's own term "bind key".
- The band's connection log (*Devices → Band → Open direct mode*): it stays in English so bug reports
  can be read by the developers; the screen says so in the user's language.
- The privacy policy exists in Spanish (`docs/privacy-policy.md`, the legal reference) and English
  (`docs/privacy-policy.en.md`). The app shows the English copy in every language other than Spanish;
  the web version (`hosting/public/privacy.html`) is Spanish with an English summary.

## Glossary

The terms each translation uses for the key concepts, as they appear in the app (taken from the
sources). Keep them consistent when editing.

GLOSSARY_TABLES
