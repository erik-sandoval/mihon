package eu.kanade.tachiyomi.util.system

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import kotlin.math.max

/**
 * Ported from upstream mihon/tachiyomi's `GLUtil` — used here as a generic device-capability
 * check (max GL texture size) to cap enhanced-decode output so it never exceeds what the
 * device can safely render. Not upscaler-specific itself, just a small self-contained
 * dependency the enhanced-decode path needs (see `ImageDecoder.kt`).
 */
object GLUtil {
    val DEVICE_TEXTURE_LIMIT: Int by lazy {
        // Get EGL Display
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

        // Initialise
        val version = IntArray(2)
        egl.eglInitialize(display, version)

        // Query total number of configurations
        val totalConfigurations = IntArray(1)
        egl.eglGetConfigs(display, null, 0, totalConfigurations)

        // Query actual list configurations
        val configurationsList = arrayOfNulls<EGLConfig>(totalConfigurations[0])
        egl.eglGetConfigs(display, configurationsList, totalConfigurations[0], totalConfigurations)

        val textureSize = IntArray(1)
        var maximumTextureSize = 0

        // Iterate through all the configurations to located the maximum texture size
        for (i in 0..<totalConfigurations[0]) {
            // Only need to check for width since opengl textures are always squared
            egl.eglGetConfigAttrib(display, configurationsList[i], EGL10.EGL_MAX_PBUFFER_WIDTH, textureSize)

            // Keep track of the maximum texture size
            if (maximumTextureSize < textureSize[0]) maximumTextureSize = textureSize[0]
        }

        // Release
        egl.eglTerminate(display)

        // Return largest texture size found, or a safe default
        max(maximumTextureSize, SAFE_TEXTURE_LIMIT)
    }

    const val SAFE_TEXTURE_LIMIT: Int = 2048
}
