package com.example.feature.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.camera2.AccessibilityStatus
import com.example.core.camera2.CameraDeviceReport
import com.example.core.camera2.HardwareDiagnosticsReport
import com.example.core.camera2.ZoomProbeSample
import com.example.core.common.UiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingDimGrey
import com.example.ui.theme.NothingGrey
import com.example.ui.theme.NothingObsidian
import com.example.ui.theme.NothingOffWhite
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingRedContainer
import com.example.ui.theme.NothingRedText
import com.example.ui.theme.NothingSurface
import com.example.ui.theme.NothingSurfaceElevated
import com.example.ui.theme.NothingWhite
import com.example.ui.theme.StatusCautionAmber
import com.example.ui.theme.StatusCautionContainer
import com.example.ui.theme.StatusVerifiedContainer
import com.example.ui.theme.StatusVerifiedGreen
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(
  viewModel: DiagnosticsViewModel,
  modifier: Modifier = Modifier,
  onNavigateBack: (() -> Unit)? = null,
) {
  if (onNavigateBack != null) {
    BackHandler(onBack = onNavigateBack)
  }
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
  val isProbing by viewModel.isProbing.collectAsStateWithLifecycle()
  val permissionDeniedError by viewModel.permissionDeniedError.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current

  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  var hasRequestedOnLaunch by rememberSaveable { mutableStateOf(false) }

  val deniedMsg = stringResource(R.string.permission_denied_title)

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasCameraPermission = granted
      viewModel.onPermissionResult(granted)
      if (!granted) {
        scope.launch { snackbarHostState.showSnackbar(deniedMsg) }
      }
    }

  val openAppSettings: () -> Unit = {
    val intent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(intent)
  }

  androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        val currentGranted =
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (currentGranted && !hasCameraPermission) {
          hasCameraPermission = true
          viewModel.onPermissionResult(true)
        } else {
          hasCameraPermission = currentGranted
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val copiedMsg = stringResource(R.string.copied_snackbar)
  val exportedMsg = stringResource(R.string.exported_snackbar)

  val createDocumentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
      val model = (uiState as? UiState.Success)?.data ?: return@rememberLauncherForActivityResult
      if (uri != null) {
        try {
          context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(model.markdown.toByteArray(Charsets.UTF_8))
          }
          scope.launch { snackbarHostState.showSnackbar(exportedMsg) }
        } catch (e: Exception) {
          scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.message}") }
        }
      }
    }

  LaunchedEffect(Unit) {
    viewModel.runScan(hasCameraPermission = hasCameraPermission, runActiveProbe = hasCameraPermission)
    if (!hasCameraPermission && !hasRequestedOnLaunch) {
      hasRequestedOnLaunch = true
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = NothingObsidian,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
      ) {
        DiagnosticsTopHeader(
          isProbing = isProbing,
          hasCameraPermission = hasCameraPermission,
          canExport = uiState is UiState.Success,
          onNavigateBack = onNavigateBack,
          onRunProbe = {
            if (hasCameraPermission) {
              viewModel.runScan(hasCameraPermission = true, runActiveProbe = true)
            } else {
              permissionLauncher.launch(Manifest.permission.CAMERA)
            }
          },
          onRescan = {
            viewModel.runScan(hasCameraPermission = hasCameraPermission, runActiveProbe = false)
          },
          onCopyMarkdown = {
            val md = (uiState as? UiState.Success)?.data?.markdown ?: return@DiagnosticsTopHeader
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CAMERA_COMPATIBILITY.md", md))
            scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
          },
          onExportMarkdown = {
            createDocumentLauncher.launch("CAMERA_COMPATIBILITY.md")
          },
        )

        AnimatedVisibility(visible = isProbing) {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth().height(2.dp),
              color = NothingRed,
              trackColor = NothingSurfaceElevated,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.probe_running),
              style = MaterialTheme.typography.labelSmall,
              color = NothingRedText,
            )
          }
        }

        DiagnosticsTabBar(
          selectedTab = selectedTab,
          onSelectTab = viewModel::selectTab,
        )

        when (val state = uiState) {
          is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(color = NothingWhite)
            }
          }
          is UiState.Error -> {
            Box(
              modifier = Modifier.fillMaxSize().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = "Diagnostic error",
                  tint = NothingRed,
                  modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                  text = state.message,
                  style = MaterialTheme.typography.bodyLarge,
                  color = NothingWhite,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                  onClick = {
                    viewModel.runScan(hasCameraPermission, runActiveProbe = false)
                  },
                  modifier = Modifier.testTag("retry_scan_button").minimumInteractiveComponentSize(),
                ) {
                  Text(stringResource(R.string.action_rescan))
                }
              }
            }
          }
          is UiState.Success -> {
            val report = state.data.report
            when (selectedTab) {
              DiagnosticsTab.CAMERAS ->
                CamerasTabContent(
                  report = report,
                  hasCameraPermission = hasCameraPermission,
                  permissionDeniedError = permissionDeniedError,
                  onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                  onOpenSettings = openAppSettings,
                )
              DiagnosticsTab.ZOOM_PROBE -> ZoomProbeTabContent(report = report)
              DiagnosticsTab.VIDEO_MATRIX -> VideoMatrixTabContent(report = report)
              DiagnosticsTab.MARKDOWN -> MarkdownTabContent(markdown = state.data.markdown)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DiagnosticsTopHeader(
  isProbing: Boolean,
  hasCameraPermission: Boolean,
  canExport: Boolean,
  onNavigateBack: (() -> Unit)?,
  onRunProbe: () -> Unit,
  onRescan: () -> Unit,
  onCopyMarkdown: () -> Unit,
  onExportMarkdown: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(NothingSurface)
        .border(width = 1.dp, color = NothingBorder)
        .padding(16.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (onNavigateBack != null) {
        IconButton(
          onClick = onNavigateBack,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("diagnostics_back_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to settings",
            tint = NothingWhite,
          )
        }
        Spacer(modifier = Modifier.width(4.dp))
      }
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(NothingRed))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.headlineSmall,
            color = NothingWhite,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.diagnostics_subtitle),
          style = MaterialTheme.typography.labelSmall,
          color = NothingGrey,
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        onClick = onRunProbe,
        enabled = !isProbing,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = NothingRed,
            contentColor = NothingWhite,
          ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("run_full_probe_button").minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text =
            if (hasCameraPermission) stringResource(R.string.action_run_probe)
            else stringResource(R.string.action_grant_camera),
          style = MaterialTheme.typography.labelMedium,
        )
      }

      OutlinedButton(
        onClick = onRescan,
        enabled = !isProbing,
        border = BorderStroke(1.dp, NothingBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("rescan_button").minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.action_rescan), style = MaterialTheme.typography.labelMedium)
      }

      OutlinedButton(
        onClick = onCopyMarkdown,
        enabled = canExport,
        border = BorderStroke(1.dp, NothingBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingOffWhite),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("copy_md_button").minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Default.ContentCopy,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.action_copy_md), style = MaterialTheme.typography.labelMedium)
      }

      OutlinedButton(
        onClick = onExportMarkdown,
        enabled = canExport,
        border = BorderStroke(1.dp, NothingBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingOffWhite),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("export_md_button").minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Default.FileDownload,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.action_export_md), style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
