#!/bin/sh
# Cross-compile Wine (or Proton's Wine) and FreeType for x86_64 Android.
#
# usage: scripts/build-wine.sh <work dir> [wine|proton]
#
# Needs: ANDROID_NDK_HOME (r27+), mingw-w64 (i686 + x86_64), flex, bison,
# cmake, ninja, gcc, autoconf, python3. See README.md.

set -e

FLAVOR=${2:-wine}
WINE_VERSION=${WINE_VERSION:-11.0}
# Wine commit used by Proton 11.0-2c (the "wine" submodule of ValveSoftware/Proton)
PROTON_WINE_COMMIT=${PROTON_WINE_COMMIT:-dc26e61847081a1b5cb0733dc30feba6ee575482}
FREETYPE_VERSION=${FREETYPE_VERSION:-2.13.3}
GMP_VERSION=${GMP_VERSION:-6.3.0}
NETTLE_VERSION=${NETTLE_VERSION:-3.10.1}
GNUTLS_VERSION=${GNUTLS_VERSION:-3.8.9}
DXVK_VERSION=${DXVK_VERSION:-3.1.1}
API=28
JOBS=${JOBS:-$(nproc)}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$1"
WORK=$(cd "$1" && pwd)
NDK_BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
export PATH="$NDK_BIN:$PATH"
cd "$WORK"

