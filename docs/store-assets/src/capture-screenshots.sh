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
#
# Usage:  ANDROID_HOME=... ./capture-screenshots.sh en-US out/en-US     (also es-ES)
# The scroll offsets are empirical for this seeded data; they put each card just below the top bar.
set -u
ADB="${ANDROID_HOME:?}/platform-tools/adb"
A() { "$ADB" -s emulator-5556 "$@"; }
LOC=$1; OUT=$2; PKG=com.enjoyyourtime.globalfitness
mkdir -p "$OUT"
A shell cmd locale set-app-locales $PKG --locales "$LOC"; sleep 2
A shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 4

# Swipes start at x=1062 (outside the cards) so they never touch a chart; slow swipes avoid fling.
totop() { for i in 1 2 3 4 5 6; do A shell input swipe 1062 300 1062 1500 120; done; sleep 0.8; }
scrollby() { local n=$1; while [ $n -gt 0 ]; do local d=$n; [ $d -gt 1100 ] && d=1100
  A shell input swipe 1062 1500 1062 $((1500-d)) $((d*13/10+200)); n=$((n-d)); done; sleep 1.5; }
snap() { MSYS_NO_PATHCONV=1 A exec-out screencap -p > "$OUT/$1.png"; }
tab() { A shell input tap $1 1758; sleep 2.5; }   # x: Today 100, Body 320, Activity 540, Devices 760, Profile 980

SLEEP=4760; [ "$LOC" = "es-ES" ] && SLEEP=4805
tab 100; totop; sleep 1; snap 01-today
tab 320; totop; scrollby 340; snap 02-body
totop; scrollby 2210; snap 03-body-composition
tab 540; totop; snap 04-activity
scrollby 2180; snap 05-heart-rate
totop; scrollby $SLEEP; snap 06-sleep
tab 760; totop; sleep 1; snap 07-devices
tab 980; totop; sleep 1; snap 08-profile
# Play wants 24-bit PNG without alpha: convert with Pillow (Image.open(f).convert("RGB").save(f)).
