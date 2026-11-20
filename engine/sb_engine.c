/*
 * SlopBox playback engine.
 *
 * Derived from Rockbox's lib/rbcodec/test/warble.c
 * (Copyright (C) 2011 Sean Bartell), changed for SlopBox: no SDL, no
 * command line, output through a PCM buffer (sb_pcmbuf), file-descriptor input, and DSP
 * settings that can be changed from another thread while a file plays.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#define _DEFAULT_SOURCE
#include <sys/types.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "buffering.h" /* TYPE_PACKET_AUDIO */
#include "kernel.h"
#include "core_alloc.h"
#include "codecs.h"
#include "dsp_core.h"
#include "dsp_misc.h"
#include "eq.h"
#include "crossfeed.h"
#include "metadata.h"
#include "settings.h"
#include "sound.h"
#include "tdspeed.h"
#include "platform.h"

#include "sb_engine.h"
#include "sb_settings.h"
#include "sb_pcmbuf.h"

#ifdef __ANDROID__
#include <android/log.h>
#define SB_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SlopBox", __VA_ARGS__)
#else
#define SB_LOG(...) fprintf(stderr, __VA_ARGS__)
#endif

/***************** Symbols the Rockbox code expects the host to provide ****/

struct user_settings global_settings;

int set_irq_level(int level) { (void)level; return 0; }
void mutex_init(struct mutex *m) { (void)m; }
void mutex_lock(struct mutex *m) { (void)m; }
void mutex_unlock(struct mutex *m) { (void)m; }

void debugf(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
#ifdef __ANDROID__
    __android_log_vprint(ANDROID_LOG_DEBUG, "SlopBox", fmt, ap);
#else
    vfprintf(stderr, fmt, ap);
#endif
    va_end(ap);
}

void panicf(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
#ifdef __ANDROID__
    __android_log_vprint(ANDROID_LOG_FATAL, "SlopBox", fmt, ap);
#else
    vfprintf(stderr, fmt, ap);
#endif
    va_end(ap);
    abort();
}

int find_first_set_bit(uint32_t value)
{
    if (value == 0)
        return 32;
    return __builtin_ctz(value);
}

off_t ffilesize(int fd)
{
    struct stat st;
    if (fstat(fd, &st) != 0)
        return 0;
    return st.st_size;
}

/***************** Engine state *****************/

static char codec_fmt[512] = "%s.codec";
static bool dsp_ready = false;

static int input_fd = -1;
static struct codec_api ci;
static char *input_buffer = NULL;

/* The track being decoded, and whether it was told to stop. */
static int cur_serial;
static bool halted;
/* A track's start position is a seek the codec makes before decoding; it
 * mustn't flush the buffer (which may hold the previous track's tail). */
static bool initial_seek;

static atomic_long codec_action = CODEC_ACTION_NULL;
static atomic_long codec_action_param = 0;
static atomic_long elapsed_ms = 0;

/* Rockbox's firmware/sound.c checks this before touching the "hardware". */
bool audio_is_initialized = false;

/* Tempo and pitch (Rockbox's pitch screen, not a .cfg setting). */
static pthread_mutex_t speed_lock = PTHREAD_MUTEX_INITIALIZER;
static double pending_tempo = 1.0, pending_pitch = 1.0;
static bool speed_dirty = true;

/* Fade on Stop/Pause: like Rockbox's pcmbuf_fade_tick(), a linear ramp over
 * ~1/3 second for the full range. Level and target are 1.31 factors. */
static atomic_uint fade_level = 1u << 31;
static atomic_uint fade_target = 1u << 31;

/* Extra attenuation for ducking, as a 1.31 fixed-point factor. */
#define VOL_FRACBITS 31
#define VOL_FACTOR_UNITY (1u << VOL_FRACBITS)
static atomic_uint duck_factor = VOL_FACTOR_UNITY;

static void apply_pending_settings(void)
{
    sb_settings_apply_pending();
    pthread_mutex_lock(&speed_lock);
    bool dirty = speed_dirty;
    double tempo = pending_tempo, pitch = pending_pitch;
    speed_dirty = false;
    pthread_mutex_unlock(&speed_lock);
    if (dirty) {
        dsp_set_timestretch((int32_t)(tempo * PITCH_SPEED_100));
        dsp_set_pitch((int32_t)(pitch * PITCH_SPEED_100));
    }
}

