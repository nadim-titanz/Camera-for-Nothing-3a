package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraCapabilitiesRepository
import com.example.core.camera.CameraEngineController
import com.example.core.camera2.CompatibilityMarkdownGenerator
import com.example.core.camera2.HardwareDiagnosticsReport
import com.example.core.common.DeviceEnvironmentInfo
import com.example.core.common.HorizonSensorMonitor
import com.example.core.imageprocessing.PhotographicStyleBitmapProcessor
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.settings.CameraPreferencesRepository
import com.example.core.storage.MediaStoreRepository
import com.example.feature.camera.CameraMode
import com.example.feature.camera.CameraViewModel
import com.example.ui.theme.NothingCameraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraAppTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `photographic styles apply distinct color and monochrome transforms`() {
    val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
      setPixel(0, 0, Color.rgb(200, 140, 110)) // skin-like warm tone
      setPixel(1, 1, Color.rgb(40, 160, 80))   // foliage green
      setPixel(2, 2, Color.rgb(10, 10, 12))    // deep shadow
      setPixel(3, 3, Color.rgb(230, 230, 230)) // highlight
    }

    val natural =
      PhotographicStyleBitmapProcessor.applyStyle(src, PhotographicStyleCatalog.Natural, 1.0f)
    assertEquals(src.getPixel(1, 1), natural.getPixel(1, 1))

    val bw =
      PhotographicStyleBitmapProcessor.applyStyle(src, PhotographicStyleCatalog.BlackAndWhite, 1.0f)
    val bwPixel = bw.getPixel(1, 1)
    assertEquals(Color.red(bwPixel), Color.green(bwPixel))
    assertEquals(Color.green(bwPixel), Color.blue(bwPixel))

    val matte =
      PhotographicStyleBitmapProcessor.applyStyle(src, PhotographicStyleCatalog.Matte, 1.0f)
    // Black lift raises deep shadow pixel above 10
    assertTrue(Color.red(matte.getPixel(2, 2)) > 15)

    val vivid =
      PhotographicStyleBitmapProcessor.applyStyle(src, PhotographicStyleCatalog.Vivid, 1.0f)
    assertNotEquals(src.getPixel(1, 1), vivid.getPixel(1, 1))
  }

  @Test
  fun `app launches camera screen and navigates through settings to developer diagnostics`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mediaRepo = MediaStoreRepository(context)
    val prefsRepo = CameraPreferencesRepository(context)
    val controller = CameraEngineController(context, mediaRepo)
    val horizon = HorizonSensorMonitor(context)
    val cameraVm = CameraViewModel(controller, prefsRepo, mediaRepo, horizon)
    val capsRepo = FakeCapabilitiesRepo()

    composeRule.setContent {
      NothingCameraTheme {
        NothingCameraNavHost(
          cameraViewModel = cameraVm,
          preferencesRepository = prefsRepo,
          mediaStoreRepository = mediaRepo,
          capabilitiesRepository = capsRepo,
        )
      }
    }
    composeRule.waitForIdle()

    // Launch screen is Camera, NOT Diagnostics
    composeRule.onNodeWithTag("shutter_button").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_tab_photo").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_tab_video").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_tab_portrait").assertIsDisplayed()
    composeRule.onNodeWithTag("gallery_thumbnail_button").assertIsDisplayed()

    // Open Photographic Styles drawer & pick Vivid
    composeRule.onNodeWithTag("styles_toggle_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("styles_drawer").assertIsDisplayed()
    composeRule.onNodeWithTag("style_chip_vivid").performClick()
    composeRule.waitForIdle()
    assertEquals("vivid", cameraVm.uiState.value.activeStyle.id)

    // Switch mode to Video and Portrait
    composeRule.onNodeWithTag("mode_tab_video").performClick()
    composeRule.waitForIdle()
    assertEquals(CameraMode.VIDEO, cameraVm.uiState.value.mode)

    composeRule.onNodeWithTag("mode_tab_portrait").performClick()
    composeRule.waitForIdle()
    assertEquals(CameraMode.PORTRAIT, cameraVm.uiState.value.mode)

    // Navigate Camera -> Settings -> Developer Tools -> Camera Diagnostics
    composeRule.onNodeWithTag("camera_settings_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("open_camera_diagnostics_row").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("diagnostics_back_button").assertIsDisplayed()

    // Navigate back Diagnostics -> Settings -> Camera
    composeRule.onNodeWithTag("diagnostics_back_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_back_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("shutter_button").assertIsDisplayed()

    // Navigate Camera -> Gallery -> Back
    composeRule.onNodeWithTag("gallery_thumbnail_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("gallery_back_button").assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("shutter_button").assertIsDisplayed()
  }

  private class FakeCapabilitiesRepo : CameraCapabilitiesRepository {
    private val emptyReport =
      HardwareDiagnosticsReport(
        generatedAtIso = "2026-11-19T00:00:00Z",
        deviceInfo =
          DeviceEnvironmentInfo(
            manufacturer = "Nothing",
            model = "A059",
            device = "asteroids",
            brand = "Nothing",
            androidRelease = "17",
            sdkInt = 37,
            buildDisplay = "Nothing OS",
            hardware = "qcom",
            board = "volcano",
          ),
        hasCameraPermission = false,
        activeProbeExecuted = false,
        publicCameraIds = listOf("0", "1"),
        allDiscoveredCameraIds = listOf("0", "1"),
        cameras = emptyList(),
        concurrentCameraGroups = emptyList(),
        zoomProbeLogicalCameraId = null,
        zoomProbeSamples = emptyList(),
        warnings = emptyList(),
      )

    override val latestReport: StateFlow<HardwareDiagnosticsReport?> = MutableStateFlow(emptyReport)
    override val latestMarkdown: StateFlow<String> =
      MutableStateFlow(CompatibilityMarkdownGenerator.generate(emptyReport))

    override suspend fun refreshDiagnostics(
      hasCameraPermission: Boolean,
      runActiveProbe: Boolean,
    ): HardwareDiagnosticsReport = emptyReport
  }
}
