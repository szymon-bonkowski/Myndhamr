package io.github.szymonbonkowski.myndhamr.capture

/** ARCore can increment its frame timestamp while reusing the same Camera2 exposure.
 * Admit exactly one observation per actual camera exposure; zero means unavailable.
 * Single GL owner, reset for each camera session; original timestamps remain untouched.
 */
internal class CameraFrameAdmission {
    enum class Decision { ACCEPT, UNAVAILABLE, REPEATED, REGRESSION }
    private var previous:Long?=null
    var repeated=0L;private set
    var unavailable=0L;private set
    fun observe(cameraTimestamp:Long):Decision {
        if(cameraTimestamp<=0) { unavailable++;return Decision.UNAVAILABLE }
        val p=previous
        if(p!=null && cameraTimestamp<p) return Decision.REGRESSION
        if(p==cameraTimestamp) { repeated++;return Decision.REPEATED }
        previous=cameraTimestamp;return Decision.ACCEPT
    }
}
