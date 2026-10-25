#!/system/bin/sh
set -u

DIR=$(cd "$(dirname "$0")" && pwd)
APK="$DIR/dist/dsh-bridge.apk"
PLUGIN="$DIR/dsh-plugin"
COMPAT="$DIR/compat"
SETUP="$DIR/deploy/setup-termux.sh"
TEXEC="$DIR/scripts/termux-exec.sh"
RIPGREP="$DIR/scripts/ensure-ripgrep.sh"
START="$DIR/scripts/start-dsh-root.sh"
BRIDGE=ai.deepseek.dshbridge
BRIDGE_SERVICE=ai.deepseek.dshbridge/ai.deepseek.dshbridge.BridgeService
HOST=127.0.0.1
PORT=3080
ACTION=install

usage() {
  cat <<'EOF'
Usage: sh install.sh [options]

  (no option)   install if needed, then start dsh and print the URL
  -start        start dsh (install only if not configured)
  -stop         stop dsh and the accessibility bridge
  -uninstall    stop, uninstall the bridge APK, remove plugins/compat/scripts
  -HOST <ip>    dsh bind address (default 127.0.0.1)
  -PORT <port>  dsh web port (default 3080)
  -h, --help    show this help

Termux itself and the dsh npm package are kept by -uninstall.
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

if [ "$(id -u)" != "0" ]; then
  echo "This script must run as root: su -c 'sh $0'" >&2
  exit 1
fi

b64() {
  printf '%s' "$1" | base64 -w 0
}

run_root() {
  if [ ! -f /data/local/tmp/termux-exec.sh ]; then
    cp "$TEXEC" /data/local/tmp/termux-exec.sh || return 1
    chmod 666 /data/local/tmp/termux-exec.sh
  fi
  sh /data/local/tmp/termux-exec.sh "$(b64 "$1")"
}

prepare_scripts() {
  cp "$TEXEC" /data/local/tmp/termux-exec.sh
  cp "$START" /data/local/tmp/start-dsh-root.sh
  cp "$RIPGREP" /data/local/tmp/ensure-ripgrep.sh
  chmod 666 /data/local/tmp/termux-exec.sh /data/local/tmp/start-dsh-root.sh /data/local/tmp/ensure-ripgrep.sh
}

do_stop() {
  pkill -f "[l]ib/bin.js --profile web" 2>/dev/null && echo "dsh stopped" || true
  CURRENT=$(settings get secure enabled_accessibility_services)
  [ "$CURRENT" = "null" ] && CURRENT=""
  NEW=$(printf '%s' "$CURRENT" | sed "s#$BRIDGE_SERVICE##g; s#::#:#g; s#^:##; s#:\$##")
  if [ -z "$NEW" ]; then
    settings put secure enabled_accessibility_services ""
    settings put secure accessibility_enabled 0
  else
    settings put secure enabled_accessibility_services "$NEW"
  fi
  am force-stop "$BRIDGE" >/dev/null 2>&1 || true
  echo "accessibility bridge stopped"
}

do_uninstall() {
  do_stop
  pm uninstall "$BRIDGE" >/dev/null 2>&1 && echo "bridge APK uninstalled" || echo "bridge APK was not installed"
  if [ -f "$TEXEC" ]; then
    run_root 'rm -rf "$HOME/.dsh/plugins" "$HOME/.dsh/compat"; rm -f "$HOME/.dsh/android.cordis.yml" "$HOME/.dsh-setup.done" "$HOME/.dsh-setup.failed" "$HOME/.dsh-setup.sh" "$HOME/.dsh-deploy.tar.gz" "$HOME/dsh-root.log" "$HOME/dsh-root.pid"' >/dev/null 2>&1 || true
  fi
  rm -f /data/local/tmp/termux-exec.sh /data/local/tmp/start-dsh-root.sh /data/local/tmp/ensure-ripgrep.sh /data/local/tmp/dsh-deploy.tar.gz
  rm -rf /data/local/tmp/dsh-deploy-root
  echo "removed DSH Android integration (Termux, Node, dsh and sessions kept)"
  rm -rf "$DIR"
}

set_bridge_service() {
  CURRENT=$(settings get secure enabled_accessibility_services)
  [ "$CURRENT" = "null" ] && CURRENT=""
  case "$CURRENT" in
    *"$BRIDGE_SERVICE"*) ;;
    *) if [ -z "$CURRENT" ]; then CURRENT="$BRIDGE_SERVICE"; else CURRENT="$CURRENT:$BRIDGE_SERVICE"; fi
       settings put secure enabled_accessibility_services "$CURRENT" ;;
  esac
  settings put secure accessibility_enabled 1
}

