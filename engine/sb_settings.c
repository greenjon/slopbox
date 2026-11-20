/*
 * SlopBox: apply Rockbox settings to the DSP the way Rockbox does.
 *
 * Values are Rockbox's own (the numbers stored in global_settings and
 * written to .cfg files). They are queued from any thread and applied on the
 * decode thread, mirroring apps/settings.c settings_apply() and
 * sound_settings_apply(); bass/treble/balance/channels/stereo width/volume
 * go through Rockbox's firmware/sound.c unchanged.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <stdatomic.h>

#include "config.h"
#include "settings.h"
#include "sound.h"
#include "dsp_core.h"
#include "dsp_misc.h"
#include "eq.h"
#include "crossfeed.h"
#include "compressor.h"
#include "surround.h"
#include "pbe.h"
#include "afr.h"
#include "tdspeed.h"

#include "sb_engine.h"
#include "sb_settings.h"
#include "sb_pcmbuf.h"

/* Rockbox's firmware/sound.c reads these. */
struct system_status global_status;
extern struct user_settings global_settings;

/* Per-channel output gain from sound.c (centibels), applied in the sink. */
static atomic_int out_cb_l = 0, out_cb_r = 0;

#if defined(AUDIOHW_HAVE_MONO_VOLUME)
void audiohw_set_volume(int volume)
{
    atomic_store(&out_cb_l, volume);
    atomic_store(&out_cb_r, volume);
}
#else
void audiohw_set_volume(int vol_l, int vol_r)
{
    atomic_store(&out_cb_l, vol_l);
    atomic_store(&out_cb_r, vol_r);
}
#endif

void sb_settings_output_gain_cb(int *l, int *r)
{
    *l = atomic_load(&out_cb_l);
    *r = atomic_load(&out_cb_r);
}

/* ---- Queue between the setting thread and the decode thread ---- */

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int pending_val[SB_SET_COUNT];
static uint64_t pending_dirty;
static struct eq_band_setting pending_band[EQ_NUM_BANDS];
static unsigned pending_band_dirty;
static bool shuffle_on;

static void push_crossfade(void)
{
    /* Lock held. The buffer applies these itself, at once. */
    sb_pcmbuf_set_crossfade(pending_val[SB_SET_CROSSFADE],
                            pending_val[SB_SET_CROSSFADE_FADE_IN_DELAY],
                            pending_val[SB_SET_CROSSFADE_FADE_IN_DURATION],
                            pending_val[SB_SET_CROSSFADE_FADE_OUT_DELAY],
                            pending_val[SB_SET_CROSSFADE_FADE_OUT_DURATION],
                            pending_val[SB_SET_CROSSFADE_FADE_OUT_MIXMODE] != 0);
}

void sb_setting_set(int id, int value)
{
    if (id < 0 || id >= SB_SET_COUNT)
        return;
    pthread_mutex_lock(&lock);
    pending_val[id] = value;
    pending_dirty |= 1ull << id;
    if (id >= SB_SET_CROSSFADE && id <= SB_SET_CROSSFADE_FADE_OUT_MIXMODE)
        push_crossfade();
    pthread_mutex_unlock(&lock);
}

void sb_setting_set_eq_band(int band, int type, int cutoff, int q, int gain)
{
    if (band < 0 || band >= EQ_NUM_BANDS)
        return;
    pthread_mutex_lock(&lock);
    pending_band[band].type = (enum eq_filter_type)type;
    pending_band[band].cutoff = cutoff;
    pending_band[band].q = q;
    pending_band[band].gain = gain;
    pending_band_dirty |= 1u << band;
    pthread_mutex_unlock(&lock);
}

void sb_setting_set_shuffle(bool on)
{
    pthread_mutex_lock(&lock);
    shuffle_on = on;
    pending_dirty |= 1ull << SB_SET_REPLAYGAIN_TYPE;
    sb_pcmbuf_set_shuffle(on);
    pthread_mutex_unlock(&lock);
}

/* apps/misc.c replaygain_setting_mode() */
static int replaygain_mode(int type)
{
    if (type == REPLAYGAIN_SHUFFLE)
        type = shuffle_on ? REPLAYGAIN_TRACK : REPLAYGAIN_ALBUM;
    return type;
}

void sb_settings_reapply_all(void)
{
    pthread_mutex_lock(&lock);
    pending_dirty = (1ull << SB_SET_COUNT) - 1;
    pending_band_dirty = (1u << EQ_NUM_BANDS) - 1;
    pthread_mutex_unlock(&lock);
}

