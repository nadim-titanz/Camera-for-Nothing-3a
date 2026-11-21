package com.example.core.camera2

object CompatibilityMarkdownGenerator {

  fun generate(report: HardwareDiagnosticsReport): String {
    val sb = StringBuilder()
    sb.appendLine("# Nothing Camera Pro — Hardware Compatibility Report")
    sb.appendLine()
    sb.appendLine("- **Generated At:** `${report.generatedAtIso}`")
    sb.appendLine(
      "- **Device:** `${report.deviceInfo.manufacturer} ${report.deviceInfo.model}` (`${report.deviceInfo.device}`)"
    )
    sb.appendLine(
      "- **Android Version:** `Android ${report.deviceInfo.androidRelease} (SDK ${report.deviceInfo.sdkInt})`"
    )
    sb.appendLine("- **Build:** `${report.deviceInfo.buildDisplay}`")
    sb.appendLine("- **Hardware / Board:** `${report.deviceInfo.hardware}` / `${report.deviceInfo.board}`")
    sb.appendLine("- **CAMERA Permission Granted:** `${report.hasCameraPermission}`")
    sb.appendLine("- **Active Open & Zoom Probe Executed:** `${report.activeProbeExecuted}`")
    sb.appendLine("- **Public `cameraIdList`:** `${report.publicCameraIds.joinToString(", ").ifEmpty { "None" }}`")
    sb.appendLine(
      "- **All Discovered IDs (including hidden physical sub-IDs):** `${report.allDiscoveredCameraIds.joinToString(", ").ifEmpty { "None" }}`"
    )
    sb.appendLine()

    if (report.warnings.isNotEmpty()) {
      sb.appendLine("## Warnings & Observations")
      report.warnings.forEach { sb.appendLine("- $it") }
      sb.appendLine()
    }

    if (report.probeLogs.isNotEmpty()) {
      sb.appendLine("## Active Probe Event Log")
      report.probeLogs.forEach { sb.appendLine("- `$it`") }
      sb.appendLine()
    }

    sb.appendLine("## Summary Matrix")
    sb.appendLine()
    sb.appendLine(
      "| ID | Name (Inferred) | Facing | Classification | Focal (35mm eq) | HW Level | Max JPEG | RAW | OIS/EIS | Accessibility |"
    )
    sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
    report.cameras.forEach { cam ->
      val focal =
        cam.focalLengthsMm.joinToString("/") { "${String.format("%.2f", it)}mm" }.ifEmpty { "N/A" }
      val eq = cam.equivalentFocalLength35mm?.let { " (~${it.toInt()}mm)" } ?: ""
      val maxJpeg =
        cam.jpegOutputSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }?.formatted()
          ?: "N/A"
      val raw = if (cam.rawCapabilities.hasRawCapability) "Yes" else "No"
      val stab = buildString {
        if (cam.oisModes.any { it == "OIS_ON" }) append("OIS ")
        if (cam.eisModes.any { it != "OFF" }) append("EIS")
        if (isEmpty()) append("None")
      }.trim()
      sb.appendLine(
        "| `${cam.cameraId}` | ${cam.inferredName} | ${cam.lensFacing.label} | ${cam.classification.label} | $focal$eq | ${cam.hardwareLevel.label} | $maxJpeg | $raw | $stab | ${cam.accessibilityStatus.label} |"
      )
    }
    sb.appendLine()

    sb.appendLine("## Live Zoom Ratio -> Active Physical Camera Probe")
    sb.appendLine()
    if (report.zoomProbeSamples.isEmpty()) {
      sb.appendLine(
        "_Not executed yet. Grant CAMERA permission and run Full Hardware Probe on-device to verify which physical lens handles 0.5x, 0.6x, 1.0x, 2.0x, and higher zoom ratios._"
      )
    } else {
      sb.appendLine("Logical Camera Tested: **ID `${report.zoomProbeLogicalCameraId}`**")
      sb.appendLine()
      sb.appendLine("| Requested Zoom | Reported Zoom | Active Physical ID | Focal Length | Crop Region | Status |")
      sb.appendLine("|---|---|---|---|---|---|")
      report.zoomProbeSamples.forEach { sample ->
        sb.appendLine(
          "| `${String.format("%.2f", sample.requestedZoomRatio)}x` | `${sample.reportedZoomRatio?.let { String.format("%.2f", it) + "x" } ?: "N/A"}` | `${sample.activePhysicalCameraId ?: "N/A"}` | `${sample.reportedFocalLengthMm?.let { "${it}mm" } ?: "N/A"}` | `${sample.cropRegion ?: "N/A"}` | ${sample.statusMessage} |"
        )
      }
    }
    sb.appendLine()

    sb.appendLine("## Concurrent Camera Capabilities")
    sb.appendLine()
    if (report.concurrentCameraGroups.isEmpty()) {
      sb.appendLine("_None reported by CameraManager.concurrentCameraIds._")
    } else {
      report.concurrentCameraGroups.forEachIndexed { idx, group ->
        sb.appendLine("- Combination ${idx + 1}: `[${group.cameraIds.joinToString(", ")}]`")
      }
    }
    sb.appendLine()

