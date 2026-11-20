/*
 * SlopBox PCM buffer: decoded (post-DSP) audio waiting to be played, and the
 * place where tracks are joined -- gapless, or crossfaded the way Rockbox's
 * apps/pcmbuf.c does it (pcmbuf_start_track(), crossfade_start(),
 * crossfade_find_buftail(), write_to_crossfade()).
 *
 * The decode thread writes; an output thread reads. Positions are stamped as
 * data is written, so the reader knows which track and time it is playing.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "sb_pcmbuf.h"

#define MAX_SECONDS 32          /* 7 s + 15 s fade-out, 7 s fade-in delay, slack */
#define MAX_MARKS   8192

struct mark {
    int64_t pos;                /* absolute frame where this stamp applies */
    int serial;
    long elapsed_ms;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t space_cv = PTHREAD_COND_INITIALIZER;
static pthread_cond_t data_cv = PTHREAD_COND_INITIALIZER;

static int16_t *ring;           /* stereo frames */
static int64_t cap;             /* frames */
static unsigned rate;
static int64_t rpos, wpos;      /* absolute frame counters */
static bool eos, aborted;

static struct mark marks[MAX_MARKS];
static int nmarks;
static int play_serial;
static long play_elapsed;

/* Crossfade settings (Rockbox values: seconds; mode as the "crossfade" setting). */
static int xf_mode, xf_in_delay, xf_in_dur, xf_out_delay, xf_out_dur;
static bool xf_mix, shuffle_on;

/* Incoming track being mixed in (Rockbox's crossfade_infader / crossfade_widx). */
static bool mixing;
static int64_t mixpos;
static int64_t fade_in_len, fade_in_done;

static int16_t clip16(int32_t v)
{
    return v > 32767 ? 32767 : v < -32768 ? -32768 : (int16_t)v;
}

static inline int16_t *frame(int64_t pos)
{
    return &ring[(pos % cap) * 2];
}

void sb_pcmbuf_init(unsigned out_rate)
{
    pthread_mutex_lock(&lock);
    if (!ring) {
        rate = out_rate;
        cap = (int64_t)rate * MAX_SECONDS;
        ring = calloc((size_t)cap * 2, sizeof(int16_t));
    }
    pthread_mutex_unlock(&lock);
}

void sb_pcmbuf_set_crossfade(int mode, int in_delay, int in_dur, int out_delay,
                             int out_dur, bool mixmode)
{
    pthread_mutex_lock(&lock);
    xf_mode = mode;
    xf_in_delay = in_delay;
    xf_in_dur = in_dur;
    xf_out_delay = out_delay;
    xf_out_dur = out_dur;
    xf_mix = mixmode;
    pthread_cond_broadcast(&space_cv); /* the fill target may have changed */
    pthread_mutex_unlock(&lock);
}

void sb_pcmbuf_set_shuffle(bool on)
{
    pthread_mutex_lock(&lock);
    shuffle_on = on;
    pthread_mutex_unlock(&lock);
}

/* How far ahead the decoder may run. Rockbox keeps the buffer nearly full
 * (all but 1 s) when crossfade is on; otherwise a short buffer keeps
 * settings changes quick. */
static int64_t fill_target(void)
{
    if (xf_mode == 0)
        return rate / 4;
    int64_t t = (int64_t)(xf_out_delay + xf_out_dur + 1) * rate;
    if (t < rate)
        t = rate;
    return t > cap - rate ? cap - rate : t;
}

static void clear_marks_from(int64_t pos)
{
    while (nmarks > 0 && marks[nmarks - 1].pos >= pos)
        nmarks--;
}

static void add_mark(int64_t pos, int serial, long elapsed)
{
    clear_marks_from(pos);
    if (nmarks == MAX_MARKS) {
        /* Drop the oldest half; only the last one before rpos matters. */
        memmove(marks, marks + MAX_MARKS / 2, sizeof(marks[0]) * (MAX_MARKS / 2));
        nmarks = MAX_MARKS / 2;
    }
    marks[nmarks++] = (struct mark){ pos, serial, elapsed };
}

/* Called with the lock held after the reader advances. */
static void update_play_position(void)
{
    int i = nmarks - 1;
    while (i > 0 && marks[i].pos > rpos)
        i--;
    if (i < 0 || nmarks == 0)
        return;
    if (marks[i].pos > rpos)
        return;
    play_serial = marks[i].serial;
    play_elapsed = marks[i].elapsed_ms + (long)((rpos - marks[i].pos) * 1000 / rate);
    /* Forget marks that are behind the newest one we've passed. */
    if (i > 0) {
        memmove(marks, marks + i, sizeof(marks[0]) * (nmarks - i));
        nmarks -= i;
    }
}

/* Rockbox crossfade_find_buftail(): where in the unplayed data to act. */
static int64_t find_buftail(bool auto_skip, int64_t rem, int64_t need, int64_t *rem_out)
{
    int64_t index = rpos;
    if (rem > need) {
        int64_t distance;
        if (auto_skip) {
            distance = rem - need;
            rem = need;
        } else {
            /* Manual skips occur immediately, but give 1/5 s to process. */
            distance = rate / 5 < rem ? rate / 5 : rem;
            rem -= distance;
        }
        index += distance;
    }
    if (rem_out)
        *rem_out = rem;
    return index;
}

/* Rockbox crossfade_start(): fade out the buffered tail of the old track and
 * set up where the new one is mixed in. Lock held. */
static void crossfade_start(bool auto_skip, bool mixmode)
{
    int64_t unplayed = wpos - rpos;
    int64_t out_delay = (int64_t)xf_out_delay * rate;
    int64_t out_rem = (int64_t)xf_out_dur * rate;
    int64_t in_delay = (int64_t)xf_in_delay * rate;
    int64_t in_dur = (int64_t)xf_in_dur * rate;

    if (!auto_skip) {
        /* Forego fade-in delay on manual skip. */
        out_delay -= out_delay < in_delay ? out_delay : in_delay;
        in_delay = 0;
    }
    int64_t need = out_delay + out_rem;

    if (!mixmode) {
        int64_t rem;
        int64_t index = find_buftail(auto_skip, unplayed, need, &rem);
        if (rem < need) {
            int64_t shortfall = need - rem;
            if (out_delay >= shortfall) {
                out_delay -= shortfall;
            } else {
                out_rem = rem;
                out_delay = 0;
            }
            need = out_delay + out_rem;
        }
        index += out_delay;
        /* Linear fade-out, then silence to the end of the old data. */
        for (int64_t k = 0; index < wpos; index++, k++) {
            int16_t *f = frame(index);
            if (k < out_rem) {
                int64_t g = ((out_rem - k) << 16) / out_rem;
                f[0] = (int16_t)((f[0] * g) >> 16);
                f[1] = (int16_t)((f[1] * g) >> 16);
            } else {
                f[0] = f[1] = 0;
            }
        }
    }

    unplayed = wpos - rpos + in_delay;
    mixpos = find_buftail(auto_skip, unplayed, need, NULL);
    if (mixpos > wpos)
        mixpos = wpos;
    fade_in_len = in_dur;
    fade_in_done = 0;
    mixing = true;
}

void sb_pcmbuf_begin_track(int serial, bool auto_skip, long elapsed_ms)
{
    pthread_mutex_lock(&lock);
    bool crossfade = false;
    if (xf_mode != 0 && !mixing && wpos - rpos >= rate / 2) {
        switch (xf_mode) {
        case 1: crossfade = auto_skip; break;                 /* auto track change only */
        case 2: crossfade = !auto_skip; break;                /* manual skip only */
        case 3: crossfade = shuffle_on; break;                /* shuffle */
        case 4: crossfade = shuffle_on || !auto_skip; break;  /* shuffle or manual */
        case 5: crossfade = true; break;                      /* always */
        }
    }
    if (crossfade) {
        crossfade_start(auto_skip, auto_skip && xf_mix);
        add_mark(mixpos, serial, elapsed_ms);
    } else if (auto_skip) {
        /* Gapless; an unfinished fade-in carries on (Rockbox CROSSFADE_CONTINUE). */
        add_mark(mixing ? mixpos : wpos, serial, elapsed_ms);
    } else {
        /* Manual change without crossfade: discard what's queued. */
        wpos = rpos;
        mixing = false;
        nmarks = 0;
        add_mark(wpos, serial, elapsed_ms);
        play_serial = serial;
        play_elapsed = elapsed_ms;
    }
    eos = false;
    pthread_mutex_unlock(&lock);
}

bool sb_pcmbuf_write(const int16_t *pcm, int frames, int serial, long elapsed_ms)
{
    pthread_mutex_lock(&lock);
    bool first = true;
    while (frames > 0 && !aborted) {
        if (mixing) {
            /* Mix into data that's already queued (old track's tail). */
            if (first)
                add_mark(mixpos, serial, elapsed_ms);
            first = false;
            while (frames > 0 && mixpos < wpos) {
                if (mixpos < rpos)
                    mixpos = rpos; /* the reader overtook us */
                if (mixpos >= wpos)
                    break;
                int32_t g = fade_in_done >= fade_in_len ? 65536
                          : (int32_t)((fade_in_done << 16) / fade_in_len);
                int16_t *f = frame(mixpos);
                f[0] = clip16(f[0] + ((pcm[0] * g) >> 16));
                f[1] = clip16(f[1] + ((pcm[1] * g) >> 16));
                if (fade_in_done < fade_in_len)
                    fade_in_done++;
                pcm += 2;
                frames--;
                mixpos++;
            }
            if (mixpos >= wpos) {
                /* Past the old data: keep fading in while appending. */
                if (fade_in_done >= fade_in_len)
                    mixing = false;
            } else {
                continue;
            }
        }
        int64_t target = fill_target();
        if (wpos - rpos >= target) {
            pthread_cond_wait(&space_cv, &lock);
            continue;
        }
        int64_t n = target - (wpos - rpos);
        if (n > frames)
            n = frames;
        if (first)
            add_mark(wpos, serial, elapsed_ms);
        first = false;
        for (int64_t i = 0; i < n; i++) {
            int16_t *f = frame(wpos + i);
            if (mixing) {
                int32_t g = fade_in_done >= fade_in_len ? 65536
                          : (int32_t)((fade_in_done << 16) / fade_in_len);
                f[0] = (int16_t)((pcm[0] * g) >> 16);
                f[1] = (int16_t)((pcm[1] * g) >> 16);
                if (++fade_in_done >= fade_in_len)
                    mixing = false;
            } else {
                f[0] = pcm[0];
                f[1] = pcm[1];
            }
            pcm += 2;
        }
        wpos += n;
        if (mixing)
            mixpos = wpos;
        frames -= (int)n;
        pthread_cond_broadcast(&data_cv);
    }
    bool ok = !aborted;
    pthread_mutex_unlock(&lock);
    return ok;
}

void sb_pcmbuf_flush(int serial, long elapsed_ms)
{
    pthread_mutex_lock(&lock);
    wpos = rpos;
    mixing = false;
    nmarks = 0;
    add_mark(wpos, serial, elapsed_ms);
    play_serial = serial;
    play_elapsed = elapsed_ms;
    pthread_cond_broadcast(&space_cv);
    pthread_mutex_unlock(&lock);
}

void sb_pcmbuf_end_of_stream(void)
{
    pthread_mutex_lock(&lock);
    eos = true;
    pthread_cond_broadcast(&data_cv);
    pthread_mutex_unlock(&lock);
}

int sb_pcmbuf_read(int16_t *dst, int max_frames, int timeout_ms)
{
    pthread_mutex_lock(&lock);
    if (wpos - rpos == 0 && !eos && !aborted && timeout_ms > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += timeout_ms / 1000;
        ts.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        pthread_cond_timedwait(&data_cv, &lock, &ts);
    }
    int ret;
    int64_t avail = wpos - rpos;
    if (avail == 0) {
        ret = (eos || aborted) ? -1 : 0;
    } else {
        int64_t n = avail < max_frames ? avail : max_frames;
        for (int64_t i = 0; i < n; i++) {
            int16_t *f = frame(rpos + i);
            dst[i * 2] = f[0];
            dst[i * 2 + 1] = f[1];
        }
        rpos += n;
        update_play_position();
        pthread_cond_broadcast(&space_cv);
        ret = (int)n;
    }
    pthread_mutex_unlock(&lock);
    return ret;
}

void sb_pcmbuf_position(int *serial, long *elapsed_ms)
{
    pthread_mutex_lock(&lock);
    *serial = play_serial;
    *elapsed_ms = play_elapsed;
    pthread_mutex_unlock(&lock);
}

void sb_pcmbuf_abort(void)
{
    pthread_mutex_lock(&lock);
    aborted = true;
    pthread_cond_broadcast(&space_cv);
    pthread_cond_broadcast(&data_cv);
    pthread_mutex_unlock(&lock);
}

void sb_pcmbuf_reset(int serial, long elapsed_ms)
{
    pthread_mutex_lock(&lock);
    rpos = wpos = 0;
    eos = aborted = mixing = false;
    nmarks = 0;
    add_mark(0, serial, elapsed_ms);
    play_serial = serial;
    play_elapsed = elapsed_ms;
    pthread_mutex_unlock(&lock);
}

bool sb_pcmbuf_ready(void)
{
    pthread_mutex_lock(&lock);
    bool r = eos || aborted || wpos - rpos >= fill_target();
    pthread_mutex_unlock(&lock);
    return r;
}