/* Centibels to a 1.31 factor (<= unity; Rockbox volume never boosts). */
static uint32_t cb_to_factor(int cb)
{
    if (cb >= 0)
        return VOL_FACTOR_UNITY;
    if (cb <= -1000)
        return 0;
    double f = 1.0;
    for (int i = 0; i > cb; i--)
        f *= 0.98855309465693886; /* -0.1 dB */
    return (uint32_t)(f * VOL_FACTOR_UNITY);
}

/***************** Codec API *****************/

static void *ci_codec_get_buffer(size_t *size)
{
    static char buffer[64 * 1024 * 1024] MEM_ALIGN_ATTR;
    *size = sizeof(buffer);
    return buffer;
}

/* Decoded, DSP-processed audio goes into the PCM buffer. */
static void emit_pcm(int16_t *pcm, int frames)
{
    if (halted)
        return;
    if (!sb_pcmbuf_write(pcm, frames, cur_serial, atomic_load(&elapsed_ms))) {
        halted = true;
        atomic_store(&codec_action, CODEC_ACTION_HALT);
    }
}

/* Rockbox apps/beep.c: a square wave on its own mixer channel. */
static atomic_uint beep_step = 0, beep_amplitude = 0;
static atomic_int beep_count = 0;
static uint32_t beep_phase;

void sb_beep(unsigned frequency, unsigned duration_ms, unsigned amplitude)
{
    if (amplitude > INT16_MAX)
        amplitude = INT16_MAX;
    atomic_store(&beep_count, 0);
    beep_phase = 0;
    atomic_store(&beep_step, (uint32_t)(((uint64_t)frequency << 32) / DSP_OUT_DEFAULT_HZ));
    atomic_store(&beep_amplitude, amplitude);
    atomic_store(&beep_count, (int)(DSP_OUT_DEFAULT_HZ / 1000 * duration_ms));
}

static void mix_beep(int16_t *pcm, int frames)
{
    int count = atomic_load(&beep_count);
    if (count <= 0)
        return;
    uint32_t step = atomic_load(&beep_step);
    int32_t amp = (int32_t)atomic_load(&beep_amplitude);
    int n = count < frames ? count : frames;
    for (int i = 0; i < n; i++) {
        int32_t a = beep_phase > UINT32_MAX / 2 ? -amp : amp;
        int32_t l = pcm[i * 2] + a, r = pcm[i * 2 + 1] + a;
        pcm[i * 2] = (int16_t)(l > 32767 ? 32767 : l < -32768 ? -32768 : l);
        pcm[i * 2 + 1] = (int16_t)(r > 32767 ? 32767 : r < -32768 ? -32768 : r);
        beep_phase += step;
    }
    atomic_fetch_sub(&beep_count, n);
}

static void output_gains(int16_t *pcm, int frames);

void sb_output_process(int16_t *pcm, int frames)
{
    output_gains(pcm, frames);
    mix_beep(pcm, frames);
}

static void output_gains(int16_t *pcm, int frames)
{
    /* Volume and balance from Rockbox's sound.c, ducking, and the pause fade:
     * applied as audio leaves the buffer, so they act at once. */
    static int last_l = 1, last_r = 1;
    static uint32_t fl = VOL_FACTOR_UNITY, fr = VOL_FACTOR_UNITY;
    int cl, cr;
    sb_settings_output_gain_cb(&cl, &cr);
    if (cl != last_l || cr != last_r) {
        fl = cb_to_factor(cl);
        fr = cb_to_factor(cr);
        last_l = cl;
        last_r = cr;
    }
    uint32_t duck = atomic_load(&duck_factor);
    uint32_t gl = (uint32_t)(((uint64_t)fl * duck) >> VOL_FRACBITS);
    uint32_t gr = (uint32_t)(((uint64_t)fr * duck) >> VOL_FRACBITS);
    uint32_t level = atomic_load(&fade_level);
    uint32_t target = atomic_load(&fade_target);
    if (level != target) {
        /* Ramp per frame. */
        uint32_t step = VOL_FACTOR_UNITY / (DSP_OUT_DEFAULT_HZ / 3);
        for (int i = 0; i < frames * 2; i += 2) {
            if (level < target)
                level += MIN(step, target - level);
            else if (level > target)
                level -= MIN(step, level - target);
            uint32_t a = (uint32_t)(((uint64_t)gl * level) >> VOL_FRACBITS);
            uint32_t b = (uint32_t)(((uint64_t)gr * level) >> VOL_FRACBITS);
            pcm[i] = (int16_t)(((int64_t)pcm[i] * a) >> VOL_FRACBITS);
            pcm[i + 1] = (int16_t)(((int64_t)pcm[i + 1] * b) >> VOL_FRACBITS);
        }
        atomic_store(&fade_level, level);
        return;
    }
    if (level != VOL_FACTOR_UNITY) {
        gl = (uint32_t)(((uint64_t)gl * level) >> VOL_FRACBITS);
        gr = (uint32_t)(((uint64_t)gr * level) >> VOL_FRACBITS);
    }
    if (gl != VOL_FACTOR_UNITY || gr != VOL_FACTOR_UNITY) {
        for (int i = 0; i < frames * 2; i += 2) {
            pcm[i] = (int16_t)(((int64_t)pcm[i] * gl) >> VOL_FRACBITS);
            pcm[i + 1] = (int16_t)(((int64_t)pcm[i + 1] * gr) >> VOL_FRACBITS);
        }
    }
}

