package com.example.core.imageprocessing

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-time OpenGL ES 2.0 CameraX [SurfaceProcessor] that executes both Photographic Styles
 * color science and Portrait background-bokeh masking directly on GPU camera preview frames.
 */
class PhotographicStyleGlProcessor : SurfaceProcessor {

  private val glThread = HandlerThread("NothingCameraGlThread").apply { start() }
  private val glHandler = Handler(glThread.looper)

  val glExecutor: Executor = Executor { runnable -> glHandler.post(runnable) }

  private val activeStyle = AtomicReference(PhotographicStyleCatalog.Natural)
  @Volatile private var styleIntensity: Float = 1.0f
  @Volatile private var bokehStrength: Float = 0.0f // 0 = off, >0 = portrait background blur
  @Volatile private var pendingMaskBuffer: ByteBuffer? = null
  @Volatile private var pendingMaskWidth: Int = 0
  @Volatile private var pendingMaskHeight: Int = 0
  @Volatile private var maskDirty: Boolean = false

  private var eglDisplay: EGLDisplay? = null
  private var eglContext: EGLContext? = null
  private var eglConfig: EGLConfig? = null
  private var pbufferSurface: EGLSurface? = null
  private var outputEglSurface: EGLSurface? = null
  private var currentSurfaceOutput: SurfaceOutput? = null

  private var programId: Int = 0
  private var oesTextureId: Int = 0
  private var maskTextureId: Int = 0
  private var inputSurfaceTexture: SurfaceTexture? = null
  private var inputSurface: Surface? = null

  private val texMatrix = FloatArray(16)
  private val surfaceOutputMatrix = FloatArray(16)
  private var isReleased = false

  private var uTexMatrixLoc = -1
  private var uTexelSizeLoc = -1
  private var uContrastLoc = -1
  private var uSaturationLoc = -1
  private var uVibranceLoc = -1
  private var uTempLoc = -1
  private var uTintLoc = -1
  private var uHighlightsLoc = -1
  private var uShadowsLoc = -1
  private var uBlackLiftLoc = -1
  private var uMonoMixLoc = -1
  private var uSkinProtectLoc = -1
  private var uBokehStrengthLoc = -1
  private var uHasMaskLoc = -1
  private var uMaskTexLoc = -1
  private var uInputTexLoc = -1
  private var aPositionLoc = -1
  private var aTexCoordLoc = -1

  private var hasValidMaskTexture = false
  private var currentOutputSize = Size(1280, 720)

