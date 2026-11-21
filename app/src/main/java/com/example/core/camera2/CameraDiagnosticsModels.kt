package com.example.core.camera2

enum class LensFacingType(val label: String) {
  BACK("Rear"),
  FRONT("Front"),
  EXTERNAL("External"),
  UNKNOWN("Unknown"),
}

enum class CameraClassification(val label: String) {
  LOGICAL_MULTI_CAMERA("Logical Multi-Camera"),
  STANDALONE_PHYSICAL("Standalone Physical"),
  PHYSICAL_SUB_CAMERA("Physical Sub-Camera (Hidden from cameraIdList)"),
  PHYSICAL_PUBLIC_SUB_CAMERA("Physical Sub-Camera (Public ID)"),
}

enum class HardwareSupportLevel(val label: String) {
  LEVEL_3("LEVEL_3"),
  FULL("FULL"),
  LIMITED("LIMITED"),
  LEGACY("LEGACY"),
  EXTERNAL("EXTERNAL"),
  UNKNOWN("UNKNOWN"),
}

sealed interface AccessibilityStatus {
  val label: String

  data object NotProbedYet : AccessibilityStatus {
    override val label: String = "Characteristics Read (Open Probe Pending)"
  }

  data object PermissionRequired : AccessibilityStatus {
    override val label: String = "Permission Required for Open Probe"
  }

  data class AccessibleDirect(val viaCameraX: Boolean) : AccessibilityStatus {
    override val label: String =
      if (viaCameraX) "Accessible (Camera2 + CameraX)" else "Accessible (Camera2 Direct)"
  }

  data class AccessibleViaPhysicalStream(val parentLogicalId: String) : AccessibilityStatus {
    override val label: String = "Accessible via Logical Camera $parentLogicalId Physical Stream"
  }

  data class RestrictedOrFailed(val reason: String) : AccessibilityStatus {
    override val label: String = "Inaccessible / Restricted"
  }
}

data class SizeInt(val width: Int, val height: Int) {
  val megapixels: Double
    get() = (width.toLong() * height.toLong()) / 1_000_000.0

  fun formatted(): String = "${width}x${height} (${String.format("%.1f", megapixels)}MP)"
}

data class SizeFloat(val widthMm: Float, val heightMm: Float) {
  fun formatted(): String =
    "${String.format("%.2f", widthMm)} x ${String.format("%.2f", heightMm)} mm"
}

data class FpsRangeData(val minFps: Int, val maxFps: Int) {
  fun formatted(): String = if (minFps == maxFps) "${maxFps}fps" else "${minFps}-${maxFps}fps"
}

data class HighSpeedVideoConfig(val width: Int, val height: Int, val fpsRanges: List<FpsRangeData>) {
  fun formatted(): String =
    "${width}x${height} @ ${fpsRanges.joinToString(", ") { it.formatted() }}"
}

data class VideoQualityReport(
  val qualityLabel: String,
  val width: Int,
  val height: Int,
  val frameRates: List<Int>,
  val source: String,
  val supportsHdr10Bit: Boolean = false,
) {
  fun formatted(): String {
    val fpsPart = if (frameRates.isNotEmpty()) frameRates.joinToString("/") + "fps" else "variable"
    val hdrTag = if (supportsHdr10Bit) " [10-bit HDR]" else ""
    return "$qualityLabel (${width}x${height}) @ $fpsPart$hdrTag — $source"
  }
}

data class ExtensionSupportReport(val extensionName: String, val isAvailable: Boolean)

data class ManualExposureCapabilities(
  val hasManualSensorCapability: Boolean,
  val isoRange: IntRange?,
  val exposureTimeRangeNs: LongRange?,
  val evRange: IntRange?,
  val evStep: Float?,
)

data class RawCapabilities(
  val hasRawCapability: Boolean,
  val rawOutputSizes: List<SizeInt>,
)

data class HdrCapabilities(
  val supportsSceneModeHdr: Boolean,
  val dynamicRangeProfiles: List<String>,
)

data class CameraDeviceReport(
  val cameraId: String,
  val inPublicCameraIdList: Boolean,
  val inferredName: String,
  val lensFacing: LensFacingType,
  val classification: CameraClassification,
  val physicalCameraIds: List<String>,
  val parentLogicalIds: List<String>,
  val focalLengthsMm: List<Float>,
  val equivalentFocalLength35mm: Float?,
  val sensorPhysicalSizeMm: SizeFloat?,
  val pixelArraySize: SizeInt?,
  val activeArraySize: String?,
  val apertures: List<Float>,
  val minimumFocusDistanceDiopters: Float?,
  val estimatedMinFocusDistanceCm: Float?,
  val hardwareLevel: HardwareSupportLevel,
  val zoomRatioRange: ClosedFloatingPointRange<Float>?,
  val maxDigitalZoom: Float?,
  val oisModes: List<String>,
  val eisModes: List<String>,
  val afModes: List<String>,
  val aeModes: List<String>,
  val awbModes: List<String>,
  val flashSupported: Boolean,
  val manualExposure: ManualExposureCapabilities,
  val rawCapabilities: RawCapabilities,
  val hdrCapabilities: HdrCapabilities,
  val jpegOutputSizes: List<SizeInt>,
  val yuvOutputSizes: List<SizeInt>,
  val maxResolutionOutputSizes: List<SizeInt>,
  val videoOutputSizes: List<SizeInt>,
  val supportedFpsRanges: List<FpsRangeData>,
  val highSpeedConfigs: List<HighSpeedVideoConfig>,
  val videoQualities: List<VideoQualityReport>,
  val cameraXExtensions: List<ExtensionSupportReport>,
  val requestedCapabilities: List<String>,
  val accessibilityStatus: AccessibilityStatus,
)

data class ZoomProbeSample(
  val requestedZoomRatio: Float,
  val reportedZoomRatio: Float?,
  val activePhysicalCameraId: String?,
  val reportedFocalLengthMm: Float?,
  val cropRegion: String?,
  val statusMessage: String,
)

data class ConcurrentCameraGroup(val cameraIds: List<String>)

data class HardwareDiagnosticsReport(
  val generatedAtIso: String,
  val deviceInfo: com.example.core.common.DeviceEnvironmentInfo,
  val hasCameraPermission: Boolean,
  val activeProbeExecuted: Boolean,
  val publicCameraIds: List<String>,
  val allDiscoveredCameraIds: List<String>,
  val cameras: List<CameraDeviceReport>,
  val concurrentCameraGroups: List<ConcurrentCameraGroup>,
  val zoomProbeLogicalCameraId: String?,
  val zoomProbeSamples: List<ZoomProbeSample>,
  val warnings: List<String>,
  val probeLogs: List<String> = emptyList(),
)