static void ci_pcmbuf_insert(const void *ch1, const void *ch2, int count)
{
    struct dsp_buffer src;
    src.remcount = count;
    src.pin[0] = ch1;
    src.pin[1] = ch2;
    src.proc_mask = 0;
    while (!halted) {
        int out_count = MAX(count, 512);
        int16_t buf[2 * out_count];
        struct dsp_buffer dst;

        dst.remcount = 0;
        dst.p16out = buf;
        dst.bufcount = out_count;

        dsp_process(ci.dsp, &src, &dst, true);

        if (dst.remcount > 0)
            emit_pcm(buf, dst.remcount);
        else if (src.remcount <= 0)
            break;
    }

    apply_pending_settings();
}

static void ci_set_elapsed(unsigned long value)
{
    atomic_store(&elapsed_ms, (long)value);
}

static size_t ci_read_filebuf(void *ptr, size_t size)
{
    free(input_buffer);
    input_buffer = NULL;

    ssize_t actual = read(input_fd, ptr, size);
    if (actual < 0)
        actual = 0;
    ci.curpos += actual;
    return actual;
}

static void *ci_request_buffer(size_t *realsize, size_t reqsize)
{
    free(input_buffer);
    if (!rbcodec_format_is_atomic(ci.id3->codectype))
        reqsize = MIN(reqsize, 32 * 1024);
    input_buffer = malloc(reqsize);
    ssize_t got = read(input_fd, input_buffer, reqsize);
    if (got < 0)
        got = 0;
    *realsize = got;
    lseek(input_fd, -got, SEEK_CUR);
    return input_buffer;
}

static void ci_advance_buffer(size_t amount)
{
    free(input_buffer);
    input_buffer = NULL;

    lseek(input_fd, amount, SEEK_CUR);
    ci.curpos += amount;
    ci.id3->offset = ci.curpos;
}

static bool ci_seek_buffer(size_t newpos)
{
    free(input_buffer);
    input_buffer = NULL;

    off_t actual = lseek(input_fd, newpos, SEEK_SET);
    if (actual >= 0)
        ci.curpos = actual;
    return actual != -1;
}

static void ci_seek_complete(void)
{
    dsp_configure(ci.dsp, DSP_FLUSH, 0);
    if (initial_seek)
        initial_seek = false;
    else /* a user seek: what's queued is from the old position */
        sb_pcmbuf_flush(cur_serial, atomic_load(&codec_action_param));
}

static void ci_set_offset(size_t value)
{
    ci.id3->offset = value;
}

static void ci_configure(int setting, intptr_t value)
{
    dsp_configure(ci.dsp, setting, value);
}

static long ci_get_command(intptr_t *param)
{
    long ret = atomic_exchange(&codec_action, CODEC_ACTION_NULL);
    *param = atomic_load(&codec_action_param);
    return ret;
}

static void ci_strip_filesize(off_t size)
{
    ci.filesize = size;
}

static bool ci_should_loop(void)
{
    return false;
}

static unsigned ci_sleep(unsigned ticks)
{
    (void)ticks;
    return 0;
}

