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
| `feature-graphic-1024x500-<locale>.png` | 1024x500, 24-bit PNG, no alpha | Feature graphic of that listing language (`pt-BR`, `pt-PT`, `fr-FR`, `de-DE`, `it-IT`, `ru-RU`, `zh-CN`, `ja-JP`, `ko-KR`, `tr-TR`, `pl-PL`, `hi-IN`, `ar`, `id`, `vi`) |
| `screenshots/en-US/01..08-*.png` | 1080x1920 (9:16), 24-bit PNG, no alpha | Phone screenshots, default language (en-US) |
| `screenshots/es-ES/01..08-*.png` | 1080x1920 (9:16), 24-bit PNG, no alpha | Phone screenshots, Spanish (es-ES) listing |
| `screenshots/<locale>/01..08-*.png` | 1080x1920 (9:16), 24-bit PNG, no alpha | Phone screenshots of the other 15 listing languages (same locale folders as the feature graphics) |
| `screenshots-20x9/<locale>/*.png` | 1080x2400 (9:20), 24-bit PNG | NOT for upload (see below); high-resolution source, also used by the feature graphic mock-up (en-US / es-ES: all 8; other locales: only `01-today` and `02-body`, the two the mock-up shows) |
| `src/` | HTML / shell sources | Not uploaded; to regenerate the assets |

Play Console language codes: `ar` is Arabic, `id` Indonesian, `vi` Vietnamese; the other folders are the
Play locale codes as shown (e.g. `zh-CN` Chinese (Simplified), `hi-IN` Hindi).

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
only as a source. All 17 listing languages now have their own screenshots and feature graphic; a language added later
falls back to the default-language graphics until its own are uploaded.

## What the pictures show (and do not)

* Sample data is fictional: user "Alex", 36, 178 cm, goal 76 kg; weight 82 -> 78 kg over four months, a Mi Body Composition
  Scale 2 added from the catalog, 30 days of band data written to Health Connect by the debug seeder.
* Status bar in demo mode: 8:00, full battery, Wi-Fi, no notifications. The clock reads 8:00 (not 10:00) on purpose: the
  emulator timezone was set to Pacific/Fiji so that "today" already holds a full day of data, and 8:00 matches the evening
  figures on screen.
* Ads: the debug build's "Simulate premium" switch was on, so no test-ad banner appears. The ad consent dialog was declined.
* The scale and band names in the screenshots are the app's own text (model names), not logos.
* `07-devices` shows the "Bands & watches" section with no band set up yet ("Add from the catalog") in every locale;
  en-US and es-ES were re-captured for it with the same setup (their other screenshots are unchanged).

## Icon

`src/icon.html` renders the adaptive icon (gradient background `ic_launcher_background` + white glyph
`ic_launcher_foreground`) as a full-bleed square. The 108 dp canvas is cropped to the central 72 dp, so the glyph has the
same proportions as on a launcher; Play applies its own rounded mask. No text, no badges.

## Feature graphic

`src/feature-graphic.html` (HTML/CSS, Roboto plus Noto Sans SC / JP / KR / Devanagari / Arabic from Google Fonts for
the scripts Roboto lacks, so it needs internet access to render with the right fonts). Brand-blue gradient from the
icon, app icon, name "GlobalFitness", tagline and two phone mock-ups with real screenshots (`screenshots-20x9/<locale>`).
`?lang=<locale>` switches tagline and screenshots (`es` and the Play locale codes of the table above). Arabic is
right-to-left: the whole composition is mirrored (copy on the right, phones on the left). A tagline that would need a
third line is shrunk a little (34 px down to 26 px at most); none of the current ones needs it.

* en: "Smart scales, bands & health — in one app"
* es: "Básculas, pulseras y salud en una sola app"
* pt-BR: "Balanças, pulseiras e saúde em um só app"
* pt-PT: "Balanças, pulseiras e saúde numa só app"
* fr-FR: "Balances, bracelets et santé dans une seule app"
* de-DE: "Waagen, Fitnessbänder und Gesundheit in einer App"
* it-IT: "Bilance, smartband e salute in un’unica app"
* ru-RU: "Весы, браслеты и здоровье в одном приложении"
* zh-CN: "智能体重秤、手环与健康，尽在一款应用"
* ja-JP: "スマート体重計、バンド、健康管理をひとつのアプリで"
* ko-KR: "스마트 체중계, 밴드, 건강 관리를 하나의 앱으로"
* tr-TR: "Akıllı tartılar, bileklikler ve sağlık tek uygulamada"
* pl-PL: "Wagi, opaski i zdrowie w jednej aplikacji"
* hi-IN: "स्मार्ट स्केल, बैंड और सेहत — सब एक ही ऐप में"
* ar: "الموازين الذكية والأساور وصحتك في تطبيق واحد"
* id: "Timbangan, gelang, dan kesehatan dalam satu aplikasi"
* vi: "Cân, vòng đeo tay và sức khỏe trong một ứng dụng"