/* Called on the decode thread between buffers. */
void sb_settings_apply_pending(void)
{
    pthread_mutex_lock(&lock);
    uint64_t dirty = pending_dirty;
    unsigned band_dirty = pending_band_dirty;
    int v[SB_SET_COUNT];
    struct eq_band_setting bands[EQ_NUM_BANDS];
    memcpy(v, pending_val, sizeof(v));
    memcpy(bands, pending_band, sizeof(bands));
    pending_dirty = 0;
    pending_band_dirty = 0;
    pthread_mutex_unlock(&lock);

    if (!dirty && !band_dirty)
        return;

#define D(id) (dirty & (1ull << (id)))
    struct user_settings *g = &global_settings;

    /* sound_settings_apply() */
    if (D(SB_SET_VOLUME_LIMIT)) g->volume_limit = v[SB_SET_VOLUME_LIMIT];
    if (D(SB_SET_BASS))   { g->bass = v[SB_SET_BASS]; sound_set(SOUND_BASS, g->bass); }
    if (D(SB_SET_TREBLE)) { g->treble = v[SB_SET_TREBLE]; sound_set(SOUND_TREBLE, g->treble); }
    if (D(SB_SET_BALANCE)) { g->balance = v[SB_SET_BALANCE]; sound_set(SOUND_BALANCE, g->balance); }
    if (D(SB_SET_VOLUME) || D(SB_SET_VOLUME_LIMIT))
        sound_set(SOUND_VOLUME, v[SB_SET_VOLUME]);
    if (D(SB_SET_CHANNELS)) { g->channel_config = v[SB_SET_CHANNELS]; sound_set(SOUND_CHANNELS, g->channel_config); }
    if (D(SB_SET_STEREO_WIDTH)) { g->stereo_width = v[SB_SET_STEREO_WIDTH]; sound_set(SOUND_STEREO_WIDTH, g->stereo_width); }

    /* settings_apply() */
    if (D(SB_SET_REPLAYGAIN_TYPE) || D(SB_SET_REPLAYGAIN_NOCLIP) || D(SB_SET_REPLAYGAIN_PREAMP)) {
        struct replaygain_settings rg = {
            .noclip = v[SB_SET_REPLAYGAIN_NOCLIP] != 0,
            .type = replaygain_mode(v[SB_SET_REPLAYGAIN_TYPE]),
            .preamp = v[SB_SET_REPLAYGAIN_PREAMP],
        };
        dsp_replaygain_set_settings(&rg);
    }
    if (D(SB_SET_CROSSFEED))
        dsp_set_crossfeed_type(v[SB_SET_CROSSFEED]);
    if (D(SB_SET_CROSSFEED_DIRECT_GAIN))
        dsp_set_crossfeed_direct_gain(v[SB_SET_CROSSFEED_DIRECT_GAIN]);
    if (D(SB_SET_CROSSFEED_CROSS_GAIN) || D(SB_SET_CROSSFEED_HF_ATTENUATION) || D(SB_SET_CROSSFEED_HF_CUTOFF))
        dsp_set_crossfeed_cross_params(v[SB_SET_CROSSFEED_CROSS_GAIN],
                                       v[SB_SET_CROSSFEED_HF_ATTENUATION],
                                       v[SB_SET_CROSSFEED_HF_CUTOFF]);
    if (D(SB_SET_EQ_ENABLED))
        dsp_eq_enable(v[SB_SET_EQ_ENABLED] != 0);
    if (D(SB_SET_EQ_PRECUT))
        dsp_set_eq_precut(v[SB_SET_EQ_PRECUT]);
    for (int b = 0; b < EQ_NUM_BANDS; b++) {
        if (band_dirty & (1u << b))
            dsp_set_eq_coefs(b, &bands[b]);
    }
    if (D(SB_SET_DITHERING))
        dsp_dither_enable(v[SB_SET_DITHERING] != 0);
    if (D(SB_SET_SURROUND_BALANCE))
        dsp_surround_set_balance(v[SB_SET_SURROUND_BALANCE]);
    if (D(SB_SET_SURROUND_FX1) || D(SB_SET_SURROUND_FX2))
        dsp_surround_set_cutoff(v[SB_SET_SURROUND_FX1], v[SB_SET_SURROUND_FX2]);
    if (D(SB_SET_SURROUND_SIDE_ONLY))
        dsp_surround_side_only(v[SB_SET_SURROUND_SIDE_ONLY] != 0);
    if (D(SB_SET_SURROUND_MIX))
        dsp_surround_mix(v[SB_SET_SURROUND_MIX]);
    if (D(SB_SET_SURROUND_ENABLED))
        dsp_surround_enable(v[SB_SET_SURROUND_ENABLED]);
    if (D(SB_SET_AFR))
        dsp_afr_enable(v[SB_SET_AFR]);
    if (D(SB_SET_PBE_PRECUT))
        dsp_pbe_precut(v[SB_SET_PBE_PRECUT]);
    if (D(SB_SET_PBE))
        dsp_pbe_enable(v[SB_SET_PBE]);
    if (D(SB_SET_TIMESTRETCH)) {
        g->timestretch_enabled = v[SB_SET_TIMESTRETCH] != 0;
        dsp_timestretch_enable(g->timestretch_enabled);
    }
    if (D(SB_SET_COMPRESSOR_THRESHOLD) || D(SB_SET_COMPRESSOR_MAKEUP_GAIN)
            || D(SB_SET_COMPRESSOR_RATIO) || D(SB_SET_COMPRESSOR_KNEE)
            || D(SB_SET_COMPRESSOR_ATTACK) || D(SB_SET_COMPRESSOR_RELEASE)) {
        struct compressor_settings c = {
            .threshold = v[SB_SET_COMPRESSOR_THRESHOLD],
            .makeup_gain = v[SB_SET_COMPRESSOR_MAKEUP_GAIN],
            .ratio = v[SB_SET_COMPRESSOR_RATIO],
            .knee = v[SB_SET_COMPRESSOR_KNEE],
            .release_time = v[SB_SET_COMPRESSOR_RELEASE],
            .attack_time = v[SB_SET_COMPRESSOR_ATTACK],
        };
        dsp_set_compressor(&c);
    }
#undef D
}

