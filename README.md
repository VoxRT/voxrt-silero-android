# VoxrtSilero for Android

Silero v5 voice-activity detection, running on the **VoxRT** custom on-device inference runtime.

- Current version: `v0.1.1`
- Minimum Android: API 26 (Android 8.0)
- ABIs shipped: `arm64-v8a` (NEON-accelerated), `x86_64` (scalar, emulator only)
- License: Apache-2.0 (Kotlin wrapper) · proprietary (compiled runtime, redistribution allowed via this artifact)

---

## What is VoxRT?

VoxRT is a from-scratch inference runtime for on-device speech models. No ONNX Runtime, no PyTorch Mobile, no LiteRT — a custom Rust core sized and tuned for streaming voice workloads on phone-class hardware.

`VoxrtSilero` is the free, open-source showcase of that runtime: a Kotlin library that runs the Silero v5 VAD with state-of-the-art per-frame latency. The runtime is the product; Silero is the demo subject.

Commercial wake-word / keyword-spotting / phrase-recognition models built on the same runtime live at [voxrt.com](https://voxrt.com).

## Performance

Measured at ship time, `arm64-v8a` release builds, post-warmup, RTF = wall-time-per-frame ÷ frame-duration (lower is better):

| Device                                       | RTF       | per-frame latency      |
| -------------------------------------------- | --------- | ---------------------- |
| Xiaomi Redmi 9C (Snapdragon 662, Cortex-A73) | **3.05%** | ~1 ms / 32 ms frame    |

What this means: at 3.05% RTF you can run dozens of parallel VAD streams on a single core before saturating it, leaving the device idle to handle the rest of the audio pipeline.

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
    implementation("com.github.VoxRT:voxrt-silero-android:v0.1.1")
}
```

Gradle resolves it to a pre-built AAR served by JitPack from the tagged commit of this repo.

## Get the VAD model

The model weights are NOT bundled — you fetch them once from
[`voxrt-silero-models`](https://github.com/VoxRT/voxrt-silero-models/releases/tag/v0.1.1):

```
https://github.com/VoxRT/voxrt-silero-models/releases/download/v0.1.1/silero_vad.vxrt
```

SHA-256: `0fe8498c9bd1ae119bcb0c75c8481b3a8b8be0f95c14f334d469851c19054156`

You decide where it lives. Three common patterns:

- **Bundle in `assets/`** — drop the file into `src/main/assets/`. Read with `context.assets.open("silero_vad.vxrt").readBytes()`. Works offline from first launch.
- **Download on first run** — `OkHttp` / `HttpURLConnection` into `context.filesDir`. Smaller APK; needs network at first launch.
- **Download on demand** — Play Asset Delivery if you want Play Store to host the file.

## Quick start

```kotlin
import com.voxrt.silero.VoxrtSileroVadEngine

// 1. Load the model bytes (however you obtained them).
val modelBytes: ByteArray = context.assets.open("silero_vad.vxrt").use { it.readBytes() }

// 2. Spin up an engine. One per audio stream.
val vad = VoxrtSileroVadEngine.fromVxrtBytes(modelBytes)

// 3. Feed PCM (Int16, 16 kHz, mono).
val events = vad.processPcm(samples)

for (event in events) {
    when (event.kind) {
        VadEvent.Kind.SPEECH_START -> Log.i("VAD", "speech started at ${event.timestampMs} ms")
        VadEvent.Kind.SPEECH_END   -> Log.i("VAD", "speech ended   at ${event.timestampMs} ms")
    }
}

// 4. When the stream ends:
vad.close()
```

The engine owns the LSTM state internally. Call `vad.reset()` between streams (e.g. when re-arming the mic). State snapshotting for replay / fork is also supported — see `VoxrtSileroVadEngine.snapshotLstmState()`.

## Audio contract

- **Sample rate:** 16 000 Hz
- **Sample format:** `ShortArray` PCM (Int16), mono, native endian
- **Buffer size:** any. The engine internally segments into 32 ms frames (512 samples) with a 4 ms (64-sample) rolling context.
- **Latency:** one frame (32 ms) of inherent buffering. End-of-speech is reported with the configurable `minSilenceMs` (default 250 ms) hysteresis.

## Architectures roadmap

`v0.1.1` ships only `arm64-v8a` for production. The `x86_64` slice is included so the library works on Android emulators (using the scalar code path, not NEON-optimized).

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
- Bugs / questions: open an issue on this repo
