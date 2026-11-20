# Native part of SlopBox: the playback engine plus Rockbox's DSP/metadata
# libraries. Codecs are separate .so files built by
# android-native/build_rockbox.sh, which must run first.
#
# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.22)
project(slopbox C)

get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(RB "${ROOT}/third_party/rockbox")
set(RBBUILD "${ROOT}/build-android/${ANDROID_ABI}")

if(NOT EXISTS "${RBBUILD}/lib/librbcodec.a")
    message(FATAL_ERROR "Run android-native/build_rockbox.sh first (missing ${RBBUILD})")
endif()

add_library(slopbox SHARED
    jni.c
    aaudio_sink.c
    ${ROOT}/engine/sb_engine.c
    ${ROOT}/engine/sb_settings.c
    # Rockbox's own sound settings code (bass/treble/balance/channels/width).
    ${RB}/firmware/sound.c
    ${RB}/firmware/drivers/audio/audiohw-swcodec.c
    # The same firmware helpers Rockbox's warble links.
    ${RB}/firmware/common/strmemccpy.c
    ${RB}/firmware/common/strlcpy.c
    ${RB}/firmware/common/unicode.c
    ${RB}/firmware/common/pathfuncs.c
    ${RB}/firmware/common/crc32.c
    ${RB}/firmware/buflib_mempool.c
    ${RB}/firmware/core_alloc.c
)

# Same defines and include order as Rockbox's own warble build; the order
# matters (lib/rbcodec/test/file.h must win over firmware/include/file.h).
target_compile_definitions(slopbox PRIVATE
    APPLICATION ROCKBOX SDLAPP WARBLE __PCTOOL__ DEBUG MEMORYSIZE=8
    TARGET_ID=25 TARGET_NAME="sdlapp" OS_USE_BYTESWAP_H
)
target_compile_options(slopbox PRIVATE
    -std=gnu11 -O2 -include ${RBBUILD}/autoconf.h
    -Wno-pointer-sign -Wno-override-init -Wno-unknown-warning-option
)
target_include_directories(slopbox PRIVATE
    ${ROOT}/engine ${RBBUILD} ${RB}/lib/fixedpoint ${RB}/lib/rbcodec/test
    ${RB}/apps ${RB}/apps/gui ${RB}/firmware/kernel/include
    ${RB}/firmware/export ${RB}/firmware/include
    ${RB}/firmware/target/hosted ${RB}/firmware/target/hosted/sdl
    ${RB}/lib/tlsf/src ${RB}/lib/rbcodec ${RB}/lib/rbcodec/codecs
    ${RB}/lib/rbcodec/dsp ${RB}/lib/rbcodec/metadata
)
target_link_libraries(slopbox
    ${RBBUILD}/lib/librbcodec.a
    ${RBBUILD}/lib/libfixedpoint.a
    ${RBBUILD}/lib/libtlsf.a
    aaudio log dl m
)
target_link_options(slopbox PRIVATE "-Wl,-z,max-page-size=16384")