install_bridge() {
  if [ ! -f "$APK" ]; then
    echo "missing bridge APK: $APK" >&2
    exit 1
  fi
  if ! pm install -r "$APK" >/dev/null 2>&1; then
    echo "failed to install bridge APK" >&2
    exit 1
  fi
  sleep 2
  am start -n "$BRIDGE/.MainActivity" >/dev/null 2>&1 || true
  appops set "$BRIDGE" 10022 allow 2>/dev/null || true
  set_bridge_service
  sleep 2
  i=0
  while [ "$i" -lt 3 ]; do
    if ss -ltnp 2>/dev/null | grep -q 17654; then
      echo "accessibility bridge installed"
      return 0
    fi
    am force-stop "$BRIDGE" >/dev/null 2>&1 || true
    sleep 1
    am start -n "$BRIDGE/.MainActivity" >/dev/null 2>&1 || true
    sleep 1
    set_bridge_service
    sleep 2
    i=$((i + 1))
  done
  echo "warning: accessibility bridge is not listening on 17654" >&2
}

ensure_termux() {
  if ! pm path com.termux >/dev/null 2>&1; then
    for candidate in /data/local/tmp/com.termux.apk "$DIR/com.termux.apk" "$DIR/dist/com.termux.apk"; do
      if [ -f "$candidate" ]; then
        pm install -r "$candidate" >/dev/null 2>&1 && break
      fi
    done
  fi
  if ! pm path com.termux >/dev/null 2>&1; then
    echo "Termux is not installed. Install it from F-Droid, then rerun this script." >&2
    echo "https://f-droid.org/repo/com.termux_1002.apk" >&2
    exit 1
  fi
  am start -n com.termux/.app.TermuxActivity >/dev/null 2>&1 || true
}

wait_termux() {
  i=0
  while [ "$i" -lt 45 ]; do
    i=$((i + 1))
    if run_root 'test -x "$PREFIX/bin/bash" && echo READY' 2>/dev/null | grep -q READY; then
      return 0
    fi
    sleep 2
  done
  echo "Termux bootstrap timed out; open Termux once and rerun." >&2
  return 1
}

allow_external_apps() {
  am start -n com.termux/.app.TermuxActivity >/dev/null 2>&1 || true
  sleep 3
  SIZE=$(wm size 2>/dev/null | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1)
  W=$(printf '%s' "$SIZE" | awk '{print $1}')
  H=$(printf '%s' "$SIZE" | awk '{print $2}')
  [ -n "$W" ] || W=1080
  [ -n "$H" ] || H=2400
  input tap $((W / 2)) $((H * 45 / 100)) >/dev/null 2>&1 || true
  sleep 1
  input text "echo%sallow-external-apps=true%s>>%s~/.termux/termux.properties" >/dev/null 2>&1 || true
  input keyevent 66 >/dev/null 2>&1 || true
  sleep 1
  input text "termux-reload-settings" >/dev/null 2>&1 || true
  input keyevent 66 >/dev/null 2>&1 || true
  sleep 2
  am force-stop com.termux >/dev/null 2>&1 || true
  sleep 1
  am start -n com.termux/.app.TermuxActivity >/dev/null 2>&1 || true
  sleep 3
}

setup_done() {
  run_root 'test -f "$HOME/.dsh-setup.done" && echo DONE' 2>/dev/null | grep -q DONE
}

