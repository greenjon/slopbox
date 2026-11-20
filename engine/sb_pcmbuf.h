/*
 * SlopBox PCM buffer between the decoder and the output, where gapless joins
 * and Rockbox-style crossfades happen. See sb_pcmbuf.c.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#ifndef SB_PCMBUF_H
#define SB_PCMBUF_H
#include <stdbool.h>
#include <stdint.h>

void sb_pcmbuf_init(unsigned out_rate);
/* Rockbox crossfade settings: mode 0 off, 1 auto track change, 2 manual skip,
 * 3 shuffle, 4 shuffle or manual skip, 5 always; delays/durations in
 * seconds; mixmode = fade-out mode "mix". */
void sb_pcmbuf_set_crossfade(int mode, int in_delay, int in_dur, int out_delay,
                             int out_dur, bool mixmode);
void sb_pcmbuf_set_shuffle(bool on);

/* Decoder side. */
void sb_pcmbuf_reset(int serial, long elapsed_ms);
/* A new track is about to be written: gapless, crossfade or (manual, no
 * crossfade) discard what's queued -- Rockbox's pcmbuf_start_track(). */
void sb_pcmbuf_begin_track(int serial, bool auto_skip, long elapsed_ms);
/* Blocks until there's room. False once aborted. */
bool sb_pcmbuf_write(const int16_t *pcm, int frames, int serial, long elapsed_ms);
/* Seek: drop what's queued. */
void sb_pcmbuf_flush(int serial, long elapsed_ms);
void sb_pcmbuf_end_of_stream(void);

/* Output side. Returns frames read, 0 on timeout, -1 when finished/aborted. */
int sb_pcmbuf_read(int16_t *dst, int max_frames, int timeout_ms);
/* Buffer at its fill target (or finished): a reader that wants to behave
 * like real-time playback, e.g. a test writing a file, waits for this. */
bool sb_pcmbuf_ready(void);
/* What the listener is hearing now. */
void sb_pcmbuf_position(int *serial, long *elapsed_ms);

void sb_pcmbuf_abort(void);
#endif
