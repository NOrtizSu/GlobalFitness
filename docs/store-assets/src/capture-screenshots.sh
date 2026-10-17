#!/bin/bash
# Captures the Play phone screenshots (1080x1920, 9:16) of the DEBUG build on an emulator.
#
# Prerequisites (all done by hand once, see ../README.md):
#   * AVD Medium_Phone_i18n on port 5556, debug APK installed, onboarding finished (profile "Alex"),
#     "Simular premium" switched on (Perfil -> Quitar anuncios) so no ads appear, consent dialog dismissed,
#     emulator status bar in demo mode, `adb shell wm size 1080x1920` (undo with `wm size reset`).
#   * Sample data seeded with the debug receivers (adb broadcast, needs DUMP):
#       am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugMeasurementSeedReceiver --ei days 121
#       am broadcast -n com.enjoyyourtime.globalfitness/.health.debug.DebugHealthSeedReceiver --ei days 30
#     (then open Activity -> "Sync now"), and the Xiaomi Mi Body Composition Scale 2 added from Devices -> catalog.
#   * Emulator clock on the seeding evening (Pacific/Fiji, Sat 17 Oct 2026 20:14, see ../README.md): the app
#     syncs Health Connect when its process starts, so "Last: Today, 8:14 PM" comes from that clock.
#
# Usage:  ANDROID_HOME=... ./capture-screenshots.sh en-US out/en-US     (any locale of the OFFSETS table below)
# Scrolling is a slow drag that stops before lifting the finger (no fling), so the content moves exactly
# the requested number of pixels; the offsets put each card just below the top bar.
set -u
ADB="${ANDROID_HOME:?}/platform-tools/adb"
# Retries when the adb server restarts or the emulator is briefly offline (another adb client can restart the server).
A() { local i; for i in 1 2 3 4 5; do "$ADB" -s emulator-5556 "$@" && return 0
  sleep 3; "$ADB" -s emulator-5556 wait-for-device >/dev/null 2>&1; done; return 1; }
LOC=$1; OUT=$2; PKG=com.enjoyyourtime.globalfitness

# Content offsets in px (body chart, body composition, heart rate, sleep) per locale; text length and script
# change the height of the cards above, so each locale needs its own values.
case "$LOC" in
  en-US) OFF="354 2271 2262 4891" ;;
  es-ES) OFF="353 2263 2230 4955" ;;
  pt-BR) OFF="354 2334 2272 4975" ;;
  pt-PT) OFF="354 2271 2272 4975" ;;
  fr-FR) OFF="354 2271 2409 5112" ;;
  de-DE) OFF="354 2271 2272 4975" ;;
  it-IT) OFF="354 2271 2314 4975" ;;
  ru-RU) OFF="354 2271 2325 5133" ;;
  zh-CN) OFF="354 2307 2282 4967" ;;
  ja-JP) OFF="354 2295 2266 4951" ;;
  ko-KR) OFF="354 2295 2266 4951" ;;
  tr-TR) OFF="354 2271 2272 4975" ;;
  pl-PL) OFF="354 2271 2314 5017" ;;
  hi-IN) OFF="354 2271 2234 4895" ;;
  ar)    OFF="354 2433 2412 5181" ;;
  id)    OFF="354 2271 2230 4933" ;;
  vi)    OFF="354 2271 2283 5028" ;;
  *) echo "No scroll offsets for $LOC: add them to the table" >&2; exit 1 ;;
esac
read -r BODY COMP HR SLEEP <<< "$OFF"

mkdir -p "$OUT"
# No force-stop: Profile shows "Ad privacy options" only in a process that refreshed the ad consent, i.e. one started
# with premium off and switched to "Simulate premium" afterwards (see ../README.md). Changing the locale keeps it.
A shell cmd locale set-app-locales $PKG --locales "$LOC"; sleep 2
A shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 8

# Swipes and drags run at x=1062 (outside the cards) so they never touch a chart.
totop() { for i in 1 2 3 4 5 6; do A shell input swipe 1062 300 1062 1500 120; done; sleep 0.8; }
SLOP=21   # touch slop (8 dp at 420 dpi): the first 21 px of a drag do not scroll
scrollby() { local n=$1 d y s cmd; while [ "$n" -gt 0 ]; do d=$n; [ $d -gt 1250 ] && d=1250
    cmd="input motionevent DOWN 1062 1580"; y=1580
    for s in 1 2 3 4 5; do y=$((1580 - (d + SLOP) * s / 5)); cmd="$cmd; input motionevent MOVE 1062 $y"; done
    A shell "$cmd; sleep 0.4; input motionevent UP 1062 $y"; n=$((n - d)); done; sleep 1.5; }
snap() { MSYS_NO_PATHCONV=1 A exec-out screencap -p > "$OUT/$1.png"; }
# Bottom bar x: Today 100, Body 320, Activity 540, Devices 760, Profile 980 (mirrored in right-to-left locales);
# y is 162 px above the bottom edge (1758 at 1080x1920, 2238 at the native 1080x2400).
TABY=$(( $(A shell wm size | tr -d '\r' | tail -1 | sed 's/.*x//') - 162 ))
tab() { local x=$1; case "$LOC" in ar*|fa*|he*|iw*|ur*) x=$((1080 - x)) ;; esac; A shell input tap $x $TABY; sleep 2.5; }

tab 100; totop; sleep 1; snap 01-today
tab 320; totop; scrollby $BODY; snap 02-body
totop; scrollby $COMP; snap 03-body-composition
tab 540; totop; snap 04-activity
scrollby $HR; snap 05-heart-rate
totop; scrollby $SLEEP; snap 06-sleep
tab 760; totop; sleep 1; snap 07-devices
tab 980; totop; sleep 1; snap 08-profile
# Play wants 24-bit PNG without alpha: convert with Pillow (Image.open(f).convert("RGB").save(f)).
