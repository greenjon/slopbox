# SlopBox

An Android music player in the spirit of [Rockbox](https://www.rockbox.org/):
every setting exposed, nothing hidden for your own good.

SlopBox is a Kotlin app built on Rockbox's own codec and DSP library
(`lib/rbcodec`), compiled with the Android NDK. The Rockbox source is a git
submodule in `third_party/rockbox` and is used unmodified; fixes go upstream.

Much of it is written with AI help, hence the name.

## Status

Early spike. Rockbox's codecs and DSP build for Android, and a one-screen app
plays a file through them (EQ, crossfeed, tempo). Not yet run on a device.

## Layout

| Path | What |
|---|---|
| `third_party/rockbox` | Rockbox, unmodified (git submodule) |
| `engine/` | Playback engine, derived from Rockbox's `warble` test player |
| `host/` | Linux test driver (`sb_cli`): decode to WAV with any DSP setting |
| `android-native/build_rockbox.sh` | Builds Rockbox's codecs/libraries with the NDK through Rockbox's own `configure` |
| `app/` | The Android app (Kotlin + Compose, JNI, AAudio output) |

## Building

```sh
git submodule update --init --depth 1
make -C host rockbox && make -C host     # optional: host test driver
android-native/build_rockbox.sh          # Rockbox for arm64-v8a + x86_64
./gradlew assembleDebug                  # needs JDK 17 and NDK 29.0.14206865
```

Codecs ship in the APK as `lib/<abi>/libsb_<codec>.so` and are loaded with
`dlopen`, the same way Rockbox's hosted builds load `.codec` files.

### Known gaps

- `sid` (cRSID) uses GCC nested functions, which the NDK's clang rejects.
- `ape` on x86_64 only: its MMX inline assembly doesn't build with clang
  (phones are arm64, which is fine).

## License

GPL-2.0-or-later, the same as Rockbox. SlopBox is not and will never be
sold.

**To the Rockbox project:** if you ever want to adopt, fork, rename or merge
any of this as Rockbox, you are more than welcome and have my full support.
The app's name lives in one place, so a fork can rename it quickly.

Contributions are accepted under GPL-2.0-or-later with a `Signed-off-by` line
([Developer Certificate of Origin](https://developercertificate.org/)). There
is no contributor license agreement (CLA).
