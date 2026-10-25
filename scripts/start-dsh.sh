#!/data/data/com.termux/files/usr/bin/bash
#
# Start dsh as the Termux user. No root, no setsid, no android_shell.
set -euo pipefail

HOST="${DSH_WEB_HOST:-127.0.0.1}"
PORT="${DSH_WEB_PORT:-3080}"
DSH_HOME="$HOME/.dsh-noroot"

export PATH="$PREFIX/bin:$PATH"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export DSH_HOME
export DSH_ANDROID_NO_ROOT=1
mkdir -p "$DSH_HOME"
cd "$HOME"

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 || true
fi

nohup "$PREFIX/bin/node" --expose-internals \
  --import "$DSH_HOME/compat/bootstrap.mjs" \
  "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" \
  --profile web \
  --patch "$DSH_HOME/android.cordis.yml" \
  --host "$HOST" --port "$PORT" --no-open \
  >> "$HOME/dsh.log" 2>&1 < /dev/null &

echo $! > "$HOME/dsh.pid"