    sb.appendLine("## Detailed Camera Specifications")
    sb.appendLine()
    report.cameras.forEach { cam ->
      sb.appendLine("### Camera ID `${cam.cameraId}` — ${cam.inferredName}")
      sb.appendLine("- **Public `cameraIdList`:** `${cam.inPublicCameraIdList}`")
      sb.appendLine("- **Facing:** `${cam.lensFacing.label}`")
      sb.appendLine("- **Classification:** `${cam.classification.label}`")
      if (cam.physicalCameraIds.isNotEmpty()) {
        sb.appendLine("- **Physical Sub-Cameras:** `${cam.physicalCameraIds.joinToString(", ")}`")
      }
      if (cam.parentLogicalIds.isNotEmpty()) {
        sb.appendLine("- **Parent Logical Cameras:** `${cam.parentLogicalIds.joinToString(", ")}`")
      }
      sb.appendLine("- **Hardware Level:** `${cam.hardwareLevel.label}`")
      sb.appendLine("- **Accessibility:** `${cam.accessibilityStatus.label}`")
      sb.appendLine(
        "- **Focal Lengths:** `${cam.focalLengthsMm.joinToString(", ") { "${it}mm" }.ifEmpty { "N/A" }}`" +
          (cam.equivalentFocalLength35mm?.let { " (~${String.format("%.1f", it)}mm 35mm eq)" } ?: "")
      )
      sb.appendLine("- **Sensor Physical Size:** `${cam.sensorPhysicalSizeMm?.formatted() ?: "N/A"}`")
      sb.appendLine("- **Pixel Array Size:** `${cam.pixelArraySize?.formatted() ?: "N/A"}`")
      sb.appendLine("- **Apertures:** `${cam.apertures.joinToString(", ") { "f/$it" }.ifEmpty { "N/A" }}`")
      sb.appendLine(
        "- **Min Focus Distance:** `${cam.minimumFocusDistanceDiopters?.let { "$it diopters (~${cam.estimatedMinFocusDistanceCm?.toInt()} cm)" } ?: "Fixed / N/A"}`"
      )
      sb.appendLine(
        "- **Zoom Ratio Range:** `${cam.zoomRatioRange?.let { "${String.format("%.2f", it.start)}x .. ${String.format("%.2f", it.endInclusive)}x" } ?: "Digital max ${cam.maxDigitalZoom}x"}`"
      )
      sb.appendLine("- **OIS Modes:** `${cam.oisModes.joinToString(", ").ifEmpty { "None" }}`")
      sb.appendLine("- **EIS Modes:** `${cam.eisModes.joinToString(", ").ifEmpty { "None" }}`")
      sb.appendLine("- **AF Modes:** `${cam.afModes.joinToString(", ").ifEmpty { "None" }}`")
      sb.appendLine(
        "- **Manual Exposure:** `MANUAL_SENSOR=${cam.manualExposure.hasManualSensorCapability}`, ISO `${cam.manualExposure.isoRange}`, Shutter(ns) `${cam.manualExposure.exposureTimeRangeNs}`, EV `${cam.manualExposure.evRange}` (step `${cam.manualExposure.evStep}`)"
      )
      sb.appendLine(
        "- **RAW Support:** `${cam.rawCapabilities.hasRawCapability}` (${cam.rawCapabilities.rawOutputSizes.joinToString(", ") { it.formatted() }.ifEmpty { "None" }})"
      )
      sb.appendLine(
        "- **HDR Capabilities:** SceneModeHDR=`${cam.hdrCapabilities.supportsSceneModeHdr}`, DynamicRangeProfiles=`${cam.hdrCapabilities.dynamicRangeProfiles.joinToString(", ").ifEmpty { "Standard 8-bit" }}`"
      )
      sb.appendLine(
        "- **AE FPS Ranges:** `${cam.supportedFpsRanges.joinToString(", ") { it.formatted() }.ifEmpty { "None" }}`"
      )
      sb.appendLine(
        "- **Top JPEG Resolutions:** `${cam.jpegOutputSizes.take(6).joinToString(", ") { it.formatted() }.ifEmpty { "None" }}`"
      )
      if (cam.maxResolutionOutputSizes.isNotEmpty()) {
        sb.appendLine(
          "- **Ultra High-Res Sensor Mode Sizes:** `${cam.maxResolutionOutputSizes.take(4).joinToString(", ") { it.formatted() }}`"
        )
      }
      if (cam.highSpeedConfigs.isNotEmpty()) {
        sb.appendLine(
          "- **High-Speed Video (Camera2):** ${cam.highSpeedConfigs.joinToString("; ") { it.formatted() }}"
        )
      }
      if (cam.videoQualities.isNotEmpty()) {
        sb.appendLine("- **Video Recording Configurations:**")
        cam.videoQualities.forEach { vq -> sb.appendLine("  - ${vq.formatted()}") }
      }
      if (cam.cameraXExtensions.isNotEmpty()) {
        sb.appendLine(
          "- **CameraX Extensions:** ${cam.cameraXExtensions.joinToString(", ") { "${it.extensionName}=${it.isAvailable}" }}"
        )
      }
      sb.appendLine(
        "- **Capabilities:** `${cam.requestedCapabilities.joinToString(", ").ifEmpty { "None" }}`"
      )
      sb.appendLine()
    }
    return sb.toString()
  }
}
