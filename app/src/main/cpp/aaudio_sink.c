/*
 * AAudio output for the SlopBox engine: a blocking stereo int16 stream.
 * Blocking writes pace the decoder, so no ring buffer is needed.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>

#include "aaudio_sink.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SlopBox", __VA_ARGS__)

struct sb_aaudio {
    AAudioStream *stream;
    int rate;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    bool paused;
    bool stopping;
};

static AAudioStream *open_stream(int rate)
{
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
        return NULL;
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStream *s = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOG("AAudio open failed: %s", AAudio_convertResultToText(r));
        return NULL;
    }
    LOG("AAudio stream: %d Hz, buffer %d frames", AAudioStream_getSampleRate(s),
        AAudioStream_getBufferCapacityInFrames(s));
    AAudioStream_requestStart(s);
    return s;
}

struct sb_aaudio *sb_aaudio_open(int rate)
{
    struct sb_aaudio *a = calloc(1, sizeof(*a));
    a->rate = rate;
    pthread_mutex_init(&a->lock, NULL);
    pthread_cond_init(&a->cond, NULL);
    a->stream = open_stream(rate);
    if (!a->stream) {
        free(a);
        return NULL;
    }
    return a;
}

void sb_aaudio_close(struct sb_aaudio *a)
{
    if (!a)
        return;
    if (a->stream) {
        AAudioStream_requestStop(a->stream);
        AAudioStream_close(a->stream);
    }
    pthread_mutex_destroy(&a->lock);
    pthread_cond_destroy(&a->cond);
    free(a);
}

void sb_aaudio_finish(struct sb_aaudio *a)
{
    /* For output, requestStop plays out what was already written. */
    if (a && a->stream)
        AAudioStream_requestStop(a->stream);
}

void sb_aaudio_set_paused(struct sb_aaudio *a, bool paused)
{
    pthread_mutex_lock(&a->lock);
    a->paused = paused;
    pthread_cond_broadcast(&a->cond);
    pthread_mutex_unlock(&a->lock);
}

void sb_aaudio_abort(struct sb_aaudio *a)
{
    pthread_mutex_lock(&a->lock);
    a->stopping = true;
    pthread_cond_broadcast(&a->cond);
    pthread_mutex_unlock(&a->lock);
}

bool sb_aaudio_write(struct sb_aaudio *a, const int16_t *pcm, int frames)
{
    /* Pause by blocking the decoder here. */
    pthread_mutex_lock(&a->lock);
    if (a->paused && !a->stopping) {
        AAudioStream_requestPause(a->stream);
        while (a->paused && !a->stopping)
            pthread_cond_wait(&a->cond, &a->lock);
        if (!a->stopping)
            AAudioStream_requestStart(a->stream);
    }
    bool stopping = a->stopping;
    pthread_mutex_unlock(&a->lock);
    if (stopping)
        return false;

    while (frames > 0) {
        aaudio_result_t n = AAudioStream_write(a->stream, pcm, frames,
                                               500 * 1000 * 1000LL);
        if (n == AAUDIO_ERROR_DISCONNECTED) {
            /* Output device changed (headphones unplugged, Bluetooth). */
            LOG("AAudio disconnected; reopening");
            AAudioStream_close(a->stream);
            a->stream = open_stream(a->rate);
            if (!a->stream)
                return false;
            continue;
        }
        if (n < 0) {
            LOG("AAudio write failed: %s", AAudio_convertResultToText(n));
            return false;
        }
        if (a->stopping)
            return false;
        pcm += n * 2;
        frames -= n;
    }
    return true;
}

int sb_aaudio_xruns(struct sb_aaudio *a)
{
    return (a && a->stream) ? AAudioStream_getXRunCount(a->stream) : 0;
}
