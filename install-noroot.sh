#!/data/data/com.termux/files/usr/bin/bash
#
# No-root DSH Android installer for Termux.
#
# Root-only pieces are intentionally absent: no su, no pm install, no
# accessibility/appops changes, no android_shell, no root daemon and no
# /data/local/tmp scripts. Install the bridge APK and enable accessibility
# yourself; this script only installs the Termux-side runtime.
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
APK="$DIR/dist/dsh-bridge.apk"
PLUGIN="$DIR/dsh-plugin"
COMPAT="$DIR/compat"
SETUP="$DIR/deploy/setup-termux.sh"
START_SRC="$DIR/scripts/start-dsh.sh"
BRIDGE_URL="http://127.0.0.1:17654"
HOST=127.0.0.1
PORT=3080
ACTION=install

usage() {
  cat <<'EOF'
Usage: bash install-noroot.sh [options]

  (no option)   install/update plugins and start dsh without root
  -start        start dsh (install if not configured)
  -stop         stop dsh; the accessibility bridge is left untouched
  -uninstall    remove plugins/compat/start script; the bridge is left installed
  -HOST <ip>    dsh bind address (default 127.0.0.1)
  -PORT <port>  dsh web port (default 3080)
  -h, --help    show this help

This script must run inside Termux, not via root. Root-only features such as
android_shell are not installed. The bridge APK and accessibility service must
be installed and enabled manually.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -start) ACTION=start ;;
    -stop) ACTION=stop ;;
    -uninstall) ACTION=uninstall ;;
    -HOST|-host) shift; HOST="${1:-127.0.0.1}" ;;
    -PORT|-port) shift; PORT="${1:-3080}" ;;
    -h|-help|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
  shift
done

if [ "$(id -u)" = "0" ]; then
  echo "This is the no-root installer. Use 'sh install.sh' via root instead." >&2
  exit 1
fi

if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX" ] || [ -z "${HOME:-}" ] || [ ! -x "$PREFIX/bin/pkg" ]; then
  echo "Run this inside Termux with bash, not from /system/bin/sh or /data/local/tmp:" >&2
  echo "  cd ~/dsh-android && bash install-noroot.sh -HOST 10.0.0.100" >&2
  exit 1
fi

export PATH="$PREFIX/bin:$PATH"
DSH="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
NOHOME="$HOME/.dsh-noroot"

if [ ! -f "$SETUP" ] || [ ! -f "$START_SRC" ] || [ ! -d "$PLUGIN" ] || [ ! -d "$COMPAT" ]; then
  echo "missing deployment files; run this from the extracted dsh-android directory" >&2
  exit 1
fi

bridge_ok() {
  command -v curl >/dev/null 2>&1 && curl -fsS --max-time 3 "$BRIDGE_URL/health" >/dev/null 2>&1
}

bridge_help() {
  cat >&2 <<EOF
Accessibility bridge is not reachable at $BRIDGE_URL.

The bridge APK does not need root, but Termux cannot install it silently:
  1. Install dist/dsh-bridge.apk:
       adb install -r "$APK"
     or tap the APK in a file manager.
  2. Enable it in Settings > Accessibility > DSH Bridge.
  3. On MIUI/HyperOS, grant Settings > Apps > DSH Bridge > Other permissions
     > Get installed app list. From a PC you can also try:
       adb shell appops set ai.deepseek.dshbridge 10022 allow
  4. Re-run: bash install-noroot.sh -start
EOF
}

stop_dsh() {
  if [ -f "$HOME/dsh.pid" ]; then
    PID=$(cat "$HOME/dsh.pid" 2>/dev/null || true)
    if [ -n "${PID:-}" ]; then
      kill "$PID" >/dev/null 2>&1 || true
    fi
    rm -f "$HOME/dsh.pid"
  fi
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "[l]ib/bin.js --profile web" >/dev/null 2>&1 || true
  fi
  sleep 1
  if command -v termux-wake-unlock >/dev/null 2>&1; then
    termux-wake-unlock >/dev/null 2>&1 || true
  fi
}

wake_lock() {
  if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock >/dev/null 2>&1 || true
  fi
}

