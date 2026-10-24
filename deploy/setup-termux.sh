#!/data/data/com.termux/files/usr/bin/bash
#
# One-shot Termux setup for the Android DSH deployment.
# The host runs this inside the Termux app (as the Termux user):
#   bash setup.sh http://127.0.0.1:<deploy-port>
#
# It installs Node/dsh if needed, rebuilds the two Android native
# dependencies, installs the WASM sharp fallback, and unpacks the Cordis
# plugins/compat bootstrap served by the host.

set -euo pipefail

HOST_URL="${1:-http://127.0.0.1:18080}"
DSH_VERSION="${DSH_VERSION:-0.2.1-rc.2}"
LOG="$HOME/dsh-setup.log"
MARKER="$HOME/.dsh-setup.done"
FAIL_MARKER="$HOME/.dsh-setup.failed"
DSH="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
NATIVE_MARKER="$HOME/.dsh/.android-native-node-version"

exec > >(tee -a "$LOG") 2>&1
trap 'echo "[setup] FAILED at line $LINENO"; touch "$FAIL_MARKER"; exit 1' ERR

rm -f "$MARKER" "$FAIL_MARKER"
echo "[setup] start $(date)"
echo "[setup] host=$HOST_URL"

# ---------------------------------------------------------------------------
# 1. Node and build tools

pkg update -y
pkg install -y nodejs-lts cmake clang make python pkg-config curl tar

node -v
npm -v

NODE_VERSION="$(node -v)"
REBUILD_NATIVE=0
if [ "$(cat "$NATIVE_MARKER" 2>/dev/null || true)" != "$NODE_VERSION" ]; then
  REBUILD_NATIVE=1
fi

# ---------------------------------------------------------------------------
# 2. dsh

INSTALLED_VERSION="$(node -p "try{require('$DSH/package.json').version}catch{''}" 2>/dev/null || true)"
if [ "$INSTALLED_VERSION" != "$DSH_VERSION" ]; then
  echo "[setup] installing @deepseek-ai/dsh@$DSH_VERSION"
  npm install -g "@deepseek-ai/dsh@$DSH_VERSION" \
    --ignore-scripts --no-audit --no-fund
else
  echo "[setup] dsh $INSTALLED_VERSION already installed"
fi

# ---------------------------------------------------------------------------
# 3. plugins, compat bootstrap and Cordis patch

mkdir -p "$HOME/.dsh"
if [ -f "$HOME/.dsh-deploy.tar.gz" ]; then
  tar -xzf "$HOME/.dsh-deploy.tar.gz" -C "$HOME"
  rm -f "$HOME/.dsh-deploy.tar.gz"
elif [ -n "${HOST_URL:-}" ]; then
  curl -fsSL "$HOST_URL/deploy.tar.gz" -o "$PREFIX/tmp/dsh-deploy.tar.gz"
  tar -xzf "$PREFIX/tmp/dsh-deploy.tar.gz" -C "$HOME"
  rm -f "$PREFIX/tmp/dsh-deploy.tar.gz"
fi

# ---------------------------------------------------------------------------
# 4. koffi: Android has no prebuilt addon, and bionic has no glibc statx

KOFFI="$DSH/node_modules/koffi"
KOFFI_BUILD="$(find "$KOFFI/build" -name koffi.node -path '*android_arm64*' 2>/dev/null | head -n 1 || true)"
if [ "$REBUILD_NATIVE" = "1" ] || [ -z "$KOFFI_BUILD" ]; then
  echo "[setup] building koffi for Android"
  python3 - "$KOFFI/lib/native/base/base.cc" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text()
old = "#if defined(__linux__)\n    const char *pathname = filename;"
new = "#if defined(__linux__) && !defined(__ANDROID__)\n    const char *pathname = filename;"
if new not in text:
    if old not in text:
        raise SystemExit("koffi StatAt block was not found; inspect base.cc")
    path.write_text(text.replace(old, new, 1))
PY
  (
    cd "$KOFFI"
    node ./cnoke.cjs -P . -D src/koffi --prebuild --release
  )
else
  echo "[setup] koffi already built"
fi

# ---------------------------------------------------------------------------
# 5. node-pty: no Android prebuild

PTY="$DSH/node_modules/node-pty"
if [ "$REBUILD_NATIVE" = "1" ] || [ ! -f "$PTY/build/Release/pty.node" ]; then
  echo "[setup] building node-pty for Android"
  (
    cd "$PTY"
    node "$PREFIX/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js" \
      rebuild --nodedir="$PREFIX"
  )
else
  echo "[setup] node-pty already built"
fi

# ---------------------------------------------------------------------------
# 6. sharp WASM fallback: Android has no native sharp binary

SHARP_VERSION="$(node -p "require('$DSH/node_modules/sharp/package.json').version")"
SHARP_DIR="$DSH/node_modules/@img/sharp-wasm32"
if [ ! -f "$SHARP_DIR/package.json" ]; then
  echo "[setup] installing sharp WASM $SHARP_VERSION"
  WORK="$PREFIX/tmp/dsh-sharp-$$"
  mkdir -p "$WORK"
  (
    cd "$WORK"
    npm pack "@img/sharp-wasm32@$SHARP_VERSION" --pack-destination .
    npm pack "@emnapi/runtime@1.11.3" --pack-destination .
    rm -rf "$SHARP_DIR" "$DSH/node_modules/@emnapi/runtime"
    mkdir -p "$SHARP_DIR" "$DSH/node_modules/@emnapi/runtime"
    tar -xzf img-sharp-wasm32-*.tgz -C "$SHARP_DIR" --strip-components=1
    tar -xzf emnapi-runtime-*.tgz -C "$DSH/node_modules/@emnapi/runtime" --strip-components=1
  )
  rm -rf "$WORK"
else
  echo "[setup] sharp WASM already installed"
fi

# ---------------------------------------------------------------------------
# 7. record the Node ABI used by the native builds

printf '%s\n' "$NODE_VERSION" > "$NATIVE_MARKER"

echo "[setup] verifying modules"
node -e "require('$DSH/node_modules/sharp'); console.log('[setup] sharp ok')"
(
  cd "$DSH"
  node -e "require('koffi'); require('node-pty'); console.log('[setup] native modules ok')"
)

echo "[setup] done $(date)"
printf '%s\n' "$(date)" > "$MARKER"
