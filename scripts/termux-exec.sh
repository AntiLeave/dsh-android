#!/system/bin/sh
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export TMPDIR=$PREFIX/tmp
export PATH=$PREFIX/bin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=$PREFIX/lib
export LANG=en_US.UTF-8
cd "$HOME" || exit 1
CMD=$(printf '%s' "$1" | base64 -d)
exec "$PREFIX/bin/bash" -lc "$CMD"
