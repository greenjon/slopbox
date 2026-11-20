/*
 * JNI bridge between llm.slop.slopbox.Engine and the engine.
 * One player thread decodes a file into AAudio.
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
static struct sb_track_info info;

struct play_job {
    int fd;
    char name[512];
};

static bool sink(void *user, const int16_t *pcm, int frames)
{
    return sb_aaudio_write((struct sb_aaudio *)user, pcm, frames);
}

static void *player_main(void *arg)
{
    struct play_job *job = arg;
    int ret = -10;
    if (out)
        ret = sb_play_fd(job->fd, job->name, sink, out, &info);
    LOG("finished %s: %d", job->name, ret);
    close(job->fd);
    free(job);
    atomic_store(&last_result, ret);
    atomic_store(&playing, false);
    return NULL;
}

/* Stop and join the current player thread. Call with player_lock held. */
static void stop_locked(void)
{
    if (!thread_running)
        return;
    sb_stop();
    if (out)
        sb_aaudio_abort(out);
    pthread_join(player_thread, NULL);
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
                                                   jint fd, jstring jname)
{
    pthread_mutex_lock(&player_lock);
    stop_locked();

    struct play_job *job = calloc(1, sizeof(*job));
    job->fd = fd;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    strncpy(job->name, name, sizeof(job->name) - 1);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    memset(&info, 0, sizeof(info));
    out = sb_aaudio_open((int)sb_output_rate());
    atomic_store(&paused, false);
    atomic_store(&playing, true);
    atomic_store(&last_result, 0);
    bool ok = pthread_create(&player_thread, NULL, player_main, job) == 0;
    thread_running = ok;
    if (!ok) {
        close(fd);
        free(job);
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
