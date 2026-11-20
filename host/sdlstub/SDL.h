/* Minimal SDL stand-in so Rockbox's warble test player builds without SDL.
 * Only file output (-o) works; live playback reports an error.
 * SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef SLOPBOX_SDL_STUB_H
#define SLOPBOX_SDL_STUB_H
#include <stdint.h>
#include <stddef.h>
typedef uint8_t Uint8;
typedef uint16_t Uint16;
typedef struct SDL_sem { int unused; } SDL_sem;
typedef struct SDL_AudioSpec {
    int freq; Uint16 format; Uint8 channels; Uint8 silence;
    Uint16 samples; Uint16 padding; uint32_t size;
    void (*callback)(void *userdata, Uint8 *stream, int len);
    void *userdata;
} SDL_AudioSpec;
#define SDL_INIT_AUDIO 0x10
#define AUDIO_S16SYS 0x8010
static inline int SDL_Init(uint32_t f) { (void)f; return -1; }
static inline const char *SDL_GetError(void) { return "SlopBox host build has no SDL; use -o out.wav"; }
static inline SDL_sem *SDL_CreateSemaphore(uint32_t v) { (void)v; static SDL_sem s; return &s; }
static inline int SDL_SemPost(SDL_sem *s) { (void)s; return 0; }
static inline int SDL_SemWait(SDL_sem *s) { (void)s; return 0; }
static inline int SDL_OpenAudio(SDL_AudioSpec *a, SDL_AudioSpec *b) { (void)a; (void)b; return -1; }
static inline void SDL_PauseAudio(int p) { (void)p; }
static inline void SDL_Quit(void) {}
#endif
