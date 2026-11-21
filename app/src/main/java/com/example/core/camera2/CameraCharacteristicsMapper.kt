package com.example.core.camera2

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import kotlin.math.hypot

object CameraCharacteristicsMapper {

  private const val FULL_FRAME_35MM_DIAGONAL_MM = 43.266615f

  fun mapLensFacing(facing: Int?): LensFacingType =
    when (facing) {
      CameraCharacteristics.LENS_FACING_BACK -> LensFacingType.BACK
      CameraCharacteristics.LENS_FACING_FRONT -> LensFacingType.FRONT
      CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacingType.EXTERNAL
      else -> LensFacingType.UNKNOWN
    }

  fun mapHardwareLevel(level: Int?): HardwareSupportLevel =
    when (level) {
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareSupportLevel.LEVEL_3
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareSupportLevel.FULL
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareSupportLevel.LIMITED
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareSupportLevel.LEGACY
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareSupportLevel.EXTERNAL
      else -> HardwareSupportLevel.UNKNOWN
    }

  fun compute35mmEquivalentFocalLength(
    focalLengthsMm: List<Float>,
    sensorSizeMm: SizeFloat?,
  ): Float? {
    val primaryFocal = focalLengthsMm.firstOrNull() ?: return null
    if (primaryFocal <= 0f || sensorSizeMm == null) return null
    val diagonal = hypot(sensorSizeMm.widthMm, sensorSizeMm.heightMm)
    if (diagonal <= 0f) return null
    return primaryFocal * (FULL_FRAME_35MM_DIAGONAL_MM / diagonal)
  }

  fun inferCameraDisplayName(
    cameraId: String,
    facing: LensFacingType,
    classification: CameraClassification,
    eq35mm: Float?,
    physicalCount: Int,
  ): String {
    val facingPrefix = facing.label
    if (classification == CameraClassification.LOGICAL_MULTI_CAMERA) {
      return "$facingPrefix Logical Multi-Cam (${physicalCount} sub-sensors)"
    }
    val lensHint =
      when {
        eq35mm == null -> "Sensor"
        eq35mm < 19f -> "Ultra-Wide (~${eq35mm.toInt()}mm eq)"
        eq35mm in 19f..33f -> if (facing == LensFacingType.FRONT) "Selfie (~${eq35mm.toInt()}mm eq)" else "Main Wide (~${eq35mm.toInt()}mm eq)"
        eq35mm > 33f -> "Telephoto (~${eq35mm.toInt()}mm eq)"
        else -> "Sensor"
      }
    return "$facingPrefix $lensHint [ID $cameraId]"
  }

  fun mapOisModes(modes: IntArray?): List<String> =
    modes?.map { mode ->
      when (mode) {
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "OIS_ON"
        else -> "UNKNOWN($mode)"
      }
    } ?: emptyList()

  fun mapEisModes(modes: IntArray?): List<String> =
    modes?.map { mode ->
      when (mode) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "EIS_ON"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STAB"
        else -> "UNKNOWN($mode)"
      }
    } ?: emptyList()

  fun mapAfModes(modes: IntArray?): List<String> =
    modes?.map { mode ->
      when (mode) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN($mode)"
      }
    } ?: emptyList()

  fun mapAeModes(modes: IntArray?): List<String> =
    modes?.map { mode ->
      when (mode) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF (Manual)"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "AUTO_FLASH_REDEYE"
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "EXTERNAL_FLASH"
        else -> "UNKNOWN($mode)"
      }
    } ?: emptyList()

  fun mapAwbModes(modes: IntArray?): List<String> =
    modes?.map { mode ->
      when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "UNKNOWN($mode)"
      }
    } ?: emptyList()

  fun mapCapabilities(caps: IntArray?): List<String> =
    caps?.map { cap ->
      when (cap) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROC"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RES_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "10_BIT_HDR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
        else -> "CAP_$cap"
      }
    } ?: emptyList()

  fun mapDynamicRangeProfiles(profiles: Set<Long>): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
    return profiles.map { profile ->
      when (profile) {
        1L -> "STANDARD (8-bit)"
        2L -> "HLG10"
        4L -> "HDR10"
        8L -> "HDR10_PLUS"
        16L -> "DOLBY_VISION_10B_HDR_REF"
        32L -> "DOLBY_VISION_10B_HDR_REF_PO"
        64L -> "DOLBY_VISION_10B_HDR_OEM"
        128L -> "DOLBY_VISION_10B_HDR_OEM_PO"
        256L -> "DOLBY_VISION_8B_HDR_REF"
        512L -> "DOLBY_VISION_8B_HDR_REF_PO"
        1024L -> "DOLBY_VISION_8B_HDR_OEM"
        2048L -> "DOLBY_VISION_8B_HDR_OEM_PO"
        else -> "PROFILE_$profile"
      }
    }
  }
}