static void ci_debugf(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
#ifdef __ANDROID__
    __android_log_vprint(ANDROID_LOG_DEBUG, "SlopBox", fmt, ap);
#else
    vfprintf(stderr, fmt, ap);
#endif
    va_end(ap);
}

#ifdef ROCKBOX_HAS_LOGF
static void ci_logf(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
#ifdef __ANDROID__
    __android_log_vprint(ANDROID_LOG_DEBUG, "SlopBox", fmt, ap);
#else
    vfprintf(stderr, fmt, ap);
    putc('\n', stderr);
#endif
    va_end(ap);
}
#endif

static void stub_void_void(void) { }

#ifdef HAVE_RECORDING
/* SlopBox only loads decoders, so the encoder half of the codec API is never
 * entered. Fail loudly instead of leaving these NULL. */
static void stub_enc_unsupported(const char *func)
{
    panicf("SlopBox does not support encoding (%s)\n", func);
}
static int ci_enc_pcmbuf_read(void *buf, int count)
{ (void)buf; (void)count; stub_enc_unsupported(__func__); return 0; }
static int ci_enc_pcmbuf_advance(int count)
{ (void)count; stub_enc_unsupported(__func__); return 0; }
static struct enc_chunk_data *ci_enc_encbuf_get_buffer(size_t need)
{ (void)need; stub_enc_unsupported(__func__); return NULL; }
static void ci_enc_encbuf_finish_buffer(void)
{ stub_enc_unsupported(__func__); }
static ssize_t ci_enc_stream_read(void *buf, size_t count)
{ (void)buf; (void)count; stub_enc_unsupported(__func__); return -1; }
static off_t ci_enc_stream_lseek(off_t offset, int whence)
{ (void)offset; (void)whence; stub_enc_unsupported(__func__); return -1; }
static ssize_t ci_enc_stream_write(const void *buf, size_t count)
{ (void)buf; (void)count; stub_enc_unsupported(__func__); return -1; }
static int ci_round_value_to_list32(unsigned long value,
                                    const unsigned long list[],
                                    int count, bool signd)
{ (void)value; (void)list; (void)count; (void)signd;
  stub_enc_unsupported(__func__); return 0; }
#endif /* HAVE_RECORDING */

static struct codec_api ci = {
    0,                   /* filesize */
    0,                   /* curpos */
    NULL,                /* id3 */
    -1,                  /* audio_hid */
    NULL,                /* struct dsp_config *dsp */
    ci_codec_get_buffer,
    ci_pcmbuf_insert,
    ci_set_elapsed,
    ci_read_filebuf,
    ci_request_buffer,
    ci_advance_buffer,
    ci_seek_buffer,
    ci_seek_complete,
    ci_set_offset,
    ci_configure,
    ci_get_command,
    ci_should_loop,
    ci_strip_filesize,
    ci_sleep,
    stub_void_void, /* yield */

#if NUM_CORES > 1
#error "SlopBox expects NUM_CORES == 1"
#endif

    stub_void_void, /* commit_dcache */
    stub_void_void, /* commit_discard_dcache */
    stub_void_void, /* commit_discard_idcache */

    /* strings and memory */
    strcpy,
    strlen,
    strcmp,
    strcat,
    memset,
    memcpy,
    memmove,
    memcmp,
    memchr,
#if defined(DEBUG) || defined(SIMULATOR)
    ci_debugf,
#endif
#ifdef ROCKBOX_HAS_LOGF
    ci_logf,
#endif

    qsort,

#ifdef HAVE_RECORDING
    ci_enc_pcmbuf_read,
    ci_enc_pcmbuf_advance,
    ci_enc_encbuf_get_buffer,
    ci_enc_encbuf_finish_buffer,
    ci_enc_stream_read,
    ci_enc_stream_lseek,
    ci_enc_stream_write,
    ci_round_value_to_list32,
#endif /* HAVE_RECORDING */
};

/***************** Public API *****************/

void sb_engine_init(const char *codec_path_fmt)
{
    snprintf(codec_fmt, sizeof(codec_fmt), "%s", codec_path_fmt);
    if (!dsp_ready) {
        core_allocator_init();
        dsp_init();
        memset(&global_settings, 0, sizeof(global_settings));
        global_settings.timestretch_enabled = true;
        dsp_timestretch_enable(true);
        audio_is_initialized = true;
        sb_pcmbuf_init(DSP_OUT_DEFAULT_HZ);
        sb_settings_init();
        dsp_ready = true;
    }
}

