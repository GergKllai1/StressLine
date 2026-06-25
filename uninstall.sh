#!/bin/sh
# Remove the `stressline` command installed by install.sh.
#
# Usage:
#   ./uninstall.sh                     # removes from ~/.local
#   PREFIX=/usr/local ./uninstall.sh   # removes a system-wide install
set -eu

PREFIX="${PREFIX:-$HOME/.local}"
BIN_LINK="$PREFIX/bin/stressline"
SHARE_DIR="$PREFIX/share/stressline"

removed=0
if [ -L "$BIN_LINK" ] || [ -e "$BIN_LINK" ]; then
    rm -f "$BIN_LINK"
    echo "Removed $BIN_LINK"
    removed=1
fi
if [ -d "$SHARE_DIR" ]; then
    rm -rf "$SHARE_DIR"
    echo "Removed $SHARE_DIR"
    removed=1
fi

if [ "$removed" -eq 0 ]; then
    echo "Nothing to remove under $PREFIX (set PREFIX if you installed elsewhere)."
else
    echo "Done."
fi