Render at exactly 1024x500 (then convert to RGB without alpha, as for the screenshots):

```
chrome --headless=new --hide-scrollbars --force-device-scale-factor=1 --window-size=1024,500 \
       --virtual-time-budget=8000 --allow-file-access-from-files \
       --screenshot=feature-graphic-1024x500.png "file:///.../docs/store-assets/src/feature-graphic.html"
# e.g. ...feature-graphic-1024x500-de-DE.png "file:///.../feature-graphic.html?lang=de-DE"
```

## Regenerating the screenshots

1. Emulator: `emulator -avd Medium_Phone_i18n -port 5556 -no-window -no-snapshot-save`; use `adb -s emulator-5556` only.
2. Build and install the debug APK, finish the onboarding, switch on Profile > Remove ads > "Simulate premium",
   decline the consent form, add the Mi Body Composition Scale 2 from Devices > catalog.
3. Seed data (`app/src/debug`): `DebugMeasurementSeedReceiver --ei days 121` and `DebugHealthSeedReceiver --ei days 30`
   via `adb shell am broadcast -n ...`, then Activity > "Sync now". Health Connect only shows steps once the app has
   been granted the read permissions (`pm grant ... android.permission.health.READ_*` / `WRITE_*`, see the receivers).
4. Demo status bar: `settings put global sysui_demo_allowed 1`, then `am broadcast -a com.android.systemui.demo -e command ...`
   (`enter`, `clock -e hhmm 0800`, `battery -e level 100 -e plugged false`,
   `network -e wifi show -e level 4 -e fully true -e mobile hide`, `notifications -e visible false`).
5. Clock: the data was seeded on the evening of Sat 17 Oct 2026 in Pacific/Fiji. To get the same pictures later, turn
   off automatic time and put the clock back before starting the app and again before each run (the app syncs Health
   Connect when its process starts, which gives "Last: Today, 8:14 PM"); shell can do it on the Play image without root:
   `cmd time_detector set_auto_detection_enabled false`, `cmd alarm set-timezone Pacific/Fiji`,
   `cmd alarm set-time 1792224840000` (2026-10-17 20:14 Fiji). Afterwards `cmd time_detector set_auto_detection_enabled true`.
6. "Ad privacy options" (Profile) only appears in an app process that refreshed the ad consent, and a premium process
   skips that refresh. So, with the app stopped, turn the simulated premium off
   (`run-as com.enjoyyourtime.globalfitness sed -i '/debug_simulated_premium/s/true/false/' shared_prefs/billing_cache.xml`),
   start the app, wait a few seconds, then Profile > Remove ads > "Simulate premium" on (the test banner disappears) and
   keep that process for all runs: the script only switches the locale, it never restarts the app.
7. `adb shell wm size 1080x1920`, then `src/capture-screenshots.sh <locale> screenshots/<locale>` for each locale
   (app locale = folder name; `id` shows the app's `values-in` Indonesian); convert the PNGs to RGB (no alpha).
   For the feature-graphic sources run it once more after `adb shell wm size reset` (native 1080x2400) into
   `screenshots-20x9/<locale>` and keep `01-today` / `02-body`.

Scroll offsets: the script drags slowly and holds still before lifting the finger, so there is no fling and the list
moves exactly the requested pixels (it adds the 21 px touch slop, which does not scroll). Translations change the
height of the cards above, so each locale has its own row in the `case` table of `capture-screenshots.sh` (body chart,
body composition, heart rate, sleep). They were calibrated with `uiautomator dump`: the card title must land at the
same y in every locale (title top 278 px for the weight chart, 303-304 px for the composition, heart rate and sleep
cards, i.e. the card just below the top bar). The en-US and es-ES rows were fitted to the committed pictures instead
(en-US re-captures pixel for pixel); their heart rate / sleep framing is a few dozen pixels off the other locales
because those two sets were first taken with fast swipes.
