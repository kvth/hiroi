#!/usr/bin/env bash
#
# Runs Hiroi in an Android 15 emulator inside podman, set up like a phone in
# use: Japanese system language, the accessibility service on, Hiroi as the
# digital assistant and a dictionary imported. Needs /dev/kvm. The emulator
# image is the `emulator` stage of Containerfile.android.
#
# Usage: scripts/emulator.sh <command>
#   start [dictionary.zip]   boot and set everything up; the dictionary defaults to
#                            the app's example dictionary (scripts/make-example-dictionary.py)
#   view                     open a window showing the emulator that you can click and type in
#                            (closing it leaves the emulator running)
#   install                  reinstall build/outputs/hiroi-debug.apk, keeping app data
#   import <dictionary.zip>  import another Yomitan dictionary
#   anki [deck.apkg]         install AnkiDroid and AnkiConnect Android, import a deck (default: the
#                            Lapis note type) and start AnkiConnect on the emulator's 127.0.0.1:8765
#   yomitan <settings.json>  hand a Yomitan settings export to Hiroi to take a card format from
#   fake-anki                serve a small fake AnkiConnect deck on the emulator's 127.0.0.1:8765,
#                            where Hiroi looks by default (scripts/fake-ankiconnect.py); then pick
#                            the deck Japanese::Core and the field Word in Hiroi's Anki section
#   settings                 open the Settings app (Japanese text to try Hiroi on)
#   app                      open Hiroi, scrolled down to its example sentences
#   trigger                  open the Hiroi overlay on the current screen, as long-pressing power does
#   tap <x> <y>              tap at screen pixels (the screen is 1080x2400)
#   texts                    list the text on screen with the position to tap it. Run it
#                            before trigger: Hiroi's boxes sit exactly there. With the
#                            overlay open it lists the panel's text and buttons
#   screenshot [file.png]    save a screenshot (default build/emulator/screenshot.png)
#   logcat                   Hiroi's log lines and crashes
#   adb <args...>            run any adb command in the emulator
#   stop                     shut the emulator down
#
# Typical round: start, settings, texts (find a box), trigger, tap X Y (the box),
# texts (find the panel), tap X Y (a word in it), screenshot.
# After a code change: ./podman-build-apk.sh && scripts/emulator.sh install

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CONTAINER=hiroi-emulator
ANKIDROID_VERSION=2.25.3
ANKICONNECT_VERSION=1.15
LAPIS_VERSION=1.7.0
VIEWER=hiroi-emulator-view
IMAGE=localhost/hiroi-emulator
PACKAGE=io.github.kvth.hiroi
APK=build/outputs/hiroi-debug.apk
OUT=build/emulator

die() { echo "error: $*" >&2; exit 1; }

adb() { podman exec "$CONTAINER" adb "$@"; }

