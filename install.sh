#!/bin/sh
# Build and install the `stressline` command on Linux/macOS.
#
# Usage:
#   ./install.sh                     # per-user install into ~/.local (no sudo)
#   PREFIX=/usr/local ./install.sh   # system-wide (may require sudo)
#
# Requires a Java 17+ runtime on PATH at run time (the launcher invokes `java`).
set -eu

# Resolve this script's directory so it works when invoked from anywhere.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
cd "$SCRIPT_DIR"

PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
SHARE_DIR="$PREFIX/share/stressline"
DIST="$SCRIPT_DIR/build/install/stressline"

echo "Building distribution..."
./gradlew --quiet installDist

if [ ! -x "$DIST/bin/stressline" ]; then
    echo "error: launcher not found at $DIST/bin/stressline" >&2
    exit 1
fi

echo "Installing to $SHARE_DIR ..."
rm -rf "$SHARE_DIR"
mkdir -p "$PREFIX/share"
cp -R "$DIST" "$SHARE_DIR"

mkdir -p "$BIN_DIR"
ln -sf "$SHARE_DIR/bin/stressline" "$BIN_DIR/stressline"
echo "Installed: $BIN_DIR/stressline -> $SHARE_DIR/bin/stressline"

# Warn if the bin dir is not on PATH.
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        echo
        echo "NOTE: $BIN_DIR is not on your PATH. Add this to your shell profile:"
        echo "    export PATH=\"$BIN_DIR:\$PATH\""
        ;;
esac

# Warn if no Java runtime is available.
if ! command -v java >/dev/null 2>&1; then
    echo
    echo "NOTE: 'java' was not found on PATH. stressline needs a Java 17+ runtime to run."
fi

echo
echo "Done. Try: stressline --help"
