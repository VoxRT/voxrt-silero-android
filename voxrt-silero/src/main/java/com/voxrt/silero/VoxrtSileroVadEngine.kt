package com.voxrt.silero

import java.io.Closeable

/**
 * Idiomatic Kotlin wrapper around the stateless [VoxrtNative] JNI
 * surface in `crates/silero`. Per ADR-0022, the closed Rust binary
 * does only model execution; this wrapper owns:
 *
 *   - PCM accumulator (waits for 512 samples per inference).
 *   - Rolling 64-sample STFT context spliced in front of each window.
 *   - LSTM `h` / `c` state as a 256-float caller-allocated buffer.
 *   - Hysteresis state machine (onset / offset / min-silence).
 *   - Event emission as [VadEvent.SpeechOnset] / [SpeechOffset].
 *
 * Contributors land state-management experiments (anti-drift, partial
 * reset, checkpoint patterns) here in this open wrapper layer.
 *
 * **Threading:** per-instance, not thread-safe. Serialise
 * [processPcm] / [reset] / [close] against one another on a given
 * instance.
 */
class VoxrtSileroVadEngine private constructor(
    private var handle: Long,
    private val config: Config,
) : Closeable {

    /** Streaming hysteresis configuration. */
    data class Config(
        val onsetThreshold: Float = 0.5f,
        val offsetThreshold: Float = 0.35f,
        val minSilenceMs: Int = 100,
    )

    // ─── Streaming state (per ADR-0022, lives in Kotlin) ────────────────────

    /** LSTM `h[0..128]` + `c[128..256]` — flat layout matches the C
     *  ABI's `voxrt_silero_model_state_t` (repr(C)). */
    private val lstmState: FloatArray = FloatArray(LSTM_STATE_FLOATS)

    /** Audio waiting for a full inference window. */
    private val pendingPcm: ShortArray = ShortArray(WINDOW_SAMPLES)
    private var pendingCount: Int = 0

    /** Trailing 64 samples of the previous inference window. */
    private val rollingContext: ShortArray = ShortArray(CONTEXT_SAMPLES)

    /** Scratch [context_64 | window_512] = [INPUT_SAMPLES]. */
    private val inferInput: ShortArray = ShortArray(INPUT_SAMPLES)

    private var inSpeech: Boolean = false
    private var candidateOffsetMs: Long = -1
    private var chunkCount: Long = 0

    @Synchronized
    fun processPcm(pcm: ShortArray): List<VadEvent> {
        check(handle != 0L) { "VoxrtSileroVadEngine is closed" }

        val events = mutableListOf<VadEvent>()
        var cursor = 0

        while (cursor < pcm.size) {
            val need = WINDOW_SAMPLES - pendingCount
            val take = minOf(need, pcm.size - cursor)
            System.arraycopy(pcm, cursor, pendingPcm, pendingCount, take)
            pendingCount += take
            cursor += take

            if (pendingCount < WINDOW_SAMPLES) break

            // Assemble [context_64 | window_512] = INPUT_SAMPLES.
            System.arraycopy(rollingContext, 0, inferInput, 0, CONTEXT_SAMPLES)
            System.arraycopy(pendingPcm, 0, inferInput, CONTEXT_SAMPLES, WINDOW_SAMPLES)
            // Save trailing 64 of new window as next call's context.
            System.arraycopy(
                pendingPcm, WINDOW_SAMPLES - CONTEXT_SAMPLES,
                rollingContext, 0, CONTEXT_SAMPLES,
            )
            pendingCount = 0

            val prob = VoxrtNative.infer(handle, inferInput, lstmState)
            if (prob < 0.0f) {
                throw IllegalStateException(
                    "VoxrtNative.infer returned negative sentinel ($prob) — native error"
                )
            }

            chunkCount += 1
            val timeMs = chunkCount * MS_PER_CHUNK
            applyHysteresis(prob, timeMs, events)
        }

        return events
    }

    @Synchronized
    fun reset() {
        check(handle != 0L) { "VoxrtSileroVadEngine is closed" }
        pendingCount = 0
        rollingContext.fill(0)
        lstmState.fill(0f)
        inSpeech = false
        candidateOffsetMs = -1
        chunkCount = 0
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            VoxrtNative.destroy(handle)
            handle = 0L
        }
    }

    // ─── State-management knobs (open per ADR-0022 for experiments) ─────────

    /** Snapshot the LSTM state as a flat `FloatArray` of length 256
     *  (`h[0..128] + c[128..256]`). Use for checkpoint inference,
     *  anti-drift recovery, etc. */
    @Synchronized
    fun snapshotLstmState(): FloatArray = lstmState.copyOf()

    /** Restore a previously snapshotted state. No-op if length differs. */
    @Synchronized
    fun restoreLstmState(snapshot: FloatArray) {
        if (snapshot.size != LSTM_STATE_FLOATS) return
        System.arraycopy(snapshot, 0, lstmState, 0, LSTM_STATE_FLOATS)
    }

    /** Zero just the LSTM state without touching PCM accumulator,
     *  rolling context, or hysteresis. "Soft reset" against
     *  Silero v5's known long-stream drift. */
    @Synchronized
    fun zeroLstmState() {
        lstmState.fill(0f)
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private fun applyHysteresis(prob: Float, timeMs: Long, events: MutableList<VadEvent>) {
        if (!inSpeech) {
            if (prob >= config.onsetThreshold) {
                inSpeech = true
                candidateOffsetMs = -1
                events.add(VadEvent.SpeechOnset(timeMs))
            }
        } else if (prob >= config.onsetThreshold) {
            candidateOffsetMs = -1
        } else if (prob < config.offsetThreshold) {
            if (candidateOffsetMs < 0) {
                candidateOffsetMs = timeMs
            }
            if (timeMs - candidateOffsetMs >= config.minSilenceMs) {
                inSpeech = false
                candidateOffsetMs = -1
                events.add(VadEvent.SpeechOffset(timeMs))
            }
        }
        // Between offset and onset (hysteresis dead-zone) — hold state.
    }

    companion object {
        // Mirror ADR-0022 / C ABI input-shape constants.
        const val WINDOW_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
        const val INPUT_SAMPLES = WINDOW_SAMPLES + CONTEXT_SAMPLES // 576
        const val LSTM_HIDDEN_SIZE = 128
        const val LSTM_STATE_FLOATS = 2 * LSTM_HIDDEN_SIZE // h + c
        private const val SAMPLE_RATE_HZ = 16_000
        private const val MS_PER_CHUNK = (WINDOW_SAMPLES * 1000L) / SAMPLE_RATE_HZ

        /** Build a VoxrtSileroVadEngine from `.vxrt` bytes with the
         *  default hysteresis config. */
        fun fromVxrtBytes(modelBytes: ByteArray): VoxrtSileroVadEngine =
            fromVxrtBytes(modelBytes, Config())

        /** Build a VoxrtSileroVadEngine with caller-supplied
         *  hysteresis configuration. */
        fun fromVxrtBytes(modelBytes: ByteArray, config: Config): VoxrtSileroVadEngine {
            val h = VoxrtNative.create(modelBytes)
            check(h != 0L) {
                "VoxrtNative.create returned 0 — model deserialisation or " +
                        "SileroModel construction failed in the runtime"
            }
            return VoxrtSileroVadEngine(h, config)
        }

        /** mmap-based factory. Caller passes an `AssetFileDescriptor`
         *  (typically `context.assets.openFd("silero_vad.vxrt")`);
         *  the native side memory-maps the slice into the session
         *  and the FD can be closed as soon as this returns.
         *
         *  Avoids the Java-heap doubling that [fromVxrtBytes] causes
         *  for bundled assets. Requires
         *  `androidResources { noCompress.add("vxrt") }` in the
         *  consumer app's `build.gradle.kts`. */
        @JvmOverloads
        fun fromAssetFd(
            assetFd: android.content.res.AssetFileDescriptor,
            config: Config = Config(),
        ): VoxrtSileroVadEngine {
            val h = VoxrtNative.createFromFd(
                assetFd.parcelFileDescriptor.fd,
                assetFd.startOffset,
                assetFd.length,
            )
            check(h != 0L) {
                "VoxrtNative.createFromFd returned 0 — asset uncompressed? " +
                        ".vxrt valid?"
            }
            return VoxrtSileroVadEngine(h, config)
        }

        /** Backwards-compat overload for the previous constructor
         *  that took loose hysteresis parameters. New code should
         *  use the [Config] overload. */
        fun fromVxrtBytes(
            modelBytes: ByteArray,
            onsetThreshold: Float,
            offsetThreshold: Float,
            minSilenceMs: Int,
        ): VoxrtSileroVadEngine {
            val cfg = Config(
                onsetThreshold = onsetThreshold,
                offsetThreshold = offsetThreshold,
                minSilenceMs = if (minSilenceMs < 0) 0 else minSilenceMs,
            )
            return fromVxrtBytes(modelBytes, cfg)
        }
    }
}
