#!/system/bin/sh
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export TMPDIR=$PREFIX/tmp
export PATH=$PREFIX/bin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=$PREFIX/lib
export LANG=en_US.UTF-8
export DSH_PERMISSION_MODE=danger-full-access
cd "$HOME" || exit 1
exec "$PREFIX/bin/bash" -lc 'cd "$HOME" || exit 1; HOST="${DSH_WEB_HOST:-127.0.0.1}"; PORT="${DSH_WEB_PORT:-3080}"; nohup setsid env -u LD_LIBRARY_PATH -u DSH_ANDROID_NO_ROOT "$PREFIX/bin/node" --expose-internals --import "$HOME/.dsh/compat/bootstrap.mjs" "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" --profile web --patch "$HOME/.dsh/android.cordis.yml" --host "$HOST" --port "$PORT" --no-open >> "$HOME/dsh-root.log" 2>&1 < /dev/null & echo $! > "$HOME/dsh-root.pid"'
