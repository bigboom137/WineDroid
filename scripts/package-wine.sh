#!/bin/sh
# Install a Wine build for x86_64-linux-android into the app's assets.
#
# usage: scripts/package-wine.sh <wine build dir> <android deps prefix> [runtime]
#
# Set DXVK_DIR to an unpacked DXVK release (with x64/ and x32/) to bundle it.
# Set LIBERATION_DIR to the unpacked liberation-fonts-ttf release to add Arial,
# Times New Roman and Courier New built from it (needs fontTools, e.g. on
# PYTHONPATH).
#
# The build dir is a Wine tree configured with --host=x86_64-linux-android
# --prefix=/ (see build-wine.sh). The deps prefix holds the cross-compiled
# FreeType and GnuTLS libraries. The runtime name (wine or proton, default wine) is the
# assets directory it goes into; the launcher offers each one present.

set -e

WINE_BUILD=$(cd "$1" && pwd)
DEPS=$(cd "$2" && pwd)
RUNTIME=${3:-wine}
APP=$(cd "$(dirname "$0")/.." && pwd)/app/src/main
ASSETS=$APP/assets/$RUNTIME
NDK_BIN=${NDK_BIN:-$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin}
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

echo "Installing Wine into $STAGE"
make -C "$WINE_BUILD" install-lib DESTDIR="$STAGE" >/dev/null

# Import libraries and headers are only needed to build Windows programs
find "$STAGE" -name '*.a' -delete
rm -rf "$STAGE/include" "$STAGE/share/man" "$STAGE/share/applications"

echo "Stripping"
# Wine for Android installs bin/ and lib/ under the ABI name, next to share/
find "$STAGE/x86_64/lib/wine/x86_64-unix" "$STAGE/x86_64/bin" -type f -exec "$NDK_BIN/llvm-strip" --strip-unneeded {} + 2>/dev/null || true
find "$STAGE/x86_64/lib/wine/x86_64-windows" -type f -exec x86_64-w64-mingw32-strip --strip-unneeded {} + 2>/dev/null || true
find "$STAGE/x86_64/lib/wine/i386-windows" -type f -exec i686-w64-mingw32-strip --strip-unneeded {} + 2>/dev/null || true

echo "Copying into $ASSETS"
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cp -a "$STAGE/x86_64" "$STAGE/share" "$ASSETS/"

# DXVK (Direct3D 8-11 on Vulkan); WineActivity copies it into the prefix
if [ -n "$DXVK_DIR" ]; then
    echo "Adding DXVK from $DXVK_DIR"
    mkdir -p "$ASSETS/share/dxvk"
    cp -r "$DXVK_DIR/x64" "$DXVK_DIR/x32" "$ASSETS/share/dxvk/"
fi

# Fonts that wine.inf registers and Windows programs expect (WineActivity
# copies them into the prefix's Fonts directory)
if [ -n "$LIBERATION_DIR" ]; then
    echo "Adding fonts from $LIBERATION_DIR"
    python3 "$(dirname "$0")/make-fonts.py" "$LIBERATION_DIR" "$ASSETS/share/wine/fonts" >/dev/null
fi

# WineActivity extracts the assets listed in files.sum and re-extracts only
# what changed, using sums.sum to detect a new build.
(cd "$ASSETS" &&
 find x86_64 share -type f -print | LC_ALL=C sort | xargs sha256sum > files.sum &&
 sha256sum files.sum > sums.sum)

echo "Copying native libraries"
mkdir -p "$APP/jniLibs/x86_64"
for lib in libfreetype libgmp libnettle libhogweed libgnutls; do
    cp "$DEPS/lib/$lib.so" "$APP/jniLibs/x86_64/"
    "$NDK_BIN/llvm-strip" --strip-unneeded "$APP/jniLibs/x86_64/$lib.so"
done

echo "Rendering icons"
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
    mkdir -p "$APP/res/drawable-${d%%:*}"
    rsvg-convert -z "${d##*:}" -o "$APP/res/drawable-${d%%:*}/wine.png" "$APP/../../../scripts/wine.svg"
done

du -sh "$ASSETS"
