# VoxrtSilero for Android

Silero v5 voice-activity detection, running on the **VoxRT** custom on-device inference runtime.

- Current version: `v0.1.2`
- Minimum Android: API 26 (Android 8.0)
- ABIs shipped: `arm64-v8a` (NEON-accelerated), `x86_64` (scalar, emulator only)
- License: Apache-2.0 (Kotlin wrapper) · proprietary (compiled runtime, redistribution allowed via this artifact)

---

## What is VoxRT?

VoxRT is a from-scratch inference runtime for on-device speech models. No ONNX Runtime, no PyTorch Mobile, no LiteRT — a custom Rust core sized and tuned for streaming voice workloads on phone-class hardware.

`VoxrtSilero` is the free, open-source showcase of that runtime: a Kotlin library that runs the Silero v5 VAD with state-of-the-art per-frame latency. The runtime is the product; Silero is the demo subject.

Siblings on the same runtime:

- [`VoxrtAsr`](https://github.com/VoxRT/voxrt-asr-android) — streaming speech recognition (FastConformer 32M)
- [`VoxrtWakeWord`](https://github.com/VoxRT/voxrt-wake-word-android) — always-on wake-phrase detection (~48 K params)

Commercial custom-phrase wake-word / keyword-spotting / domain-specific ASR models built on the same runtime live at [voxrt.com](https://voxrt.com).

## Performance

Measured at ship time, `arm64-v8a` release builds, post-warmup, RTF = wall-time-per-frame ÷ frame-duration (lower is better):

| Device                                       | RTF       | per-frame latency      |
| -------------------------------------------- | --------- | ---------------------- |
| Xiaomi Redmi 9C (Snapdragon 662, Cortex-A73) | **3.05%** | ~1 ms / 32 ms frame    |

What this means: at 3.05% RTF you can run dozens of parallel VAD streams on a single core before saturating it, leaving the device idle to handle the rest of the audio pipeline.

## How it compares

VAD became a commodity by 2026 — the question is **who you can actually ship in a paid mobile app** with measured numbers and a clean license:

| | **VoxrtSilero** | Picovoice Cobra | WebRTC VAD | TEN VAD |
|---|---|---|---|---|
| Underlying model | Silero v5 (MIT upstream) | proprietary | GMM (2010) | proprietary |
| Model / binary footprint | 1.2 MB model (.vxrt) | not published | < 100 KB | 320–532 KB shared lib (runtime + model bundled) |
| Mobile RTF disclosed | ✅ measured on cheap Android + iPhone | ❌ desktop Ryzen + Raspberry Pi Zero only | ❌ | ✅ vendor-measured on Android + iPhone |
| License | Apache-2.0 wrapper + proprietary runtime + MIT weights (Silero Team) | Commercial freemium (paid tier opaque) | BSD-3 | Apache-2.0 **with non-compete clause**: redistribution blocked if it could enable Agora competitors |
| Ship in a paid app | ✅ no per-deployment terms | ⚠️ requires paid commercial tier | ✅ accuracy is the 2010 floor | ❌ license clause 1 forbids it |

We don't innovate on the VAD model — Silero v5 is the upstream MIT architecture you'd already pick. What we add is a NEON-optimized Rust runtime, a stateless C ABI suitable for SDK packaging, and per-device measured RTF that other vendors don't publish.

Full sourced analysis: [voxrt.com](https://voxrt.com).

## Binary footprint

- Kotlin wrapper source: ~30 KB total (compiled into your APK / AAB)
- `libvoxrt_silero.so` (arm64-v8a): ~424 KB stripped
- Silero VAD weights `silero_vad.vxrt`: 1.2 MB (downloaded separately, see below)

Net APK-size impact for the supported ABI: ~1.6 MB.

## Install

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        // ...
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.VoxRT:voxrt-silero-android:v0.1.2")
}
```

Gradle resolves it to a pre-built AAR served by JitPack from the tagged commit of this repo.

## Get the VAD model

The model weights are NOT bundled — you fetch them once from
[`voxrt-silero-models`](https://github.com/VoxRT/voxrt-silero-models/releases/tag/v0.1.2):

```
https://github.com/VoxRT/voxrt-silero-models/releases/download/v0.1.2/silero_vad.vxrt
```

SHA-256: `0fe8498c9bd1ae119bcb0c75c8481b3a8b8be0f95c14f334d469851c19054156`

You decide where it lives. Three common patterns:

- **Bundle in `assets/`** — drop the file into `src/main/assets/` and tell AAPT to leave the asset uncompressed so the engine can `mmap` it zero-copy via `AssetFileDescriptor` (see [Required: `noCompress`](#required-nocompress-on-vxrt-assets) below). Works offline from first launch.
- **Download on first run** — `OkHttp` / `HttpURLConnection` into `context.filesDir`. Smaller APK; needs network at first launch.
- **Download on demand** — Play Asset Delivery if you want Play Store to host the file.

### Required: `noCompress` on `.vxrt` assets

When you bundle `silero_vad.vxrt` under `assets/`, add the following to your **app**'s `build.gradle.kts` so AAPT leaves the asset stored-as-is and `openFd()` returns a real FD slice:

```kotlin
android {
    androidResources {
        noCompress.add("vxrt")
    }
}
```

Without this the asset is gzip'd inside the APK, `openFd()` falls back to a decompressed in-RAM `ByteArray`, and you lose the mmap zero-copy load (peak memory roughly doubles at session start). For 3 MB Silero this is harmless in absolute terms, but the same `noCompress` rule applies if you ever bundle the heavier ASR `.vxrt` in the same app — set it once and forget.

## Quick start

```kotlin
import com.voxrt.silero.VoxrtSileroVadEngine

// 1. Open the model as a file-descriptor — no managed-heap copy.
val modelFd = context.assets.openFd("silero_vad.vxrt")
val vad = VoxrtSileroVadEngine.fromAssetFd(modelFd)
modelFd.close()  // native side has copied the bytes; FD can go.

// (Optional, equivalent for downloaded models)
//    val modelBytes: ByteArray = downloadedFile.readBytes()
//    val vad = VoxrtSileroVadEngine.fromVxrtBytes(modelBytes)

// 2. Feed PCM (Int16, 16 kHz, mono).
val events = vad.processPcm(samples)

for (event in events) {
    when (event) {
        is VadEvent.SpeechOnset  -> Log.i("VAD", "speech started at ${event.timeMs} ms")
        is VadEvent.SpeechOffset -> Log.i("VAD", "speech ended   at ${event.timeMs} ms")
    }
}

// 4. When the stream ends:
vad.close()
```

The engine owns the LSTM state internally. Call `vad.reset()` between streams (e.g. when re-arming the mic). State snapshotting for replay / fork is also supported — see `VoxrtSileroVadEngine.snapshotLstmState()`.

## Live microphone example

The engine is **synchronous and stateful** — no internal worker
thread, no callbacks. You drive it from your own capture loop and
get events back as the return value of `processPcm`.

```kotlin
import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voxrt.silero.VadEvent
import com.voxrt.silero.VoxrtSileroVadEngine

// Caller is responsible for requesting RECORD_AUDIO permission
// before starting — see the manifest line below.

val sampleRate = 16_000
val minBuf = AudioRecord.getMinBufferSize(
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
)
val rec = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    sampleRate, AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(minBuf, 4096),
)

val modelFd = context.assets.openFd("silero_vad.vxrt")
val vad = VoxrtSileroVadEngine.fromAssetFd(modelFd)
modelFd.close()

Thread {
    rec.startRecording()
    // 32 ms blocks (512 samples @ 16 kHz) — one per inference window.
    val buf = ShortArray(512)
    try {
        while (!stopped) {
            val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            val block = if (n < buf.size) buf.copyOf(n) else buf
            for (event in vad.processPcm(block)) {
                when (event) {
                    is VadEvent.SpeechOnset  ->
                        runOnUiThread { Log.i("VAD", "speech started @ ${event.timeMs} ms") }
                    is VadEvent.SpeechOffset ->
                        runOnUiThread { Log.i("VAD", "speech ended   @ ${event.timeMs} ms") }
                }
            }
        }
    } finally {
        rec.stop(); rec.release()
        vad.close()
    }
}.start()
```

`vad.processPcm` returns immediately with whatever VAD events
crossed the hysteresis thresholds during this chunk — often an
empty list while inside a speech segment, an onset/offset event
when the state machine transitions. UI marshalling is the
caller's job (we don't assume anything about your UI framework).

Required permission in your **app**'s `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Request it at runtime before constructing `AudioRecord` on
Android 6+.

## Audio contract

- **Sample rate:** 16 000 Hz. **No automatic resampling.** If your source is 44.1 kHz / 48 kHz (typical for `AudioRecord`), resample first. Feeding the wrong rate is the #1 source of "VAD never fires" bugs.
- **Sample format:** `ShortArray` PCM (Int16), mono, native endian.
- **Buffer size:** any. The engine internally segments into 32 ms frames (512 samples) with a 4 ms (64-sample) rolling context.
- **Latency:** one frame (32 ms) of inherent buffering. End-of-speech is reported with the configurable `minSilenceMs` (default 250 ms) hysteresis.

## Threading

- The engine is a **synchronous, stateful function**. It does NOT own a worker thread. Each `processPcm` call blocks on the calling thread for the duration of the inference work — for live mic, put the engine + capture loop on your own background thread (see the example above). Marshal events back to UI via `runOnUiThread` / `Handler` / a `MutableStateFlow`.
- One engine instance is **single-thread-at-a-time**. Serialise `processPcm` / `reset` / `close` against each other on a given instance. The engine is annotated `@Synchronized` for basic safety, but concurrent calls don't make detection correct — only serial use does.
- One engine instance handles a stream of audio. Between unrelated streams (e.g. re-arming the mic for a new session), call `engine.reset()` to zero the LSTM state without paying weight-load cost again. Call `engine.close()` (or use `.use { }`) when done with the instance.

## Permissions

The library declares **no permissions** in its manifest. Your app declares them as needed by your input pipeline:

- Live mic capture → `RECORD_AUDIO` (runtime-requested on Android 6+).
- Reading audio files from external storage → `READ_MEDIA_AUDIO` (Android 13+) or `READ_EXTERNAL_STORAGE` (lower).

Add the line to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

And request it at runtime before constructing `AudioRecord` on Android 6+.

## Architectures roadmap

`v0.1.2` ships only `arm64-v8a` for production. The `x86_64` slice is included so the library works on Android emulators (using the scalar code path, not NEON-optimized).

| ABI                       | Status     | Notes |
| ------------------------- | ---------- | ----- |
| arm64-v8a (NEON)          | ✅ Shipped | Full NEON-optimized inner loops. |
| x86_64                    | ✅ Shipped | Scalar fallback, emulator-only. No SSE/AVX kernels yet. |
| armeabi-v7a               | 🟡 Coming soon | ARMv7 NEON kernels not yet implemented. |
| x86                       | ☁️ On request | Tiny share, unlikely to ship. |

If you target a device whose ABI is not in this list, Gradle will fail at install time with a `findLibrary` error. Filter `splits.abi` accordingly.

## Project layout

```
voxrt-silero-android/
├── settings.gradle.kts                            # SPM equivalent
├── build.gradle.kts                               # plugin versions
├── voxrt-silero/                                  # the library module
│   ├── build.gradle.kts                           # publish config
│   ├── consumer-rules.pro                         # R8 keep rules for JNI symbols
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/voxrt/silero/                 # Kotlin wrapper (open, Apache-2.0)
│       │   ├── CpuAffinity.kt
│       │   ├── VadEvent.kt
│       │   ├── VoxrtNative.kt
│       │   └── VoxrtSileroVadEngine.kt
│       └── jniLibs/
│           ├── arm64-v8a/libvoxrt_silero.so
│           └── x86_64/libvoxrt_silero.so
├── jitpack.yml                                    # JitPack build instructions
└── README.md                                      # this file
```

The compiled `libvoxrt_silero.so` per ABI is checked in as the binary half of the distribution — JitPack does NOT rebuild Rust.

## License

- The Kotlin wrapper (`voxrt-silero/src/main/java/com/voxrt/silero/`) is licensed under **Apache-2.0**. See [`LICENSE`](LICENSE).
- The compiled `libvoxrt_silero.so` files are proprietary VoxRT runtime code owned by Elephant Enterprises LLC, redistributable as part of this unmodified Kotlin library. See [`LICENSE-BINARY`](LICENSE-BINARY) for the full terms.
- Silero VAD model weights are © Silero Team, originally MIT-licensed; the `.vxrt` encoded form retains the same license. See the [models repository](https://github.com/VoxRT/voxrt-silero-models).
- Commercial integration / custom-model packaging questions: help@voxrt.com.

## Links

- VoxRT runtime + commercial models: [voxrt.com](https://voxrt.com)
- iOS counterpart: [voxrt-silero-ios](https://github.com/VoxRT/voxrt-silero-ios)
- VAD model weights & versions: [voxrt-silero-models](https://github.com/VoxRT/voxrt-silero-models)
- Streaming ASR (Android): [voxrt-asr-android](https://github.com/VoxRT/voxrt-asr-android) · [models](https://github.com/VoxRT/voxrt-asr-models)
- Wake-word (Android): [voxrt-wake-word-android](https://github.com/VoxRT/voxrt-wake-word-android) · [models](https://github.com/VoxRT/voxrt-wake-word-models)
- Bugs / questions: open an issue on this repo
