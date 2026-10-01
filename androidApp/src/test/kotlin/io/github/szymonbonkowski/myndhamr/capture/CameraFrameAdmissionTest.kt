package io.github.szymonbonkowski.myndhamr.capture
import kotlin.test.Test
import kotlin.test.assertEquals
class CameraFrameAdmissionTest {
    @Test fun reusedExposureNeverCreatesDuplicateAssociationDespiteChangingArTime() {
        val a=CameraFrameAdmission()
        assertEquals(CameraFrameAdmission.Decision.UNAVAILABLE,a.observe(0))
        val sources=listOf(122450492088893L,122450492088893L,122450492088893L,122450525408059L)
        val decisions=sources.map(a::observe)
        assertEquals(listOf(CameraFrameAdmission.Decision.ACCEPT,CameraFrameAdmission.Decision.REPEATED,CameraFrameAdmission.Decision.REPEATED,CameraFrameAdmission.Decision.ACCEPT),decisions)
        assertEquals(2,a.repeated);assertEquals(1,a.unavailable)
        assertEquals(CameraFrameAdmission.Decision.REGRESSION,a.observe(122450492088893L))
    }
}
