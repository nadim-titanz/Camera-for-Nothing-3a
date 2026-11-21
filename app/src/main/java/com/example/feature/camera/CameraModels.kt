package com.example.feature.camera

import android.net.Uri
import com.example.core.imageprocessing.PhotographicStyle
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.settings.CameraAspectRatio
import com.example.core.settings.CameraFlashMode
import com.example.core.settings.CameraSettingsState
import com.example.core.settings.CaptureTimer
import com.example.core.settings.VideoResolutionOption

enum class CameraMode(val label: String) {
  PORTRAIT("PORTRAIT"),
  PHOTO("PHOTO"),
  VIDEO("VIDEO"),
}

data class ZoomPreset(
  val ratio: Float,
  val label: String,
)

data class SupportedVideoOption(
  val resolution: VideoResolutionOption,
  val supportedFps: List<Int>,
)

data class LiveCameraCapabilities(
  val minZoom: Float = 1.0f,
  val maxZoom: Float = 4.0f,
  val zoomPresets: List<ZoomPreset> = listOf(ZoomPreset(1.0f, "1x"), ZoomPreset(2.0f, "2x")),
  val hasFlash: Boolean = false,
  val exposureRange: IntRange = 0..0,
  val exposureStep: Float = 0.33f,
  val supportsRaw: Boolean = false,
  val supportsHardwareBokeh: Boolean = false,
  val supportsPreviewStabilization: Boolean = false,
  val supportsVideoStabilization: Boolean = false,
  val supportedVideoOptions: List<SupportedVideoOption> =
    listOf(SupportedVideoOption(VideoResolutionOption.FHD_1080P, listOf(30))),
  val activeCameraId: String = "0",
  val physicalCameraIds: Set<String> = emptySet(),
)

data class LiveLensTelemetry(
  val logicalCameraId: String = "0",
  val activePhysicalId: String? = null,
  val focalLengthMm: Float? = null,
  val zoomRatio: Float = 1.0f,
)

data class FocusIndicatorState(
  val xFraction: Float = 0.5f,
  val yFraction: Float = 0.5f,
  val isLocked: Boolean = false,
  val isVisible: Boolean = false,
  val triggerId: Long = 0L,
)

data class VideoRecordingUiState(
  val isRecording: Boolean = false,
  val isPaused: Boolean = false,
  val durationMs: Long = 0L,
)

data class CameraUiState(
  val mode: CameraMode = CameraMode.PHOTO,
  val isFrontCamera: Boolean = false,
  val flashMode: CameraFlashMode = CameraFlashMode.AUTO,
  val torchEnabled: Boolean = false,
  val captureTimer: CaptureTimer = CaptureTimer.OFF,
  val countdownRemainingSec: Int = 0,
  val isCapturing: Boolean = false,
  val showShutterFlash: Boolean = false,
  val zoomRatio: Float = 1.0f,
  val exposureIndex: Int = 0,
  val portraitBlurStrength: Float = 0.65f, // maps to f/2.8 feel
  val portraitSubjectDetected: Boolean = false,
  val focusIndicator: FocusIndicatorState = FocusIndicatorState(),
  val capabilities: LiveCameraCapabilities = LiveCameraCapabilities(),
  val telemetry: LiveLensTelemetry = LiveLensTelemetry(),
  val recordingState: VideoRecordingUiState = VideoRecordingUiState(),
  val settings: CameraSettingsState = CameraSettingsState(),
  val activeStyle: PhotographicStyle = PhotographicStyleCatalog.Natural,
  val latestMediaUri: Uri? = null,
  val latestMediaIsVideo: Boolean = false,
  val tiltDegrees: Float = 0f,
  val isStylesSheetOpen: Boolean = false,
  val cameraPermissionGranted: Boolean = false,
  val audioPermissionGranted: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)