private fun PermissionRequiredBanner(
  permissionDeniedError: Boolean,
  onGrantClick: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Surface(
    color = NothingRedContainer,
    border =
      BorderStroke(
        width = if (permissionDeniedError) 1.5.dp else 1.dp,
        color = NothingRed.copy(alpha = if (permissionDeniedError) 0.9f else 0.5f),
      ),
    shape = RoundedCornerShape(12.dp),
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag(if (permissionDeniedError) "permission_denied_error" else "permission_required_banner"),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = if (permissionDeniedError) Icons.Default.Warning else Icons.Outlined.Lock,
          contentDescription = "Camera permission status",
          tint = NothingRedText,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            stringResource(
              if (permissionDeniedError) R.string.permission_denied_title
              else R.string.permission_banner_title
            ),
          style = MaterialTheme.typography.labelMedium,
          color = NothingRedText,
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text =
          stringResource(
            if (permissionDeniedError) R.string.permission_denied_body
            else R.string.permission_banner_body
          ),
        style = MaterialTheme.typography.bodyMedium,
        color = NothingOffWhite,
      )
      Spacer(modifier = Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onGrantClick,
          colors = ButtonDefaults.buttonColors(containerColor = NothingWhite, contentColor = NothingObsidian),
          shape = RoundedCornerShape(20.dp),
          modifier = Modifier.testTag("grant_camera_permission_button").minimumInteractiveComponentSize(),
        ) {
          Text(
            text =
              stringResource(
                if (permissionDeniedError) R.string.action_retry_permission
                else R.string.action_grant_camera
              ),
            style = MaterialTheme.typography.labelMedium,
          )
        }
        if (permissionDeniedError) {
          OutlinedButton(
            onClick = onOpenSettings,
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.testTag("open_app_settings_button").minimumInteractiveComponentSize(),
          ) {
            Text(
              text = stringResource(R.string.action_open_settings),
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DiagnosticsTabBar(
  selectedTab: DiagnosticsTab,
  onSelectTab: (DiagnosticsTab) -> Unit,
) {
  val tabs =
    listOf(
      DiagnosticsTab.CAMERAS to stringResource(R.string.tab_cameras),
      DiagnosticsTab.ZOOM_PROBE to stringResource(R.string.tab_zoom_probe),
      DiagnosticsTab.VIDEO_MATRIX to stringResource(R.string.tab_video_matrix),
      DiagnosticsTab.MARKDOWN to stringResource(R.string.tab_markdown),
    )

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    tabs.forEach { (tab, label) ->
      val selected = tab == selectedTab
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) NothingWhite else NothingSurfaceElevated,
        border = BorderStroke(1.dp, if (selected) NothingWhite else NothingBorder),
        modifier =
          Modifier.testTag("tab_${tab.name.lowercase()}")
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onSelectTab(tab) },
      ) {
        Box(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NothingObsidian else NothingGrey,
          )
        }
      }
    }
  }
}

