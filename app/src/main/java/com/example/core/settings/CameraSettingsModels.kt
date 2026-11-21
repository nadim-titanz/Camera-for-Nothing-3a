package com.example.core.settings

enum class GridMode(val label: String) {
  OFF("OFF"),
  GRID_3X3("3x3"),
  GRID_4X4("4x4"),
}

enum class CameraAspectRatio(val label: String, val ratio: Float?) {
  RATIO_4_3("4:3", 3f / 4f),
  RATIO_16_9("16:9", 9f / 16f),
  RATIO_1_1("1:1", 1f),
  FULL("FULL", null),
}

enum class CameraFlashMode(val label: String) {
  AUTO("AUTO"),
  ON("ON"),
  OFF("OFF"),
}

enum class CaptureTimer(val seconds: Int, val label: String) {
  OFF(0, "OFF"),
  SEC_3(3, "3S"),
  SEC_5(5, "5S"),
  SEC_10(10, "10S"),
}

enum class VideoResolutionOption(val label: String) {
  UHD_4K("4K"),
  FHD_1080P("1080p"),
  HD_720P("720p"),
}

data class CameraSettingsState(
  val gridMode: GridMode = GridMode.GRID_3X3,
  val showLevelIndicator: Boolean = true,
  val aspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_4_3,
  val mirrorFrontSelfie: Boolean = true,
  val saveOriginalWithStyle: Boolean = true,
  val captureRawWhenSupported: Boolean = false,
  val videoStabilizationEnabled: Boolean = true,
  val hapticsEnabled: Boolean = true,
  val selectedStyleId: String = "natural",
  val styleIntensity: Float = 1.0f,
  val preferredVideoResolution: VideoResolutionOption = VideoResolutionOption.FHD_1080P,
  val preferredVideoFps: Int = 30,
)
