package io.github.szymonbonkowski.myndhamr.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GL-thread owned camera preview; measurement intrinsics stay in unrotated CPU coordinates. */
internal class CameraPreview {
    var texture = 0; private set
    private var program = 0
    private val positions = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); rewind() }
    private val uv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun create() {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        fun shader(type:Int, source:String):Int {
            val s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s)
            val ok=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0]!=0) { GLES20.glGetShaderInfoLog(s) }; return s
        }
        val v=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 u; varying vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=u;}")
        val f=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES c; varying vec2 t; void main(){gl_FragColor=texture2D(c,t);}")
        program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,v); GLES20.glAttachShader(program,f); GLES20.glLinkProgram(program)
        val ok=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]!=0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
    }
    fun draw(frame: Frame) {
        positions.rewind(); uv.rewind(); frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,positions,Coordinates2d.TEXTURE_NORMALIZED,uv)
        positions.rewind(); uv.rewind(); GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"c"),0)
        val p=GLES20.glGetAttribLocation(program,"p"); val u=GLES20.glGetAttribLocation(program,"u")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(u)
        GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,positions); GLES20.glVertexAttribPointer(u,2,GLES20.GL_FLOAT,false,0,uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(u)
    }
}
