package com.example.feature.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.settings.CameraAspectRatio
import com.example.core.settings.CameraFlashMode
import com.example.core.settings.CaptureTimer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingDimGrey
import com.example.ui.theme.NothingGrey
import com.example.ui.theme.NothingObsidian
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingSurface
import com.example.ui.theme.NothingSurfaceElevated
import com.example.ui.theme.NothingWhite
import com.example.ui.theme.StatusCautionAmber
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
  viewModel: CameraViewModel,
  onOpenGallery: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val haptics = LocalHapticFeedback.current
  val snackbarHostState = remember { SnackbarHostState() }

  var previewView by remember { mutableStateOf<PreviewView?>(null) }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
      val camGranted =
        results[Manifest.permission.CAMERA] == true ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
      val audGranted =
        results[Manifest.permission.RECORD_AUDIO] == true ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
      viewModel.onPermissionsUpdated(camGranted, audGranted)
    }

  val syncPermissions = {
    val camGranted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val audGranted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    viewModel.onPermissionsUpdated(camGranted, audGranted)
    camGranted to audGranted
  }

  LaunchedEffect(Unit) {
    val (camGranted, audGranted) = syncPermissions()
    if (!camGranted) {
      permissionLauncher.launch(
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
      )
    } else if (!audGranted) {
      permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        syncPermissions()
        viewModel.refreshLatestMedia()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(uiState.errorMessage, uiState.statusMessage) {
    val msg = uiState.errorMessage ?: uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearMessages()
    }
  }

  LaunchedEffect(previewView, uiState.cameraPermissionGranted) {
    val pv = previewView
    if (pv != null && uiState.cameraPermissionGranted) {
      viewModel.bindPreview(lifecycleOwner, pv)
    }
  }

  Scaffold(
    containerColor = NothingObsidian,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    modifier = modifier.fillMaxSize(),
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .background(NothingObsidian)
          .padding(innerPadding)
          .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
      TopCameraControlBar(
        uiState = uiState,
        onCycleFlash = {
          if (uiState.settings.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          viewModel.cycleFlashMode()
        },
        onCycleTimer = { viewModel.cycleTimer() },
        onCycleAspect = { viewModel.cycleAspectRatio() },
        onToggleStyles = { viewModel.toggleStylesSheet() },
        onSelectVideoQuality = { res, fps -> viewModel.setVideoQuality(res, fps) },
        onOpenSettings = onOpenSettings,
      )

      // Center Live Viewfinder Container
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
      ) {
        if (!uiState.cameraPermissionGranted) {
          CameraPermissionPrompt(
            onRequestPermission = {
              permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
              )
            },
            onOpenAppSettings = {
              context.startActivity(
                Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.fromParts("package", context.packageName, null),
                )
              )
            },
          )
        } else {
          val aspect =
            if (uiState.mode == CameraMode.VIDEO) {
              9f / 16f
            } else {
              uiState.settings.aspectRatio.ratio
            }
          val frameModifier =
            if (aspect != null) {
              Modifier.fillMaxWidth().aspectRatio(aspect)
            } else {
              Modifier.fillMaxSize()
            }

          BoxWithConstraints(
            modifier =
              frameModifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .testTag("camera_viewfinder"),
          ) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            AndroidView(
              factory = { ctx ->
                PreviewView(ctx).apply {
                  implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                  scaleType = PreviewView.ScaleType.FILL_CENTER
                  previewView = this
                }
              },
              modifier =
                Modifier.fillMaxSize()
                  .pointerInput(uiState.isFrontCamera, uiState.mode) {
                    detectTapGestures(
                      onTap = { offset ->
                        viewModel.tapToFocus(
                          xPx = offset.x,
                          yPx = offset.y,
                          xFrac = offset.x / widthPx,
                          yFrac = offset.y / heightPx,
                          lockAeAf = false,
                        )
                      },
                      onLongPress = { offset ->
                        if (uiState.settings.hapticsEnabled) {
                          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        viewModel.tapToFocus(
                          xPx = offset.x,
                          yPx = offset.y,
                          xFrac = offset.x / widthPx,
                          yFrac = offset.y / heightPx,
                          lockAeAf = true,
                        )
                      },
                    )
                  }
                  .pointerInput(Unit) {
                    awaitEachGesture {
                      awaitFirstDown(requireUnconsumed = false)
                      var totalDragX = 0f
                      var totalDragY = 0f
                      var modeSwiped = false
                      do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                          val zoomChange = event.calculateZoom()
                          if (abs(zoomChange - 1f) > 0.005f) {
                            viewModel.scaleZoomBy(zoomChange)
                            event.changes.forEach { it.consume() }
                          }
                        } else if (pressed == 1 && !modeSwiped) {
                          val change = event.changes.first()
                          val dx = change.position.x - change.previousPosition.x
                          val dy = change.position.y - change.previousPosition.y
                          totalDragX += dx
                          totalDragY += dy
                          if (abs(totalDragX) > 120f && abs(totalDragX) > abs(totalDragY) * 1.5f) {
                            modeSwiped = true
                            val step = if (totalDragX < 0f) 1 else -1
                            viewModel.stepModeBySwipe(step)
                          }
                        }
                      } while (event.changes.any { it.pressed })
                    }
                  },
            )

            ViewfinderGridAndLevelOverlay(
              gridMode = uiState.settings.gridMode,
              showLevel = uiState.settings.showLevelIndicator,
              tiltDegrees = uiState.tiltDegrees,
            )

            FocusReticleOverlay(
              focusState = uiState.focusIndicator,
              exposureIndex = uiState.exposureIndex,
              exposureRange = uiState.capabilities.exposureRange,
              exposureStep = uiState.capabilities.exposureStep,
              onExposureChange = { viewModel.setExposureIndex(it) },
              onUnlockAeAf = { viewModel.unlockAeAf() },
            )

            TelemetryBadge(
              telemetry = uiState.telemetry,
              isPortrait = uiState.mode == CameraMode.PORTRAIT,
              portraitSubjectDetected = uiState.portraitSubjectDetected,
              modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )

            if (uiState.recordingState.isRecording) {
              RecordingTimerBadge(
                durationMs = uiState.recordingState.durationMs,
                isPaused = uiState.recordingState.isPaused,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
              )
            }

            if (uiState.countdownRemainingSec > 0) {
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
              ) {
                Text(
                  text = uiState.countdownRemainingSec.toString(),
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 84.sp,
                  fontWeight = FontWeight.Bold,
                  color = NothingWhite,
                )
              }
            }

            androidx.compose.animation.AnimatedVisibility(
              visible = uiState.showShutterFlash,
              enter = fadeIn(),
              exit = fadeOut(),
            ) {
              Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.45f)))
            }
          }
        }
      }

      // Contextual Style Drawer or Portrait Blur Slider
      AnimatedVisibility(visible = uiState.isStylesSheetOpen) {
        PhotographicStyleDrawer(
          activeStyle = uiState.activeStyle,
          intensity = uiState.settings.styleIntensity,
          onSelectStyle = { viewModel.selectStyle(it) },
          onIntensityChange = { viewModel.setStyleIntensity(it) },
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }

      if (!uiState.isStylesSheetOpen && uiState.mode == CameraMode.PORTRAIT) {
        PortraitApertureSliderBar(
          blurStrength = uiState.portraitBlurStrength,
          onBlurChange = { viewModel.setPortraitBlurStrength(it) },
        )
      }

      // Bottom Zoom Presets + Mode Bar + Shutter Deck
      BottomCameraDeck(
        uiState = uiState,
        onSelectZoom = { presetRatio ->
          if (uiState.settings.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          viewModel.setZoomRatio(presetRatio)
        },
        onSelectMode = { mode ->
          if (uiState.settings.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          viewModel.selectMode(mode)
        },
        onShutterClick = {
          if (uiState.settings.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          viewModel.onShutterClicked()
        },
        onPauseResumeVideo = { viewModel.pauseOrResumeVideo() },
        onSwitchCamera = {
          if (uiState.settings.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          viewModel.toggleFrontRearCamera()
        },
        onOpenGallery = onOpenGallery,
      )
    }
  }
}

@Composable
private fun TopCameraControlBar(
  uiState: CameraUiState,
  onCycleFlash: () -> Unit,
  onCycleTimer: () -> Unit,
  onCycleAspect: () -> Unit,
  onToggleStyles: () -> Unit,
  onSelectVideoQuality: (com.example.core.settings.VideoResolutionOption, Int) -> Unit,
  onOpenSettings: () -> Unit,
) {
  var showQualityMenu by remember { mutableStateOf(false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    // Flash / Torch
    val flashEnabled = uiState.capabilities.hasFlash
    IconButton(
      onClick = onCycleFlash,
      enabled = flashEnabled,
      modifier = Modifier.minimumInteractiveComponentSize().testTag("flash_button"),
    ) {
      val flashIcon =
        if (uiState.mode == CameraMode.VIDEO) {
          if (uiState.torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff
        } else {
          when (uiState.flashMode) {
            CameraFlashMode.AUTO -> Icons.Filled.FlashAuto
            CameraFlashMode.ON -> Icons.Filled.FlashOn
            CameraFlashMode.OFF -> Icons.Filled.FlashOff
          }
        }
      val flashActive =
        (uiState.mode == CameraMode.VIDEO && uiState.torchEnabled) ||
          (uiState.mode != CameraMode.VIDEO && uiState.flashMode == CameraFlashMode.ON)
      Icon(
        imageVector = flashIcon,
        contentDescription = "Flash mode",
        tint =
          when {
            !flashEnabled -> NothingDimGrey
            flashActive -> StatusCautionAmber
            else -> NothingWhite
          },
      )
    }

    if (uiState.mode == CameraMode.VIDEO) {
      Box {
        Surface(
          color = NothingSurfaceElevated,
          shape = RoundedCornerShape(999.dp),
          border = BorderStroke(1.dp, NothingBorder),
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .clickable(enabled = !uiState.recordingState.isRecording) { showQualityMenu = true }
              .testTag("video_quality_button"),
        ) {
          Text(
            text =
              "${uiState.settings.preferredVideoResolution.label} • ${uiState.settings.preferredVideoFps}FPS",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = NothingWhite,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }
        DropdownMenu(
          expanded = showQualityMenu,
          onDismissRequest = { showQualityMenu = false },
          containerColor = NothingSurfaceElevated,
        ) {
          uiState.capabilities.supportedVideoOptions.forEach { option ->
            option.supportedFps.forEach { fps ->
              val isSelected =
                option.resolution == uiState.settings.preferredVideoResolution &&
                  fps == uiState.settings.preferredVideoFps
              DropdownMenuItem(
                text = {
                  Text(
                    text = "${option.resolution.label} @ ${fps}fps",
                    fontFamily = JetBrainsMonoFontFamily,
                    color = if (isSelected) NothingRed else NothingWhite,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  )
                },
                onClick = {
                  showQualityMenu = false
                  onSelectVideoQuality(option.resolution, fps)
                },
              )
            }
          }
        }
      }
    } else {
      // Timer
      Surface(
        color =
          if (uiState.captureTimer != CaptureTimer.OFF) NothingWhite else NothingSurfaceElevated,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, NothingBorder),
        modifier =
          Modifier.minimumInteractiveComponentSize()
            .clickable(onClick = onCycleTimer)
            .testTag("timer_button"),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = "Capture timer",
            tint =
              if (uiState.captureTimer != CaptureTimer.OFF) NothingObsidian else NothingWhite,
            modifier = Modifier.size(14.dp),
          )
          if (uiState.captureTimer != CaptureTimer.OFF) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = uiState.captureTimer.label,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = NothingObsidian,
            )
          }
        }
      }

      // Aspect Ratio
      Surface(
        color = NothingSurfaceElevated,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, NothingBorder),
        modifier =
          Modifier.minimumInteractiveComponentSize()
            .clickable(onClick = onCycleAspect)
            .testTag("aspect_ratio_button"),
      ) {
        Text(
          text = uiState.settings.aspectRatio.label,
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = NothingWhite,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }

    // Photographic Styles Toggle
    val styleActive = uiState.activeStyle.id != PhotographicStyleCatalog.Natural.id
    Surface(
      color =
        when {
          uiState.isStylesSheetOpen -> NothingWhite
          styleActive -> NothingRed.copy(alpha = 0.22f)
          else -> NothingSurfaceElevated
        },
      shape = RoundedCornerShape(999.dp),
      border =
        BorderStroke(
          1.dp,
          when {
            uiState.isStylesSheetOpen -> NothingWhite
            styleActive -> NothingRed
            else -> NothingBorder
          },
        ),
      modifier =
        Modifier.minimumInteractiveComponentSize()
          .clickable(onClick = onToggleStyles)
          .testTag("styles_toggle_button"),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.AutoAwesome,
          contentDescription = "Photographic styles",
          tint =
            when {
              uiState.isStylesSheetOpen -> NothingObsidian
              styleActive -> NothingRed
              else -> NothingWhite
            },
          modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = uiState.activeStyle.shortCode,
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = if (uiState.isStylesSheetOpen) NothingObsidian else NothingWhite,
        )
      }
    }

    // Settings
    IconButton(
      onClick = onOpenSettings,
      modifier = Modifier.minimumInteractiveComponentSize().testTag("camera_settings_button"),
    ) {
      Icon(
        imageVector = Icons.Filled.Settings,
        contentDescription = "Camera Settings",
        tint = NothingWhite,
      )
    }
  }
}

