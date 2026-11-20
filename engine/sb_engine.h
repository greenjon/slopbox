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

/* Output: decoded audio queues in the PCM buffer (sb_pcmbuf.h); an output
 * thread reads it with sb_pcmbuf_read() and passes each block through
 * sb_output_process() (volume, balance, ducking, pause fade) before playing it. */
void sb_output_process(int16_t *pcm, int frames);

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

/* Decode one file into the PCM buffer, starting start_ms into it. serial
 * identifies the track for position reporting; auto_skip says whether this is
 * an automatic track change (gapless/crossfade into what's queued) or a
 * manual one (crossfade or cut, per the crossfade setting). Blocks until the
 * end, sb_stop() or an error. Returns 0 on success. One file at a time. */
int sb_play_file(const char *path, long start_ms, int serial, bool auto_skip,
                 struct sb_track_info *info_out);
/* Same, from an already-open file descriptor (Android content URIs). The
 * engine does not close fd. name is used for format detection by extension. */
int sb_play_fd(int fd, const char *name, long start_ms, int serial,
               bool auto_skip, struct sb_track_info *info_out);

/* A file's tags as Rockbox's metadata parser (get_metadata) reads them. */
struct sb_tags {
    char title[256], artist[256], album[256], albumartist[256], genre[128],
         composer[256], comment[512], grouping[256], disc[32], track[32],
         year[32], mb_track_id[64];
    char codec[32];
    int tracknum, discnum, year_num;
    unsigned long length_ms, frequency;
    int bitrate;
    /* ReplayGain as Rockbox keeps it: gains are linear factors with
     * 1 << 24 = unity (0 when absent); peaks likewise. */
    long track_gain, album_gain, track_peak, album_peak;
    /* Embedded album art: type 1 BMP, 2 PNG, 3 JPG (0 none); flags bit 0 ID3
     * unsynchronised, bit 1 Vorbis base64 (a FLAC picture block). */
    int art_type, art_flags, art_size;
    long art_pos;
};

/* Read tags without playing. Thread-safe; doesn't close fd. 0 on success. */
int sb_read_tags(int fd, const char *name, struct sb_tags *out);

/* These may be called from any thread while a file plays. */
void sb_stop(void);
void sb_seek_ms(long ms);
/* What the listener hears now (not what's being decoded). */
long sb_elapsed_ms(void);
int sb_playing_serial(void);

/* Rockbox settings (EQ, crossfeed, tone, ...) are in sb_settings.h. */

/* Rockbox's pitch screen: 1.0 = normal. Tempo changes speed, not pitch. */
void sb_set_tempo(double tempo);
void sb_set_pitch(double rate);
/* Fade on Stop/Pause: ramp the output in or out over ~1/3 second (Rockbox's
 * pcmbuf_fade), or jump there when ramp is false. */
void sb_fade(bool in, bool ramp);
bool sb_fade_done(void);
bool sb_fade_silent(void);

/* Temporary attenuation while another app talks over us (0 or negative dB). */
void sb_set_duck_db(int db);

#ifdef __cplusplus
}
#endif

#endif