unsigned sb_output_rate(void)
{
    return DSP_OUT_DEFAULT_HZ;
}

static void copy_str(char *dst, size_t n, const char *src)
{
    snprintf(dst, n, "%s", src ? src : "");
}

/* get_metadata() isn't known to be reentrant; one parse at a time. */
static pthread_mutex_t metadata_lock = PTHREAD_MUTEX_INITIALIZER;

static bool read_metadata(struct mp3entry *id3, int fd, const char *name)
{
    pthread_mutex_lock(&metadata_lock);
    memset(id3, 0, sizeof(*id3));
    lseek(fd, 0, SEEK_SET);
    bool ok = get_metadata(id3, fd, name);
    pthread_mutex_unlock(&metadata_lock);
    return ok;
}

int sb_read_tags(int fd, const char *name, struct sb_tags *t)
{
    static struct mp3entry id3; /* large; guarded by tags_lock */
    static pthread_mutex_t tags_lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&tags_lock);
    memset(t, 0, sizeof(*t));
    int ret = -1;
    if (read_metadata(&id3, fd, name)) {
        copy_str(t->title, sizeof(t->title), id3.title);
        copy_str(t->artist, sizeof(t->artist), id3.artist);
        copy_str(t->album, sizeof(t->album), id3.album);
        copy_str(t->albumartist, sizeof(t->albumartist), id3.albumartist);
        copy_str(t->genre, sizeof(t->genre), id3.genre_string);
        copy_str(t->composer, sizeof(t->composer), id3.composer);
        copy_str(t->comment, sizeof(t->comment), id3.comment);
        copy_str(t->grouping, sizeof(t->grouping), id3.grouping);
        copy_str(t->disc, sizeof(t->disc), id3.disc_string);
        copy_str(t->track, sizeof(t->track), id3.track_string);
        copy_str(t->year, sizeof(t->year), id3.year_string);
        copy_str(t->mb_track_id, sizeof(t->mb_track_id), id3.mb_track_id);
        copy_str(t->codec, sizeof(t->codec), audio_formats[id3.codectype].label);
        t->tracknum = id3.tracknum;
        t->discnum = id3.discnum;
        t->year_num = id3.year;
        t->length_ms = id3.length;
        t->frequency = id3.frequency;
        t->bitrate = id3.bitrate;
        t->track_gain = id3.track_gain;
        t->album_gain = id3.album_gain;
        t->track_peak = id3.track_peak;
        t->album_peak = id3.album_peak;
#ifdef HAVE_ALBUMART
        if (id3.has_embedded_albumart) {
            t->art_type = id3.albumart.type & ((1 << AA_FLAGS_SHIFT) - 1);
            t->art_flags = (id3.albumart.type & AA_FLAG_ID3_UNSYNC ? 1 : 0)
                         | (id3.albumart.type & AA_FLAG_VORBIS_BASE64 ? 2 : 0);
            t->art_pos = (long)id3.albumart.pos;
            t->art_size = id3.albumart.size;
        }
#endif
        ret = 0;
    }
    pthread_mutex_unlock(&tags_lock);
    return ret;
}

