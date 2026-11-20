/*
 * JNI bridge between llm.slop.slopbox.Engine and the engine.
 *
 * A decode thread runs Rockbox codecs into the PCM buffer (sb_pcmbuf), file
 * after file: a queued next file follows as an automatic track change
 * (gapless or crossfade), a skip interrupts as a manual one. An output thread
 * plays the buffer through one AAudio stream. Every file gets a serial so
 * Kotlin can tell what's being decoded from what's being heard.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "sb_engine.h"
#include "sb_settings.h"
#include "sb_pcmbuf.h"
#include "aaudio_sink.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SlopBox", __VA_ARGS__)

static pthread_mutex_t player_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t decode_thread, output_thread;
static bool threads_running = false;
static struct sb_aaudio *out = NULL;
static atomic_bool playing = false;     /* output still running */
static atomic_bool decoding = false;    /* decode thread still taking files */
static atomic_bool paused = false;
static atomic_int last_result = 0;
static atomic_int decode_serial = 0;
static int next_serial = 0;
static atomic_bool stop_requested = false;
static bool skip_requested = false;     /* player_lock */

struct play_job {
    int fd;
    long start_ms;
    int serial;
    bool auto_skip;
    char name[512];
};

/* Queued next file (or a pending skip), owned by player_lock. */
static struct play_job *next_job = NULL;

/* Track info by serial, so the one being heard can be reported. */
#define INFO_SLOTS 4
static struct { int serial; struct sb_track_info info; } infos[INFO_SLOTS];
static pthread_mutex_t info_lock = PTHREAD_MUTEX_INITIALIZER;

static struct play_job *make_job(JNIEnv *env, jint fd, jstring jname, jlong start_ms,
                                 bool auto_skip)
{
    struct play_job *job = calloc(1, sizeof(*job));
    job->fd = fd;
    job->start_ms = (long)start_ms;
    job->serial = ++next_serial;
    job->auto_skip = auto_skip;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    strncpy(job->name, name, sizeof(job->name) - 1);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return job;
}

static void free_job(struct play_job *job)
{
    if (!job)
        return;
    close(job->fd);
    free(job);
}

static void *decode_main(void *arg)
{
    struct play_job *job = arg;
    int ret = -10;
    while (job) {
        atomic_store(&decode_serial, job->serial);
        /* sb_play_fd fills the info as soon as it has read the tags. */
        int slot = job->serial % INFO_SLOTS;
        pthread_mutex_lock(&info_lock);
        infos[slot].serial = job->serial;
        memset(&infos[slot].info, 0, sizeof(infos[slot].info));
        pthread_mutex_unlock(&info_lock);
        ret = sb_play_fd(job->fd, job->name, job->start_ms, job->serial,
                         job->auto_skip, &infos[slot].info);
        LOG("decoded %s (serial %d): %d", job->name, job->serial, ret);
        free_job(job);
        job = NULL;
        pthread_mutex_lock(&player_lock);
        if (skip_requested) {
            /* sb_stop() ended this track early for a skip. */
            skip_requested = false;
            ret = 0;
        }
        if ((ret == 0 || next_job) && !atomic_load(&stop_requested)) {
            job = next_job;
            next_job = NULL;
        }
        if (!job)
            atomic_store(&decoding, false);
        pthread_mutex_unlock(&player_lock);
    }
    if (ret != 0)
        atomic_store(&last_result, ret);
    sb_pcmbuf_end_of_stream();
    return NULL;
}

/* Fade on Stop/Pause: pause the stream once the fade-out has gone silent. */
static atomic_bool pause_after_fade = false;

static void *output_main(void *arg)
{
    (void)arg;
    int16_t buf[1024 * 2];
    int xruns = 0;
    for (;;) {
        int n = sb_pcmbuf_read(buf, 1024, 100);
        if (n < 0)
            break;
        if (n == 0)
            continue;
        sb_output_process(buf, n);
        if (atomic_load(&pause_after_fade) && sb_fade_silent()) {
            atomic_store(&pause_after_fade, false);
            sb_aaudio_set_paused(out, true);
        }
        if (!sb_aaudio_write(out, buf, n))
            break;
        int x = sb_aaudio_xruns(out);
        if (x != xruns) {
            LOG("output underruns: %d", x);
            xruns = x;
        }
    }
    sb_aaudio_finish(out);
    atomic_store(&playing, false);
    return NULL;
}

