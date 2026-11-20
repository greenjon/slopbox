/*
 * sb_cli: host-side test driver for the SlopBox engine. Decodes a file
 * through Rockbox codecs + DSP and writes a 16-bit stereo WAV.
 *
 *   sb_cli [options] IN... OUT.wav     (several INs play back to back,
 *                                       as gapless playback does)
 *     -e BAND:TYPE:HZ:Q:GAIN   EQ band 0-9 (TYPE 0 low shelf, 1 peak, 2 high
 *                              shelf; Q and GAIN in tenths). Repeatable.
 *     -p PRECUT                EQ precut, tenths of a dB
 *     -x TYPE                  crossfeed: 0 off, 1 meier, 2 custom
 *     -t TEMPO                 timestretch, 1.0 = normal
 *     -r RATE                  pitch/speed, 1.0 = normal
 *     -v DB                    Rockbox volume, dB (-80..0)
 *     -o ID=VALUE              any sb_setting id (see sb_settings.h)
 *     -s MS                    start MS into the file
 *     -F MS                    start a fade-out (Fade on Stop/Pause) after MS
 *     -M MS                    after MS of output, skip to the next file
 *                              manually (like pressing Next)
 *     -X M:ID:IL:OD:OL:MIX     crossfade: mode (0-5), fade-in delay/duration,
 *                              fade-out delay/duration (s), mix (0/1)
 *
 *   sb_cli -T FILE...                   print tags (sb_read_tags) and exit
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>

#include "sb_engine.h"
#include "sb_settings.h"
#include "sb_pcmbuf.h"
#include <pthread.h>

static FILE *out;
static uint32_t frames_out;
static long start_ms = 0;
static long fade_at_ms = -1;
static long skip_at_ms = -1;
static volatile bool skipped;

static void put_le32(uint8_t *p, uint32_t v)
{ p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static void put_le16(uint8_t *p, uint16_t v)
{ p[0] = v; p[1] = v >> 8; }

static void write_header(uint32_t frames)
{
    uint8_t h[44];
    unsigned rate = sb_output_rate();
    memcpy(h, "RIFF", 4); put_le32(h + 4, 36 + frames * 4);
    memcpy(h + 8, "WAVEfmt ", 8); put_le32(h + 16, 16);
    put_le16(h + 20, 1); put_le16(h + 22, 2);
    put_le32(h + 24, rate); put_le32(h + 28, rate * 4);
    put_le16(h + 32, 4); put_le16(h + 34, 16);
    memcpy(h + 36, "data", 4); put_le32(h + 40, frames * 4);
    fseek(out, 0, SEEK_SET);
    fwrite(h, 1, sizeof(h), out);
}

/* The output side: read the PCM buffer as fast as it fills, into the WAV. */
static void *output_thread(void *arg)
{
    (void)arg;
    int16_t buf[4096 * 2];
    for (;;) {
        /* Like real playback, the decoder runs ahead and keeps the buffer full. */
        while (!sb_pcmbuf_ready())
            usleep(200);
        int n = sb_pcmbuf_read(buf, 1024, 200);
        if (n < 0)
            break;
        if (n == 0)
            continue;
        if (fade_at_ms >= 0 && frames_out >= (uint32_t)(fade_at_ms * (long)sb_output_rate() / 1000)) {
            sb_fade(false, true);
            fade_at_ms = -1;
        }
        if (skip_at_ms >= 0 && frames_out >= (uint32_t)(skip_at_ms * (long)sb_output_rate() / 1000)) {
            skipped = true;
            sb_stop();
            skip_at_ms = -1;
        }
        sb_output_process(buf, n);
        fwrite(buf, 4, n, out);  /* host is little-endian */
        frames_out += n;
    }
    return NULL;
}