/* Rockbox's defaults (apps/settings_list.c), so the engine behaves like a
 * fresh Rockbox until the app sends the user's values. */
static const int defaults[SB_SET_COUNT] = {
    [SB_SET_VOLUME] = 0,
    [SB_SET_VOLUME_LIMIT] = 0,
    [SB_SET_STEREO_WIDTH] = 100,
    [SB_SET_CROSSFEED_DIRECT_GAIN] = -15,
    [SB_SET_CROSSFEED_CROSS_GAIN] = -60,
    [SB_SET_CROSSFEED_HF_ATTENUATION] = -160,
    [SB_SET_CROSSFEED_HF_CUTOFF] = 700,
    [SB_SET_SURROUND_BALANCE] = 35,
    [SB_SET_SURROUND_FX1] = 3400,
    [SB_SET_SURROUND_FX2] = 320,
    [SB_SET_SURROUND_MIX] = 50,
    [SB_SET_PBE_PRECUT] = -25,
    [SB_SET_COMPRESSOR_MAKEUP_GAIN] = 1,
    [SB_SET_COMPRESSOR_RATIO] = 1,
    [SB_SET_COMPRESSOR_KNEE] = 1,
    [SB_SET_COMPRESSOR_ATTACK] = 5,
    [SB_SET_COMPRESSOR_RELEASE] = 500,
    [SB_SET_REPLAYGAIN_TYPE] = REPLAYGAIN_SHUFFLE,
    /* Crossfade off; delays/durations as in settings_list.c. */
    [SB_SET_CROSSFADE_FADE_IN_DELAY] = 0,
    [SB_SET_CROSSFADE_FADE_IN_DURATION] = 2,
    [SB_SET_CROSSFADE_FADE_OUT_DELAY] = 0,
    [SB_SET_CROSSFADE_FADE_OUT_DURATION] = 2,
};
static const struct eq_band_setting default_bands[EQ_NUM_BANDS] = {
    { EQ_FILTER_LOW_SHELF,  32,    7, 0 },
    { EQ_FILTER_PEAK,       64,   10, 0 },
    { EQ_FILTER_PEAK,       125,  10, 0 },
    { EQ_FILTER_PEAK,       250,  10, 0 },
    { EQ_FILTER_PEAK,       500,  10, 0 },
    { EQ_FILTER_PEAK,       1000, 10, 0 },
    { EQ_FILTER_PEAK,       2000, 10, 0 },
    { EQ_FILTER_PEAK,       4000, 10, 0 },
    { EQ_FILTER_PEAK,       8000, 10, 0 },
    { EQ_FILTER_HIGH_SHELF, 16000, 7, 0 },
};

void sb_settings_init(void)
{
    memset(&global_status, 0, sizeof(global_status));
    memcpy(pending_val, defaults, sizeof(pending_val));
    memcpy(pending_band, default_bands, sizeof(pending_band));
    global_settings.volume_limit = defaults[SB_SET_VOLUME_LIMIT];
    pthread_mutex_lock(&lock);
    push_crossfade();
    pthread_mutex_unlock(&lock);
    sb_settings_reapply_all();
}