@Composable
private fun CamerasTabContent(
  report: HardwareDiagnosticsReport,
  hasCameraPermission: Boolean,
  permissionDeniedError: Boolean,
  onGrantPermission: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("cameras_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (!hasCameraPermission) {
      item {
        PermissionRequiredBanner(
          permissionDeniedError = permissionDeniedError,
          onGrantClick = onGrantPermission,
          onOpenSettings = onOpenSettings,
        )
      }
    }

    item {
      DeviceSummaryCard(report = report)
    }

    if (report.warnings.isNotEmpty()) {
      item {
        WarningsCard(warnings = report.warnings)
      }
    }

    if (report.cameras.isEmpty()) {
      item {
        Surface(
          color = NothingSurface,
          border = BorderStroke(1.dp, NothingBorder),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(
              imageVector = Icons.Default.CameraAlt,
              contentDescription = "No cameras found",
              tint = NothingGrey,
              modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.empty_cameras_title),
              style = MaterialTheme.typography.titleMedium,
              color = NothingWhite,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.empty_cameras_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = NothingGrey,
            )
          }
        }
      }
    } else {
      items(report.cameras, key = { it.cameraId }) { camera ->
        CameraDiagnosticCard(camera = camera)
      }
    }

    if (report.probeLogs.isNotEmpty()) {
      item {
        ProbeLogsCard(logs = report.probeLogs)
      }
    }
  }
}

