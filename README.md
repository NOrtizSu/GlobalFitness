# Fortia — source code

This repository publishes the complete source code (the "Corresponding Source") of every version of
**Fortia** released on Google Play, as the GNU Affero General Public License v3.0 or later requires
(see [LICENSE](LICENSE) and [NOTICE](NOTICE)). Fortia includes code ported from
[openScale](https://github.com/oliexdev/openScale) (GPL-3.0-or-later) and
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0-or-later).

- Each tag `vX.Y.Z` is the exact source of that published version. The Android package is
  `com.enjoyyourtime.globalfitness` (the app was called GlobalFitness before 1.1.0).
- Development happens elsewhere; this repository is updated when a version is released.
- Questions about the source or the licence: enjoyyourtime.online@gmail.com.

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 36), or Android Studio.

```
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
./gradlew :app:testDebugUnitTest
```

- **Release signing** reads the `GLOBALFITNESS_UPLOAD_*` Gradle properties (for example in
  `~/.gradle/gradle.properties`); without them the release build is left unsigned.
- **Ads**: without the AdMob IDs in those Gradle properties, builds use Google's test ad units.
- **Strings** are generated: edit `tools/i18n/src/<locale>/*.txt` (English and Spanish in
  `tools/i18n/src/master/`) and run `python tools/i18n/gen.py app/src/main/res`.
- The in-app privacy policy is built from `docs/privacy-policy.md` and `docs/privacy-policy.en.md`.
- `app/google-services.json` holds a Firebase Android key restricted to this app's package and signing
  certificates; cloud backup only works with the official builds.

---

**Español.** Este repositorio publica el código fuente completo de cada versión de Fortia publicada en
Google Play, como exige la licencia AGPL-3.0 (Fortia incluye código de openScale y Gadgetbridge). Cada
etiqueta `vX.Y.Z` es el código exacto de esa versión. El desarrollo se hace en otro sitio; aquí se
publica el código de cada versión al lanzarla.