/* Stop and join both threads. Call with player_lock held. */
static void stop_locked(void)
{
    free_job(next_job);
    next_job = NULL;
    skip_requested = false;
    if (!threads_running)
        return;
    atomic_store(&stop_requested, true);
    sb_stop();
    sb_pcmbuf_abort();
    if (out)
        sb_aaudio_abort(out);
    /* The decode thread takes player_lock between files; let it. */
    pthread_mutex_unlock(&player_lock);
    pthread_join(decode_thread, NULL);
    pthread_join(output_thread, NULL);
    pthread_mutex_lock(&player_lock);
    free_job(next_job);
    next_job = NULL;
    threads_running = false;
    atomic_store(&decoding, false);
    atomic_store(&playing, false);
    sb_aaudio_close(out);
    out = NULL;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeInit(JNIEnv *env, jobject thiz)
{
    /* Codecs ship in the APK as lib/<abi>/libsb_<codec>.so. */
    sb_engine_init("libsb_%s.so");
}

/* Start fresh with this file. Returns its serial, or -1. */
JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativePlayFd(JNIEnv *env, jobject thiz,
                                          jint fd, jstring jname,
                                          jlong start_ms, jboolean start_paused)
{
    pthread_mutex_lock(&player_lock);
    stop_locked();

    struct play_job *job = make_job(env, fd, jname, start_ms, true);
    int serial = job->serial;
    atomic_store(&stop_requested, false);
    sb_pcmbuf_reset(serial, (long)start_ms);
    out = sb_aaudio_open((int)sb_output_rate());
    if (!out) {
        free_job(job);
        pthread_mutex_unlock(&player_lock);
        return -1;
    }
    atomic_store(&paused, start_paused);
    atomic_store(&pause_after_fade, false);
    /* Starting paused leaves the level down so resuming can fade in. */
    sb_fade(!start_paused, false);
    if (start_paused)
        sb_aaudio_set_paused(out, true);
    atomic_store(&playing, true);
    atomic_store(&decoding, true);
    atomic_store(&last_result, 0);
    atomic_store(&decode_serial, serial);
    bool ok = pthread_create(&output_thread, NULL, output_main, NULL) == 0;
    if (ok && pthread_create(&decode_thread, NULL, decode_main, job) != 0) {
        sb_pcmbuf_abort();
        pthread_join(output_thread, NULL);
        ok = false;
    }
    threads_running = ok;
    if (!ok) {
        free_job(job);
        atomic_store(&playing, false);
        atomic_store(&decoding, false);
        sb_aaudio_close(out);
        out = NULL;
    }
    pthread_mutex_unlock(&player_lock);
    return ok ? serial : -1;
}

/* Manual track change into this file, keeping the output running so the
 * buffer can crossfade (or cut) as the crossfade setting says. Returns the
 * serial, or -1 if nothing is decoding (then start fresh with play). */
JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativeSkipTo(JNIEnv *env, jobject thiz,
                                          jint fd, jstring jname, jlong start_ms)
{
    pthread_mutex_lock(&player_lock);
    if (!threads_running || !atomic_load(&decoding) || !atomic_load(&playing)) {
        pthread_mutex_unlock(&player_lock);
        close(fd);
        return -1;
    }
    free_job(next_job);
    next_job = make_job(env, fd, jname, start_ms, false);
    int serial = next_job->serial;
    skip_requested = true;
    sb_stop();
    pthread_mutex_unlock(&player_lock);
    return serial;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeStop(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&player_lock);
    stop_locked();
    pthread_mutex_unlock(&player_lock);
}

/* Pause or resume; with fade, like Rockbox's audio_on_pause() + pcmbuf_fade(). */
JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetPaused(JNIEnv *env, jobject thiz,
                                             jboolean p, jboolean fade)
{
    pthread_mutex_lock(&player_lock);
    atomic_store(&paused, p);
    if (p) {
        sb_fade(false, fade);
        if (fade && out) {
            atomic_store(&pause_after_fade, true);
        } else if (out) {
            sb_aaudio_set_paused(out, true);
        }
    } else {
        atomic_store(&pause_after_fade, false);
        sb_fade(true, fade);
        if (out)
            sb_aaudio_set_paused(out, false);
    }
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT jboolean JNICALL
Java_llm_slop_slopbox_Engine_nativeIsPlaying(JNIEnv *env, jobject thiz)
{
    return atomic_load(&playing);
}

JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativeLastResult(JNIEnv *env, jobject thiz)
{
    return atomic_load(&last_result);
}

JNIEXPORT jlong JNICALL
Java_llm_slop_slopbox_Engine_nativeElapsedMs(JNIEnv *env, jobject thiz)
{
    return sb_elapsed_ms();
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSeekMs(JNIEnv *env, jobject thiz,
                                                  jlong ms)
{
    sb_seek_ms((long)ms);
}

/* [title, artist, album, codec, lengthMs, frequency, bitrate] of what's heard */
JNIEXPORT jobjectArray JNICALL
Java_llm_slop_slopbox_Engine_nativeTrackInfo(JNIEnv *env, jobject thiz)
{
    struct sb_track_info info;
    memset(&info, 0, sizeof(info));
    int serial = sb_playing_serial();
    pthread_mutex_lock(&info_lock);
    if (infos[serial % INFO_SLOTS].serial == serial)
        info = infos[serial % INFO_SLOTS].info;
    pthread_mutex_unlock(&info_lock);
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jobjectArray a = (*env)->NewObjectArray(env, 7, str, NULL);
    char num[32];
    (*env)->SetObjectArrayElement(env, a, 0, (*env)->NewStringUTF(env, info.title));
    (*env)->SetObjectArrayElement(env, a, 1, (*env)->NewStringUTF(env, info.artist));
    (*env)->SetObjectArrayElement(env, a, 2, (*env)->NewStringUTF(env, info.album));
    (*env)->SetObjectArrayElement(env, a, 3, (*env)->NewStringUTF(env, info.codec));
    snprintf(num, sizeof(num), "%lu", info.length_ms);
    (*env)->SetObjectArrayElement(env, a, 4, (*env)->NewStringUTF(env, num));
    snprintf(num, sizeof(num), "%lu", info.frequency);
    (*env)->SetObjectArrayElement(env, a, 5, (*env)->NewStringUTF(env, num));
    snprintf(num, sizeof(num), "%d", info.bitrate);
    (*env)->SetObjectArrayElement(env, a, 6, (*env)->NewStringUTF(env, num));
    return a;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetSetting(JNIEnv *env, jobject thiz,
                                              jint id, jint value)
{
    sb_setting_set(id, value);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetEqBand(JNIEnv *env, jobject thiz,
                                             jint band, jint type, jint hz,
                                             jint q, jint gain)
{
    sb_setting_set_eq_band(band, type, hz, q, gain);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetShuffle(JNIEnv *env, jobject thiz,
                                              jboolean on)
{
    sb_setting_set_shuffle(on);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetTempo(JNIEnv *env, jobject thiz,
                                                    jdouble t)
{
    sb_set_tempo(t);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetPitch(JNIEnv *env, jobject thiz,
                                                    jdouble r)
{
    sb_set_pitch(r);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetDuckDb(JNIEnv *env, jobject thiz, jint db)
{
    sb_set_duck_db(db);
}

/* Queue the file to follow the one being decoded (gapless or crossfade).
 * Replaces an earlier queued file. Takes ownership of fd. Returns its serial,
 * or -1 if nothing is decoding to follow. */
JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativeQueueNext(JNIEnv *env, jobject thiz,
                                             jint fd, jstring jname, jlong start_ms)
{
    pthread_mutex_lock(&player_lock);
    if (!threads_running || !atomic_load(&decoding) || skip_requested) {
        pthread_mutex_unlock(&player_lock);
        close(fd);
        return -1;
    }
    free_job(next_job);
    next_job = make_job(env, fd, jname, start_ms, true);
    int serial = next_job->serial;
    pthread_mutex_unlock(&player_lock);
    return serial;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeClearNext(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&player_lock);
    if (!skip_requested) {
        free_job(next_job);
        next_job = NULL;
    }
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativeDecodeSerial(JNIEnv *env, jobject thiz)
{
    return atomic_load(&decode_serial);
}

JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativeTrackSerial(JNIEnv *env, jobject thiz)
{
    return sb_playing_serial();
}

/* Tags via Rockbox's parser, without playing. Doesn't take ownership of fd.
 * Returns null if the file can't be parsed; otherwise strings in the order of
 * Engine.RockboxTags. */
JNIEXPORT jobjectArray JNICALL
Java_llm_slop_slopbox_Engine_nativeReadTags(JNIEnv *env, jobject thiz,
                                            jint fd, jstring jname)
{
    struct sb_tags t;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    int r = sb_read_tags(fd, name, &t);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (r != 0)
        return NULL;
    char num[14][32];
    snprintf(num[0], 32, "%d", t.tracknum);
    snprintf(num[1], 32, "%d", t.discnum);
    snprintf(num[2], 32, "%d", t.year_num);
    snprintf(num[3], 32, "%lu", t.length_ms);
    snprintf(num[4], 32, "%lu", t.frequency);
    snprintf(num[5], 32, "%d", t.bitrate);
    snprintf(num[6], 32, "%ld", t.track_gain);
    snprintf(num[7], 32, "%ld", t.album_gain);
    snprintf(num[8], 32, "%ld", t.track_peak);
    snprintf(num[9], 32, "%ld", t.album_peak);
    snprintf(num[10], 32, "%d", t.art_type);
    snprintf(num[11], 32, "%d", t.art_flags);
    snprintf(num[12], 32, "%ld", t.art_pos);
    snprintf(num[13], 32, "%d", t.art_size);
    const char *strs[] = {
        t.title, t.artist, t.album, t.albumartist, t.genre, t.composer, t.comment,
        t.grouping, t.disc, t.track, t.year, t.mb_track_id, t.codec,
        num[0], num[1], num[2], num[3], num[4], num[5], num[6], num[7], num[8],
        num[9], num[10], num[11], num[12], num[13],
    };
    int n = sizeof(strs) / sizeof(strs[0]);
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jobjectArray a = (*env)->NewObjectArray(env, n, str, NULL);
    for (int i = 0; i < n; i++) {
        jstring js = (*env)->NewStringUTF(env, strs[i]);
        (*env)->SetObjectArrayElement(env, a, i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    return a;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeBeep(JNIEnv *env, jobject thiz,
                                        jint freq, jint ms, jint amplitude)
{
    sb_beep((unsigned)freq, (unsigned)ms, (unsigned)amplitude);
}

/* Embedded cuesheet: ["text", pos, size, encoding], ["chapters", ms, title, ...],
 * or null when there's none. Doesn't take ownership of fd. */
JNIEXPORT jobjectArray JNICALL
Java_llm_slop_slopbox_Engine_nativeReadEmbeddedCue(JNIEnv *env, jobject thiz,
                                                   jint fd, jstring jname)
{
    static struct sb_embedded_cue cue;
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&lock);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    int r = sb_read_embedded_cue(fd, name, &cue);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    jobjectArray a = NULL;
    if (r == 0 && cue.kind != 0) {
        jclass str = (*env)->FindClass(env, "java/lang/String");
        char num[32];
        if (cue.kind == 1) {
            a = (*env)->NewObjectArray(env, 4, str, NULL);
            (*env)->SetObjectArrayElement(env, a, 0, (*env)->NewStringUTF(env, "text"));
            snprintf(num, sizeof(num), "%ld", cue.pos);
            (*env)->SetObjectArrayElement(env, a, 1, (*env)->NewStringUTF(env, num));
            snprintf(num, sizeof(num), "%d", cue.size);
            (*env)->SetObjectArrayElement(env, a, 2, (*env)->NewStringUTF(env, num));
            snprintf(num, sizeof(num), "%d", cue.encoding);
            (*env)->SetObjectArrayElement(env, a, 3, (*env)->NewStringUTF(env, num));
        } else {
            a = (*env)->NewObjectArray(env, 1 + cue.count * 2, str, NULL);
            (*env)->SetObjectArrayElement(env, a, 0, (*env)->NewStringUTF(env, "chapters"));
            for (int i = 0; i < cue.count; i++) {
                snprintf(num, sizeof(num), "%lu", cue.chapters[i].ms);
                jstring n = (*env)->NewStringUTF(env, num);
                jstring t = (*env)->NewStringUTF(env, cue.chapters[i].title);
                (*env)->SetObjectArrayElement(env, a, 1 + i * 2, n);
                (*env)->SetObjectArrayElement(env, a, 2 + i * 2, t);
                (*env)->DeleteLocalRef(env, n);
                (*env)->DeleteLocalRef(env, t);
            }
        }
    }
    pthread_mutex_unlock(&lock);
    return a;
}
