/*
 * JNI bridge between llm.slop.slopbox.Engine and the engine.
 * One player thread decodes files into one AAudio stream. A "next" file can be
 * queued while a track plays; the thread goes straight into it when the
 * current one ends, without closing the stream, which makes playback gapless.
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
#include "aaudio_sink.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SlopBox", __VA_ARGS__)

static pthread_mutex_t player_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t player_thread;
static bool thread_running = false;
static struct sb_aaudio *out = NULL;
static atomic_bool playing = false;
static atomic_bool paused = false;
static atomic_int last_result = 0;
/* Bumped each time a file starts, so Kotlin can follow gapless transitions. */
static atomic_int track_serial = 0;
static atomic_bool stop_requested = false;
static struct sb_track_info info;

struct play_job {
    int fd;
    long start_ms;
    char name[512];
};

/* Queued next file, owned by player_lock. */
static struct play_job *next_job = NULL;

static struct play_job *make_job(JNIEnv *env, jint fd, jstring jname, jlong start_ms)
{
    struct play_job *job = calloc(1, sizeof(*job));
    job->fd = fd;
    job->start_ms = (long)start_ms;
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

static bool sink(void *user, const int16_t *pcm, int frames)
{
    return sb_aaudio_write((struct sb_aaudio *)user, pcm, frames);
}

static void *player_main(void *arg)
{
    struct play_job *job = arg;
    int ret = -10;
    while (job && out) {
        int xruns = sb_aaudio_xruns(out);
        ret = sb_play_fd(job->fd, job->name, job->start_ms, sink, out, &info);
        LOG("finished %s: %d (underruns so far %d, +%d during track)", job->name,
            ret, sb_aaudio_xruns(out), sb_aaudio_xruns(out) - xruns);
        free_job(job);
        job = NULL;
        if (ret != 0 || atomic_load(&stop_requested))
            break;
        pthread_mutex_lock(&player_lock);
        job = next_job;
        next_job = NULL;
        if (job) {
            /* Clear the old track's info before announcing the new one. */
            memset(&info, 0, sizeof(info));
            atomic_fetch_add(&track_serial, 1);
        }
        pthread_mutex_unlock(&player_lock);
    }
    free_job(job);
    sb_aaudio_finish(out);
    atomic_store(&last_result, ret);
    atomic_store(&playing, false);
    return NULL;
}

/* Stop and join the current player thread. Call with player_lock held. */
static void stop_locked(void)
{
    free_job(next_job);
    next_job = NULL;
    if (!thread_running)
        return;
    atomic_store(&stop_requested, true);
    sb_stop();
    if (out)
        sb_aaudio_abort(out);
    /* The thread takes player_lock to fetch the next job; let it. */
    pthread_mutex_unlock(&player_lock);
    pthread_join(player_thread, NULL);
    pthread_mutex_lock(&player_lock);
    /* Something may have been queued while the lock was free. */
    free_job(next_job);
    next_job = NULL;
    thread_running = false;
    sb_aaudio_close(out);
    out = NULL;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeInit(JNIEnv *env, jobject thiz)
{
    /* Codecs ship in the APK as lib/<abi>/libsb_<codec>.so. */
    sb_engine_init("libsb_%s.so");
}

JNIEXPORT jboolean JNICALL
Java_llm_slop_slopbox_Engine_nativePlayFd(JNIEnv *env, jobject thiz,
                                                   jint fd, jstring jname,
                                                   jlong start_ms, jboolean start_paused)
{
    pthread_mutex_lock(&player_lock);
    stop_locked();

    struct play_job *job = make_job(env, fd, jname, start_ms);
    atomic_store(&stop_requested, false);
    atomic_fetch_add(&track_serial, 1);
    memset(&info, 0, sizeof(info));
    out = sb_aaudio_open((int)sb_output_rate());
    atomic_store(&paused, start_paused);
    if (out && start_paused)
        sb_aaudio_set_paused(out, true);
    atomic_store(&playing, true);
    atomic_store(&last_result, 0);
    bool ok = pthread_create(&player_thread, NULL, player_main, job) == 0;
    thread_running = ok;
    if (!ok) {
        free_job(job);
        atomic_store(&playing, false);
    }
    pthread_mutex_unlock(&player_lock);
    return ok;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeStop(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&player_lock);
    stop_locked();
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetPaused(JNIEnv *env, jobject thiz,
                                                      jboolean p)
{
    pthread_mutex_lock(&player_lock);
    atomic_store(&paused, p);
    if (out)
        sb_aaudio_set_paused(out, p);
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

/* [title, artist, album, codec, lengthMs, frequency, bitrate] */
JNIEXPORT jobjectArray JNICALL
Java_llm_slop_slopbox_Engine_nativeTrackInfo(JNIEnv *env, jobject thiz)
{
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
Java_llm_slop_slopbox_Engine_nativeSetEqEnabled(JNIEnv *env, jobject thiz,
                                                        jboolean on)
{
    sb_set_eq_enabled(on);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetEqBand(JNIEnv *env, jobject thiz,
                                                     jint band, jint type,
                                                     jint hz, jint q, jint gain)
{
    sb_set_eq_band(band, type, hz, q, gain);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetEqPrecut(JNIEnv *env, jobject thiz,
                                                       jint precut)
{
    sb_set_eq_precut(precut);
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeSetCrossfeed(JNIEnv *env, jobject thiz,
                                                        jint type)
{
    sb_set_crossfeed(type);
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
Java_llm_slop_slopbox_Engine_nativeSetVolumeDb(JNIEnv *env, jobject thiz, jint db)
{
    sb_set_volume_db(db);
}

/* Queue the file to play right after the current one (gapless). Replaces any
 * earlier queued file. Takes ownership of fd. Returns false if nothing is
 * playing to follow. */
JNIEXPORT jboolean JNICALL
Java_llm_slop_slopbox_Engine_nativeQueueNext(JNIEnv *env, jobject thiz,
                                             jint fd, jstring jname)
{
    pthread_mutex_lock(&player_lock);
    free_job(next_job);
    next_job = NULL;
    bool ok = thread_running && atomic_load(&playing);
    if (ok)
        next_job = make_job(env, fd, jname, 0);
    else
        close(fd);
    pthread_mutex_unlock(&player_lock);
    return ok;
}

JNIEXPORT void JNICALL
Java_llm_slop_slopbox_Engine_nativeClearNext(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&player_lock);
    free_job(next_job);
    next_job = NULL;
    pthread_mutex_unlock(&player_lock);
}

JNIEXPORT jint JNICALL
Java_llm_slop_slopbox_Engine_nativeTrackSerial(JNIEnv *env, jobject thiz)
{
    return atomic_load(&track_serial);
}