int sb_play_fd(int fd, const char *name, long start_ms, int serial,
               bool auto_skip, struct sb_track_info *info_out)
{
    if (!dsp_ready)
        return -1;

    struct mp3entry id3;
    if (!read_metadata(&id3, fd, name)) {
        SB_LOG("SlopBox: can't read metadata for %s\n", name);
        return -2;
    }
    lseek(fd, 0, SEEK_SET);

    if (info_out) {
        memset(info_out, 0, sizeof(*info_out));
        copy_str(info_out->title, sizeof(info_out->title), id3.title);
        copy_str(info_out->artist, sizeof(info_out->artist), id3.artist);
        copy_str(info_out->album, sizeof(info_out->album), id3.album);
        copy_str(info_out->codec, sizeof(info_out->codec),
                 audio_formats[id3.codectype].label);
        info_out->length_ms = id3.length;
        info_out->frequency = id3.frequency;
        info_out->bitrate = id3.bitrate;
    }

    input_fd = fd;
    cur_serial = serial;
    halted = false;
    initial_seek = start_ms > 0;
    atomic_store(&codec_action, CODEC_ACTION_NULL);
    atomic_store(&elapsed_ms, 0);
    /* Join to what's queued: gapless, crossfade, or a clean cut. */
    sb_pcmbuf_begin_track(serial, auto_skip, start_ms > 0 ? start_ms : 0);
    if (start_ms > 0) {
        /* The codec sees this on its first get_command, before decoding. */
        atomic_store(&codec_action_param, start_ms);
        atomic_store(&codec_action, CODEC_ACTION_SEEK_TIME);
        atomic_store(&elapsed_ms, start_ms);
    }

    ci.filesize = ffilesize(fd);
    ci.curpos = 0;
    ci.id3 = &id3;
    ci.dsp = dsp_get_config(CODEC_IDX_AUDIO);
    dsp_configure(ci.dsp, DSP_SET_OUT_FREQUENCY, DSP_OUT_DEFAULT_HZ);
    dsp_configure(ci.dsp, DSP_RESET, 0);

    /* Re-apply every setting: DSP_RESET may have cleared some. */
    sb_settings_reapply_all();
    pthread_mutex_lock(&speed_lock);
    speed_dirty = true;
    pthread_mutex_unlock(&speed_lock);
    apply_pending_settings();

    char path[MAX_PATH + 64];
    snprintf(path, sizeof(path), codec_fmt,
             audio_formats[id3.codectype].codec_root_fn);
    void *dlcodec = dlopen(path, RTLD_NOW);
    if (!dlcodec) {
        SB_LOG("SlopBox: dlopen %s failed: %s\n", path, dlerror());
        return -3;
    }
    struct codec_header *c_hdr = dlsym(dlcodec, "__header");
    int ret = 0;
    if (!c_hdr || c_hdr->lc_hdr.magic != CODEC_MAGIC
            || c_hdr->lc_hdr.target_id != TARGET_ID
            || c_hdr->lc_hdr.api_version != CODEC_API_VERSION) {
        SB_LOG("SlopBox: %s is not a compatible codec\n", path);
        ret = -4;
    } else {
        *c_hdr->api = &ci;
        if (c_hdr->entry_point(CODEC_LOAD) != CODEC_OK) {
            ret = -5;
        } else {
            if (c_hdr->run_proc() != CODEC_OK)
                ret = halted ? 0 : -6;
            c_hdr->entry_point(CODEC_UNLOAD);
        }
    }
    dlclose(dlcodec);

    free(input_buffer);
    input_buffer = NULL;
    input_fd = -1;
    ci.id3 = NULL;
    return ret;
}

int sb_play_file(const char *path, long start_ms, int serial, bool auto_skip,
                 struct sb_track_info *info_out)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0)
        return -1;
    int ret = sb_play_fd(fd, path, start_ms, serial, auto_skip, info_out);
    close(fd);
    return ret;
}

void sb_stop(void)
{
    atomic_store(&codec_action, CODEC_ACTION_HALT);
}

void sb_seek_ms(long ms)
{
    atomic_store(&codec_action_param, ms);
    atomic_store(&codec_action, CODEC_ACTION_SEEK_TIME);
}

long sb_elapsed_ms(void)
{
    int serial;
    long ms;
    sb_pcmbuf_position(&serial, &ms);
    return ms;
}

int sb_playing_serial(void)
{
    int serial;
    long ms;
    sb_pcmbuf_position(&serial, &ms);
    return serial;
}

void sb_set_tempo(double tempo)
{
    pthread_mutex_lock(&speed_lock);
    pending_tempo = tempo;
    speed_dirty = true;
    pthread_mutex_unlock(&speed_lock);
}

void sb_set_pitch(double rate)
{
    pthread_mutex_lock(&speed_lock);
    pending_pitch = rate;
    speed_dirty = true;
    pthread_mutex_unlock(&speed_lock);
}

void sb_set_duck_db(int db)
{
    atomic_store(&duck_factor, cb_to_factor(db * 10));
}

void sb_fade(bool in, bool ramp)
{
    uint32_t t = in ? VOL_FACTOR_UNITY : 0;
    atomic_store(&fade_target, t);
    if (!ramp)
        atomic_store(&fade_level, t);
}

bool sb_fade_done(void)
{
    return atomic_load(&fade_level) == atomic_load(&fade_target);
}

bool sb_fade_silent(void)
{
    return atomic_load(&fade_level) == 0;
}
