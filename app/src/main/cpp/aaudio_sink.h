/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef SB_AAUDIO_SINK_H
#define SB_AAUDIO_SINK_H
#include <stdbool.h>
#include <stdint.h>

struct sb_aaudio;
struct sb_aaudio *sb_aaudio_open(int rate);
void sb_aaudio_close(struct sb_aaudio *a);
/* Blocks until written; returns false when aborted or the device fails. */
bool sb_aaudio_write(struct sb_aaudio *a, const int16_t *pcm, int frames);
/* Track ended: let buffered audio play out, then stop the stream. */
void sb_aaudio_finish(struct sb_aaudio *a);
void sb_aaudio_set_paused(struct sb_aaudio *a, bool paused);
/* Make a blocked or future write return false (used to stop playback). */
void sb_aaudio_abort(struct sb_aaudio *a);
/* Underruns since the stream opened (gaps heard by the listener). */
int sb_aaudio_xruns(struct sb_aaudio *a);
#endif
