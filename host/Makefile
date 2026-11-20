# Host (Linux) build of the SlopBox engine test driver.
#
# Uses Rockbox's own build system, unmodified, for the codecs and libraries:
#   make -C host rockbox   # configure + build Rockbox's warble target
#   make -C host           # build sb_cli against it
#
# SPDX-License-Identifier: GPL-2.0-or-later

ROOT    := $(abspath ..)
RB      := $(ROOT)/third_party/rockbox
BUILD   := $(ROOT)/build-host
CODECS  := $(BUILD)/lib/rbcodec/codecs

DEFS := -DAPPLICATION -DROCKBOX -DSDLAPP -DWARBLE -D__PCTOOL__ -DDEBUG \
        -DMEMORYSIZE=8 -DTARGET_ID=25 -DTARGET_NAME=\"sdlapp\" \
        -DOS_USE_BYTESWAP_H \
        -DCODEC_PATH_FMT=\"$(CODECS)/%s.codec\"

# Same include order as Rockbox's warble build; lib/rbcodec/test/file.h must
# win over firmware/include/file.h.
INCS := -I$(ROOT)/engine -I$(BUILD) -I$(RB)/lib/fixedpoint \
        -I$(RB)/lib/rbcodec/test -I$(RB)/apps -I$(RB)/apps/gui \
        -I$(RB)/firmware/kernel/include -I$(RB)/firmware/export \
        -I$(RB)/firmware/include -I$(RB)/firmware/target/hosted \
        -I$(RB)/firmware/target/hosted/sdl -I$(RB)/lib/tlsf/src \
        -I$(RB)/lib/rbcodec -I$(RB)/lib/rbcodec/codecs \
        -I$(RB)/lib/rbcodec/dsp -I$(RB)/lib/rbcodec/metadata

CFLAGS := -std=gnu11 -O2 -g -Wall -Wno-pointer-sign -Wno-override-init \
          -include $(BUILD)/autoconf.h

FW_OBJS := $(addprefix $(BUILD)/lib/rbcodec/test/../../../firmware/, \
           common/strmemccpy.o common/strlcpy.o common/unicode.o \
           common/pathfuncs.o common/crc32.o buflib_mempool.o core_alloc.o)
LIBS := $(BUILD)/lib/librbcodec.a $(BUILD)/lib/libfixedpoint.a \
        $(BUILD)/lib/libtlsf.a -lm -ldl -lpthread

ENGINE := $(ROOT)/engine/sb_engine.c $(ROOT)/engine/sb_settings.c \
          $(RB)/firmware/sound.c $(RB)/firmware/drivers/audio/audiohw-swcodec.c

sb_cli: sb_cli.c $(ENGINE) $(ROOT)/engine/sb_engine.h $(ROOT)/engine/sb_settings.h
	$(CC) $(CFLAGS) $(DEFS) $(INCS) -o $@ sb_cli.c $(ENGINE) \
	    $(FW_OBJS) $(LIBS)

rockbox:
	mkdir -p $(BUILD)
	cd $(BUILD) && PATH=$(ROOT)/host:$$PATH SDLCONFIG=$(ROOT)/host/sdl2-config \
	    $(RB)/tools/configure --target=sdlapp --type=w \
	    --lcdwidth=320 --lcdheight=240
	PATH=$(ROOT)/host:$$PATH $(MAKE) -C $(BUILD)

.PHONY: rockbox
