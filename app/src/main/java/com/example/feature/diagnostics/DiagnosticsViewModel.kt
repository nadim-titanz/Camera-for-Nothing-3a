package com.example.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.camera.CameraCapabilitiesRepository
import com.example.core.camera2.HardwareDiagnosticsReport
import com.example.core.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DiagnosticsTab {
  CAMERAS,
  ZOOM_PROBE,
  VIDEO_MATRIX,
  MARKDOWN,
}

data class DiagnosticsUiModel(
  val report: HardwareDiagnosticsReport,
  val markdown: String,
)

class DiagnosticsViewModel(
  private val repository: CameraCapabilitiesRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow<UiState<DiagnosticsUiModel>>(UiState.Loading)
  val uiState: StateFlow<UiState<DiagnosticsUiModel>> = _uiState.asStateFlow()

  private val _selectedTab = MutableStateFlow(DiagnosticsTab.CAMERAS)
  val selectedTab: StateFlow<DiagnosticsTab> = _selectedTab.asStateFlow()

  private val _isProbing = MutableStateFlow(false)
  val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

  private val _permissionDeniedError = MutableStateFlow(false)
  val permissionDeniedError: StateFlow<Boolean> = _permissionDeniedError.asStateFlow()

  fun selectTab(tab: DiagnosticsTab) {
    _selectedTab.value = tab
  }

  fun onPermissionResult(granted: Boolean) {
    _permissionDeniedError.value = !granted
    runScan(hasCameraPermission = granted, runActiveProbe = granted)
  }

  fun runScan(hasCameraPermission: Boolean, runActiveProbe: Boolean) {
    if (_isProbing.value) return
    if (hasCameraPermission) {
      _permissionDeniedError.value = false
    }
    viewModelScope.launch {
      _isProbing.value = true
      if (_uiState.value !is UiState.Success) {
        _uiState.value = UiState.Loading
      }
      try {
        val report =
          repository.refreshDiagnostics(
            hasCameraPermission = hasCameraPermission,
            runActiveProbe = runActiveProbe && hasCameraPermission,
          )
        val md = repository.latestMarkdown.value
        _uiState.value = UiState.Success(DiagnosticsUiModel(report = report, markdown = md))
      } catch (e: Exception) {
        _uiState.value =
          UiState.Error(
            message = e.message ?: "Failed to inspect camera hardware",
            cause = e,
          )
      } finally {
        _isProbing.value = false
      }
    }
  }

  class Factory(private val repository: CameraCapabilitiesRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return DiagnosticsViewModel(repository) as T
    }
  }
}