require_running() {
  [ "$(podman inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = true ] ||
    die "the emulator isn't running; start it with: $0 start"
}

# The viewer shares the emulator's network, so it has to go first.
remove_containers() {
  podman rm -f "$VIEWER" >/dev/null 2>&1 || true
  podman rm -f "$CONTAINER" >/dev/null 2>&1 || true
}

wait_for_boot() {
  for _ in $(seq 1 150); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return
    sleep 2
  done
  die "the emulator did not finish booting"
}

cmd_install() {
  require_running
  [ -f "$APK" ] || die "no APK yet; build it with ./podman-build-apk.sh"
  adb install -r "/apk/$(basename "$APK")" | tail -1
}

cmd_import() {
  require_running
  local zip="${1:?usage: $0 import <dictionary.zip>}" name
  [ -f "$zip" ] || die "$zip not found"
  name="import-$(date +%s).zip"
  # Into the app's own cache, then "open" it with Hiroi; debug builds accept file:// URIs there.
  podman cp "$zip" "$CONTAINER:/tmp/$name"
  adb push "/tmp/$name" "/data/local/tmp/$name" >/dev/null 2>&1
  adb shell run-as "$PACKAGE" cp "/data/local/tmp/$name" "cache/$name"
  adb shell rm "/data/local/tmp/$name"
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -t application/zip \
    -d "file:///data/user/0/$PACKAGE/cache/$name" -n "$PACKAGE/.MainActivity" >/dev/null 2>&1
  echo "==> Importing $(basename "$zip") (big dictionaries take a few minutes)"
  local line=""
  for _ in $(seq 1 900); do
    line="$(adb logcat -d -s HiroiImport:I | grep -E 'Imported|Import failed' | tail -1 || true)"
    [ -n "$line" ] && break
    sleep 1
  done
  adb shell run-as "$PACKAGE" rm -f "cache/$name"
  [ -n "$line" ] || die "no result from the import after 15 minutes"
  echo "${line#*: }"
  [[ "$line" == *Imported* ]] || exit 1
}

cmd_start() {
  [ -r /dev/kvm ] && [ -w /dev/kvm ] || die "/dev/kvm is not accessible"
  local dictionary="${1:-}"
  [ -f "$APK" ] || ./podman-build-apk.sh
  [ -n "$dictionary" ] || dictionary=app/src/main/assets/example-dictionary.zip

  echo "==> Building the emulator image (cached after the first run)"
  podman build --ulimit nofile=65536:65536 -f Containerfile.android --target emulator -t "$IMAGE" . >/dev/null

  echo "==> Booting Android"
  remove_containers
  podman run -d --name "$CONTAINER" --device /dev/kvm -v "$REPO_ROOT/build/outputs:/apk:ro,Z" "$IMAGE" \
    emulator -avd test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -memory 3072 >/dev/null
  wait_for_boot

  echo "==> Switching to Japanese"
  adb root >/dev/null
  sleep 2
  adb wait-for-device
  adb shell setprop persist.sys.locale ja-JP
  # Restart the Android framework so the locale applies; system_server sets boot_completed again.
  adb shell setprop sys.boot_completed 0
  adb shell setprop ctl.restart zygote
  wait_for_boot

  echo "==> Installing Hiroi"
  cmd_install
  adb shell settings put secure enabled_accessibility_services "$PACKAGE/$PACKAGE.HiroiAccessibilityService"
  adb shell settings put secure accessibility_enabled 1
  adb shell cmd role add-role-holder android.app.role.ASSISTANT "$PACKAGE" 0
  for _ in $(seq 1 10); do
    adb shell dumpsys accessibility | grep -q "label=Hiroi screen text" && break
    sleep 1
  done
  adb shell dumpsys accessibility | grep -q "label=Hiroi screen text" ||
    echo "warning: the accessibility service didn't start; captures fall back to the assist data" >&2

  cmd_import "$dictionary"
  adb shell input keyevent KEYCODE_HOME
  echo "==> Ready. Watch and click along with: $0 view"
}

# Taps the first text on screen matching a regular expression, optionally only right of x=$2 and
# above y=$3. Retries for $4 seconds (default 15) while the screen appears.
tap_text() {
  local pattern=$1 min_x=${2:-0} max_y=${3:-99999} tries=${4:-15} position
  for _ in $(seq 1 "$tries"); do
    position=$(cmd_texts 2>/dev/null | awk -v p="$pattern" -v x="$min_x" -v y="$max_y" '$1 >= x && $2 <= y && $0 ~ p { print $1, $2; exit }')
    if [ -n "$position" ]; then
      adb shell input tap $position
      return 0
    fi
    sleep 1
  done
  return 1
}

# Waits until a text matching the regular expression is on screen.
wait_for_text() {
  for _ in $(seq 1 "${2:-30}"); do
    cmd_texts 2>/dev/null | grep -qE "$1" && return 0
    sleep 1
  done
  return 1
}

cmd_anki() {
  require_running
  local deck="${1:-}" downloads="$OUT/downloads"
  mkdir -p "$downloads"
  fetch() {
    local file="$downloads/$(basename "$1")"
    [ -f "$file" ] || curl -fsSL -o "$file" "$1"
    echo "$file"
  }
  local ankidroid ankiconnect
  ankidroid=$(fetch "https://github.com/ankidroid/Anki-Android/releases/download/v$ANKIDROID_VERSION/variant-abi-AnkiDroid-$ANKIDROID_VERSION-x86_64.apk")
  ankiconnect=$(fetch "https://github.com/KamWithK/AnkiconnectAndroid/releases/download/$ANKICONNECT_VERSION/ankiconnect_android_${ANKICONNECT_VERSION//./_}.apk")
  [ -n "$deck" ] || deck=$(fetch "https://github.com/donkuri/lapis/releases/download/$LAPIS_VERSION/Lapis.apkg")
  [ -f "$deck" ] || die "$deck not found"

  echo "==> Installing AnkiDroid $ANKIDROID_VERSION and AnkiConnect Android $ANKICONNECT_VERSION"
  for apk in "$ankidroid" "$ankiconnect"; do
    podman cp "$apk" "$CONTAINER:/tmp/$(basename "$apk")"
    adb install -r "/tmp/$(basename "$apk")" | tail -1
  done
  adb shell pm grant com.ichi2.anki android.permission.POST_NOTIFICATIONS
  adb shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow
  adb shell pm grant com.kamwithk.ankiconnectandroid android.permission.POST_NOTIFICATIONS
  adb shell pm grant com.kamwithk.ankiconnectandroid com.ichi2.anki.permission.READ_WRITE_DATABASE
  # The fake server's port forwarding would keep AnkiConnect from listening on 8765.
  podman exec "$CONTAINER" pkill -f fake-ankiconnect.py >/dev/null 2>&1 || true
  adb reverse --remove tcp:8765 >/dev/null 2>&1 || true

  echo "==> Setting up AnkiDroid"
  adb shell monkey -p com.ichi2.anki -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  tap_text "Get started" 0 99999 8 || true # only on the very first start
  wait_for_text "AnkiDroid" || die "AnkiDroid didn't open"

  local name
  name=$(basename "$deck")
  echo "==> Importing $name"
  podman cp "$deck" "$CONTAINER:/tmp/$name"
  adb push "/tmp/$name" "/sdcard/Download/$name" >/dev/null 2>&1
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$name" >/dev/null
  sleep 2
  # AnkiDroid can't open a plain file path, but takes the file from the media store.
  local id
  id=$(adb shell content query --uri content://media/external/file --projection _id --where "_display_name=\'$name\'" |
    grep -o "_id=[0-9]*" | tail -1 | cut -d= -f2)
  [ -n "$id" ] || die "$name isn't in the media store"
  adb shell am start -a android.intent.action.VIEW -d "content://media/external/file/$id" -t application/apkg com.ichi2.anki >/dev/null
  tap_text "^ *[0-9]+ +[0-9]+ +(追加|Add)$" 600 1800 || die "AnkiDroid didn't offer to import $name"
  tap_text "(インポート|Import)$" 800 || die "AnkiDroid's import screen didn't open"
  wait_for_text "概要|Summary" 60 || die "the import didn't finish"
  adb shell input keyevent KEYCODE_BACK

  echo "==> Starting AnkiConnect Android"
  adb shell monkey -p com.kamwithk.ankiconnectandroid -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  tap_text "START SERVICE" || die "AnkiConnect Android didn't open"
  sleep 2
  adb forward tcp:18765 tcp:8765 >/dev/null
  podman exec "$CONTAINER" python3 -c '
import json, urllib.request
def anki(action, **params):
    request = urllib.request.Request("http://127.0.0.1:18765", data=json.dumps({"action": action, "version": 6, "params": params}).encode())
    return json.loads(urllib.request.urlopen(request, timeout=20).read())["result"]
print("    decks:", ", ".join(anki("deckNames")))
print("    note types:", ", ".join(anki("modelNames")))
' || die "AnkiConnect Android doesn't answer"
  adb shell input keyevent KEYCODE_HOME
  echo "==> Anki is ready on the emulator's 127.0.0.1:8765, where Hiroi looks by default"
}

cmd_yomitan() {
  require_running
  local settings="${1:?usage: $0 yomitan <yomitan-settings.json>}" name
  [ -f "$settings" ] || die "$settings not found"
  name="yomitan-settings-$(date +%s).json"
  podman cp "$settings" "$CONTAINER:/tmp/$name"
  adb push "/tmp/$name" "/data/local/tmp/$name" >/dev/null 2>&1
  adb shell run-as "$PACKAGE" cp "/data/local/tmp/$name" "cache/$name"
  adb shell rm "/data/local/tmp/$name"
  adb shell am start -a android.intent.action.VIEW -t application/json \
    -d "file:///data/user/0/$PACKAGE/cache/$name" -n "$PACKAGE/.MainActivity" >/dev/null 2>&1
  echo "==> Hiroi has the settings; pick the card format in its dialog (texts, then tap)"
}

cmd_fake_anki() {
  require_running
  podman cp scripts/fake-ankiconnect.py "$CONTAINER:/tmp/fake-ankiconnect.py"
  podman exec "$CONTAINER" pkill -f fake-ankiconnect.py >/dev/null 2>&1 || true
  podman exec -d "$CONTAINER" python3 /tmp/fake-ankiconnect.py >/dev/null
  # The phone's 127.0.0.1:8765 then reaches the fake server in the container.
  adb reverse tcp:8765 tcp:8765 >/dev/null
  echo "==> Fake AnkiConnect on the emulator's 127.0.0.1:8765 (deck Japanese::Core, field Word)"
}

cmd_view() {
  require_running
  [ -n "${DISPLAY:-}" ] || die "no X display (DISPLAY is not set)"
  local xauth=()
  [ -n "${XAUTHORITY:-}" ] && xauth=(-v "$XAUTHORITY:/tmp/.Xauthority:ro" -e XAUTHORITY=/tmp/.Xauthority)
  podman rm -f "$VIEWER" >/dev/null 2>&1 || true
  # Shares the emulator container's network, so scrcpy reaches its adb server on localhost.
  podman run -d --name "$VIEWER" --network "container:$CONTAINER" \
    -e DISPLAY="$DISPLAY" -v /tmp/.X11-unix:/tmp/.X11-unix "${xauth[@]}" \
    -e ADB=/opt/android-sdk/platform-tools/adb \
    "$IMAGE" scrcpy --render-driver=software --no-audio --window-title "Hiroi emulator" >/dev/null
  echo "==> Opened the emulator window. Open Hiroi with the accessibility button floating at the right"
  echo "    edge; back: right click, home: middle click, more shortcuts: https://github.com/Genymobile/scrcpy/blob/master/doc/shortcuts.md"
}

cmd_app() {
  require_running
  adb shell am start -n "$PACKAGE/.MainActivity" >/dev/null 2>&1
  sleep 1
  for _ in 1 2 3 4 5 6; do adb shell input swipe 540 1900 540 400 150; done
}

cmd_texts() {
  require_running
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null
  adb shell cat /data/local/tmp/ui.xml | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
for node in ET.fromstring(sys.stdin.read()).iter("node"):
    text = node.get("text") or node.get("content-desc")
    if not text:
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    print(f"{(x1 + x2) // 2:5} {(y1 + y2) // 2:5}  {text}")
'
}

cmd_screenshot() {
  require_running
  local file="${1:-$OUT/screenshot.png}"
  mkdir -p "$(dirname "$file")"
  adb exec-out screencap -p > "$file"
  echo "$file"
}

command="${1:-}"
[ $# -gt 0 ] && shift
case "$command" in
  start) cmd_start "$@" ;;
  install) cmd_install ;;
  import) cmd_import "$@" ;;
  anki) cmd_anki "$@" ;;
  yomitan) cmd_yomitan "$@" ;;
  fake-anki) cmd_fake_anki ;;
  view) cmd_view ;;
  settings) require_running; adb shell am start -a android.settings.SETTINGS >/dev/null ;;
  app) cmd_app ;;
  trigger) require_running; adb shell input keyevent KEYCODE_ASSIST ;;
  tap) require_running; adb shell input tap "${1:?x}" "${2:?y}" ;;
  texts) cmd_texts ;;
  screenshot) cmd_screenshot "$@" ;;
  logcat) require_running; adb logcat -d -s HiroiLookup:V HiroiImport:V HiroiSession:V AndroidRuntime:E ;;
  adb) require_running; adb "$@" ;;
  stop) remove_containers; echo "stopped" ;;
  *) awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0"; exit 1 ;;
esac