@Composable
private fun ProbeLogsCard(logs: List<String>) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  Surface(
    color = NothingSurface,
    border = BorderStroke(1.dp, NothingBorder),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth().testTag("probe_logs_card"),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${stringResource(R.string.probe_log_title)} (${logs.size})",
          style = MaterialTheme.typography.labelMedium,
          color = NothingWhite,
        )
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (expanded) "Collapse probe logs" else "Expand probe logs",
          tint = NothingGrey,
        )
      }
      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier.padding(top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          logs.forEach { entry ->
            Text(
              text = entry,
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelSmall,
              color = NothingGrey,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSummaryCard(report: HardwareDiagnosticsReport) {
  Surface(
    color = NothingSurface,
    border = BorderStroke(1.dp, NothingBorder),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth().testTag("device_summary_card"),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "${report.deviceInfo.manufacturer} ${report.deviceInfo.model}".trim(),
        style = MaterialTheme.typography.titleMedium,
        color = NothingWhite,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text =
          "Android ${report.deviceInfo.androidRelease} (SDK ${report.deviceInfo.sdkInt}) • ${report.deviceInfo.hardware}",
        style = MaterialTheme.typography.labelSmall,
        color = NothingGrey,
      )
      Spacer(modifier = Modifier.height(12.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TelemetryChip("PUBLIC IDs: ${report.publicCameraIds.size}")
        TelemetryChip("TOTAL DISCOVERED: ${report.allDiscoveredCameraIds.size}")
        TelemetryChip("CONCURRENT SETS: ${report.concurrentCameraGroups.size}")
        TelemetryChip(
          label = if (report.activeProbeExecuted) "ACTIVE PROBE: DONE" else "PASSIVE SCAN",
          accent = report.activeProbeExecuted,
        )
      }
    }
  }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
  Surface(
    color = StatusCautionContainer,
    border = BorderStroke(1.dp, StatusCautionAmber.copy(alpha = 0.45f)),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "DIAGNOSTIC NOTES (${warnings.size})",
        style = MaterialTheme.typography.labelMedium,
        color = StatusCautionAmber,
      )
      warnings.forEach { w ->
        Text(
          text = "• $w",
          style = MaterialTheme.typography.bodyMedium,
          color = NothingOffWhite,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraDiagnosticCard(camera: CameraDeviceReport) {
  var expanded by rememberSaveable(camera.cameraId) { mutableStateOf(true) }

  Surface(
    color = NothingSurface,
    border = BorderStroke(1.dp, NothingBorder),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth().testTag("camera_card_${camera.cameraId}"),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { expanded = !expanded }
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
              color = NothingSurfaceElevated,
              shape = RoundedCornerShape(6.dp),
              border = BorderStroke(1.dp, NothingBorder),
            ) {
              Text(
                text = "ID ${camera.cameraId}",
                style = MaterialTheme.typography.labelMedium,
                color = NothingWhite,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = camera.inferredName,
              style = MaterialTheme.typography.titleMedium,
              color = NothingWhite,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "${camera.lensFacing.label} • ${camera.classification.label}",
            style = MaterialTheme.typography.labelSmall,
            color = NothingGrey,
          )
        }
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (expanded) "Collapse camera details" else "Expand camera details",
          tint = NothingGrey,
        )
      }

      Spacer(modifier = Modifier.height(10.dp))
      AccessibilityBadge(status = camera.accessibilityStatus)

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          HorizontalDivider(color = NothingBorder)

          SpecRow(
            label = "1. CAMERA NAME",
            value = camera.inferredName,
          )
          SpecRow(
            label = "2. CAMERA ID",
            value =
              "ID=${camera.cameraId} (in public cameraIdList: ${camera.inPublicCameraIdList})",
          )
          SpecRow(
            label = "3. CLASSIFICATION",
            value =
              buildString {
                append(camera.classification.label)
                if (camera.physicalCameraIds.isNotEmpty()) {
                  append(" | Sub-IDs: ${camera.physicalCameraIds.joinToString(", ")}")
                }
                if (camera.parentLogicalIds.isNotEmpty()) {
                  append(" | Parents: ${camera.parentLogicalIds.joinToString(", ")}")
                }
              },
          )
          SpecRow(
            label = "4. FOCAL LENGTH & OPTICS",
            value =
              buildString {
                append(
                  camera.focalLengthsMm.joinToString("/") { "${it}mm" }.ifEmpty { "N/A" }
                )
                camera.equivalentFocalLength35mm?.let {
                  append(" (~${String.format("%.1f", it)}mm 35mm eq)")
                }
                camera.sensorPhysicalSizeMm?.let { append(" | Sensor: ${it.formatted()}") }
                if (camera.apertures.isNotEmpty()) {
                  append(" | ${camera.apertures.joinToString("/") { "f/$it" }}")
                }
                camera.estimatedMinFocusDistanceCm?.let {
                  append(" | MinFocus: ~${it.toInt()}cm")
                }
              },
          )
          SpecRow(
            label = "5. AVAILABLE RESOLUTIONS",
            value =
              buildString {
                val maxJpeg = camera.jpegOutputSizes.firstOrNull()?.formatted() ?: "None"
                append("Max JPEG: $maxJpeg (${camera.jpegOutputSizes.size} sizes)")
                if (camera.maxResolutionOutputSizes.isNotEmpty()) {
                  append(" | Ultra-Res Mode: ${camera.maxResolutionOutputSizes.first().formatted()}")
                }
                if (camera.rawCapabilities.rawOutputSizes.isNotEmpty()) {
                  append(" | RAW: ${camera.rawCapabilities.rawOutputSizes.first().formatted()}")
                }
              },
          )
          SpecRow(
            label = "6. SUPPORTED FRAME RATES",
            value =
              buildString {
                append(
                  "AE: ${camera.supportedFpsRanges.joinToString(", ") { it.formatted() }.ifEmpty { "None" }}"
                )
                if (camera.highSpeedConfigs.isNotEmpty()) {
                  append(
                    "\nHigh-Speed: ${camera.highSpeedConfigs.joinToString("; ") { it.formatted() }}"
                  )
                }
              },
          )
          SpecRow(
            label = "7. HARDWARE CAPABILITIES",
            value =
              buildString {
                append("Level=${camera.hardwareLevel.label}")
                append(
                  " | Zoom=${camera.zoomRatioRange?.let { "${String.format("%.2f", it.start)}x..${String.format("%.2f", it.endInclusive)}x" } ?: "Digital ${camera.maxDigitalZoom}x"}"
                )
                append(" | OIS=${camera.oisModes.joinToString("/").ifEmpty { "None" }}")
                append(" | EIS=${camera.eisModes.joinToString("/").ifEmpty { "None" }}")
                append(" | ManualSensor=${camera.manualExposure.hasManualSensorCapability}")
                camera.manualExposure.isoRange?.let { append(" (ISO ${it.first}..${it.last})") }
                append(" | RAW=${camera.rawCapabilities.hasRawCapability}")
                append(
                  " | HDR=${camera.hdrCapabilities.dynamicRangeProfiles.joinToString("/").ifEmpty { if (camera.hdrCapabilities.supportsSceneModeHdr) "SceneHDR" else "SDR" }}"
                )
              },
          )
          SpecRow(
            label = "8. ACCESSIBILITY STATUS",
            value = camera.accessibilityStatus.label,
          )

          if (camera.cameraXExtensions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "CAMERAX EXTENSIONS",
              style = MaterialTheme.typography.labelSmall,
              color = NothingGrey,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              camera.cameraXExtensions.forEach { ext ->
                TelemetryChip(
                  label = "${ext.extensionName}: ${if (ext.isAvailable) "YES" else "NO"}",
                  accent = ext.isAvailable,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AccessibilityBadge(status: AccessibilityStatus) {
  val (bg, fg, icon) =
    when (status) {
      is AccessibilityStatus.AccessibleDirect ->
        Triple(StatusVerifiedContainer, StatusVerifiedGreen, Icons.Default.CheckCircle)
      is AccessibilityStatus.AccessibleViaPhysicalStream ->
        Triple(StatusVerifiedContainer, StatusVerifiedGreen, Icons.Default.CheckCircle)
      is AccessibilityStatus.RestrictedOrFailed ->
        Triple(NothingRedContainer, NothingRedText, Icons.Default.Warning)
      is AccessibilityStatus.PermissionRequired,
      AccessibilityStatus.NotProbedYet ->
        Triple(StatusCautionContainer, StatusCautionAmber, Icons.Outlined.Lock)
    }

  Surface(
    color = bg,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, fg.copy(alpha = 0.4f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text =
          when (status) {
            is AccessibilityStatus.RestrictedOrFailed -> "${status.label}: ${status.reason}"
            else -> status.label
          },
        style = MaterialTheme.typography.labelSmall,
        color = fg,
      )
    }
  }
}

@Composable
private fun ZoomProbeTabContent(report: HardwareDiagnosticsReport) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("zoom_probe_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (report.zoomProbeSamples.isEmpty()) {
      item {
        Surface(
          color = NothingSurface,
          border = BorderStroke(1.dp, NothingBorder),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = "Zoom probe",
                tint = NothingRed,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "ACTIVE PHYSICAL LENS ZOOM PROBE",
                style = MaterialTheme.typography.titleMedium,
                color = NothingWhite,
              )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.empty_zoom_probe),
              style = MaterialTheme.typography.bodyMedium,
              color = NothingGrey,
            )
          }
        }
      }
    } else {
      item {
        Text(
          text = "LOGICAL CAMERA ID ${report.zoomProbeLogicalCameraId} — REAL FRAME CAPTURE RESULTS",
          style = MaterialTheme.typography.labelMedium,
          color = NothingGrey,
        )
      }
      items(report.zoomProbeSamples) { sample ->
        ZoomSampleRow(sample = sample)
      }
    }
  }
}

