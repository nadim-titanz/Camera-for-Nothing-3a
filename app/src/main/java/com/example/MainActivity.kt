package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.core.camera.CameraEngineController
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.camera2.Camera2DiagnosticsCollector
import com.example.core.common.HorizonSensorMonitor
import com.example.core.settings.CameraPreferencesRepository
import com.example.core.storage.MediaStoreRepository
import com.example.feature.camera.CameraScreen
import com.example.feature.camera.CameraViewModel
import com.example.feature.diagnostics.DiagnosticsScreen
import com.example.feature.diagnostics.DiagnosticsViewModel
import com.example.feature.gallery.GalleryScreen
import com.example.feature.settings.SettingsScreen
import com.example.ui.theme.NothingCameraTheme

object AppRoutes {
  const val ROUTE_CAMERA = "camera"
  const val ROUTE_GALLERY = "gallery"
  const val ROUTE_SETTINGS = "settings"
  const val ROUTE_DIAGNOSTICS = "diagnostics"
}

class MainActivity : ComponentActivity() {

  private val capabilitiesRepository by lazy {
    DefaultCameraCapabilitiesRepository(Camera2DiagnosticsCollector(applicationContext))
  }

  private val preferencesRepository by lazy {
    CameraPreferencesRepository(applicationContext)
  }

  private val mediaStoreRepository by lazy {
    MediaStoreRepository(applicationContext)
  }

  private val horizonMonitor by lazy {
    HorizonSensorMonitor(applicationContext)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      NothingCameraTheme {
        val controller = remember { CameraEngineController(applicationContext, mediaStoreRepository) }
        val cameraViewModel: CameraViewModel =
          viewModel(
            factory =
              CameraViewModel.Factory(
                controller = controller,
                preferencesRepository = preferencesRepository,
                mediaStoreRepository = mediaStoreRepository,
                horizonMonitor = horizonMonitor,
              )
          )
        NothingCameraNavHost(
          cameraViewModel = cameraViewModel,
          preferencesRepository = preferencesRepository,
          mediaStoreRepository = mediaStoreRepository,
          capabilitiesRepository = capabilitiesRepository,
        )
      }
    }
  }
}

@Composable
fun NothingCameraNavHost(
  cameraViewModel: CameraViewModel,
  preferencesRepository: CameraPreferencesRepository,
  mediaStoreRepository: MediaStoreRepository,
  capabilitiesRepository: com.example.core.camera.CameraCapabilitiesRepository,
  navController: NavHostController = rememberNavController(),
) {
  val cameraUiState by cameraViewModel.uiState.collectAsStateWithLifecycle()

  NavHost(
    navController = navController,
    startDestination = AppRoutes.ROUTE_CAMERA,
  ) {
    composable(AppRoutes.ROUTE_CAMERA) {
      CameraScreen(
        viewModel = cameraViewModel,
        onOpenGallery = { navController.navigate(AppRoutes.ROUTE_GALLERY) },
        onOpenSettings = { navController.navigate(AppRoutes.ROUTE_SETTINGS) },
      )
    }

    composable(AppRoutes.ROUTE_GALLERY) {
      GalleryScreen(
        mediaStoreRepository = mediaStoreRepository,
        onNavigateBack = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_SETTINGS) {
      SettingsScreen(
        preferencesRepository = preferencesRepository,
        supportsRawCapture = cameraUiState.capabilities.supportsRaw,
        onNavigateBack = { navController.popBackStack() },
        onOpenCameraDiagnostics = { navController.navigate(AppRoutes.ROUTE_DIAGNOSTICS) },
      )
    }

    composable(AppRoutes.ROUTE_DIAGNOSTICS) {
      val diagnosticsViewModel: DiagnosticsViewModel =
        viewModel(factory = DiagnosticsViewModel.Factory(capabilitiesRepository))
      DiagnosticsScreen(
        viewModel = diagnosticsViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
