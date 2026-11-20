/*
 * sb_cli: host-side test driver for the SlopBox engine. Decodes a file
 * through Rockbox codecs + DSP and writes a 16-bit stereo WAV.
 *
 *   sb_cli [options] IN OUT.wav
 *     -e BAND:TYPE:HZ:Q:GAIN   EQ band (TYPE 0 low shelf, 1 peak, 2 high
 *                              shelf; Q and GAIN in tenths). Repeatable.
 *     -p PRECUT                EQ precut, tenths of a dB
 *     -x TYPE                  crossfeed: 0 off, 1 meier, 2 custom
 *     -t TEMPO                 timestretch, 1.0 = normal
 *     -r RATE                  pitch/speed, 1.0 = normal
 *     -v DB                    volume attenuation, whole dB (<= 0)
 *     -s MS                    seek to MS before the first sample
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "sb_engine.h"

static FILE *out;
static uint32_t frames_out;
static long seek_ms = -1;

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

static bool sink(void *user, const int16_t *pcm, int frames)
{
    (void)user;
    if (seek_ms >= 0) {           /* first buffer: ask for the seek now */
        sb_seek_ms(seek_ms);
        seek_ms = -1;
    }
    fwrite(pcm, 4, frames, out);  /* host is little-endian */
    frames_out += frames;
    return true;
}

int main(int argc, char **argv)
{
    int opt;
    sb_engine_init(CODEC_PATH_FMT);
    while ((opt = getopt(argc, argv, "e:p:x:t:r:v:s:")) != -1) {
        switch (opt) {
        case 'e': {
            int b, ty, hz, q, g;
            if (sscanf(optarg, "%d:%d:%d:%d:%d", &b, &ty, &hz, &q, &g) != 5) {
                fprintf(stderr, "bad -e %s\n", optarg);
                return 2;
            }
            sb_set_eq_band(b, ty, hz, q, g);
            sb_set_eq_enabled(true);
            break;
        }
        case 'p': sb_set_eq_precut(atoi(optarg)); break;
        case 'x': sb_set_crossfeed(atoi(optarg)); break;
        case 't': sb_set_tempo(atof(optarg)); break;
        case 'r': sb_set_pitch(atof(optarg)); break;
        case 'v': sb_set_volume_db(atoi(optarg)); break;
        case 's': seek_ms = atol(optarg); break;
        default: return 2;
        }
    }
    if (argc != optind + 2) {
        fprintf(stderr, "usage: %s [options] IN OUT.wav\n", argv[0]);
        return 2;
    }
    out = fopen(argv[optind + 1], "wb");
    if (!out) { perror(argv[optind + 1]); return 1; }
    write_header(0);

    struct sb_track_info info;
    int ret = sb_play_file(argv[optind], sink, NULL, &info);
    write_header(frames_out);
    fclose(out);
    fprintf(stderr, "%s - %s [%s, %lu ms] -> %u frames, ret %d\n",
            info.artist, info.title, info.codec, info.length_ms,
            frames_out, ret);
    return ret == 0 ? 0 : 1;
}
