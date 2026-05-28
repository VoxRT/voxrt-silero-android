package com.voxrt.silero

/**
 * Kotlin facade for the VoxRT Silero native library.
 *
 * The companion object's `init` block calls `System.loadLibrary("voxrt_silero")`
 * once per process. Android resolves "voxrt_silero" to
 * `libvoxrt_silero.so` by adding the lib prefix and .so suffix and
 * looking under the right ABI directory in the APK.
 *
 * The `external fun` declarations match the JNI symbols exported by
 * `crates/silero/src/lib.rs`. The naming convention Java/Kotlin uses
 * is mapped by JNI to symbol names like
 * `Java_com_voxrt_silero_VoxrtNative_create`.
 */
object VoxrtNative {
    init {
        System.loadLibrary("voxrt_silero")
    }

    /** Returns the SDK version string. */
    external fun voxrtVersion(): String

    // ─── Silero VAD (stateless C ABI per ADR-0022) ──────────────────────────
    //
    // Thin marshalling layer over the `voxrt_silero_*` C ABI in
    // `crates/silero`. State (LSTM h+c, PCM accumulator, rolling
    // STFT context, hysteresis, event emission) lives in Kotlin —
    // see [VoxrtSileroVadEngine] for the real state machine.
    //
    // Single opaque native handle (`Long`) per stream — caller MUST
    // serialise calls on a given handle. 0 == invalid handle.

    /** Deserialise a Silero `.vxrt` and build a streaming handle.
     *  Returns 0 on any error (bad bytes, bad shape, OOM). */
    external fun create(modelBytes: ByteArray): Long

    /** mmap-based create. Caller passes the file-descriptor, offset
     *  and length from an `AssetFileDescriptor` (typically obtained
     *  via `context.assets.openFd("silero_vad.vxrt")`). The native
     *  side memory-maps the slice into the session and closes the
     *  mapping after the v1 deserializer copies the bytes — caller
     *  can close the FD immediately after this returns.
     *
     *  Avoids the Java-heap doubling that the [create] path causes
     *  for bundled assets. The asset MUST be stored uncompressed in
     *  the APK (`androidResources { noCompress.add("vxrt") }`), or
     *  `openFd` throws on the Kotlin side. Returns 0 on error. */
    external fun createFromFd(fd: Int, offset: Long, length: Long): Long

    /** Run one inference on a 576-sample (= [VoxrtSileroVadEngine.INPUT_SAMPLES])
     *  i16 PCM window. `state` is a flat `FloatArray(256)` holding
     *  `h[0..128] | c[128..256]` (matches the C ABI's
     *  `voxrt_silero_model_state_t` repr-C layout). `state` is read
     *  in + written out. Returns the per-window speech probability
     *  in `[0, 1]`, or `-1.0` on any error (bad handle, wrong sizes,
     *  native panic). */
    external fun infer(
        handle: Long,
        input: ShortArray,
        state: FloatArray,
    ): Float

    /** Reclaim the handle. Must be called exactly once per [create]. */
    external fun destroy(handle: Long)

    /** Pin the *calling* thread to a CPU cluster.
     *
     *  Mode values:
     *    - 0 = AUTO — clear pinning, scheduler picks freely.
     *    - 1 = HIGH_PERF — pin to the highest-frequency cluster
     *                      (big A73 / X-class cores on big.LITTLE
     *                      phones).
     *    - 2 = LOW_POWER — pin to the lowest-frequency cluster.
     *
     *  Cluster boundaries are discovered at runtime from each CPU's
     *  `cpuinfo_max_freq` sysfs — never hardcoded to a specific SoC.
     *  On homogeneous chips (all cores same freq), both pinning modes
     *  resolve to the full mask and the call is a no-op.
     *
     *  Returns `true` on success, `false` on any error. Soft fail by
     *  design — if affinity can't be applied, the thread keeps
     *  running without pinning.
     *
     *  **Threading:** the affinity applies to the *calling* thread.
     *  Invoke from the audio / capture thread that runs [infer]. */
    external fun setCurrentThreadAffinity(mode: Int): Boolean
}