deploy_noroot() {
  rm -rf "$NOHOME/plugins" "$NOHOME/compat"
  mkdir -p "$NOHOME/plugins" "$NOHOME/compat"
  cp "$PLUGIN/android-tool.mjs" "$NOHOME/plugins/android-tool.mjs"
  cp "$PLUGIN/android-compat.mjs" "$NOHOME/plugins/android-compat.mjs"
  sed 's#/\.dsh/plugins/#/.dsh-noroot/plugins/#g' "$PLUGIN/android.cordis.yml" > "$NOHOME/android.cordis.yml"
  cp "$COMPAT"/* "$NOHOME/compat/"
  cp "$START_SRC" "$NOHOME/start-dsh.sh"
  chmod 755 "$NOHOME/start-dsh.sh"
}

deploy_bundle() {
  STAGE="$HOME/.dsh-stage-$$"
  rm -rf "$STAGE"
  mkdir -p "$STAGE/.dsh/plugins" "$STAGE/.dsh/compat"
  cp "$PLUGIN/android-tool.mjs" "$STAGE/.dsh/plugins/android-tool.mjs"
  cp "$PLUGIN/android-compat.mjs" "$STAGE/.dsh/plugins/android-compat.mjs"
  cp "$PLUGIN/android.cordis.yml" "$STAGE/.dsh/android.cordis.yml"
  cp "$COMPAT"/* "$STAGE/.dsh/compat/"
  tar -czf "$HOME/.dsh-deploy.tar.gz" -C "$STAGE" .dsh
  rm -rf "$STAGE"
}

setup_termux() {
  if [ -f "$HOME/.dsh-setup.done" ] && [ -f "$DSH/lib/bin.js" ] && [ -x "$PREFIX/bin/node" ]; then
    echo "using existing Termux dependencies"
  else
    rm -f "$HOME/.dsh-setup.failed"
    deploy_bundle
    echo "running Termux setup (first run can take 10-20 minutes)"
    ( cd "$HOME" && "$PREFIX/bin/bash" "$SETUP" )
    rm -f "$HOME/.dsh-deploy.tar.gz"
  fi
  deploy_noroot
}

open_bridge_installer() {
  if bridge_ok; then
    return 0
  fi
  if [ -f "$APK" ] && command -v termux-open >/dev/null 2>&1; then
    case "$APK" in
      "$HOME"/*)
        APK_TO_OPEN="$APK"
        ;;
      *)
        LOCAL_APK="$HOME/dsh-bridge.apk"
        if cp "$APK" "$LOCAL_APK" 2>/dev/null; then
          APK_TO_OPEN="$LOCAL_APK"
        else
          APK_TO_OPEN="$APK"
        fi
        ;;
    esac
    echo "opening the DSH Bridge installer; confirm the install on screen"
    termux-open "$APK_TO_OPEN" >/dev/null 2>&1 || true
    sleep 2
  fi
}

start_dsh() {
  [ -f "$NOHOME/start-dsh.sh" ] || deploy_noroot
  stop_dsh
  : > "$HOME/dsh.log"
  rm -f "$HOME/dsh.pid"
  DSH_WEB_HOST="$HOST" DSH_WEB_PORT="$PORT" DSH_ANDROID_NO_ROOT=1 \
    bash "$NOHOME/start-dsh.sh"
  wake_lock

  I=0
  TOKEN=""
  while [ "$I" -lt 30 ]; do
    I=$((I + 1))
    LINE=$(grep "dsh web:" "$HOME/dsh.log" 2>/dev/null | tail -1 || true)
    TOKEN=$(printf '%s' "$LINE" | sed -n 's/.*token=\([A-Za-z0-9_-]*\).*/\1/p')
    [ -n "$TOKEN" ] && break
    sleep 2
  done
  if [ -z "$TOKEN" ]; then
    echo "dsh did not start" >&2
    tail -n 40 "$HOME/dsh.log" >&2 || true
    return 1
  fi
  sleep 4
  LINE=$(grep "dsh web:" "$HOME/dsh.log" 2>/dev/null | tail -1 || true)
  NEW_TOKEN=$(printf '%s' "$LINE" | sed -n 's/.*token=\([A-Za-z0-9_-]*\).*/\1/p')
  [ -n "$NEW_TOKEN" ] && TOKEN="$NEW_TOKEN"
  if command -v curl >/dev/null 2>&1; then
    if ! curl -fsS --max-time 5 "http://$HOST:$PORT/?token=$TOKEN" >/dev/null 2>&1; then
      echo "dsh is not responding on $HOST:$PORT" >&2
      tail -n 40 "$HOME/dsh.log" >&2 || true
      return 1
    fi
  fi
  echo "http://$HOST:$PORT/?token=$TOKEN"
}

check_bridge() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "accessibility bridge check skipped (curl not installed yet)"
    return 0
  fi
  if bridge_ok; then
    echo "accessibility bridge: ok"
  else
    bridge_help
  fi
}

ensure_ripgrep() {
  "$PREFIX/bin/bash" "$DIR/scripts/ensure-ripgrep.sh"
}

check_shizuku() {
  if ! command -v curl >/dev/null 2>&1; then
    return 0
  fi
  STATUS=$(curl -fsS --max-time 3 "$BRIDGE_URL/shizuku" 2>/dev/null || true)
  case "$STATUS" in
    *'"running":true'*'"granted":true'*)
      echo "shizuku: ok"
      ;;
    *'"running":true'*)
      echo "shizuku: running, permission not granted (open Shizuku and allow DSH Bridge)"
      ;;
    *)
      echo "shizuku: not running (android_shell and virtual-screen launch need Shizuku)"
      ;;
  esac
}

do_install() {
  open_bridge_installer
  setup_termux
  ensure_ripgrep
  wake_lock
  start_dsh
  check_bridge
  check_shizuku
}

do_uninstall() {
  stop_dsh
  rm -rf "$NOHOME/plugins" "$NOHOME/compat"
  rm -f "$NOHOME/android.cordis.yml" "$NOHOME/start-dsh.sh" \
    "$HOME/.dsh-deploy.tar.gz" "$HOME/dsh.log" "$HOME/dsh.pid"
  echo "removed no-root DSH Android integration"
  echo "Termux, Node, dsh and sessions were kept"
  echo "the no-root dsh home was kept at $NOHOME"
  echo "the bridge APK and accessibility service were left installed"
  echo "MIUI app-list permission was left unchanged"
}

case "$ACTION" in
  install) do_install ;;
  start)
    if [ ! -f "$NOHOME/start-dsh.sh" ] || [ ! -f "$NOHOME/android.cordis.yml" ] \
      || [ ! -f "$HOME/.dsh-setup.done" ] || [ ! -f "$DSH/lib/bin.js" ]; then
      do_install
    else
      ensure_ripgrep
      wake_lock
      start_dsh
      check_bridge
      check_shizuku
    fi
    ;;
  stop)
    stop_dsh
    echo "dsh stopped; accessibility bridge left enabled"
    ;;
  uninstall) do_uninstall ;;
esac
