/*
 * SlopBox: Rockbox settings that reach the DSP. Values are Rockbox's own,
 * as stored in global_settings and .cfg files.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#ifndef SB_SETTINGS_H
#define SB_SETTINGS_H
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Keep in sync with Settings.kt (ENGINE_IDS). */
enum sb_setting_id {
    SB_SET_VOLUME,
    SB_SET_VOLUME_LIMIT,
    SB_SET_BASS,
    SB_SET_TREBLE,
    SB_SET_BALANCE,
    SB_SET_CHANNELS,
    SB_SET_STEREO_WIDTH,
    SB_SET_CROSSFEED,
    SB_SET_CROSSFEED_DIRECT_GAIN,
    SB_SET_CROSSFEED_CROSS_GAIN,
    SB_SET_CROSSFEED_HF_ATTENUATION,
    SB_SET_CROSSFEED_HF_CUTOFF,
    SB_SET_EQ_ENABLED,
    SB_SET_EQ_PRECUT,
    SB_SET_DITHERING,
    SB_SET_SURROUND_ENABLED,
    SB_SET_SURROUND_BALANCE,
    SB_SET_SURROUND_FX1,
    SB_SET_SURROUND_FX2,
    SB_SET_SURROUND_SIDE_ONLY,
    SB_SET_SURROUND_MIX,
    SB_SET_PBE,
    SB_SET_PBE_PRECUT,
    SB_SET_AFR,
    SB_SET_TIMESTRETCH,
    SB_SET_COMPRESSOR_THRESHOLD,
    SB_SET_COMPRESSOR_MAKEUP_GAIN,
    SB_SET_COMPRESSOR_RATIO,
    SB_SET_COMPRESSOR_KNEE,
    SB_SET_COMPRESSOR_ATTACK,
    SB_SET_COMPRESSOR_RELEASE,
    SB_SET_REPLAYGAIN_TYPE,
    SB_SET_REPLAYGAIN_NOCLIP,
    SB_SET_REPLAYGAIN_PREAMP,
    SB_SET_COUNT
};

void sb_settings_init(void);
/* Thread-safe; takes effect on the decode thread. */
void sb_setting_set(int id, int value);
/* EQ band 0-9: type (0 low shelf, 1 peak, 2 high shelf), Hz, Q in tenths,
 * gain in tenths of a dB -- Rockbox's "eq filter N" setting. */
void sb_setting_set_eq_band(int band, int type, int cutoff, int q, int gain);
void sb_setting_set_shuffle(bool on);

/* Engine internals. */
void sb_settings_reapply_all(void);
void sb_settings_apply_pending(void);
/* Per-channel output gain in centibels (volume and balance). */
void sb_settings_output_gain_cb(int *l, int *r);

#ifdef __cplusplus
}
#endif
#endif