  private val quadBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(QUAD_COORDS)
        position(0)
      }

  fun updateStyle(style: PhotographicStyle, intensity: Float) {
    activeStyle.set(style)
    styleIntensity = intensity.coerceIn(0f, 1f)
  }

  fun updatePortraitBokeh(strength: Float) {
    bokehStrength = strength.coerceIn(0f, 1f)
    if (strength <= 0.01f) {
      hasValidMaskTexture = false
    }
  }

  fun updateSegmentationMask(maskBytes: ByteArray, width: Int, height: Int) {
    if (isReleased || width <= 0 || height <= 0) return
    val buf = ByteBuffer.allocateDirect(maskBytes.size).apply {
      put(maskBytes)
      position(0)
    }
    pendingMaskBuffer = buf
    pendingMaskWidth = width
    pendingMaskHeight = height
    maskDirty = true
  }

  fun createPreviewEffect(): CameraEffect =
    object : CameraEffect(PREVIEW, glExecutor, this, { err ->
      Log.e(TAG, "CameraEffect error", err)
    }) {}

  override fun onInputSurface(request: SurfaceRequest) {
    if (isReleased) {
      request.willNotProvideSurface()
      return
    }
    ensureGlInitialized()

    val resolution = request.resolution
    inputSurface?.release()
    inputSurfaceTexture?.release()

    val st = SurfaceTexture(oesTextureId).apply {
      setDefaultBufferSize(resolution.width, resolution.height)
    }
    val surface = Surface(st)
    inputSurfaceTexture = st
    inputSurface = surface

    st.setOnFrameAvailableListener(
      { surfaceTex ->
        if (isReleased) return@setOnFrameAvailableListener
        renderFrame(surfaceTex)
      },
      glHandler,
    )

    request.provideSurface(surface, glExecutor) {
      st.setOnFrameAvailableListener(null)
      st.release()
      surface.release()
      if (inputSurfaceTexture === st) inputSurfaceTexture = null
      if (inputSurface === surface) inputSurface = null
    }
  }

  override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
    if (isReleased) {
      surfaceOutput.close()
      return
    }
    ensureGlInitialized()

    releaseOutputSurface()
    currentSurfaceOutput = surfaceOutput
    currentOutputSize = surfaceOutput.size

    val surface =
      surfaceOutput.getSurface(glExecutor) {
        releaseOutputSurface()
      }
    outputEglSurface =
      EGL14.eglCreateWindowSurface(
        eglDisplay,
        eglConfig,
        surface,
        intArrayOf(EGL14.EGL_NONE),
        0,
      )
  }

  fun release() {
    glHandler.post {
      isReleased = true
      releaseOutputSurface()
      inputSurface?.release()
      inputSurfaceTexture?.release()
      if (programId != 0) GLES20.glDeleteProgram(programId)
      if (oesTextureId != 0 || maskTextureId != 0) {
        GLES20.glDeleteTextures(2, intArrayOf(oesTextureId, maskTextureId), 0)
      }
      if (pbufferSurface != null && pbufferSurface != EGL14.EGL_NO_SURFACE && eglDisplay != null) {
        EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
      }
      if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT && eglDisplay != null) {
        EGL14.eglDestroyContext(eglDisplay, eglContext)
      }
      if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglTerminate(eglDisplay)
      }
      eglDisplay = null
      eglContext = null
      glThread.quitSafely()
    }
  }

  private fun releaseOutputSurface() {
    if (
      outputEglSurface != null &&
        outputEglSurface != EGL14.EGL_NO_SURFACE &&
        eglDisplay != null &&
        eglDisplay != EGL14.EGL_NO_DISPLAY
    ) {
      EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
      EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
      outputEglSurface = null
    }
    currentSurfaceOutput?.close()
    currentSurfaceOutput = null
  }

  private fun ensureGlInitialized() {
    if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) return

    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

    val configAttribs =
      intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE,
        EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE,
        8,
        EGL14.EGL_GREEN_SIZE,
        8,
        EGL14.EGL_BLUE_SIZE,
        8,
        EGL14.EGL_ALPHA_SIZE,
        8,
        EGL14.EGL_NONE,
      )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
    eglConfig = configs[0]

    eglContext =
      EGL14.eglCreateContext(
        eglDisplay,
        eglConfig,
        EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
        0,
      )

    pbufferSurface =
      EGL14.eglCreatePbufferSurface(
        eglDisplay,
        eglConfig,
        intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
        0,
      )
    EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

    val textures = IntArray(2)
    GLES20.glGenTextures(2, textures, 0)
    oesTextureId = textures[0]
    maskTextureId = textures[1]

    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MIN_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MAG_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_S,
      GLES20.GL_CLAMP_TO_EDGE,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_T,
      GLES20.GL_CLAMP_TO_EDGE,
    )

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
    aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
    uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
    uTexelSizeLoc = GLES20.glGetUniformLocation(programId, "uTexelSize")
    uInputTexLoc = GLES20.glGetUniformLocation(programId, "uInputTex")
    uMaskTexLoc = GLES20.glGetUniformLocation(programId, "uMaskTex")
    uHasMaskLoc = GLES20.glGetUniformLocation(programId, "uHasMask")
    uBokehStrengthLoc = GLES20.glGetUniformLocation(programId, "uBokehStrength")
    uContrastLoc = GLES20.glGetUniformLocation(programId, "uContrast")
    uSaturationLoc = GLES20.glGetUniformLocation(programId, "uSaturation")
    uVibranceLoc = GLES20.glGetUniformLocation(programId, "uVibrance")
    uTempLoc = GLES20.glGetUniformLocation(programId, "uTemp")
    uTintLoc = GLES20.glGetUniformLocation(programId, "uTint")
    uHighlightsLoc = GLES20.glGetUniformLocation(programId, "uHighlights")
    uShadowsLoc = GLES20.glGetUniformLocation(programId, "uShadows")
    uBlackLiftLoc = GLES20.glGetUniformLocation(programId, "uBlackLift")
    uMonoMixLoc = GLES20.glGetUniformLocation(programId, "uMonoMix")
    uSkinProtectLoc = GLES20.glGetUniformLocation(programId, "uSkinProtect")
  }

  private fun renderFrame(st: SurfaceTexture) {
    val outSurface = currentSurfaceOutput ?: return
    val outEgl = outputEglSurface ?: return
    if (outEgl == EGL14.EGL_NO_SURFACE) return

    EGL14.eglMakeCurrent(eglDisplay, outEgl, outEgl, eglContext)
    st.updateTexImage()
    st.getTransformMatrix(texMatrix)
    outSurface.updateTransformMatrix(surfaceOutputMatrix, texMatrix)

    if (maskDirty) {
      maskDirty = false
      val buf = pendingMaskBuffer
      if (buf != null && pendingMaskWidth > 0 && pendingMaskHeight > 0) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
          GLES20.GL_TEXTURE_2D,
          0,
          GLES20.GL_LUMINANCE,
          pendingMaskWidth,
          pendingMaskHeight,
          0,
          GLES20.GL_LUMINANCE,
          GLES20.GL_UNSIGNED_BYTE,
          buf,
        )
        hasValidMaskTexture = true
      }
    }

    GLES20.glViewport(0, 0, currentOutputSize.width, currentOutputSize.height)
    GLES20.glUseProgram(programId)

    quadBuffer.position(0)
    GLES20.glEnableVertexAttribArray(aPositionLoc)
    GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

    quadBuffer.position(2)
    GLES20.glEnableVertexAttribArray(aTexCoordLoc)
    GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

    GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, surfaceOutputMatrix, 0)
    GLES20.glUniform2f(
      uTexelSizeLoc,
      1.0f / currentOutputSize.width.coerceAtLeast(1),
      1.0f / currentOutputSize.height.coerceAtLeast(1),
    )

    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    GLES20.glUniform1i(uInputTexLoc, 0)

    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
    GLES20.glUniform1i(uMaskTexLoc, 1)

    val style = activeStyle.get()
    val alpha = styleIntensity
    GLES20.glUniform1f(uHasMaskLoc, if (hasValidMaskTexture && bokehStrength > 0.01f) 1f else 0f)
    GLES20.glUniform1f(uBokehStrengthLoc, bokehStrength)
    GLES20.glUniform1f(uContrastLoc, 1.0f + (style.contrast - 1.0f) * alpha)
    GLES20.glUniform1f(uSaturationLoc, 1.0f + (style.saturation - 1.0f) * alpha)
    GLES20.glUniform1f(uVibranceLoc, style.vibrance * alpha)
    GLES20.glUniform1f(uTempLoc, style.temperature * alpha)
    GLES20.glUniform1f(uTintLoc, style.tint * alpha)
    GLES20.glUniform1f(uHighlightsLoc, style.highlights * alpha)
    GLES20.glUniform1f(uShadowsLoc, style.shadows * alpha)
    GLES20.glUniform1f(uBlackLiftLoc, style.blackLift * alpha)
    GLES20.glUniform1f(uMonoMixLoc, if (style.monochrome) alpha else 0f)
    GLES20.glUniform1f(uSkinProtectLoc, style.skinToneProtection)

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    EGL14.eglSwapBuffers(eglDisplay, outputEglSurface)
  }

  private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val prog = GLES20.glCreateProgram()
    GLES20.glAttachShader(prog, vs)
    GLES20.glAttachShader(prog, fs)
    GLES20.glLinkProgram(prog)
    return prog
  }

  private fun compileShader(type: Int, src: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, src)
    GLES20.glCompileShader(shader)
    return shader
  }

  companion object {
    private const val TAG = "StyleGlProcessor"

    private val QUAD_COORDS =
      floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
      )

    private const val VERTEX_SHADER =
      """
      attribute vec4 aPosition;
      attribute vec2 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      varying vec2 vRawCoord;
      void main() {
        gl_Position = aPosition;
        vRawCoord = aTexCoord;
        vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
      }
      """

    private const val FRAGMENT_SHADER =
      """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES uInputTex;
      uniform sampler2D uMaskTex;
      uniform vec2 uTexelSize;
      uniform float uHasMask;
      uniform float uBokehStrength;
      uniform float uContrast;
      uniform float uSaturation;
      uniform float uVibrance;
      uniform float uTemp;
      uniform float uTint;
      uniform float uHighlights;
      uniform float uShadows;
      uniform float uBlackLift;
      uniform float uMonoMix;
      uniform float uSkinProtect;
      varying vec2 vTexCoord;
      varying vec2 vRawCoord;

      vec3 sampleBokeh(vec2 uv, float radius) {
        vec3 acc = texture2D(uInputTex, uv).rgb;
        float r = radius * 8.0;
        vec2 offsets[8];
        offsets[0] = vec2(-1.0,  0.0);
        offsets[1] = vec2( 1.0,  0.0);
        offsets[2] = vec2( 0.0, -1.0);
        offsets[3] = vec2( 0.0,  1.0);
        offsets[4] = vec2(-0.707, -0.707);
        offsets[5] = vec2( 0.707, -0.707);
        offsets[6] = vec2(-0.707,  0.707);
        offsets[7] = vec2( 0.707,  0.707);
        for (int i = 0; i < 8; i++) {
          acc += texture2D(uInputTex, clamp(uv + offsets[i] * uTexelSize * r, 0.0, 1.0)).rgb;
        }
        return acc / 9.0;
      }

      void main() {
        vec3 sharpRgb = texture2D(uInputTex, vTexCoord).rgb;
        vec3 rgb = sharpRgb;

        if (uHasMask > 0.5) {
          float mask = texture2D(uMaskTex, vec2(vRawCoord.x, 1.0 - vRawCoord.y)).r;
          float fgFactor = smoothstep(0.32, 0.72, mask);
          vec3 bgRgb = sampleBokeh(vTexCoord, uBokehStrength);
          rgb = mix(bgRgb, sharpRgb, fgFactor);
        }

        // 1. White balance temp & tint
        rgb.r = clamp(rgb.r + uTemp * 0.24 + uTint * 0.08, 0.0, 1.0);
        rgb.g = clamp(rgb.g - uTint * 0.14 + uTemp * 0.03, 0.0, 1.0);
        rgb.b = clamp(rgb.b - uTemp * 0.24 + uTint * 0.08, 0.0, 1.0);

        // 2. Highlights & shadows
        float lum = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        float sMask = (1.0 - lum) * (1.0 - lum);
        float hMask = lum * lum;
        float tDelta = uShadows * sMask * 0.35 + uHighlights * hMask * 0.35;
        rgb = clamp(rgb + vec3(tDelta), 0.0, 1.0);

        // 3. Contrast + matte black lift
        rgb = clamp((rgb - 0.5) * uContrast + 0.5, 0.0, 1.0);
        rgb = mix(rgb, uBlackLift + rgb * (1.0 - uBlackLift), step(0.001, uBlackLift));

        // 4. Skin-tone-protected saturation & vibrance
        float lumAfter = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        float maxC = max(rgb.r, max(rgb.g, rgb.b));
        float minC = min(rgb.r, min(rgb.g, rgb.b));
        float curSat = maxC > 0.001 ? (maxC - minC) / maxC : 0.0;
        float isSkin = (rgb.r > rgb.g && rgb.g > rgb.b && (rgb.r - rgb.b) > 0.06 && (rgb.r - rgb.g) < 0.36) ? 1.0 : 0.0;
        float skinShield = mix(1.0, 1.0 - uSkinProtect * 0.65, isSkin);
        float satBoost = 1.0 + ((uSaturation - 1.0) + uVibrance * (1.0 - curSat)) * skinShield;
        rgb = clamp(vec3(lumAfter) + (rgb - vec3(lumAfter)) * satBoost, 0.0, 1.0);

        // 5. Monochrome mix
        float bw = dot(rgb, vec3(0.299, 0.587, 0.114));
        rgb = mix(rgb, vec3(bw), uMonoMix);

        gl_FragColor = vec4(rgb, 1.0);
      }
      """
  }
}
