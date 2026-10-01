package io.github.szymonbonkowski.myndhamr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CaptureSessionTest {
    private fun accepted(machine: CaptureSessionMachine, event: CaptureSessionEvent, state: CaptureSessionState) {
        val before = machine.snapshot
        val result = assertIs<CaptureSessionTransition.Accepted>(machine.dispatch(event))
        assertEquals(before, result.previous)
        assertEquals(state, machine.state)
        assertEquals(machine.snapshot, result.current)
    }

    @Test
    fun repeatedCyclesStartRecordStopAndCompleteWithoutLeakingPriorReasons() {
        val machine = CaptureSessionMachine()
        assertEquals(CaptureSessionState.IDLE, machine.state)
        repeat(100) {
            accepted(machine, CaptureSessionEvent.Start, CaptureSessionState.STARTING)
            assertNull(machine.snapshot.completionReason)
            assertNull(machine.snapshot.failure)
            accepted(machine, CaptureSessionEvent.RecordingStarted, CaptureSessionState.RECORDING)
            accepted(machine, CaptureSessionEvent.Stop(), CaptureSessionState.STOPPING)
            assertEquals(CaptureCompletionReason.USER_STOP, machine.snapshot.completionReason)
            accepted(machine, CaptureSessionEvent.Stopped, CaptureSessionState.COMPLETE)
            assertEquals(CaptureCompletionReason.USER_STOP, machine.snapshot.completionReason)
        }
    }

    @Test
    fun permissionAndCameraFailuresRemainActionableAndPermitExplicitRetry() {
        val machine = CaptureSessionMachine()
        for (failure in CaptureFailure.entries) {
            accepted(machine, CaptureSessionEvent.Start, CaptureSessionState.STARTING)
            accepted(machine, CaptureSessionEvent.Failed(failure), CaptureSessionState.FAILED)
            assertEquals(failure, machine.snapshot.failure)
            assertNull(machine.snapshot.completionReason)
        }
        accepted(machine, CaptureSessionEvent.Start, CaptureSessionState.STARTING)
        assertNull(machine.snapshot.failure)
        accepted(machine, CaptureSessionEvent.RecordingStarted, CaptureSessionState.RECORDING)
        accepted(machine, CaptureSessionEvent.Failed(CaptureFailure.CAMERA_DISCONNECTED), CaptureSessionState.FAILED)
        assertEquals(CaptureFailure.CAMERA_DISCONNECTED, machine.snapshot.failure)
    }

    @Test
    fun cancelAndBackgroundStopInStartupOrRecordingAndWaitForResourceClosure() {
        for (recording in listOf(false, true)) for (reason in listOf(CaptureCompletionReason.CANCELLED, CaptureCompletionReason.BACKGROUND)) {
            val machine = CaptureSessionMachine()
            accepted(machine, CaptureSessionEvent.Start, CaptureSessionState.STARTING)
            if (recording) accepted(machine, CaptureSessionEvent.RecordingStarted, CaptureSessionState.RECORDING)
            accepted(machine, CaptureSessionEvent.Stop(reason), CaptureSessionState.STOPPING)
            assertEquals(reason, machine.snapshot.completionReason)
            assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.RecordingStarted))
            accepted(machine, CaptureSessionEvent.Stopped, CaptureSessionState.COMPLETE)
            assertEquals(reason, machine.snapshot.completionReason)
            assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.RecordingStarted))
            assertEquals(CaptureSessionState.COMPLETE, machine.state)
        }
    }

    @Test
    fun failureDuringStoppingWinsOverSuccessfulCompletion() {
        val machine = CaptureSessionMachine()
        accepted(machine, CaptureSessionEvent.Start, CaptureSessionState.STARTING)
        accepted(machine, CaptureSessionEvent.Stop(), CaptureSessionState.STOPPING)
        accepted(machine, CaptureSessionEvent.Failed(CaptureFailure.STORAGE_ERROR), CaptureSessionState.FAILED)
        assertEquals(CaptureFailure.STORAGE_ERROR, machine.snapshot.failure)
        assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.Stopped))
        assertEquals(CaptureSessionState.FAILED, machine.state)
    }

    @Test
    fun invalidEventsLeaveCurrentStateUnchanged() {
        val machine = CaptureSessionMachine()
        for (event in listOf(CaptureSessionEvent.RecordingStarted, CaptureSessionEvent.Stop(), CaptureSessionEvent.Stopped,
            CaptureSessionEvent.Failed(CaptureFailure.CAMERA_ERROR))) {
            val original = machine.snapshot
            val rejected = assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(event))
            assertEquals(original, rejected.current)
            assertEquals(event, rejected.event)
            assertEquals(original, machine.snapshot)
        }
        accepted(machine, CaptureSessionEvent.Start, CaptureSessionState.STARTING)
        assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.Start))
        accepted(machine, CaptureSessionEvent.RecordingStarted, CaptureSessionState.RECORDING)
        assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.RecordingStarted))
        assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.Start))
        accepted(machine, CaptureSessionEvent.Stop(), CaptureSessionState.STOPPING)
        assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.Start))
        assertIs<CaptureSessionTransition.Rejected>(machine.dispatch(CaptureSessionEvent.Stop(CaptureCompletionReason.CANCELLED)))
        assertEquals(CaptureCompletionReason.USER_STOP, machine.snapshot.completionReason)
    }
}
