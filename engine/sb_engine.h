/*
 * SlopBox playback engine: runs Rockbox codecs and the Rockbox DSP chain.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#ifndef SB_ENGINE_H
#define SB_ENGINE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Receives interleaved stereo 16-bit PCM at sb_output_rate(). May block (that
 * is how the decoder is paced). Return false to stop decoding. */
typedef bool (*sb_pcm_sink)(void *user, const int16_t *pcm, int frames);

struct sb_track_info {
    char title[256];
    char artist[256];
    char album[256];
    char codec[32];
    unsigned long length_ms;
    unsigned long frequency;
    int bitrate;
};

/* codec_path_fmt is a printf pattern with one %s for the codec name, e.g.
 * "/path/to/codecs/%s.codec" on a host build or "libsb_%s.so" on Android. */
void sb_engine_init(const char *codec_path_fmt);
unsigned sb_output_rate(void);

/* Decode one file to the sink. Blocks until the end, sb_stop() or an error.
 * Returns 0 on success. Only one file plays at a time. */
int sb_play_file(const char *path, sb_pcm_sink sink, void *user,
                 struct sb_track_info *info_out);
/* Same, from an already-open file descriptor (Android content URIs). The
 * engine does not close fd. name is used for format detection by extension. */
int sb_play_fd(int fd, const char *name, sb_pcm_sink sink, void *user,
               struct sb_track_info *info_out);

/* These may be called from any thread while a file plays. */
void sb_stop(void);
void sb_seek_ms(long ms);
long sb_elapsed_ms(void);

/* --- DSP settings, applied on the decode thread between buffers --- */
enum sb_eq_type { SB_EQ_LOW_SHELF = 0, SB_EQ_PEAK = 1, SB_EQ_HIGH_SHELF = 2 };
#define SB_EQ_BANDS 10

void sb_set_eq_enabled(bool on);
/* q in tenths (7 = 0.7), gain in tenths of a dB (60 = +6.0 dB). */
void sb_set_eq_band(int band, int type, int cutoff_hz, int q, int gain);
/* Precut in tenths of a dB, 0..240 (Rockbox "eq precut"). */
void sb_set_eq_precut(int precut);

enum sb_crossfeed { SB_CROSSFEED_OFF = 0, SB_CROSSFEED_MEIER = 1,
                    SB_CROSSFEED_CUSTOM = 2 };
void sb_set_crossfeed(int type);

/* 1.0 = normal. Tempo changes speed without changing pitch. */
void sb_set_tempo(double tempo);
void sb_set_pitch(double rate);
/* Attenuation in whole dB, 0 or negative. */
void sb_set_volume_db(int db);
void sb_set_dither(bool on);

#ifdef __cplusplus
}
#endif

#endif