@Composable
private fun PortraitApertureSliderBar(
  blurStrength: Float,
  onBlurChange: (Float) -> Unit,
) {
  // Map blurStrength 1.0 -> f/1.4, 0.0 -> f/16
  val fStop = 1.4f + (1f - blurStrength) * 14.6f
  Surface(
    color = NothingSurface.copy(alpha = 0.85f),
    shape = RoundedCornerShape(999.dp),
    border = BorderStroke(1.dp, NothingBorder),
    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.Tune,
        contentDescription = "Aperture blur depth",
        tint = StatusCautionAmber,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = String.format(Locale.US, "f/%.1f", fStop),
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = StatusCautionAmber,
      )
      Spacer(modifier = Modifier.width(10.dp))
      Slider(
        value = blurStrength,
        onValueChange = onBlurChange,
        valueRange = 0f..1f,
        colors =
          SliderDefaults.colors(
            thumbColor = StatusCautionAmber,
            activeTrackColor = StatusCautionAmber,
            inactiveTrackColor = NothingBorder,
          ),
        modifier = Modifier.weight(1f).height(28.dp).testTag("portrait_blur_slider"),
      )
    }
  }
}

@Composable
private fun BottomCameraDeck(
  uiState: CameraUiState,
  onSelectZoom: (Float) -> Unit,
  onSelectMode: (CameraMode) -> Unit,
  onShutterClick: () -> Unit,
  onPauseResumeVideo: () -> Unit,
  onSwitchCamera: () -> Unit,
  onOpenGallery: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 6.dp),
  ) {
    // Zoom Presets Row
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = 10.dp).testTag("zoom_presets_row"),
    ) {
      val presets = uiState.capabilities.zoomPresets
      presets.forEachIndexed { idx, preset ->
        val nextRatio = presets.getOrNull(idx + 1)?.ratio ?: Float.MAX_VALUE
        val isActive =
          uiState.zoomRatio >= preset.ratio - 0.12f && uiState.zoomRatio < nextRatio - 0.12f
        val displayLabel =
          if (isActive && abs(uiState.zoomRatio - preset.ratio) >= 0.15f) {
            String.format(Locale.US, "%.1fx", uiState.zoomRatio)
          } else {
            preset.label
          }

        Surface(
          color = if (isActive) NothingWhite else NothingSurfaceElevated,
          shape = CircleShape,
          border = BorderStroke(1.dp, if (isActive) NothingWhite else NothingBorder),
          modifier =
            Modifier.size(44.dp)
              .clip(CircleShape)
              .clickable { onSelectZoom(preset.ratio) }
              .testTag("zoom_pill_${preset.label}"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              text = displayLabel,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = if (isActive) NothingObsidian else NothingWhite,
            )
          }
        }
      }
    }

    // Mode Switcher Row
    Row(
      horizontalArrangement = Arrangement.spacedBy(18.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = 12.dp),
    ) {
      CameraMode.entries.forEach { mode ->
        val selected = mode == uiState.mode
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .clickable(enabled = !uiState.recordingState.isRecording) { onSelectMode(mode) }
              .padding(horizontal = 6.dp, vertical = 4.dp)
              .testTag("mode_tab_${mode.name.lowercase()}"),
        ) {
          Text(
            text = mode.label,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color =
              when {
                selected -> NothingWhite
                uiState.recordingState.isRecording -> NothingDimGrey
                else -> NothingGrey
              },
          )
          Spacer(modifier = Modifier.height(3.dp))
          Box(
            modifier =
              Modifier.size(4.dp)
                .clip(CircleShape)
                .background(if (selected) NothingRed else Color.Transparent),
          )
        }
      }
    }

    // Shutter Row
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceEvenly,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
      // Gallery Thumbnail
      Surface(
        color = NothingSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, NothingBorder),
        modifier =
          Modifier.size(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenGallery)
            .testTag("gallery_thumbnail_button"),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (uiState.latestMediaUri != null) {
            AsyncImage(
              model = uiState.latestMediaUri,
              contentDescription = "Latest captured photo or video",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
            if (uiState.latestMediaIsVideo) {
              Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = NothingWhite,
                modifier =
                  Modifier.size(22.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(2.dp),
              )
            }
          } else {
            Icon(
              imageVector = Icons.Filled.PhotoLibrary,
              contentDescription = "Open Gallery",
              tint = NothingGrey,
              modifier = Modifier.size(24.dp),
            )
          }
        }
      }

      // Main Shutter / Record Button
      ShutterControlButton(
        mode = uiState.mode,
        isRecording = uiState.recordingState.isRecording,
        isCapturing = uiState.isCapturing,
        onClick = onShutterClick,
      )

      // Camera Flip OR Pause/Resume while recording
      if (uiState.recordingState.isRecording) {
        Surface(
          color = NothingSurfaceElevated,
          shape = CircleShape,
          border = BorderStroke(1.5.dp, NothingBorder),
          modifier =
            Modifier.size(54.dp)
              .clip(CircleShape)
              .clickable(onClick = onPauseResumeVideo)
              .testTag("pause_resume_video_button"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector =
                if (uiState.recordingState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
              contentDescription =
                if (uiState.recordingState.isPaused) "Resume recording" else "Pause recording",
              tint = NothingWhite,
              modifier = Modifier.size(24.dp),
            )
          }
        }
      } else {
        Surface(
          color = NothingSurfaceElevated,
          shape = CircleShape,
          border = BorderStroke(1.5.dp, NothingBorder),
          modifier =
            Modifier.size(54.dp)
              .clip(CircleShape)
              .clickable(onClick = onSwitchCamera)
              .testTag("switch_camera_button"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.Cameraswitch,
              contentDescription = "Switch front or rear camera",
              tint = NothingWhite,
              modifier = Modifier.size(24.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ShutterControlButton(
  mode: CameraMode,
  isRecording: Boolean,
  isCapturing: Boolean,
  onClick: () -> Unit,
) {
  val innerSize by animateDpAsState(targetValue = if (isRecording) 30.dp else 60.dp, label = "innerSize")
  val innerCorner by animateDpAsState(targetValue = if (isRecording) 8.dp else 30.dp, label = "innerCorner")
  val innerColor by
    animateColorAsState(
      targetValue =
        when {
          mode == CameraMode.VIDEO || isRecording -> NothingRed
          isCapturing -> NothingGrey
          else -> NothingWhite
        },
      label = "innerColor",
    )

  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.size(78.dp)
        .clip(CircleShape)
        .border(3.dp, NothingWhite, CircleShape)
        .clickable(onClick = onClick)
        .testTag("shutter_button"),
  ) {
    Box(
      modifier =
        Modifier.size(innerSize)
          .clip(RoundedCornerShape(innerCorner))
          .background(innerColor),
    )
  }
}

@Composable
private fun RecordingTimerBadge(
  durationMs: Long,
  isPaused: Boolean,
  modifier: Modifier = Modifier,
) {
  val totalSec = (durationMs / 1000L).toInt()
  val mins = totalSec / 60
  val secs = totalSec % 60
  Surface(
    color = if (isPaused) NothingSurfaceElevated else NothingRed,
    shape = RoundedCornerShape(999.dp),
    modifier = modifier.testTag("recording_timer_badge"),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
      Box(
        modifier =
          Modifier.size(7.dp)
            .clip(CircleShape)
            .background(if (isPaused) StatusCautionAmber else NothingWhite),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text =
          if (isPaused) {
            String.format(Locale.US, "PAUSED %02d:%02d", mins, secs)
          } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
          },
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = NothingWhite,
      )
    }
  }
}

@Composable
private fun CameraPermissionPrompt(
  onRequestPermission: () -> Unit,
  onOpenAppSettings: () -> Unit,
) {
  Surface(
    color = NothingSurface,
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, NothingBorder),
    modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(24.dp),
    ) {
      Text(
        text = "CAMERA ACCESS REQUIRED",
        style = MaterialTheme.typography.titleMedium,
        color = NothingWhite,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text =
          "Nothing Camera Pro needs Camera access for live viewfinder preview, photo capture, and video recording.",
        style = MaterialTheme.typography.bodyMedium,
        color = NothingGrey,
      )
      Spacer(modifier = Modifier.height(18.dp))
      Button(
        onClick = onRequestPermission,
        colors = ButtonDefaults.buttonColors(containerColor = NothingWhite, contentColor = NothingObsidian),
        modifier = Modifier.fillMaxWidth().testTag("grant_camera_permission_button"),
      ) {
        Text("GRANT CAMERA ACCESS", fontWeight = FontWeight.Bold)
      }
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedButton(
        onClick = onOpenAppSettings,
        border = BorderStroke(1.dp, NothingBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("OPEN APP SETTINGS")
      }
    }
  }
}
