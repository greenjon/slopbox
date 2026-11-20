#!/bin/sh
# Regenerate app/src/main/assets/rockbox_settings.json from Rockbox's own
# settings table and Settings menus (unmodified source, hosted app config).
#
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RB=$ROOT/third_party/rockbox
B=$ROOT/build-settings
export PATH=$ROOT/host:$PATH

if [ ! -f "$B/Makefile" ]; then
    mkdir -p "$B"
    (cd "$B" && "$RB/tools/configure" --target=sdlapp --type=n \
        --lcdwidth=320 --lcdheight=240 >configure.log)
fi

MENUS="main_menu settings_menu sound_menu playback_menu playlist_menu
       display_menu theme_menu time_menu eq_menu menu_common plugin_menu"
OBJS="$B/apps/settings_list.o $B/firmware/sound.o $B/apps/gui/option_select.o $B/apps/misc.o $B/apps/settings.o $B/apps/language.o $B/firmware/common/itoa_buf.o $B/firmware/common/strmemccpy.o"
for m in $MENUS; do OBJS="$OBJS $B/apps/menus/$m.o"; done
make -C "$B" -s $OBJS "$B/lib/libfixedpoint.a" >/dev/null

# Same flags Rockbox used for settings_list.o.
CFLAGS=$(make -C "$B" -s -n -B "$B/apps/settings_list.o" 2>/dev/null \
    | tr ' ' '\n' | grep -E '^-[ID]|^-std' | grep -vE 'YEAR|MONTH|DAY|TARGET_NAME' | tr '\n' ' ')
CFLAGS="$CFLAGS -DTARGET_NAME=\"sdlapp\""
gcc $CFLAGS -c "$B/lang/lang_core.c" -o "$B/lang_core.o"
gcc $CFLAGS -no-pie -o "$B/settings_dump" "$ROOT/settings-gen/settings_dump.c" \
    $OBJS "$B/lang_core.o" "$B/lib/libfixedpoint.a" -Wl,--unresolved-symbols=ignore-all
"$B/settings_dump" > "$B/settings_raw.json"
python3 -I "$ROOT/settings-gen/gen_settings.py" "$B/settings_dump" "$B/settings_raw.json" \
    "$RB" "$ROOT/app/src/main/assets/rockbox_settings.json"