run_setup() {
  echo "starting Termux setup"
  run_root 'rm -rf "$HOME/.dsh/plugins" "$HOME/.dsh/compat"; rm -f "$HOME/.dsh/android.cordis.yml" "$HOME/.dsh-setup.done" "$HOME/.dsh-setup.failed"' >/dev/null 2>&1 || true
  STAGE=/data/local/tmp/dsh-deploy-root
  rm -rf "$STAGE"
  mkdir -p "$STAGE/.dsh/plugins" "$STAGE/.dsh/compat"
  cp "$PLUGIN/android-tool.mjs" "$STAGE/.dsh/plugins/android-tool.mjs"
  cp "$PLUGIN/android-compat.mjs" "$STAGE/.dsh/plugins/android-compat.mjs"
  cp "$PLUGIN/android.cordis.yml" "$STAGE/.dsh/android.cordis.yml"
  cp "$COMPAT"/* "$STAGE/.dsh/compat/"
  tar -czf /data/local/tmp/dsh-deploy.tar.gz -C "$STAGE" .dsh
  SETUP_B64=$(base64 -w 0 "$SETUP")
  BUNDLE_B64=$(base64 -w 0 /data/local/tmp/dsh-deploy.tar.gz)
  CMD="printf %s $SETUP_B64 | base64 -d > \$HOME/.dsh-setup.sh; printf %s $BUNDLE_B64 | base64 -d > \$HOME/.dsh-deploy.tar.gz; bash \$HOME/.dsh-setup.sh"
  am start-foreground-service -n com.termux/com.termux.app.RunCommandService \
    -a com.termux.RUN_COMMAND \
    --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/bash \
    --esa com.termux.RUN_COMMAND_ARGUMENTS "-lc,$CMD" \
    --ez com.termux.RUN_COMMAND_BACKGROUND true >/dev/null 2>&1 || {
      echo "could not start Termux RunCommandService" >&2
      return 1
    }
  i=0
  while [ "$i" -lt 480 ]; do
    i=$((i + 1))
    STATUS=$(run_root 'test -f "$HOME/.dsh-setup.done" && echo DONE; test -f "$HOME/.dsh-setup.failed" && echo FAILED' 2>/dev/null)
    case "$STATUS" in
      *DONE*) echo "Termux setup complete"; return 0 ;;
      *FAILED*) echo "Termux setup failed" >&2
                run_root 'tail -n 40 "$HOME/dsh-setup.log" 2>/dev/null' >&2 || true
                return 1 ;;
    esac
    if [ $((i % 6)) -eq 0 ]; then
      run_root 'tail -n 3 "$HOME/dsh-setup.log" 2>/dev/null' || true
    fi
    sleep 5
  done
  echo "Termux setup timed out" >&2
  return 1
}

start_dsh() {
  pkill -f "[l]ib/bin.js --profile web" 2>/dev/null || true
  sleep 2
  run_root ': > "$HOME/dsh-root.log"; rm -f "$HOME/dsh-root.pid"' >/dev/null 2>&1 || true
  run_root "DSH_WEB_HOST=$HOST DSH_WEB_PORT=$PORT sh /data/local/tmp/start-dsh-root.sh" >/dev/null 2>&1 || true
  i=0
  TOKEN=""
  while [ "$i" -lt 30 ]; do
    i=$((i + 1))
    LINE=$(run_root 'grep "dsh web:" "$HOME/dsh-root.log" 2>/dev/null | tail -1' 2>/dev/null)
    TOKEN=$(printf '%s' "$LINE" | sed -n 's/.*token=\([A-Za-z0-9_-]*\).*/\1/p')
    [ -n "$TOKEN" ] && break
    sleep 2
  done
  if [ -z "$TOKEN" ]; then
    echo "dsh did not start" >&2
    run_root 'tail -n 40 "$HOME/dsh-root.log" 2>/dev/null' >&2 || true
    return 1
  fi
  sleep 4
  LINE=$(run_root 'grep "dsh web:" "$HOME/dsh-root.log" 2>/dev/null | tail -1' 2>/dev/null)
  NEW_TOKEN=$(printf '%s' "$LINE" | sed -n 's/.*token=\([A-Za-z0-9_-]*\).*/\1/p')
  [ -n "$NEW_TOKEN" ] && TOKEN="$NEW_TOKEN"
  if ! /system/bin/ss -ltnp 2>/dev/null | grep -q "$HOST:$PORT"; then
    echo "dsh is not listening on $HOST:$PORT" >&2
    run_root 'tail -n 40 "$HOME/dsh-root.log" 2>/dev/null' >&2 || true
    return 1
  fi
  echo "http://$HOST:$PORT/?token=$TOKEN"
}

ensure_ripgrep() {
  run_root 'bash /data/local/tmp/ensure-ripgrep.sh' >/dev/null 2>&1 || true
}

do_install() {
  prepare_scripts
  install_bridge
  ensure_termux
  wait_termux || exit 1
  if ! setup_done; then
    allow_external_apps
    run_setup || exit 1
  fi
  ensure_ripgrep
  start_dsh || exit 1
}

case "$ACTION" in
  stop) do_stop ;;
  uninstall) do_uninstall ;;
  start)
    prepare_scripts
    install_bridge
    ensure_termux
    wait_termux || exit 1
    if setup_done; then ensure_ripgrep; start_dsh; else do_install; fi
    ;;
  install) do_install ;;
esac
