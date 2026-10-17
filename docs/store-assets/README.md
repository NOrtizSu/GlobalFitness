# Google Play store graphics

Graphics for the Play Console listing of GlobalFitness (`com.enjoyyourtime.globalfitness`).
Everything here is generated from the real app (debug build on an emulator with seeded sample data) or
from the app's own launcher icon; no device brand logos are used.

## Files and where they go in Play Console

Play Console > Grow users > Store presence > Main store listing > Graphics (per language via "Manage translations").

| File | Size / format | Play Console field |
| --- | --- | --- |
| `icon-512.png` | 512x512, 32-bit PNG (RGBA), 80 KB | App icon (all languages) |
| `feature-graphic-1024x500.png` | 1024x500, 24-bit PNG, no alpha | Feature graphic, default language (en-US) and any language without its own |
| `feature-graphic-1024x500-es.png` | 1024x500, 24-bit PNG, no alpha | Feature graphic, Spanish (es-ES) listing |
| `screenshots/en-US/01..08-*.png` | 1080x1920 (9:16), 24-bit PNG, no alpha | Phone screenshots, default language (en-US) |
| `screenshots/es-ES/01..08-*.png` | 1080x1920 (9:16), 24-bit PNG, no alpha | Phone screenshots, Spanish (es-ES) listing |
| `screenshots-20x9/<locale>/*.png` | 1080x2400 (9:20), 24-bit PNG | NOT for upload (see below); high-resolution source, also used by the feature graphic mock-up |
| `src/` | HTML / shell sources | Not uploaded; to regenerate the assets |

Screenshot order (upload in this order, the first ones are the most visible):

1. `01-today` – Today: steps / sleep / resting heart rate rings, weight, body composition
2. `02-body` – Body: weight trend chart with 7-day average, normal range and goal
3. `03-body-composition` – Body: current composition (fat / muscle / bone) and measurement history
4. `04-activity` – Activity: Health Connect daily figures and steps chart
5. `05-heart-rate` – Activity: heart rate (day and 30 days)
6. `06-sleep` – Activity: sleep stages and last 14 nights
7. `07-devices` – Devices: scale section, band, Health Connect
8. `08-profile` – Profile: profile, account and backup, data export

Play's rule for phone screenshots is "the longest side can't be more than twice the shortest side", and promotion
eligibility asks for 9:16 at 1080x1920 or more. The emulator's native 1080x2400 (9:20, ratio 2.22) would be rejected, so
the upload set was captured with `adb shell wm size 1080x1920` (a real 9:16 layout, not a crop). The 9:20 set is kept
only as a source. Other listing languages (the 15 other locales) fall back to the default-language graphics unless you
upload their own; the same capture script produces them (`--locales de-DE`, etc.), but they were not generated here.

## What the pictures show (and do not)

* Sample data is fictional: user "Alex", 36, 178 cm, goal 76 kg; weight 82 -> 78 kg over four months, a Mi Body Composition
  Scale 2 added from the catalog, 30 days of band data written to Health Connect by the debug seeder.
* Status bar in demo mode: 8:00, full battery, Wi-Fi, no notifications. The clock reads 8:00 (not 10:00) on purpose: the
  emulator timezone was set to Pacific/Fiji so that "today" already holds a full day of data, and 8:00 matches the evening
  figures on screen.
* Ads: the debug build's "Simular premium" switch was on, so no test-ad banner appears. The ad consent dialog was declined.
* The scale and band names in the screenshots are the app's own text (model names), not logos.

## Icon

`src/icon.html` renders the adaptive icon (gradient background `ic_launcher_background` + white glyph
`ic_launcher_foreground`) as a full-bleed square. The 108 dp canvas is cropped to the central 72 dp, so the glyph has the
same proportions as on a launcher; Play applies its own rounded mask. No text, no badges.

## Feature graphic

`src/feature-graphic.html` (HTML/CSS, Roboto from Google Fonts, so it needs internet access to render with the right
font). Brand-blue gradient from the icon, app icon, name "GlobalFitness", tagline and two phone mock-ups with real
screenshots. `?lang=es` switches text and screenshots to Spanish.

* en: "Smart scales, bands & health — in one app"
* es: "Básculas, pulseras y salud en una sola app"

Render at exactly 1024x500:

```
chrome --headless=new --hide-scrollbars --force-device-scale-factor=1 --window-size=1024,500 \
       --virtual-time-budget=8000 --allow-file-access-from-files \
       --screenshot=feature-graphic-1024x500.png "file:///.../docs/store-assets/src/feature-graphic.html"
```

## Regenerating the screenshots

1. Emulator: `emulator -avd Medium_Phone_i18n -port 5556 -no-window -no-snapshot-save`; use `adb -s emulator-5556` only.
2. Build and install the debug APK, finish the onboarding, switch on Profile > Remove ads > "Simulate premium",
   decline the consent form, add the Mi Body Composition Scale 2 from Devices > catalog.
3. Seed data (`app/src/debug`): `DebugMeasurementSeedReceiver --ei days 121` and `DebugHealthSeedReceiver --ei days 30`
   via `adb shell am broadcast -n ...`, then Activity > "Sync now". Health Connect only shows steps once the app has
   been granted the read permissions (`pm grant ... android.permission.health.READ_*` / `WRITE_*`, see the receivers).
4. Demo status bar: `settings put global sysui_demo_allowed 1`, then `am broadcast -a com.android.systemui.demo -e command ...`
   (`enter`, `clock -e hhmm 2000`, `battery -e level 100`, `network -e wifi show -e level 4`, `notifications -e visible false`).
5. `adb shell wm size 1080x1920`, then `src/capture-screenshots.sh en-US out/en-US` (and `es-ES`);
   convert the PNGs to RGB (no alpha) and finish with `adb shell wm size reset`.
