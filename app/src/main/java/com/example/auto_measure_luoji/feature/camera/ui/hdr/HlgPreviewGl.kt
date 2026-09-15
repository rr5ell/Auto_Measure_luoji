package com.example.auto_measure_luoji.feature.camera.ui.hdr

import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Display
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/** HLG 编码的 BT.2020 RGB 从输入转换后一直保留到显示，不按 sRGB 重解释。 */
object HlgPreviewGl {
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

    fun checkSupport(display: Display?): Result<Unit> = runCatching {
        check(Build.VERSION.SDK_INT >= 33) { "HDR 预览需要 Android 13 或以上版本" }
        check(display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true) {
            "当前屏幕未开放 HLG HDR 显示"
        }
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法初始化 HDR 显示" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "无法初始化 EGL" }
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS).orEmpty().split(' ')
            check("EGL_EXT_gl_colorspace_bt2020_hlg" in extensions && "EGL_KHR_gl_colorspace" in extensions) {
                "GPU 不支持 HLG HDR 显示表面"
            }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0) && count[0] > 0) { "GPU 不支持 10-bit 预览缓存" }
            context = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "无法创建 OpenGL ES 3 HDR 上下文" }
            surface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(eglDisplay, surface, surface, context)) {
                "无法验证 HDR 渲染器"
            }
            val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ')
            check("GL_EXT_YUV_target" in glExtensions && "GL_OES_EGL_image_external_essl3" in glExtensions) {
                "GPU 不支持 10-bit YUV 纹理读取"
            }
        } finally {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, context)
            EGL14.eglTerminate(eglDisplay)
            EGL14.eglReleaseThread()
        }
    }

    fun configure(view: GLSurfaceView, onFailure: (String) -> Unit) {
        check(Build.VERSION.SDK_INT >= 33)
        view.setEGLContextClientVersion(3)
        view.setEGLConfigChooser(10, 10, 10, 2, 0, 0)
        view.holder.setFormat(PixelFormat.RGBA_1010102)
        view.setEGLWindowSurfaceFactory(object : GLSurfaceView.EGLWindowSurfaceFactory {
            override fun createWindowSurface(egl: EGL10, display: EGLDisplay, config: EGLConfig, nativeWindow: Any): EGLSurface {
                val surface = try {
                    egl.eglCreateWindowSurface(display, config, nativeWindow, intArrayOf(
                        EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL10.EGL_NONE,
                    ))
                } catch (error: IllegalArgumentException) {
                    onFailure("无法创建 HDR 显示表面：${error.message}")
                    return EGL10.EGL_NO_SURFACE
                }
                if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
                    onFailure("无法创建 HDR 显示表面：EGL 0x${egl.eglGetError().toString(16)}")
                }
                return surface ?: EGL10.EGL_NO_SURFACE
            }

            override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
                egl.eglDestroySurface(display, surface)
            }
        })
    }

    const val VERTEX_SHADER = """#version 300 es
        in vec4 aPosition;
        in vec4 aTexCoord;
        uniform mat4 uTextureMatrix;
        uniform mat4 uDisplayMatrix;
        out vec2 vTexCoord;
        void main() {
            gl_Position = uDisplayMatrix * aPosition;
            vTexCoord = (uTextureMatrix * aTexCoord).xy;
        }
    """

    // BT.2020 非恒定亮度 Y'CbCr -> R'G'B'，此处不解码 HLG 传递函数。
    // 10-bit limited range: Y=(code-64)/876，Cb/Cr=(code-512)/896。
    // full range: Y=code/1023，Cb/Cr=(code-512)/1023。
    // Kr=0.2627，Kb=0.0593，Kg=1-Kr-Kb；R'=Y'+2(1-Kr)Cr，B'=Y'+2(1-Kb)Cb，
    // G'=(Y'-Kr*R'-Kb*B')/Kg。与 10-bit FBO、BT2020_HLG EGL 输出成对使用。
    const val FRAGMENT_SHADER = """#version 300 es
        #extension GL_EXT_YUV_target : require
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        uniform __samplerExternal2DY2YEXT uTexture;
        uniform bool uFullRange;
        in vec2 vTexCoord;
        out vec4 outColor;
        void main() {
            vec3 code = texture(uTexture, vTexCoord).xyz * 1023.0;
            float y = uFullRange ? code.x / 1023.0 : (code.x - 64.0) / 876.0;
            vec2 chroma = (code.yz - vec2(512.0)) / (uFullRange ? 1023.0 : 896.0);
            float r = y + 1.4746 * chroma.y;
            float b = y + 1.8814 * chroma.x;
            float g = (y - 0.2627 * r - 0.0593 * b) / 0.6780;
            outColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
        }
    """
}
