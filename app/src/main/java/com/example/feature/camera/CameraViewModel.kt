package com.example.feature.camera

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.camera.CameraEngineController
import com.example.core.common.HorizonSensorMonitor
import com.example.core.imageprocessing.PhotographicStyle
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.settings.CameraAspectRatio
import com.example.core.settings.CameraFlashMode
import com.example.core.settings.CameraPreferencesRepository
import com.example.core.settings.CameraSettingsState
import com.example.core.settings.CaptureTimer
import com.example.core.settings.VideoResolutionOption
import com.example.core.storage.MediaStoreRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(
  private val controller: CameraEngineController,
  private val preferencesRepository: CameraPreferencesRepository,
  private val mediaStoreRepository: MediaStoreRepository,
  private val horizonMonitor: HorizonSensorMonitor,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CameraUiState())
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

  private var countdownJob: Job? = null
  private var focusHideJob: Job? = null
  private var lastBoundLifecycle: LifecycleOwner? = null
  private var lastBoundPreview: PreviewView? = null

  init {
    observeStreams()
    refreshLatestMedia()
  }

  private fun observeStreams() {
    viewModelScope.launch {
      combine(
        preferencesRepository.settingsFlow,
        controller.capabilities,
        controller.telemetry,
        controller.zoomRatio,
        controller.portraitSubjectDetected,
      ) { settings, caps, telemetry, zoom, subject ->
        FiveWayTuple(settings, caps, telemetry, zoom, subject)
      }.collect { tuple ->
        val style = PhotographicStyleCatalog.findById(tuple.settings.selectedStyleId)
        controller.updateLiveStyle(style, tuple.settings.styleIntensity)
        _uiState.update { state ->
          state.copy(
            settings = tuple.settings,
            activeStyle = style,
            capabilities = tuple.caps,
            telemetry = tuple.telemetry,
            zoomRatio = tuple.zoom,
            portraitSubjectDetected = tuple.subject,
          )
        }
      }
    }

    viewModelScope.launch {
      combine(
        controller.isRecording,
        controller.isPaused,
        controller.recordingDurationMs,
      ) { rec, paused, dur ->
        VideoRecordingUiState(isRecording = rec, isPaused = paused, durationMs = dur)
      }.collect { recState ->
        _uiState.update { it.copy(recordingState = recState) }
      }
    }

    viewModelScope.launch {
      horizonMonitor.tiltDegreesFlow().collect { deg ->
        _uiState.update { it.copy(tiltDegrees = deg) }
      }
    }
  }

  fun onPermissionsUpdated(cameraGranted: Boolean, audioGranted: Boolean) {
    val prevCamera = _uiState.value.cameraPermissionGranted
    _uiState.update {
      it.copy(
        cameraPermissionGranted = cameraGranted,
        audioPermissionGranted = audioGranted,
      )
    }
    if (cameraGranted && !prevCamera) {
      rebindIfReady()
    }
  }

  fun bindPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
    lastBoundLifecycle = lifecycleOwner
    lastBoundPreview = previewView
    if (!_uiState.value.cameraPermissionGranted) return
    viewModelScope.launch {
      val s = _uiState.value
      controller
        .bindCamera(
          lifecycleOwner = lifecycleOwner,
          previewView = previewView,
          mode = s.mode,
          isFrontCamera = s.isFrontCamera,
          settings = s.settings,
          flashMode = s.flashMode,
          torchEnabled = s.torchEnabled,
          portraitBlurStrength = s.portraitBlurStrength,
        )
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Camera bind failed") }
        }
    }
  }

  private fun rebindIfReady() {
    val lifecycle = lastBoundLifecycle ?: return
    val pv = lastBoundPreview ?: return
    bindPreview(lifecycle, pv)
  }

  fun refreshLatestMedia() {
    viewModelScope.launch {
      val recent = mediaStoreRepository.loadAppCapturedMedia(limit = 1).firstOrNull()
      _uiState.update {
        it.copy(
          latestMediaUri = recent?.uri,
          latestMediaIsVideo = recent?.isVideo == true,
        )
      }
    }
  }

  fun selectMode(mode: CameraMode) {
    if (_uiState.value.recordingState.isRecording) return
    if (_uiState.value.mode == mode) return
    _uiState.update {
      it.copy(
        mode = mode,
        torchEnabled = if (mode == CameraMode.VIDEO) it.torchEnabled else false,
        exposureIndex = 0,
        focusIndicator = it.focusIndicator.copy(isVisible = false, isLocked = false),
      )
    }
    controller.updatePortraitBokehStrength(
      _uiState.value.portraitBlurStrength,
      mode == CameraMode.PORTRAIT,
    )
    rebindIfReady()
  }

  fun stepModeBySwipe(deltaSteps: Int) {
    val modes = CameraMode.entries
    val curIdx = modes.indexOf(_uiState.value.mode)
    val nextIdx = (curIdx + deltaSteps).coerceIn(0, modes.lastIndex)
    if (nextIdx != curIdx) {
      selectMode(modes[nextIdx])
    }
  }

  fun toggleFrontRearCamera() {
    if (_uiState.value.recordingState.isRecording) return
    _uiState.update {
      it.copy(
        isFrontCamera = !it.isFrontCamera,
        torchEnabled = false,
        exposureIndex = 0,
        focusIndicator = FocusIndicatorState(),
      )
    }
    rebindIfReady()
  }

  fun cycleFlashMode() {
    val s = _uiState.value
    if (s.mode == CameraMode.VIDEO) {
      val nextTorch = !s.torchEnabled
      _uiState.update { it.copy(torchEnabled = nextTorch) }
      controller.setTorchEnabled(nextTorch)
    } else {
      val next =
        when (s.flashMode) {
          CameraFlashMode.AUTO -> CameraFlashMode.ON
          CameraFlashMode.ON -> CameraFlashMode.OFF
          CameraFlashMode.OFF -> CameraFlashMode.AUTO
        }
      _uiState.update { it.copy(flashMode = next) }
      controller.setFlashMode(next)
    }
  }

  fun cycleTimer() {
    val values = CaptureTimer.entries
    val next = values[(values.indexOf(_uiState.value.captureTimer) + 1) % values.size]
    _uiState.update { it.copy(captureTimer = next) }
  }

  fun cycleAspectRatio() {
    if (_uiState.value.mode == CameraMode.VIDEO || _uiState.value.recordingState.isRecording) return
    val values = CameraAspectRatio.entries
    val next = values[(values.indexOf(_uiState.value.settings.aspectRatio) + 1) % values.size]
    viewModelScope.launch {
      preferencesRepository.setAspectRatio(next)
      delay(40)
      rebindIfReady()
    }
  }

  fun setZoomRatio(ratio: Float) {
    controller.setZoomRatio(ratio)
  }

  fun scaleZoomBy(scaleFactor: Float) {
    val current = _uiState.value.zoomRatio
    controller.setZoomRatio(current * scaleFactor)
  }

  fun tapToFocus(xPx: Float, yPx: Float, xFrac: Float, yFrac: Float, lockAeAf: Boolean) {
    controller.focusAtPoint(xPx, yPx, lockAeAf)
    val trigger = System.currentTimeMillis()
    _uiState.update {
      it.copy(
        focusIndicator =
          FocusIndicatorState(
            xFraction = xFrac.coerceIn(0.08f, 0.92f),
            yFraction = yFrac.coerceIn(0.08f, 0.92f),
            isLocked = lockAeAf,
            isVisible = true,
            triggerId = trigger,
          )
      )
    }
    focusHideJob?.cancel()
    if (!lockAeAf) {
      focusHideJob =
        viewModelScope.launch {
          delay(3500)
          _uiState.update { state ->
            if (state.focusIndicator.triggerId == trigger && !state.focusIndicator.isLocked) {
              state.copy(focusIndicator = state.focusIndicator.copy(isVisible = false))
            } else {
              state
            }
          }
        }
    }
  }

  fun unlockAeAf() {
    controller.unlockFocusAndExposure()
    _uiState.update {
      it.copy(focusIndicator = it.focusIndicator.copy(isLocked = false, isVisible = false))
    }
  }

  fun setExposureIndex(index: Int) {
    val range = _uiState.value.capabilities.exposureRange
    val clamped = index.coerceIn(range.first, range.last)
    _uiState.update { it.copy(exposureIndex = clamped) }
    controller.setExposureIndex(clamped)
  }

  fun setPortraitBlurStrength(strength: Float) {
    val clamped = strength.coerceIn(0f, 1f)
    _uiState.update { it.copy(portraitBlurStrength = clamped) }
    controller.updatePortraitBokehStrength(clamped, _uiState.value.mode == CameraMode.PORTRAIT)
  }

  fun toggleStylesSheet() {
    _uiState.update { it.copy(isStylesSheetOpen = !it.isStylesSheetOpen) }
  }

  fun closeStylesSheet() {
    _uiState.update { it.copy(isStylesSheetOpen = false) }
  }

  fun selectStyle(style: PhotographicStyle) {
    val intensity = _uiState.value.settings.styleIntensity
    _uiState.update {
      it.copy(
        activeStyle = style,
        settings = it.settings.copy(selectedStyleId = style.id),
      )
    }
    controller.updateLiveStyle(style, intensity)
    viewModelScope.launch {
      preferencesRepository.setPhotographicStyle(style.id, intensity)
    }
  }

  fun setStyleIntensity(intensity: Float) {
    val clamped = intensity.coerceIn(0f, 1f)
    val style = _uiState.value.activeStyle
    _uiState.update {
      it.copy(settings = it.settings.copy(styleIntensity = clamped))
    }
    controller.updateLiveStyle(style, clamped)
    viewModelScope.launch {
      preferencesRepository.setPhotographicStyle(style.id, clamped)
    }
  }

  fun setVideoQuality(resolution: VideoResolutionOption, fps: Int) {
    if (_uiState.value.recordingState.isRecording) return
    viewModelScope.launch {
      preferencesRepository.setVideoQuality(resolution, fps)
      delay(40)
      rebindIfReady()
    }
  }

  fun onShutterClicked() {
    val state = _uiState.value
    if (!state.cameraPermissionGranted) return

    if (state.mode == CameraMode.VIDEO) {
      if (state.recordingState.isRecording) {
        controller.stopVideoRecording()
      } else {
        controller
          .startVideoRecording(viewModelScope) { result ->
            result
              .onSuccess { uri ->
                _uiState.update {
                  it.copy(
                    latestMediaUri = uri,
                    latestMediaIsVideo = true,
                    statusMessage = "Video saved",
                  )
                }
              }
              .onFailure { err ->
                _uiState.update {
                  it.copy(errorMessage = err.localizedMessage ?: "Video recording failed")
                }
              }
          }
          .onFailure { err ->
            _uiState.update {
              it.copy(errorMessage = err.localizedMessage ?: "Unable to start video")
            }
          }
      }
      return
    }

    if (state.isCapturing) return
    if (state.countdownRemainingSec > 0) {
      countdownJob?.cancel()
      _uiState.update { it.copy(countdownRemainingSec = 0) }
      return
    }

    val timerSec = state.captureTimer.seconds
    if (timerSec > 0) {
      countdownJob =
        viewModelScope.launch {
          for (sec in timerSec downTo 1) {
            _uiState.update { it.copy(countdownRemainingSec = sec) }
            delay(1000)
          }
          _uiState.update { it.copy(countdownRemainingSec = 0) }
          executePhotoCapture()
        }
    } else {
      executePhotoCapture()
    }
  }

  fun pauseOrResumeVideo() {
    controller.pauseOrResumeRecording()
  }

  private fun executePhotoCapture() {
    val s = _uiState.value
    viewModelScope.launch {
      _uiState.update { it.copy(isCapturing = true, showShutterFlash = true) }
      launch {
        delay(120)
        _uiState.update { it.copy(showShutterFlash = false) }
      }
      controller
        .capturePhoto(
          mode = s.mode,
          isFrontCamera = s.isFrontCamera,
          settings = s.settings,
          portraitBlurStrength = s.portraitBlurStrength,
          flashMode = s.flashMode,
        )
        .onSuccess { uri ->
          _uiState.update {
            it.copy(
              isCapturing = false,
              latestMediaUri = uri,
              latestMediaIsVideo = false,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isCapturing = false,
              errorMessage = err.localizedMessage ?: "Capture failed",
            )
          }
        }
    }
  }

  fun clearMessages() {
    _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    controller.release()
  }

  private data class FiveWayTuple(
    val settings: CameraSettingsState,
    val caps: LiveCameraCapabilities,
    val telemetry: LiveLensTelemetry,
    val zoom: Float,
    val subject: Boolean,
  )

  class Factory(
    private val controller: CameraEngineController,
    private val preferencesRepository: CameraPreferencesRepository,
    private val mediaStoreRepository: MediaStoreRepository,
    private val horizonMonitor: HorizonSensorMonitor,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return CameraViewModel(
        controller = controller,
        preferencesRepository = preferencesRepository,
        mediaStoreRepository = mediaStoreRepository,
        horizonMonitor = horizonMonitor,
      ) as T
    }
  }
}