int main(int argc, char **argv)
{
    int opt;
    sb_engine_init(CODEC_PATH_FMT);
    if (argc > 2 && !strcmp(argv[1], "-T")) {
        for (int i = 2; i < argc; i++) {
            struct sb_tags t;
            int fd = open(argv[i], O_RDONLY);
            int r = fd < 0 ? -1 : sb_read_tags(fd, argv[i], &t);
            if (fd >= 0) close(fd);
            if (r) { printf("%s: no tags (%d)\n", argv[i], r); continue; }
            printf("%s: [%s] title=%s | artist=%s | album=%s | albumartist=%s | genre=%s | "
                   "composer=%s | year=%s(%d) | track=%s(%d) | disc=%s(%d) | %lu ms %d kb/s %lu Hz | "
                   "rg track=%ld album=%ld | art type=%d flags=%d pos=%ld size=%d\n",
                   argv[i], t.codec, t.title, t.artist, t.album, t.albumartist, t.genre,
                   t.composer, t.year, t.year_num, t.track, t.tracknum, t.disc, t.discnum,
                   t.length_ms, t.bitrate, t.frequency, t.track_gain, t.album_gain,
                   t.art_type, t.art_flags, t.art_pos, t.art_size);
        }
        return 0;
    }
    while ((opt = getopt(argc, argv, "e:p:x:t:r:v:s:o:F:X:M:")) != -1) {
        switch (opt) {
        case 'e': {
            int b, ty, hz, q, g;
            if (sscanf(optarg, "%d:%d:%d:%d:%d", &b, &ty, &hz, &q, &g) != 5) {
                fprintf(stderr, "bad -e %s\n", optarg);
                return 2;
            }
            sb_setting_set_eq_band(b, ty, hz, q, g);
            sb_setting_set(SB_SET_EQ_ENABLED, 1);
            break;
        }
        case 'p': sb_setting_set(SB_SET_EQ_PRECUT, atoi(optarg)); break;
        case 'x': sb_setting_set(SB_SET_CROSSFEED, atoi(optarg)); break;
        case 't': sb_set_tempo(atof(optarg)); break;
        case 'r': sb_set_pitch(atof(optarg)); break;
        case 'v': sb_setting_set(SB_SET_VOLUME, atoi(optarg)); break;
        case 'o': {
            int id, val;
            if (sscanf(optarg, "%d=%d", &id, &val) != 2) return 2;
            sb_setting_set(id, val);
            break;
        }
        case 's': start_ms = atol(optarg); break;
        case 'F': fade_at_ms = atol(optarg); break;
        case 'M': skip_at_ms = atol(optarg); break;
        case 'X': {
            int m, id, il, od, ol, mx;
            if (sscanf(optarg, "%d:%d:%d:%d:%d:%d", &m, &id, &il, &od, &ol, &mx) != 6) return 2;
            sb_setting_set(SB_SET_CROSSFADE_FADE_IN_DELAY, id);
            sb_setting_set(SB_SET_CROSSFADE_FADE_IN_DURATION, il);
            sb_setting_set(SB_SET_CROSSFADE_FADE_OUT_DELAY, od);
            sb_setting_set(SB_SET_CROSSFADE_FADE_OUT_DURATION, ol);
            sb_setting_set(SB_SET_CROSSFADE_FADE_OUT_MIXMODE, mx);
            sb_setting_set(SB_SET_CROSSFADE, m);
            break;
        }
        default: return 2;
        }
    }
    if (argc < optind + 2) {
        fprintf(stderr, "usage: %s [options] IN... OUT.wav\n", argv[0]);
        return 2;
    }
    const char *out_fn = argv[argc - 1];
    out = fopen(out_fn, "wb");
    if (!out) { perror(out_fn); return 1; }
    write_header(0);

    struct sb_track_info info;
    int ret = 0;
    sb_pcmbuf_reset(0, 0);
    pthread_t th;
    pthread_create(&th, NULL, output_thread, NULL);
    bool manual = false;
    for (int i = optind; i < argc - 1 && ret == 0; i++) {
        /* Each file follows the last as an automatic track change, unless -M
         * cut the previous one short (then it's a manual skip). */
        ret = sb_play_file(argv[i], i == optind ? start_ms : 0, i - optind + 1, !manual, &info);
        manual = skipped;
        skipped = false;
        fprintf(stderr, "%s - %s [%s, %lu ms], ret %d\n",
                info.artist, info.title, info.codec, info.length_ms, ret);
    }
    sb_pcmbuf_end_of_stream();
    pthread_join(th, NULL);
    write_header(frames_out);
    fclose(out);
    return ret == 0 ? 0 : 1;
}
