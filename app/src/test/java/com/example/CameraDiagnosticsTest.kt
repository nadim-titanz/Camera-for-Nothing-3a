package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.core.camera.CameraCapabilitiesRepository
import com.example.core.camera2.AccessibilityStatus
import com.example.core.camera2.CameraCharacteristicsMapper
import com.example.core.camera2.CameraClassification
import com.example.core.camera2.CameraDeviceReport
import com.example.core.camera2.CompatibilityMarkdownGenerator
import com.example.core.camera2.FpsRangeData
import com.example.core.camera2.HardwareDiagnosticsReport
import com.example.core.camera2.HardwareSupportLevel
import com.example.core.camera2.HdrCapabilities
import com.example.core.camera2.LensFacingType
import com.example.core.camera2.ManualExposureCapabilities
import com.example.core.camera2.RawCapabilities
import com.example.core.camera2.SizeFloat
import com.example.core.camera2.SizeInt
import com.example.core.camera2.ZoomProbeSample
import com.example.core.common.DeviceEnvironmentInfo
import com.example.feature.diagnostics.DiagnosticsScreen
import com.example.feature.diagnostics.DiagnosticsViewModel
import com.example.ui.theme.NothingCameraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraDiagnosticsTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `compute 35mm equivalent focal length for typical smartphone sensors`() {
    // Typical 1/1.57" sensor ~8.16 x 6.12 mm (diag ~10.2mm), 5.65mm physical -> ~24mm eq
    val mainEq =
      CameraCharacteristicsMapper.compute35mmEquivalentFocalLength(
        focalLengthsMm = listOf(5.65f),
        sensorSizeMm = SizeFloat(8.16f, 6.12f),
      )!!
    assertEquals(24.0f, mainEq, 1.0f)

    val displayName =
      CameraCharacteristicsMapper.inferCameraDisplayName(
        cameraId = "2",
        facing = LensFacingType.BACK,
        classification = CameraClassification.PHYSICAL_SUB_CAMERA,
        eq35mm = 15.0f,
        physicalCount = 0,
      )
    assertTrue(displayName.contains("Ultra-Wide"))
  }

  @Test
  fun `markdown generator includes camera table and zoom probe`() {
    val report = sampleReport()
    val md = CompatibilityMarkdownGenerator.generate(report)
    assertTrue(md.contains("# Nothing Camera Pro — Hardware Compatibility Report"))
    assertTrue(md.contains("`0`"))
    assertTrue(md.contains("`0.60x`"))
    assertTrue(md.contains("`3`"))
  }

  @Test
  fun `diagnostics screen renders cameras and switches tabs`() {
    val fakeRepo = FakeCapabilitiesRepository(sampleReport())
    val vm = DiagnosticsViewModel(fakeRepo)

    composeRule.setContent {
      NothingCameraTheme { DiagnosticsScreen(viewModel = vm) }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("cameras_list")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("camera_card_0"))
    composeRule.onNodeWithTag("camera_card_0").assertIsDisplayed()

    composeRule.onNodeWithTag("tab_zoom_probe").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("zoom_probe_list").assertIsDisplayed()

    composeRule.onNodeWithTag("tab_markdown").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("markdown_preview").assertIsDisplayed()
  }

  @Test
  fun `permission denied shows clear error card and settings action`() {
    val fakeRepo = FakeCapabilitiesRepository(sampleReport())
    val vm = DiagnosticsViewModel(fakeRepo)

    composeRule.setContent {
      NothingCameraTheme { DiagnosticsScreen(viewModel = vm) }
    }
    composeRule.waitForIdle()

    vm.onPermissionResult(granted = false)
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("cameras_list")
      .performScrollToNode(hasTestTag("permission_denied_error"))
    composeRule.onNodeWithTag("permission_denied_error").assertIsDisplayed()
    composeRule.onNodeWithTag("open_app_settings_button").assertIsDisplayed()
  }

  @Test
  fun `camera device error codes map to readable names`() {
    assertEquals(
      "ERROR_CAMERA_IN_USE",
      com.example.core.camera2.Camera2DiagnosticsCollector.cameraDeviceErrorName(
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
      ),
    )
    assertEquals(
      "ERROR_CAMERA_SERVICE",
      com.example.core.camera2.Camera2DiagnosticsCollector.cameraDeviceErrorName(
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_SERVICE
      ),
    )
  }

  private class FakeCapabilitiesRepository(private val report: HardwareDiagnosticsReport) :
    CameraCapabilitiesRepository {
    override val latestReport: StateFlow<HardwareDiagnosticsReport?> = MutableStateFlow(report)
    override val latestMarkdown: StateFlow<String> =
      MutableStateFlow(CompatibilityMarkdownGenerator.generate(report))

    override suspend fun refreshDiagnostics(
      hasCameraPermission: Boolean,
      runActiveProbe: Boolean,
    ): HardwareDiagnosticsReport = report
  }

  private fun sampleReport(): HardwareDiagnosticsReport {
    val cam0 =
      CameraDeviceReport(
        cameraId = "0",
        inPublicCameraIdList = true,
        inferredName = "Rear Logical Multi-Cam (3 sub-sensors)",
        lensFacing = LensFacingType.BACK,
        classification = CameraClassification.LOGICAL_MULTI_CAMERA,
        physicalCameraIds = listOf("2", "3", "4"),
        parentLogicalIds = emptyList(),
        focalLengthsMm = listOf(5.65f),
        equivalentFocalLength35mm = 24f,
        sensorPhysicalSizeMm = SizeFloat(8.16f, 6.12f),
        pixelArraySize = SizeInt(4080, 3060),
        activeArraySize = "[0,0,4080,3060]",
        apertures = listOf(1.88f),
        minimumFocusDistanceDiopters = 10f,
        estimatedMinFocusDistanceCm = 10f,
        hardwareLevel = HardwareSupportLevel.LEVEL_3,
        zoomRatioRange = 0.6f..10.0f,
        maxDigitalZoom = 10.0f,
        oisModes = listOf("OIS_ON"),
        eisModes = listOf("EIS_ON"),
        afModes = listOf("CONTINUOUS_PICTURE"),
        aeModes = listOf("ON"),
        awbModes = listOf("AUTO"),
        flashSupported = true,
        manualExposure = ManualExposureCapabilities(true, 50..6400, 100_000L..30_000_000_000L, -12..12, 0.33f),
        rawCapabilities = RawCapabilities(true, listOf(SizeInt(4080, 3060))),
        hdrCapabilities = HdrCapabilities(true, listOf("STANDARD (8-bit)", "HLG10")),
        jpegOutputSizes = listOf(SizeInt(4080, 3060)),
        yuvOutputSizes = listOf(SizeInt(1920, 1080)),
        maxResolutionOutputSizes = emptyList(),
        videoOutputSizes = listOf(SizeInt(3840, 2160)),
        supportedFpsRanges = listOf(FpsRangeData(15, 30), FpsRangeData(30, 30)),
        highSpeedConfigs = emptyList(),
        videoQualities = emptyList(),
        cameraXExtensions = emptyList(),
        requestedCapabilities = listOf("LOGICAL_MULTI_CAMERA", "RAW"),
        accessibilityStatus = AccessibilityStatus.AccessibleDirect(viaCameraX = true),
      )

    return HardwareDiagnosticsReport(
      generatedAtIso = "2026-11-19T00:00:00Z",
      deviceInfo =
        DeviceEnvironmentInfo(
          manufacturer = "Nothing",
          model = "A059",
          device = "asteroids",
          brand = "Nothing",
          androidRelease = "15",
          sdkInt = 35,
          buildDisplay = "Nothing OS 3.1",
          hardware = "qcom",
          board = "volcano",
        ),
      hasCameraPermission = true,
      activeProbeExecuted = true,
      publicCameraIds = listOf("0", "1"),
      allDiscoveredCameraIds = listOf("0", "1", "2", "3", "4"),
      cameras = listOf(cam0),
      concurrentCameraGroups = emptyList(),
      zoomProbeLogicalCameraId = "0",
      zoomProbeSamples =
        listOf(
          ZoomProbeSample(0.6f, 0.6f, "3", 2.2f, "[0,0,3264,2448]", "Captured"),
          ZoomProbeSample(1.0f, 1.0f, "2", 5.65f, "[0,0,4080,3060]", "Captured"),
        ),
      warnings = emptyList(),
    )
  }
}
