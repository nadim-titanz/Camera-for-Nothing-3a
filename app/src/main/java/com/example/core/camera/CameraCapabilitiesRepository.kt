package com.example.core.camera

import com.example.core.camera2.Camera2DiagnosticsCollector
import com.example.core.camera2.CompatibilityMarkdownGenerator
import com.example.core.camera2.HardwareDiagnosticsReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface CameraCapabilitiesRepository {
  val latestReport: StateFlow<HardwareDiagnosticsReport?>
  val latestMarkdown: StateFlow<String>

  suspend fun refreshDiagnostics(
    hasCameraPermission: Boolean,
    runActiveProbe: Boolean,
  ): HardwareDiagnosticsReport
}

class DefaultCameraCapabilitiesRepository(
  private val collector: Camera2DiagnosticsCollector,
) : CameraCapabilitiesRepository {

  private val _latestReport = MutableStateFlow<HardwareDiagnosticsReport?>(null)
  override val latestReport: StateFlow<HardwareDiagnosticsReport?> = _latestReport.asStateFlow()

  private val _latestMarkdown = MutableStateFlow("")
  override val latestMarkdown: StateFlow<String> = _latestMarkdown.asStateFlow()

  override suspend fun refreshDiagnostics(
    hasCameraPermission: Boolean,
    runActiveProbe: Boolean,
  ): HardwareDiagnosticsReport {
    val report =
      collector.collectDiagnostics(
        hasCameraPermission = hasCameraPermission,
        runActiveProbe = runActiveProbe,
      )
    _latestReport.value = report
    _latestMarkdown.value = CompatibilityMarkdownGenerator.generate(report)
    return report
  }
}
