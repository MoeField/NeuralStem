# NeuralStem

[![Android CI](https://github.com/MoeField/NeuralStem/actions/workflows/android-ci.yml/badge.svg)](https://github.com/MoeField/NeuralStem/actions/workflows/android-ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README.md) | [简体中文](README.zh-Hans.md)

**Split a song into vocals and accompaniment — then mix them separately.**

A fully **offline** Android music player. Import a song and it uses on-device AI to split it into
**vocals / accompaniment** (or 4 stems: vocals / drums / bass / other). You can then adjust each
stem's volume, mute any stem, or solo one.

**Everything runs on your phone** — no network, no uploads, no account.

---

## What it is for

| Scenario | How to use it |
|---|---|
| **Learn a song** | Keep only vocals to sing along, or only the backing track for karaoke |
| **Transcribe melody / chords** | Remove vocals so guitar and bass are much easier to hear |
| **Practice an instrument** | Solo the backing track + slow down to 0.7× + pitch down 2 semitones |
| **Hear the details** | Solo one stem to isolate a part of the arrangement |
| **Make a demo** | Export separated WAV files for editing (4-stem export is available) |

---

## Features

### 🎛 Separation and mixing
- **2 stems**: vocals + accompaniment
- **4 stems**: vocals / drums / bass / other
- **20 selectable models** — from "4-minute song in 30 seconds" to "quality first"; pick by your phone's speed
- Per-stem **volume / mute / solo**
- **Separate while playing**: start listening to the finished part before the whole song is done

### 🎚 Playback controls
- **Speed** — range set in Settings; default `0.71× – 1.40×` (can be widened to `0.25× – 4×`), **pitch preserved**
- **Pitch** ±12 semitones, **speed preserved**
- **Equalizer (EQ)** — multi-band, band count depends on your device
- Waveform seek, colored multi-stem waveform overlay
- Background playback + lock-screen controls

### 💾 Local first
- Separation is **fully offline**, CPU inference, no cloud
- Models download once and stay available
- No account, no telemetry, no ads

---

## Getting started

### 1. Install

Download an APK from [Releases](https://github.com/MoeField/NeuralStem/releases):

| File | Use |
|---|---|
| `app-arm64-v8a-release.apk` | **Most phones** (recommended, smallest) |
| `app-universal-release.apk` | If you are unsure about the ABI |
| `app-x86_64-release.apk` | Emulator |

> Requires Android 6.0 (API 23) or newer.

#### Self-hosted F-Droid repository (optional)

You can add the self-hosted repo to F-Droid and receive updates with Releases:

```text
https://neuralstem.miku.pub/fdroid/repo/
```

F-Droid → Settings → Repositories → add the URL → refresh.  
The repo URL and signing key are stable; no need to re-add it for future releases.

### 2. Download a model (required once)

Separation needs AI model files; the app can download them:

1. Open **Settings** → **Model library**
2. Tap **Download** next to a model
3. Accept the license → wait for the download to finish

**Recommended first model**:

> **Spleeter 2-stem** · 39 MB · about 8× realtime on a mid-range phone (4-minute song ≈ 30 s)

Try UVR models later for better quality (28–64 MB, slower but cleaner).
Not sure which one? Look at the measured "xx×" label in **Settings → Model library** —
**larger is faster**; 1.0× means separation takes about as long as the song.

### 3. Import and play

1. Go to **Player** → **Import audio** → pick a song
2. Drag the waveform to seek; use the right-side sliders for **speed / pitch**

### 4. Separate

1. Go to **Separate** → **Start separation**
2. Pick a tier (only **installed and enabled** tiers are shown):

   **2 stems (vocals + accompaniment)** — grouped by measured phone speed:

   | Tier | Meaning |
   |---|---|
   | **⚡ Fastest** | ≥2× realtime, comfortable to separate while listening |
   | **Borderline** | 0.9–2×, separation time ≈ playback time |
   | **Slow** | 0.4–0.9×, slower than playback |
   | **Very slow** | <0.4×, be patient |

   **4 stems (vocals/drums/bass/other)** — fixed two tiers: **Fast** (Spleeter) / **Quality** (Demucs)

3. Wait for separation → it **automatically switches to multi-track**
4. Go back to **Player**; if it shows "Multi-track", you are hearing the separated result

### 5. Mix

Go to **Mix**:
- Per-stem **volume slider** / **mute** / **solo**
- **EQ** below

---

## Tiers and models

Open **Settings → Separation strategy** to:

- **Disable tiers you do not want** — they disappear from the separation page (e.g. disable "Very slow" on a mid-range phone)
- **Change the model for a tier** — if multiple models fit the same speed tier, tap to switch
- **Disable either 4-stem tier**

> Tier names describe **separation speed**, not quality ranking.
> You can change models within a tier in **Separation strategy**; different models have different speed and quality.

---

## FAQ

**Q: How long does separation take?**
Depends on the model and your phone's CPU. A mid-range phone (Snapdragon 6/7) runs Spleeter at about
**8× realtime** — roughly 30 seconds for a 4-minute song. Flagships are faster; older phones are slower.
On first launch the app runs a **device benchmark** and groups models by measured speed.

**Q: Why does the model library still show "not installed" after downloading?**
It should not — a finished download immediately shows "✓ Installed" and updates the count.
If it happens, reopen the model library page.

**Q: Can I export after separation?**
Yes. After 2-stem or 4-stem separation, **Export to Music/separate/** becomes available and exports
lossless WAV files to `Music/separate/<song>/<model>/` (e.g. `spleeter-2stem`, `spleeter-4stem`, `demucs`).

This requires `coverage.complete`; a partial/cost-gated separation will not enable export, so you cannot
export a half-silent file.

**Q: What does "separate while playing" mean?**
You do not wait for the whole song. After separation starts, the app buffers a first segment, switches
when it is enough to keep listening, and continues separating in the background. If separation is slower
than playback (slow model), the app **pauses** instead of playing silence — the yellow ticks on the
outer ring show separation progress.

**Q: Why are some models marked "experimental"?**
UVR models have no unified quality benchmark; "experimental" warns that their roles may overlap.
You can compare them in **Separation strategy**.

**Q: Does it use the network?**
Only for **model downloads**, and only from the source you choose (HuggingFace or a mirror).
Separation and playback are fully offline.

**Q: I only see one or two tiers on the separation page. Did I miss models?**
Two normal reasons:
1. Your phone is too slow/fast, so some speed clusters have no model;
2. You disabled tiers in **Settings › Separation strategy**.

Open **Settings › Separation strategy** to see each tier's real state: has model, no model, or disabled.

---

## Known limitations

- **Separation quality**: Spleeter is a 2019 model; vocals/accompaniment separate with some bleed.
  Good enough for learning/transcription, not studio-grade.
- **Multichannel input**: audio with more than 2 channels uses only the first 2 channels.
- **Reinstalling the app removes models**: models live in app-private storage and are deleted on uninstall.
- **MP3 seek stutter on some devices**: a known software-decoder issue; the app has automatic recovery.

---

## Technical implementation (for developers)

<details>
<summary>Show stack and build instructions</summary>

### Stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin + Jetpack Compose (Material 3) |
| Audio playback | Media3 / ExoPlayer (session facade) + custom StemMixer (single AudioTrack) |
| Speed/pitch | ExoPlayer's built-in Sonic (`setPlaybackParameters`) |
| Waveform | Custom Compose Canvas (50 envelope points/s, sample-rate independent) |
| Local separation | sherpa-onnx (ONNX Runtime) + Spleeter / UVR-MDX models |
| EQ | Platform `android.media.audiofx.Equalizer` |
| Background | `MediaSessionService` + `dataSync` foreground service |
| API | minSdk 23 · targetSdk 37 |

**Architecture**: playback and multi-track mixing live in `PlaybackService` (foreground service).
MediaSession always uses `singlePlayer` (the main stem in multi-track mode), so notifications,
background playback and lock-screen controls work naturally.

### Build

Requirements:
- Android Studio with SDK (**API 37** required for compilation)
- **NDK `30.0.16248370`** + **CMake `4.1.2`**

```bash
unset ANDROID_PREFS_ROOT          # AGP 9.x can hang if ANDROID_USER_HOME is also set
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

### Release signing

Official signing is described in [`docs/DEVELOP.md`](docs/DEVELOP.md) under "Release".
CI injects Repository Secrets (`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`);
local builds without keys fall back to debug signing.

```bash
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
```

> `-x lintVital*` is needed when `lint-gradle` cannot be downloaded due to network restrictions;
> CI can omit it (the runner can reach dl.google.com).

### Developer docs

- [`docs/DEVELOP.md`](docs/DEVELOP.md): build, architecture, invariants, pitfalls, tests and release.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): playback/separation/persistence invariants and rejected designs.
- [`docs/REMEDIATION_STATUS.md`](docs/REMEDIATION_STATUS.md): audit remediation status, gaps and next steps.
- [`docs/REVIEW_CONVENTIONS.md`](docs/REVIEW_CONVENTIONS.md): code/architecture review conventions and release gate.
- [`docs/STABILITY_TEST_PLAN.md`](docs/STABILITY_TEST_PLAN.md): state stability tests, fault injection and release gate.
- [`docs/STABILITY_REPORT_v0.2.55.md`](docs/STABILITY_REPORT_v0.2.55.md): stability test run record.
- [`docs/STEP_HISTORY.md`](docs/STEP_HISTORY.md): version-by-version change history and verification.
- [`docs/LICENSING.md`](docs/LICENSING.md): MIT / NCM / AVS3 boundary.
- [`docs/AV3A_INVESTIGATION.md`](docs/AV3A_INVESTIGATION.md): AV3A investigation and license boundary.
- [`docs/archive/`](docs/archive/README.md): historical snapshots (audit/acceptance/soak/design), not current state.

### Performance (reference device)

| Item | Result |
|---|---|
| 4-minute separation (Spleeter fp16, foreground) | **30.2 s** (mp3) / 30.7 s (wav), ≈7.9× realtime |
| Peak memory | ~710–740 MB |
| Cache hit | < 100 ms |

Background execution falls into a low-priority cgroup and can take about 2× longer (mitigated by the foreground service).

</details>

---

## AVS3-P3 (Audio Vivid) support status

> **Experimental, build-it-yourself, not shipped in release builds.**
>
> See [`docs/AV3A_INVESTIGATION.md`](docs/AV3A_INVESTIGATION.md) for the FFmpeg/OpenHarmony/Media3
> support and license investigation.

Many tracks downloaded from NetEase Cloud Music use **AVS3-P3** (MP4 codec tag `av3a`), which Android
does not support natively. In a typical private NetEase directory (1001 files):

| Extension | Count | Internal codec |
|---|---|---|
| `.ncm` | 732 | mostly `av3a` |
| `.m4a` | 220 | **219 are `av3a`** |
| `.flac` | 45 | natively supported ✅ |
| `.mp3` | 4 | natively supported ✅ |

In other words: **"not encrypted" does not mean "playable"** — those `.m4a` files are still AVS3 inside.

### Why the decoder is not in this repository

The AVS3-P3 reference implementation ([Dreamello/av3a](https://github.com/Dreamello/av3a))
is **not** MIT/Apache/GPL; it uses the UWA code-sharing policy:

| Clause | Content |
|---|---|
| 2 | Only applies to UWA members; the alliance licenses it to applying members |
| 6 | Except "conditional forwarding", it may not be redistributed; conditional forwarding only allows members to give object code to their own downstream customers |
| 7 | License is non-transferable and non-sublicensable |
| 8 | **No patent license** (AVS3-P3 implementation may involve patents) |

This project is neither a UWA member nor a downstream customer of one, so it cannot redistribute
source or object code. (FFmpeg also has no AVS3 audio decoder; its `avs3` demuxer is for **video** (P2).)

### Build it yourself

```bash
./tools/fetch-avs3.sh                          # fetch sources locally (not committed)
./gradlew app:assembleDebug -Pwith_ncm=true     # build (produces libavs3self.so)
```

After fetching, CMake builds a **separate** `libavs3self.so`; the bridge (`avs3_audio_bridge.c`)
loads it with `dlopen`, so the bridge itself contains no upstream code and can stay MIT.

```bash
./tools/fetch-avs3.sh --clean    # remove
```

> ⚠️ Verify that your use of the upstream code complies with its license. This project takes no responsibility.

### Without the decoder

**Nothing else breaks.** Import, FLAC/MP3 playback, separation, speed/pitch, EQ and mixing all work;
only AVS3 tracks cannot play (`nativeInit` returns an error and the UI explains it).

---

## License

- Project code: **GPL-3.0-or-later** (see [LICENSE](LICENSE))
  - Versions **up to v0.3.2** were released under MIT (see [LICENSE-MIT](LICENSE-MIT)) and remain available under MIT.
- Time/pitch shifting uses [Rubber Band Library](https://breakfastquay.com/rubberband/)
  (GPL-2.0-or-later / commercial dual license); this project uses it under **GPL-3.0-or-later**.
- **NCM container parsing** (`app/src/ncm/`): our own format-interoperability code, also MIT.
  It is based on public community analysis of `.ncm`, intended only for files you already purchased/downloaded;
  it is not compiled by default (requires `-Pwith_ncm=true`).
  Note: bypassing DRM may constitute circumvention of technical measures in some jurisdictions; judge for yourself.
- **AVS3-P3 decoder**: **not in this repository**; licensed under the UWA code-sharing policy, fetch it yourself (see above).
- Third-party libraries and **model weights** have separate licenses — check each one before commercial use; see [NOTICE](NOTICE).
- UVR / MDX-Net: UVR's official README declares its models usable under **MIT** and requires third-party
  attribution to UVR and its developers
  (<https://github.com/Anjok07/ultimatevocalremovergui#license>).
  This applies only to UVR's core UVR-MDX-NET models; aggregate repos such as Blane187 also include
  Demucs/MDX23C/Kim third-party weights and cannot be called MIT as a whole.
  Download mirror: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models>
  Upstream weights: <https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models>