case $FLAVOR in
wine)
    SRC=wine-$WINE_VERSION
    if [ ! -d $SRC ]; then
        major=${WINE_VERSION%%.*}
        case $WINE_VERSION in *.0) dir=$WINE_VERSION ;; *) dir=$major.x ;; esac
        curl -sL "https://dl.winehq.org/wine/source/$dir/wine-$WINE_VERSION.tar.xz" | tar xJ
        for p in "$ROOT"/patches/*.patch; do patch -d $SRC -p1 < "$p"; done
    fi
    ;;
proton)
    SRC=wine-proton
    if [ ! -d $SRC ]; then
        curl -sL "https://github.com/ValveSoftware/wine/archive/$PROTON_WINE_COMMIT.tar.gz" | tar xz
        mv wine-$PROTON_WINE_COMMIT $SRC
        for p in "$ROOT"/patches/*.patch "$ROOT"/patches/proton/*.patch; do patch -d $SRC -p1 < "$p"; done
        # git snapshots don't ship generated files (see autogen.sh)
        (cd $SRC && tools/make_requests && tools/make_specfiles &&
         dlls/winevulkan/make_vulkan -x vk.xml -X video.xml && autoreconf -if && rm -rf autom4te.cache)
    fi
    ;;
*)
    echo "unknown flavor $FLAVOR (wine or proton)" >&2
    exit 1
    ;;
esac

if [ ! -f deps/lib/libfreetype.so ]; then
    curl -sL "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VERSION.tar.xz" | tar xJ
    cmake -S freetype-$FREETYPE_VERSION -B ft-build -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=$API -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$WORK/deps" \
        -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
    ninja -C ft-build install
fi

# GnuTLS gives Wine schannel (TLS/HTTPS), bcrypt and crypt32 their crypto.
# Wine dlopen()s it, so it ships in the APK's native libraries. APKs can only
# hold lib*.so names, so every library gets an unversioned SONAME.
DEPS_HOST="--host=x86_64-linux-android --prefix=$WORK/deps --disable-static --enable-shared"
DEPS_CC="CC=x86_64-linux-android$API-clang CXX=x86_64-linux-android$API-clang++ AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip"
DEPS_FLAGS="CPPFLAGS=-I$WORK/deps/include LDFLAGS=-L$WORK/deps/lib"
export PKG_CONFIG_LIBDIR="$WORK/deps/lib/pkgconfig" PKG_CONFIG_PATH=

if [ ! -f deps/lib/libgmp.so ]; then
    curl -sL "https://ftp.gnu.org/gnu/gmp/gmp-$GMP_VERSION.tar.xz" | tar xJ
    (cd gmp-$GMP_VERSION && env $DEPS_CC ./configure $DEPS_HOST && make -j"$JOBS" && make install)
fi

if [ ! -f deps/lib/libhogweed.so ]; then
    curl -sL "https://ftp.gnu.org/gnu/nettle/nettle-$NETTLE_VERSION.tar.gz" | tar xz
    (cd nettle-$NETTLE_VERSION &&
     env $DEPS_CC ./configure $DEPS_HOST --disable-documentation --disable-openssl $DEPS_FLAGS &&
     make -j"$JOBS" LIBNETTLE_SONAME=libnettle.so LIBHOGWEED_SONAME=libhogweed.so &&
     make install-headers install-pkgconfig &&
     cp libnettle.so libhogweed.so "$WORK/deps/lib/")
fi

if [ ! -f deps/lib/libgnutls.so ]; then
    curl -sL "https://www.gnupg.org/ftp/gcrypt/gnutls/v${GNUTLS_VERSION%.*}/gnutls-$GNUTLS_VERSION.tar.xz" | tar xJ
    (cd gnutls-$GNUTLS_VERSION &&
     env $DEPS_CC ./configure $DEPS_HOST --with-included-libtasn1 --with-included-unistring \
        --without-p11-kit --without-idn --without-zlib --without-brotli --without-zstd \
        --without-tpm --without-tpm2 --without-leancrypto --disable-doc --disable-tools \
        --disable-cxx --disable-tests --disable-nls --disable-guile --disable-libdane $DEPS_FLAGS &&
     make -j"$JOBS" && make install)
fi

# sfnt2fon (a build tool) needs a native FreeType to make Wine's bitmap fonts
if [ ! -f host-deps/lib/libfreetype.a ]; then
    cmake -S freetype-$FREETYPE_VERSION -B host-ft -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_INSTALL_PREFIX="$WORK/host-deps" \
        -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
    ninja -C host-ft install
fi

# Wine needs native copies of its build tools (winebuild, wrc, widl...)
TOOLS=tools-$FLAVOR
if [ ! -x $TOOLS/tools/winebuild/winebuild ]; then
    mkdir -p $TOOLS
    (cd $TOOLS && ../$SRC/configure --enable-win64 --enable-archs=x86_64 \
        FREETYPE_CFLAGS="-I$WORK/host-deps/include/freetype2" \
        FREETYPE_LIBS="$WORK/host-deps/lib/libfreetype.a -lm" \
        --without-x --without-wayland --without-vulkan --without-opengl \
        --without-unwind --disable-tests &&
     make -j"$JOBS" __tooldeps__ nls/locale.nls)
fi

# Proton modules that only make sense on desktop Linux (they need libdrm,
# Steam or other libraries Android doesn't have)
EXTRA_CONFIG=
[ $FLAVOR = proton ] && EXTRA_CONFIG="--disable-amd_ags_x64"

mkdir -p $FLAVOR-android
cd $FLAVOR-android
if [ ! -f Makefile ]; then
    ../$SRC/configure --host=x86_64-linux-android --prefix=/ --disable-tests \
        CC=x86_64-linux-android$API-clang CXX=x86_64-linux-android$API-clang++ \
        AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
        --with-wine-tools=../$TOOLS --enable-archs=i386,x86_64 \
        FREETYPE_CFLAGS="-I$WORK/deps/include/freetype2" FREETYPE_LIBS="-L$WORK/deps/lib -lfreetype" \
        GNUTLS_CFLAGS="-I$WORK/deps/include" GNUTLS_LIBS="-L$WORK/deps/lib -lgnutls" \
        --without-x --without-wayland --without-gstreamer --without-pulse --without-alsa \
        --without-dbus --without-cups --without-sane --without-usb \
        --without-v4l2 --without-krb5 --without-sdl --without-udev --without-fontconfig \
        --without-capi --without-gphoto --without-pcsclite --without-ffmpeg --without-unwind \
        $EXTRA_CONFIG
fi

# Wine's own makefile tries to build its old Gradle project at the end; the
# APK is built by this repository instead, so stub that step out.
mkdir -p "$WORK/fakebin"
printf '#!/bin/sh\nmkdir -p build/outputs/apk && touch build/outputs/apk/wine-debug.apk\n' > "$WORK/fakebin/gradle"
chmod +x "$WORK/fakebin/gradle"
PATH="$WORK/fakebin:$PATH" make -j"$JOBS"

cd "$WORK"
if [ ! -d dxvk-$DXVK_VERSION ]; then
    curl -sL "https://github.com/doitsujin/dxvk/releases/download/v$DXVK_VERSION/dxvk-$DXVK_VERSION.tar.gz" | tar xz
fi

DXVK_DIR="$WORK/dxvk-$DXVK_VERSION" NDK_BIN=$NDK_BIN \
    "$ROOT/scripts/package-wine.sh" "$WORK/$FLAVOR-android" "$WORK/deps" $FLAVOR
