package com.example.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.settings.CameraPreferencesRepository
import com.example.core.settings.CameraSettingsState
import com.example.core.settings.GridMode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingGrey
import com.example.ui.theme.NothingObsidian
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingSurface
import com.example.ui.theme.NothingSurfaceElevated
import com.example.ui.theme.NothingWhite
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  preferencesRepository: CameraPreferencesRepository,
  supportsRawCapture: Boolean,
  onNavigateBack: () -> Unit,
  onOpenCameraDiagnostics: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onNavigateBack)
  val settings by preferencesRepository.settingsFlow.collectAsStateWithLifecycle(
    initialValue = CameraSettingsState()
  )
  val scope = rememberCoroutineScope()

  Scaffold(
    containerColor = NothingObsidian,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "CAMERA SETTINGS",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = NothingWhite,
          )
        },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("settings_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to camera",
              tint = NothingWhite,
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NothingObsidian),
      )
    },
    modifier = modifier.fillMaxSize(),
  ) { innerPadding ->
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.fillMaxSize()
          .background(NothingObsidian)
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
      ) {
        SectionHeader("COMPOSITION")
        Surface(
          color = NothingSurface,
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, NothingBorder),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "Framing Grid",
              style = MaterialTheme.typography.titleMedium,
              color = NothingWhite,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              GridMode.entries.forEach { mode ->
                val selected = settings.gridMode == mode
                FilterChip(
                  selected = selected,
                  onClick = { scope.launch { preferencesRepository.setGridMode(mode) } },
                  label = {
                    Text(
                      text = mode.label,
                      fontFamily = JetBrainsMonoFontFamily,
                    )
                  },
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = NothingWhite,
                      selectedLabelColor = NothingObsidian,
                      containerColor = NothingSurfaceElevated,
                      labelColor = NothingWhite,
                    ),
                  modifier = Modifier.testTag("grid_mode_${mode.name.lowercase()}"),
                )
              }
            }
            HorizontalDivider(color = NothingBorder, modifier = Modifier.padding(vertical = 12.dp))
            SettingSwitchRow(
              title = "Horizon Level Indicator",
              subtitle = "Show gyroscopic tilt gauge in viewfinder",
              checked = settings.showLevelIndicator,
              testTag = "switch_horizon_level",
              onCheckedChange = { scope.launch { preferencesRepository.setShowLevel(it) } },
            )
          }
        }

        SectionHeader("PHOTOGRAPHY & CAPTURE")
        Surface(
          color = NothingSurface,
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, NothingBorder),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            SettingSwitchRow(
              title = "Save Original Copy",
              subtitle = "Keep unedited image when applying Styles or Portrait Blur",
              checked = settings.saveOriginalWithStyle,
              testTag = "switch_save_original",
              onCheckedChange = { scope.launch { preferencesRepository.setSaveOriginalWithStyle(it) } },
            )
            HorizontalDivider(color = NothingBorder, modifier = Modifier.padding(vertical = 12.dp))
            SettingSwitchRow(
              title = "Mirror Front Selfie",
              subtitle = "Flip captured front-camera photos horizontally",
              checked = settings.mirrorFrontSelfie,
              testTag = "switch_mirror_selfie",
              onCheckedChange = { scope.launch { preferencesRepository.setMirrorFrontSelfie(it) } },
            )
            HorizontalDivider(color = NothingBorder, modifier = Modifier.padding(vertical = 12.dp))
            SettingSwitchRow(
              title = "RAW (DNG + JPEG)",
              subtitle =
                if (supportsRawCapture) {
                  "Capture uncompressed DNG alongside JPEG"
                } else {
                  "Not exposed by active camera sensor"
                },
              checked = settings.captureRawWhenSupported && supportsRawCapture,
              enabled = supportsRawCapture,
              testTag = "switch_capture_raw",
              onCheckedChange = { scope.launch { preferencesRepository.setCaptureRaw(it) } },
            )
          }
        }

        SectionHeader("VIDEO & INTERACTION")
        Surface(
          color = NothingSurface,
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, NothingBorder),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            SettingSwitchRow(
              title = "Video Stabilization",
              subtitle = "Enable hardware preview and recording stabilization when available",
              checked = settings.videoStabilizationEnabled,
              testTag = "switch_video_stabilization",
              onCheckedChange = { scope.launch { preferencesRepository.setVideoStabilization(it) } },
            )
            HorizontalDivider(color = NothingBorder, modifier = Modifier.padding(vertical = 12.dp))
            SettingSwitchRow(
              title = "Haptic Feedback",
              subtitle = "Tactile ticks on shutter press, zoom stops, and AE/AF lock",
              checked = settings.hapticsEnabled,
              testTag = "switch_haptics",
              onCheckedChange = { scope.launch { preferencesRepository.setHapticsEnabled(it) } },
            )
          }
        }

        SectionHeader("DEVELOPER TOOLS")
        Surface(
          color = NothingSurface,
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, NothingBorder),
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .clickable(onClick = onOpenCameraDiagnostics)
              .testTag("open_camera_diagnostics_row"),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.BugReport,
              contentDescription = null,
              tint = NothingRed,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Camera Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                color = NothingWhite,
              )
              Text(
                text = "Inspect Camera2 IDs, logical zoom switching, video matrix & extensions",
                style = MaterialTheme.typography.bodyMedium,
                color = NothingGrey,
              )
            }
            Icon(
              imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = "Open Camera Diagnostics",
              tint = NothingGrey,
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }
}

@Composable
private fun SectionHeader(label: String) {
  Text(
    text = label,
    fontFamily = JetBrainsMonoFontFamily,
    style = MaterialTheme.typography.labelMedium,
    color = NothingGrey,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(horizontal = 4.dp),
  )
}

@Composable
private fun SettingSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  testTag: String,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) NothingWhite else NothingGrey,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = NothingGrey,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = NothingWhite,
          checkedTrackColor = NothingRed,
          uncheckedThumbColor = NothingGrey,
          uncheckedTrackColor = NothingSurfaceElevated,
        ),
      modifier = Modifier.testTag(testTag),
    )
  }
}
