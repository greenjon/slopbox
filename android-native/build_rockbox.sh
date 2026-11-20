#!/bin/sh
# Build Rockbox's codecs and libraries for Android with the NDK, using
# Rockbox's own (unmodified) configure + make through its CROSS_COMPILE hook.
#
#   android-native/build_rockbox.sh [ABI...]     default: arm64-v8a x86_64
#
# Output per ABI in build-android/<abi>/:
#   lib/librbcodec.a lib/libfixedpoint.a lib/libtlsf.a, firmware objects,
#   lib/rbcodec/codecs/*.codec  (copied to jniLibs as libsb_<name>.so)
#
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RB=$ROOT/third_party/rockbox
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/29.0.14206865}
API=${SB_ANDROID_API:-28}
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
JNILIBS=$ROOT/app/src/main/jniLibs

[ -x "$LLVM/clang" ] || { echo "NDK not found at $NDK" >&2; exit 1; }
[ $# -gt 0 ] || set -- arm64-v8a x86_64

for ABI in "$@"; do
    case $ABI in
        arm64-v8a) TRIPLE=aarch64-linux-android ;;
        x86_64)    TRIPLE=x86_64-linux-android ;;
        *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
    esac
    OUT=$ROOT/build-android/$ABI
    TC=$OUT/toolchain
    mkdir -p "$TC"

    # GCC-style names that Rockbox's configure expects, pointing at the NDK.
    P=$TC/$TRIPLE-
    # Drop GCC-only flags clang rejects; libgcc's role is played by
    # compiler-rt, which clang links on its own.
    printf '#!/bin/sh\nfor a; do shift; case "$a" in -Wimplicit-fallthrough=0|-lgcc|--print-sysroot) continue;; esac; set -- "$@" "$a"; done\nexec %s/clang --target=%s%s "$@"\n' \
        "$LLVM" "$TRIPLE" "$API" > "${P}gcc"
    # configure pipes code into cpp on stdin; clang needs "-x c -" for that.
    # clang spells __ARM_ARCH_PROFILE as 'A' where GCC (and configure) use 65.
    printf '#!/bin/sh\nfor a; do case "$a" in -*) ;; *) exec %s/clang --target=%s%s -E "$@";; esac; done\n%s/clang --target=%s%s -E "$@" -x c - | sed "s/__ARM_ARCH_PROFILE '"'"'A'"'"'/__ARM_ARCH_PROFILE 65/"\n' \
        "$LLVM" "$TRIPLE" "$API" "$LLVM" "$TRIPLE" "$API" > "${P}cpp"
    printf '#!/bin/sh\nexec %s/llvm-ar "$@"\n' "$LLVM" > "${P}gcc-ar"
    printf '#!/bin/sh\nexec %s/llvm-ranlib "$@"\n' "$LLVM" > "${P}gcc-ranlib"
    printf '#!/bin/sh\nexec %s/ld.lld "$@"\n' "$LLVM" > "${P}ld"
    printf '#!/bin/sh\nexec %s/llvm-objcopy "$@"\n' "$LLVM" > "${P}objcopy"
    printf '#!/bin/sh\nexec %s/clang --target=%s%s -c "$@"\n' "$LLVM" "$TRIPLE" "$API" > "${P}as"
    # warble's make file asks sdl2-config for flags; point it at the stub.
    printf '#!/bin/sh\ncase "$1" in --version) echo 2.30.0;; --cflags) echo "-I%s/host/sdlstub";; *) echo "";; esac\n' \
        "$ROOT" > "${P}sdl2-config"
    chmod +x "$TC"/*

    (
        cd "$OUT"
        PATH=$TC:$PATH CROSS_COMPILE=$TRIPLE- HOSTCC=gcc HOSTAR=ar \
            "$RB/tools/configure" --target=sdlapp --type=w \
            --lcdwidth=320 --lcdheight=240 >configure.log
        # lld has no --no-warn-rwx-segments; Android also wants 16 KB pages,
        # and the static libraries end up inside libslopbox.so, so -fPIC.
        sed -i -e 's/ -Wl,--no-warn-rwx-segments//g' \
               -e 's/^export GCCOPTS=\(.*\)/export GCCOPTS=\1 -fPIC/' \
               -e 's/^export GLOBAL_LDOPTS=\(.*\)/export GLOBAL_LDOPTS=\1 -Wl,-z,max-page-size=16384/' \
            Makefile
        # Codecs and libraries only: warble itself is a host program.
        PATH=$TC:$PATH make -j"$(nproc)" -k codecs "$OUT/lib/librbcodec.a" \
            "$OUT/lib/libfixedpoint.a" "$OUT/lib/libtlsf.a" >make.log 2>&1 || true
    )

    mkdir -p "$JNILIBS/$ABI"
    rm -f "$JNILIBS/$ABI"/libsb_*.so
    n=0
    for c in "$OUT"/lib/rbcodec/codecs/*.codec; do
        [ -e "$c" ] || continue
        cp "$c" "$JNILIBS/$ABI/libsb_$(basename "$c" .codec).so"
        n=$((n + 1))
    done
    # Known gap: sid (cRSID) uses GCC nested functions, which clang rejects.
    echo "$ABI: $n codecs -> $JNILIBS/$ABI (log: $OUT/make.log)"
done
