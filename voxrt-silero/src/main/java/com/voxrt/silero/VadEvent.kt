package com.voxrt.silero

/**
 * Discrete events emitted by the [VoxrtSileroVadEngine] hysteresis
 * state machine. `timeMs` is an absolute timestamp from the engine's
 * creation (or its last `reset()`), measured in milliseconds.
 */
sealed class VadEvent {
    data class SpeechOnset(val timeMs: Long) : VadEvent()
    data class SpeechOffset(val timeMs: Long) : VadEvent()
}
