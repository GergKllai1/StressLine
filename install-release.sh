#!/bin/sh
# Download and install the latest released `stressline` binary (Linux/macOS).
# Requires a Java 21+ runtime on PATH at run time.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/GergKllai1/StressLine/main/install-release.sh | sh
#
# Options (environment):
#   STRESSLINE_REPO   GitHub "owner/repo" to download from (default: GergKllai1/StressLine;
#                     override only when installing from a fork)
#   PREFIX            install prefix (default: ~/.local; use /usr/local for system-wide)
#   VERSION           release tag to install (default: latest)
set -eu

REPO="${STRESSLINE_REPO:-GergKllai1/StressLine}"
PREFIX="${PREFIX:-$HOME/.local}"
VERSION="${VERSION:-latest}"
BIN_DIR="$PREFIX/bin"
SHARE_DIR="$PREFIX/share/stressline"

# The release publishes a stable-named asset so "latest" needs no API call.
if [ "$VERSION" = "latest" ]; then
    URL="https://github.com/$REPO/releases/latest/download/stressline.tar.gz"
else
    URL="https://github.com/$REPO/releases/download/$VERSION/stressline.tar.gz"
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Downloading $URL ..."
curl -fSL "$URL" -o "$TMP/stressline.tar.gz"

echo "Installing to $SHARE_DIR ..."
rm -rf "$SHARE_DIR"
mkdir -p "$SHARE_DIR"
# Strip the top-level stressline-<version>/ directory from the archive.
tar -xzf "$TMP/stressline.tar.gz" -C "$SHARE_DIR" --strip-components=1

if [ ! -x "$SHARE_DIR/bin/stressline" ]; then
    echo "error: launcher not found after extraction ($SHARE_DIR/bin/stressline)" >&2
    exit 1
fi

mkdir -p "$BIN_DIR"
ln -sf "$SHARE_DIR/bin/stressline" "$BIN_DIR/stressline"
echo "Installed: $BIN_DIR/stressline"

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
    echo "NOTE: 'java' was not found on PATH. stressline needs a Java 21+ runtime to run."
fi

echo
echo "Done. Try: stressline --help"
