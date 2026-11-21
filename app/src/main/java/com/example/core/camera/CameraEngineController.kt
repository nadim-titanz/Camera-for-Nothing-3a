package com.example.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureCapabilities
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.core.imageprocessing.PhotographicStyle
import com.example.core.imageprocessing.PhotographicStyleBitmapProcessor
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.imageprocessing.PhotographicStyleGlProcessor
import com.example.core.imageprocessing.PortraitBokehEngine
import com.example.core.settings.CameraAspectRatio
import com.example.core.settings.CameraFlashMode
import com.example.core.settings.CameraSettingsState
import com.example.core.settings.VideoResolutionOption
import com.example.core.storage.MediaStoreRepository
import com.example.feature.camera.CameraMode
import com.example.feature.camera.LiveCameraCapabilities
import com.example.feature.camera.LiveLensTelemetry
import com.example.feature.camera.SupportedVideoOption
import com.example.feature.camera.ZoomPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class CameraEngineController(
  private val context: Context,
  private val mediaStoreRepository: MediaStoreRepository,
) {

  private val mainExecutor = ContextCompat.getMainExecutor(context)
  private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val glProcessor = PhotographicStyleGlProcessor()
  private val portraitBokehEngine = PortraitBokehEngine()

  private var cameraProvider: ProcessCameraProvider? = null
  private var extensionsManager: ExtensionsManager? = null
  private var boundCamera: Camera? = null
  private var imageCapture: ImageCapture? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var imageAnalysis: ImageAnalysis? = null
  private var activeRecording: Recording? = null
  private var previewViewRef: PreviewView? = null

  private val _capabilities = MutableStateFlow(LiveCameraCapabilities())
  val capabilities: StateFlow<LiveCameraCapabilities> = _capabilities.asStateFlow()

  private val _telemetry = MutableStateFlow(LiveLensTelemetry())
  val telemetry: StateFlow<LiveLensTelemetry> = _telemetry.asStateFlow()

  private val _zoomRatio = MutableStateFlow(1.0f)
  val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

  private val _portraitSubjectDetected = MutableStateFlow(false)
  val portraitSubjectDetected: StateFlow<Boolean> = _portraitSubjectDetected.asStateFlow()

  private val _recordingDurationMs = MutableStateFlow(0L)
  val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  private val _isPaused = MutableStateFlow(false)
  val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

  private suspend fun ensureProvidersInitialized(): Pair<ProcessCameraProvider, ExtensionsManager?> {
    val provider =
      cameraProvider
        ?: awaitFuture(ProcessCameraProvider.getInstance(context)).also { cameraProvider = it }
    val ext =
      extensionsManager
        ?: runCatching { awaitFuture(ExtensionsManager.getInstanceAsync(context, provider)) }
          .getOrNull()
          ?.also { extensionsManager = it }
    return provider to ext
  }

  fun updateLiveStyle(style: PhotographicStyle, intensity: Float) {
    glProcessor.updateStyle(style, intensity)
  }

  fun updatePortraitBokehStrength(strength: Float, isPortraitMode: Boolean) {
    glProcessor.updatePortraitBokeh(if (isPortraitMode) strength else 0f)
  }

  @OptIn(ExperimentalCamera2Interop::class)
  suspend fun bindCamera(
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    mode: CameraMode,
    isFrontCamera: Boolean,
    settings: CameraSettingsState,
    flashMode: CameraFlashMode,
    torchEnabled: Boolean,
    portraitBlurStrength: Float,
  ): Result<LiveCameraCapabilities> =
    runCatching {
      previewViewRef = previewView
      val (provider, extManager) = ensureProvidersInitialized()
      val baseSelector =
        if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

      provider.unbindAll()

      val previewBuilder = Preview.Builder()
      val videoCapCheck =
        Recorder.getVideoCapabilities(provider.getCameraInfo(baseSelector))
      val supportedQualities = videoCapCheck.getSupportedQualities(DynamicRange.SDR)

      // Live Camera2 telemetry listener for logical multi-camera physical ID tracking
      Camera2Interop.Extender(previewBuilder)
        .setSessionCaptureCallback(
          object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
              session: CameraCaptureSession,
              request: CaptureRequest,
              result: TotalCaptureResult,
            ) {
              val physicalId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                  result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else {
                  null
                }
              val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)
              val currentZoom = boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio ?: _zoomRatio.value
              _telemetry.value =
                LiveLensTelemetry(
                  logicalCameraId = _capabilities.value.activeCameraId,
                  activePhysicalId = physicalId,
                  focalLengthMm = focalLength,
                  zoomRatio = currentZoom,
                )
              _zoomRatio.value = currentZoom
            }
          }
        )

      val tempCameraInfo = provider.getCameraInfo(baseSelector)
      val previewStabSupported = Preview.getPreviewCapabilities(tempCameraInfo).isStabilizationSupported
      if (settings.videoStabilizationEnabled && mode == CameraMode.VIDEO && previewStabSupported) {
        previewBuilder.setPreviewStabilizationEnabled(true)
      }

      val preview = previewBuilder.build().apply {
        surfaceProvider = previewView.surfaceProvider
      }

      val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
      val aspectRational =
        when (settings.aspectRatio) {
          CameraAspectRatio.RATIO_4_3 -> Rational(3, 4)
          CameraAspectRatio.RATIO_16_9 -> Rational(9, 16)
          CameraAspectRatio.RATIO_1_1 -> Rational(1, 1)
          CameraAspectRatio.FULL -> {
            val w = previewView.width.coerceAtLeast(9)
            val h = previewView.height.coerceAtLeast(16)
            Rational(w, h)
          }
        }
      val viewPort = ViewPort.Builder(aspectRational, targetRotation).build()
      val useCaseGroupBuilder =
        UseCaseGroup.Builder()
          .setViewPort(viewPort)
          .addUseCase(preview)
          .addEffect(glProcessor.createPreviewEffect())

      imageCapture = null
      videoCapture = null
      imageAnalysis = null

      if (mode == CameraMode.VIDEO) {
        val selectedQuality =
          mapResolutionToQuality(settings.preferredVideoResolution, supportedQualities)
        val qualitySelector =
          QualitySelector.from(
            selectedQuality,
            FallbackStrategy.higherQualityOrLowerThan(selectedQuality),
          )
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        val videoBuilder = VideoCapture.Builder(recorder)
        val videoStabSupported = videoCapCheck.isStabilizationSupported
        if (settings.videoStabilizationEnabled && videoStabSupported) {
          videoBuilder.setVideoStabilizationEnabled(true)
        }
        val targetRange = pickClosestFpsRange(tempCameraInfo, settings.preferredVideoFps)
        if (targetRange != null) {
          videoBuilder.setTargetFrameRate(targetRange)
        }
        val vc = videoBuilder.build()
        videoCapture = vc
        useCaseGroupBuilder.addUseCase(vc)
        glProcessor.updatePortraitBokeh(0f)
        _portraitSubjectDetected.value = false
      } else {
        val icCaps = ImageCapture.getImageCaptureCapabilities(tempCameraInfo)
        val icBuilder =
          ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(mapFlashMode(flashMode))
        if (
          settings.captureRawWhenSupported &&
            icCaps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        ) {
          icBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }
        val ic = icBuilder.build()
        imageCapture = ic
        useCaseGroupBuilder.addUseCase(ic)

        if (mode == CameraMode.PORTRAIT) {
          glProcessor.updatePortraitBokeh(portraitBlurStrength)
          val analysis =
            ImageAnalysis.Builder()
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .build()
          analysis.setAnalyzer(analysisExecutor) { proxy ->
            portraitBokehEngine.analyzePreviewFrame(proxy) { result ->
              _portraitSubjectDetected.value = result.subjectDetected
              glProcessor.updateSegmentationMask(result.maskBytes, result.width, result.height)
            }
          }
          imageAnalysis = analysis
          useCaseGroupBuilder.addUseCase(analysis)
        } else {
          glProcessor.updatePortraitBokeh(0f)
          _portraitSubjectDetected.value = false
        }
      }

      val camera =
        provider.bindToLifecycle(
          lifecycleOwner,
          baseSelector,
          useCaseGroupBuilder.build(),
        )
      boundCamera = camera

      if (camera.cameraInfo.hasFlashUnit()) {
        camera.cameraControl.enableTorch(mode == CameraMode.VIDEO && torchEnabled)
      }

      val caps = inspectBoundCapabilities(camera.cameraInfo, baseSelector, extManager)
      _capabilities.value = caps
      _zoomRatio.value = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
      caps
    }

  @OptIn(ExperimentalCamera2Interop::class)
  private fun inspectBoundCapabilities(
    cameraInfo: CameraInfo,
    selector: CameraSelector,
    extManager: ExtensionsManager?,
  ): LiveCameraCapabilities {
    val zoomState = cameraInfo.zoomState.value
    val minZ = zoomState?.minZoomRatio ?: 1.0f
    val maxZ = zoomState?.maxZoomRatio ?: 4.0f

    val presets = buildList {
      if (minZ <= 0.72f) {
        add(ZoomPreset(ratio = maxOf(0.6f, minZ), label = "0.6x"))
      }
      add(ZoomPreset(ratio = 1.0f.coerceIn(minZ, maxZ), label = "1x"))
      if (maxZ >= 2.0f) {
        add(ZoomPreset(ratio = 2.0f, label = "2x"))
      }
      if (maxZ >= 5.0f) {
        add(ZoomPreset(ratio = 5.0f, label = "5x"))
      }
    }

    val expState = cameraInfo.exposureState
    val expRange =
      if (expState.isExposureCompensationSupported) {
        expState.exposureCompensationRange.lower..expState.exposureCompensationRange.upper
      } else {
        0..0
      }

    val icCaps = ImageCapture.getImageCaptureCapabilities(cameraInfo)
    val supportsRaw =
      icCaps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) ||
        icCaps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)

    val supportsHwBokeh =
      runCatching { extManager?.isExtensionAvailable(selector, ExtensionMode.BOKEH) == true }
        .getOrDefault(false)

    val previewStab = Preview.getPreviewCapabilities(cameraInfo).isStabilizationSupported
    val videoCaps = Recorder.getVideoCapabilities(cameraInfo)
    val videoStab = videoCaps.isStabilizationSupported
    val qualities = videoCaps.getSupportedQualities(DynamicRange.SDR)
    val fpsSet =
      cameraInfo.supportedFrameRateRanges
        .filter { it.upper >= 24 }
        .map { it.upper }
        .distinct()
        .filter { it in listOf(24, 30, 60, 120) }
        .sorted()
        .ifEmpty { listOf(30) }

    val videoOptions = buildList {
      if (qualities.contains(Quality.UHD)) {
        add(SupportedVideoOption(VideoResolutionOption.UHD_4K, fpsSet.filter { it <= 60 }.ifEmpty { listOf(30) }))
      }
      if (qualities.contains(Quality.FHD)) {
        add(SupportedVideoOption(VideoResolutionOption.FHD_1080P, fpsSet))
      }
      if (qualities.contains(Quality.HD)) {
        add(SupportedVideoOption(VideoResolutionOption.HD_720P, fpsSet.filter { it <= 60 }.ifEmpty { listOf(30) }))
      }
    }.ifEmpty {
      listOf(SupportedVideoOption(VideoResolutionOption.FHD_1080P, listOf(30)))
    }

    val cam2Info = runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull()
    val camId = cam2Info?.cameraId ?: "0"
    val physicalIds =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cam2Info != null) {
        runCatching {
            val chars =
              (context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager)
                .getCameraCharacteristics(camId)
            chars.physicalCameraIds
          }
          .getOrDefault(emptySet())
      } else {
        emptySet()
      }

    return LiveCameraCapabilities(
      minZoom = minZ,
      maxZoom = maxZ,
      zoomPresets = presets,
      hasFlash = cameraInfo.hasFlashUnit(),
      exposureRange = expRange,
      exposureStep = expState.exposureCompensationStep.toFloat(),
      supportsRaw = supportsRaw,
      supportsHardwareBokeh = supportsHwBokeh,
      supportsPreviewStabilization = previewStab,
      supportsVideoStabilization = videoStab,
      supportedVideoOptions = videoOptions,
      activeCameraId = camId,
      physicalCameraIds = physicalIds,
    )
  }

  fun setZoomRatio(ratio: Float) {
    val camera = boundCamera ?: return
    val minZ = _capabilities.value.minZoom
    val maxZ = _capabilities.value.maxZoom
    val clamped = ratio.coerceIn(minZ, maxZ)
    camera.cameraControl.setZoomRatio(clamped)
    _zoomRatio.value = clamped
  }

  fun setExposureIndex(index: Int) {
    val camera = boundCamera ?: return
    val range = _capabilities.value.exposureRange
    if (range.first == 0 && range.last == 0) return
    camera.cameraControl.setExposureCompensationIndex(index.coerceIn(range.first, range.last))
  }

  fun setFlashMode(flashMode: CameraFlashMode) {
    imageCapture?.flashMode = mapFlashMode(flashMode)
  }

  fun setTorchEnabled(enabled: Boolean) {
    val camera = boundCamera ?: return
    if (camera.cameraInfo.hasFlashUnit()) {
      camera.cameraControl.enableTorch(enabled)
    }
  }

  fun focusAtPoint(
    x: Float,
    y: Float,
    lockAeAf: Boolean,
  ) {
    val camera = boundCamera ?: return
    val pv = previewViewRef ?: return
    val factory = pv.meteringPointFactory
    val point = factory.createPoint(x, y)
    val builder =
      FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
      )
    if (lockAeAf) {
      builder.disableAutoCancel()
    } else {
      builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
    }
    camera.cameraControl.startFocusAndMetering(builder.build())
  }

  fun unlockFocusAndExposure() {
    boundCamera?.cameraControl?.cancelFocusAndMetering()
  }

  suspend fun capturePhoto(
    mode: CameraMode,
    isFrontCamera: Boolean,
    settings: CameraSettingsState,
    portraitBlurStrength: Float,
    flashMode: CameraFlashMode,
  ): Result<Uri> {
    val ic = imageCapture ?: return Result.failure(IllegalStateException("ImageCapture not bound"))
    if (!mediaStoreRepository.hasEnoughStorage()) {
      return Result.failure(IllegalStateException("Low storage (<50 MB free)"))
    }

    ic.flashMode = mapFlashMode(flashMode)
    val style = PhotographicStyleCatalog.findById(settings.selectedStyleId)
    val needsStyle = style.id != PhotographicStyleCatalog.Natural.id && settings.styleIntensity > 0.02f
    val needsBokeh = mode == CameraMode.PORTRAIT && portraitBlurStrength > 0.02f

    // If RAW+JPEG is requested & active, capture via OutputFileOptions directly
    if (settings.captureRawWhenSupported && _capabilities.value.supportsRaw) {
      return captureRawAndJpegToMediaStore(ic)
    }

    return runCatching {
      val imageProxy = takePictureProxy(ic)
      val decodedBitmap =
        withContext(Dispatchers.Default) {
          val bmp = decodeJpegProxy(imageProxy, mirror = isFrontCamera && settings.mirrorFrontSelfie)
          imageProxy.close()
          bmp
        } ?: throw IllegalStateException("Failed to decode captured photo")

      var originalSavedUri: Uri? = null
      if (settings.saveOriginalWithStyle && (needsStyle || needsBokeh)) {
        originalSavedUri =
          mediaStoreRepository.saveBitmapToMediaStore(decodedBitmap, prefix = "ORIG", quality = 95)
      }

      var workingBitmap = decodedBitmap
      if (needsBokeh) {
        val (bokehResult, _) =
          portraitBokehEngine.applyPortraitBokeh(workingBitmap, portraitBlurStrength)
        if (bokehResult !== workingBitmap) {
          if (workingBitmap !== decodedBitmap) workingBitmap.recycle()
          workingBitmap = bokehResult
        }
      }

      if (needsStyle) {
        val styled =
          withContext(Dispatchers.Default) {
            PhotographicStyleBitmapProcessor.applyStyle(workingBitmap, style, settings.styleIntensity)
          }
        if (styled !== workingBitmap) {
          if (workingBitmap !== decodedBitmap) workingBitmap.recycle()
          workingBitmap = styled
        }
      }

      val prefix = if (mode == CameraMode.PORTRAIT) "PORTRAIT" else "IMG"
      val savedUri =
        mediaStoreRepository.saveBitmapToMediaStore(workingBitmap, prefix = prefix, quality = 95)
          ?: throw IllegalStateException("MediaStore failed to save captured photo")

      if (workingBitmap !== decodedBitmap) workingBitmap.recycle()
      decodedBitmap.recycle()

      if (originalSavedUri != null) {
        mediaStoreRepository.registerOriginalSidecar(savedUri, originalSavedUri)
      }
      savedUri
    }
  }

  @SuppressLint("MissingPermission")
  fun startVideoRecording(
    scope: CoroutineScope,
    onFinalized: (Result<Uri>) -> Unit,
  ): Result<Unit> {
    val vc = videoCapture ?: return Result.failure(IllegalStateException("VideoCapture not bound"))
    if (_isRecording.value) return Result.success(Unit)
    if (!mediaStoreRepository.hasEnoughStorage()) {
      return Result.failure(IllegalStateException("Low storage (<50 MB free)"))
    }

    return runCatching {
      val outputOptions = mediaStoreRepository.createVideoOutputOptions()
      val hasAudioPermission =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

      var pendingRecording = vc.output.prepareRecording(context, outputOptions)
      if (hasAudioPermission) {
        pendingRecording = pendingRecording.withAudioEnabled()
      }

      _recordingDurationMs.value = 0L
      _isPaused.value = false
      _isRecording.value = true

      activeRecording =
        pendingRecording.start(mainExecutor) { event ->
          when (event) {
            is VideoRecordEvent.Start -> {
              _isRecording.value = true
              _isPaused.value = false
            }
            is VideoRecordEvent.Status -> {
              _recordingDurationMs.value = event.recordingStats.recordedDurationNanos / 1_000_000L
            }
            is VideoRecordEvent.Pause -> {
              _isPaused.value = true
            }
            is VideoRecordEvent.Resume -> {
              _isPaused.value = false
            }
            is VideoRecordEvent.Finalize -> {
              _isRecording.value = false
              _isPaused.value = false
              activeRecording = null
              if (event.hasError()) {
                val msg = "Recording error (${event.error})"
                scope.launch { onFinalized(Result.failure(IllegalStateException(msg))) }
              } else {
                val uri = event.outputResults.outputUri
                scope.launch { onFinalized(Result.success(uri)) }
              }
            }
          }
        }
    }
  }

  fun pauseOrResumeRecording() {
    val rec = activeRecording ?: return
    if (_isPaused.value) {
      rec.resume()
    } else {
      rec.pause()
    }
  }

  fun stopVideoRecording() {
    activeRecording?.stop()
    activeRecording = null
  }

  fun release() {
    stopVideoRecording()
    glProcessor.release()
    portraitBokehEngine.close()
    analysisExecutor.shutdown()
  }

  private suspend fun takePictureProxy(ic: ImageCapture): ImageProxy =
    suspendCancellableCoroutine { cont ->
      ic.takePicture(
        mainExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
          override fun onCaptureSuccess(image: ImageProxy) {
            if (cont.isActive) cont.resume(image) else image.close()
          }

          override fun onError(exception: ImageCaptureException) {
            if (cont.isActive) cont.resumeWithException(exception)
          }
        },
      )
    }

  private suspend fun captureRawAndJpegToMediaStore(ic: ImageCapture): Result<Uri> =
    suspendCancellableCoroutine { cont ->
      val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
      val rawValues =
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, "RAW_$stamp")
          put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/NothingCameraPro")
          }
        }
      val jpegValues = mediaStoreRepository.createPhotoOutputValues("IMG")

      val rawOptions =
        ImageCapture.OutputFileOptions.Builder(
          context.contentResolver,
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          rawValues,
        ).build()
      val jpegOptions =
        ImageCapture.OutputFileOptions.Builder(
          context.contentResolver,
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          jpegValues,
        ).build()

      ic.takePicture(
        rawOptions,
        jpegOptions,
        mainExecutor,
        object : ImageCapture.OnImageSavedCallback {
          override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
            val uri = outputFileResults.savedUri
            if (cont.isActive) {
              if (uri != null) {
                cont.resume(Result.success(uri))
              } else {
                cont.resume(Result.failure(IllegalStateException("RAW+JPEG saved without URI")))
              }
            }
          }

          override fun onError(exception: ImageCaptureException) {
            if (cont.isActive) cont.resume(Result.failure(exception))
          }
        },
      )
    }

  private fun decodeJpegProxy(imageProxy: ImageProxy, mirror: Boolean): Bitmap? {
    val buffer = imageProxy.planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = imageProxy.imageInfo.rotationDegrees
    if (rotation == 0 && !mirror) return rawBitmap
    val matrix = Matrix()
    if (rotation != 0) matrix.postRotate(rotation.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)
    val transformed =
      Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    if (transformed !== rawBitmap) rawBitmap.recycle()
    return transformed
  }

  private fun mapFlashMode(mode: CameraFlashMode): Int =
    when (mode) {
      CameraFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
      CameraFlashMode.ON -> ImageCapture.FLASH_MODE_ON
      CameraFlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    }

  private fun mapResolutionToQuality(
    option: VideoResolutionOption,
    supported: List<Quality>,
  ): Quality {
    val desired =
      when (option) {
        VideoResolutionOption.UHD_4K -> Quality.UHD
        VideoResolutionOption.FHD_1080P -> Quality.FHD
        VideoResolutionOption.HD_720P -> Quality.HD
      }
    return if (supported.contains(desired)) desired else supported.firstOrNull() ?: Quality.FHD
  }

  private fun pickClosestFpsRange(cameraInfo: CameraInfo, targetFps: Int): Range<Int>? {
    val ranges = cameraInfo.supportedFrameRateRanges
    return ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
      ?: ranges.firstOrNull { it.upper == targetFps }
  }

  private suspend fun <T> awaitFuture(future: com.google.common.util.concurrent.ListenableFuture<T>): T =
    suspendCancellableCoroutine { cont ->
      future.addListener(
        {
          try {
            if (cont.isActive) cont.resume(future.get())
          } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
          }
        },
        mainExecutor,
      )
    }

  companion object {
    private const val TAG = "CameraEngineController"
  }
}
