#!/usr/bin/env bash
set -euo pipefail

# This fixture changes permissions and stores a synthetic registration: dedicated AVD only.
CALLMAP_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CALLMAP_ADB="$CALLMAP_SDK/platform-tools/adb"
CALLMAP_SERIAL="${1:?Usage: bash scripts/test-reboot-recovery.sh emulator-5554 prepare|reboot|process-restart|disable-accessibility|enable-accessibility|status}"
CALLMAP_MODE="${2:-status}"
CALLMAP_PACKAGE="com.callmap.agenttracker"
CALLMAP_SERVICE="$CALLMAP_PACKAGE/.service.MyAccessibilityService"
CALLMAP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CALLMAP_REPORT="$(mktemp -d /private/tmp/callmap-recovery.XXXXXX)"

adb_test() { "$CALLMAP_ADB" -s "$CALLMAP_SERIAL" "$@"; }
[[ "$CALLMAP_SERIAL" == emulator-* ]] || { echo 'Refusing to modify a physical device'; exit 1; }
[[ "$(adb_test shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 1
adb_test emu avd name | tr -d '\r' | grep -qx 'CallMap_Recovery_API_35' || {
    echo 'Use the dedicated CallMap_Recovery_API_35 AVD'; exit 1;
}

fixture() {
    adb_test shell am instrument -w -r \
        -e callmapRecoveryFixture true \
        -e class "com.callmap.agenttracker.RecoveryFixtureTest#$1" \
        "$CALLMAP_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > "$CALLMAP_REPORT/fixture-$1.txt"
    cat "$CALLMAP_REPORT/fixture-$1.txt"
    grep -q 'OK (1 test)' "$CALLMAP_REPORT/fixture-$1.txt"
}

wait_for_boot() {
    for ((i=0; i<120; i++)); do
        if [[ "$(adb_test shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
            adb_test shell input keyevent KEYCODE_WAKEUP
            adb_test shell wm dismiss-keyguard
            return
        fi
        sleep 1
    done
    echo 'Emulator did not finish booting'; exit 1
}

case "$CALLMAP_MODE" in
    prepare)
        adb_test install -r "$CALLMAP_ROOT/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
        adb_test install -r "$CALLMAP_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        fixture prepare
        for permission in RECORD_AUDIO ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION READ_PHONE_STATE CALL_PHONE READ_CALL_LOG READ_CONTACTS; do
            adb_test shell pm grant "$CALLMAP_PACKAGE" "android.permission.$permission"
        done
        adb_test shell appops set "$CALLMAP_PACKAGE" MANAGE_EXTERNAL_STORAGE allow
        adb_test shell dumpsys deviceidle whitelist +"$CALLMAP_PACKAGE"
        adb_test shell cmd location set-location-enabled true
        adb_test shell settings put secure enabled_accessibility_services "$CALLMAP_SERVICE"
        adb_test shell settings put secure accessibility_enabled 1
        ;;
    reboot)
        fixture verifySession
        adb_test reboot
        wait_for_boot
        ;;
    process-restart)
        fixture verifySession
        adb_test shell am force-stop "$CALLMAP_PACKAGE"
        ;;
    disable-accessibility)
        fixture verifySession
        adb_test shell settings delete secure enabled_accessibility_services
        adb_test shell settings put secure accessibility_enabled 0
        ;;
    enable-accessibility)
        fixture verifySession
        adb_test shell settings put secure enabled_accessibility_services "$CALLMAP_SERVICE"
        adb_test shell settings put secure accessibility_enabled 1
        ;;
    status) ;;
    *) echo "Unknown mode: $CALLMAP_MODE"; exit 1 ;;
esac

fixture verifySession
adb_test shell am start -W -n "$CALLMAP_PACKAGE/.MainActivity"
for ((i=0; i<15; i++)); do
    adb_test shell uiautomator dump /sdcard/callmap-recovery.xml >/dev/null 2>&1 || true
    adb_test pull /sdcard/callmap-recovery.xml "$CALLMAP_REPORT/screen.xml" >/dev/null 2>&1 || true
    if grep -q 'Agent Dashboard' "$CALLMAP_REPORT/screen.xml" 2>/dev/null; then break; fi
    sleep 1
done
grep -q 'Agent Dashboard' "$CALLMAP_REPORT/screen.xml"
grep -q 'Recovery Test User' "$CALLMAP_REPORT/screen.xml"
if [[ "$CALLMAP_MODE" == disable-accessibility ]]; then
    grep -q 'Accessibility needs attention' "$CALLMAP_REPORT/screen.xml"
fi
adb_test shell dumpsys accessibility > "$CALLMAP_REPORT/accessibility.txt"
adb_test exec-out screencap -p > "$CALLMAP_REPORT/screen.png"
echo "PASS: same user reaches Home ($CALLMAP_MODE). Evidence: $CALLMAP_REPORT"
