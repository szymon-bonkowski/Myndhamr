package io.github.szymonbonkowski.myndhamr.domain

enum class CaptureSessionState { IDLE, STARTING, RECORDING, STOPPING, COMPLETE, FAILED }
enum class CaptureCompletionReason { USER_STOP, CANCELLED, BACKGROUND }
enum class CaptureFailure {
    PERMISSION_DENIED,
    CAMERA_UNAVAILABLE,
    CAMERA_DISCONNECTED,
    CAMERA_ERROR,
    AR_UNAVAILABLE,
    STORAGE_ERROR,
    CRITICAL_SAMPLE_DROPPED,
}

data class CaptureSessionSnapshot(
    val state: CaptureSessionState,
    val completionReason: CaptureCompletionReason? = null,
    val failure: CaptureFailure? = null,
)

sealed class CaptureSessionEvent {
    data object Start : CaptureSessionEvent()
    data object RecordingStarted : CaptureSessionEvent()
    data class Stop(val reason: CaptureCompletionReason = CaptureCompletionReason.USER_STOP) : CaptureSessionEvent()
    data object Stopped : CaptureSessionEvent()
    data class Failed(val failure: CaptureFailure) : CaptureSessionEvent()
}

sealed class CaptureSessionTransition {
    data class Accepted(val previous: CaptureSessionSnapshot, val current: CaptureSessionSnapshot) : CaptureSessionTransition()
    data class Rejected(val current: CaptureSessionSnapshot, val event: CaptureSessionEvent) : CaptureSessionTransition()
}

/**
 * Single-owner lifecycle. Platform code serializes events and releases resources before Stopped.
 * Cancel/background both stop safely and retain committed measurements. Foreground does not restart.
 * Every asynchronous completion belongs to the platform's current session; stale callbacks must be
 * filtered by that owner before dispatch. Terminal states permit an explicitly requested new cycle.
 */
class CaptureSessionMachine {
    var snapshot: CaptureSessionSnapshot = CaptureSessionSnapshot(CaptureSessionState.IDLE)
        private set
    val state: CaptureSessionState get() = snapshot.state

    fun dispatch(event: CaptureSessionEvent): CaptureSessionTransition {
        val previous = snapshot
        val next = when (event) {
            CaptureSessionEvent.Start -> when (state) {
                CaptureSessionState.IDLE, CaptureSessionState.COMPLETE, CaptureSessionState.FAILED ->
                    CaptureSessionSnapshot(CaptureSessionState.STARTING)
                else -> null
            }
            CaptureSessionEvent.RecordingStarted -> if (state == CaptureSessionState.STARTING)
                CaptureSessionSnapshot(CaptureSessionState.RECORDING) else null
            is CaptureSessionEvent.Stop -> if (state == CaptureSessionState.STARTING || state == CaptureSessionState.RECORDING)
                CaptureSessionSnapshot(CaptureSessionState.STOPPING, completionReason = event.reason) else null
            CaptureSessionEvent.Stopped -> if (state == CaptureSessionState.STOPPING)
                CaptureSessionSnapshot(CaptureSessionState.COMPLETE, completionReason = previous.completionReason) else null
            is CaptureSessionEvent.Failed -> if (state == CaptureSessionState.STARTING ||
                state == CaptureSessionState.RECORDING || state == CaptureSessionState.STOPPING)
                CaptureSessionSnapshot(CaptureSessionState.FAILED, failure = event.failure) else null
        }
        if (next == null) return CaptureSessionTransition.Rejected(previous, event)
        snapshot = next
        return CaptureSessionTransition.Accepted(previous, next)
    }
}