@Composable
private fun ZoomSampleRow(sample: ZoomProbeSample) {
  Surface(
    color = NothingSurface,
    border = BorderStroke(1.dp, NothingBorder),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Requested ${String.format("%.2f", sample.requestedZoomRatio)}x",
          style = MaterialTheme.typography.titleMedium,
          color = NothingWhite,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text =
            "Reported: ${sample.reportedZoomRatio?.let { String.format("%.2f", it) + "x" } ?: "N/A"} • Focal: ${sample.reportedFocalLengthMm?.let { "${it}mm" } ?: "N/A"}",
          style = MaterialTheme.typography.labelSmall,
          color = NothingGrey,
        )
        sample.cropRegion?.let {
          Text(
            text = "Crop: $it",
            style = MaterialTheme.typography.labelSmall,
            color = NothingDimGrey,
          )
        }
      }
      Surface(
        color = NothingRedContainer,
        border = BorderStroke(1.dp, NothingRed.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text(
          text = "PHYS ID: ${sample.activePhysicalCameraId ?: "N/A"}",
          style = MaterialTheme.typography.labelMedium,
          color = NothingRedText,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun VideoMatrixTabContent(report: HardwareDiagnosticsReport) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("video_matrix_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(report.cameras, key = { it.cameraId }) { cam ->
      Surface(
        color = NothingSurface,
        border = BorderStroke(1.dp, NothingBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Outlined.Videocam,
              contentDescription = null,
              tint = NothingWhite,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "ID ${cam.cameraId} — ${cam.inferredName}",
              style = MaterialTheme.typography.titleMedium,
              color = NothingWhite,
            )
          }
          if (cam.videoQualities.isEmpty() && cam.highSpeedConfigs.isEmpty()) {
            Text(
              text = "No CamcorderProfile / CameraX VideoCapabilities reported for this ID.",
              style = MaterialTheme.typography.bodyMedium,
              color = NothingGrey,
            )
          } else {
            cam.videoQualities.forEach { vq ->
              Text(
                text = "• ${vq.formatted()}",
                style = MaterialTheme.typography.labelSmall,
                color = NothingOffWhite,
              )
            }
            if (cam.highSpeedConfigs.isNotEmpty()) {
              Text(
                text =
                  "High-Speed Slow-Mo: ${cam.highSpeedConfigs.joinToString("; ") { it.formatted() }}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusVerifiedGreen,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MarkdownTabContent(markdown: String) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(16.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(NothingSurface)
        .border(1.dp, NothingBorder, RoundedCornerShape(14.dp))
        .verticalScroll(rememberScrollState())
        .padding(14.dp)
        .testTag("markdown_preview")
  ) {
    Text(
      text = markdown,
      fontFamily = JetBrainsMonoFontFamily,
      style = MaterialTheme.typography.labelSmall,
      color = NothingOffWhite,
    )
  }
}

@Composable
private fun SpecRow(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = NothingDimGrey,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
      color = NothingOffWhite,
    )
  }
}

@Composable
private fun TelemetryChip(label: String, accent: Boolean = false) {
  Surface(
    color = if (accent) StatusVerifiedContainer else NothingSurfaceElevated,
    border =
      BorderStroke(
        1.dp,
        if (accent) StatusVerifiedGreen.copy(alpha = 0.45f) else NothingBorder,
      ),
    shape = RoundedCornerShape(8.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = if (accent) StatusVerifiedGreen else NothingOffWhite,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}
