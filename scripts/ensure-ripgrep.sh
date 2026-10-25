#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DSH="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
RG="$DSH/node_modules/@vscode/ripgrep"
PLATFORM="$DSH/node_modules/@vscode/ripgrep-android-arm64"

if [ -x "$PLATFORM/bin/rg" ]; then
  exit 0
fi

if ! command -v rg >/dev/null 2>&1; then
  pkg install -y ripgrep >/dev/null
fi

mkdir -p "$PLATFORM/bin"
cp "$(command -v rg)" "$PLATFORM/bin/rg"
chmod 755 "$PLATFORM/bin/rg"

VERSION=1.18.0
if [ -f "$RG/package.json" ]; then
  VERSION=$(node -p "require('$RG/package.json').version" 2>/dev/null || echo 1.18.0)
fi
printf '{"name":"@vscode/ripgrep-android-arm64","version":"%s","os":["android"],"cpu":["arm64"]}\n' "$VERSION" > "$PLATFORM/package.json"
