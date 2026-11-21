package com.example.feature.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.imageprocessing.PhotographicStyle
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.settings.GridMode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingGrey
import com.example.ui.theme.NothingObsidian
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingSurface
import com.example.ui.theme.NothingSurfaceElevated
import com.example.ui.theme.NothingWhite
import com.example.ui.theme.StatusCautionAmber
import com.example.ui.theme.StatusVerifiedGreen
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ViewfinderGridAndLevelOverlay(
  gridMode: GridMode,
  showLevel: Boolean,
  tiltDegrees: Float,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    val lineColor = Color.White.copy(alpha = 0.24f)

    if (gridMode != GridMode.OFF) {
      val divisions = if (gridMode == GridMode.GRID_3X3) 3 else 4
      for (i in 1 until divisions) {
        val x = w * i / divisions.toFloat()
        val y = h * i / divisions.toFloat()
        drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
      }
    }

    if (showLevel) {
      val center = Offset(w / 2f, h / 2f)
      val isLevel = abs(tiltDegrees) <= 1.2f
      val levelColor = if (isLevel) StatusCautionAmber else Color.White.copy(alpha = 0.5f)
      // Fixed reference ticks
      val tickHalf = 46.dp.toPx()
      val gap = 22.dp.toPx()
      drawLine(
        Color.White.copy(alpha = 0.35f),
        Offset(center.x - tickHalf, center.y),
        Offset(center.x - gap, center.y),
        strokeWidth = 1.5.dp.toPx(),
      )
      drawLine(
        Color.White.copy(alpha = 0.35f),
        Offset(center.x + gap, center.y),
        Offset(center.x + tickHalf, center.y),
        strokeWidth = 1.5.dp.toPx(),
      )
      // Rotating horizon bar
      rotate(degrees = -tiltDegrees.coerceIn(-45f, 45f), pivot = center) {
        drawLine(
          color = levelColor,
          start = Offset(center.x - 18.dp.toPx(), center.y),
          end = Offset(center.x + 18.dp.toPx(), center.y),
          strokeWidth = if (isLevel) 2.5.dp.toPx() else 1.5.dp.toPx(),
        )
      }
    }
  }
}

@Composable
fun FocusReticleOverlay(
  focusState: FocusIndicatorState,
  exposureIndex: Int,
  exposureRange: IntRange,
  exposureStep: Float,
  onExposureChange: (Int) -> Unit,
  onUnlockAeAf: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = focusState.isVisible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier.fillMaxSize(),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val reticleSize = 76.dp
      val cx = (maxWidth * focusState.xFraction) - reticleSize / 2
      val cy = (maxHeight * focusState.yFraction) - reticleSize / 2
      val ringColor = if (focusState.isLocked) StatusCautionAmber else NothingWhite

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
          Modifier.offset {
            IntOffset(
              x = cx.roundToPx().coerceIn(0, (maxWidth - reticleSize).roundToPx().coerceAtLeast(0)),
              y = cy.roundToPx().coerceIn(0, (maxHeight - reticleSize).roundToPx().coerceAtLeast(0)),
            )
          },
      ) {
        if (focusState.isLocked) {
          Surface(
            color = StatusCautionAmber,
            shape = RoundedCornerShape(4.dp),
            modifier =
              Modifier.padding(bottom = 6.dp)
                .clickable(onClick = onUnlockAeAf)
                .testTag("ae_af_lock_badge"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Unlock AE/AF",
                tint = NothingObsidian,
                modifier = Modifier.size(10.dp),
              )
              Spacer(modifier = Modifier.width(3.dp))
              Text(
                text = "AE/AF LOCK",
                style = MaterialTheme.typography.labelSmall,
                color = NothingObsidian,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }

        Box(
          modifier =
            Modifier.size(reticleSize)
              .border(1.5.dp, ringColor, RoundedCornerShape(4.dp)),
        ) {
          // Precision center crosshair dot
          Box(
            modifier =
              Modifier.align(Alignment.Center)
                .size(4.dp)
                .clip(CircleShape)
                .background(ringColor),
          )
        }

        if (exposureRange.first < exposureRange.last) {
          Spacer(modifier = Modifier.height(6.dp))
          Surface(
            color = NothingObsidian.copy(alpha = 0.78f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, NothingBorder),
            modifier = Modifier.width(172.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.WbSunny,
                contentDescription = "Exposure compensation",
                tint = StatusCautionAmber,
                modifier = Modifier.size(14.dp),
              )
              Slider(
                value = exposureIndex.toFloat(),
                onValueChange = { onExposureChange(it.roundToInt()) },
                valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                colors =
                  SliderDefaults.colors(
                    thumbColor = StatusCautionAmber,
                    activeTrackColor = StatusCautionAmber,
                    inactiveTrackColor = NothingGrey.copy(alpha = 0.35f),
                  ),
                modifier = Modifier.weight(1f).height(28.dp).testTag("exposure_slider"),
              )
              val ev = exposureIndex * exposureStep
              Text(
                text = String.format(Locale.US, "%+.1f", ev),
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun TelemetryBadge(
  telemetry: LiveLensTelemetry,
  isPortrait: Boolean,
  portraitSubjectDetected: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier,
  ) {
    Surface(
      color = NothingObsidian.copy(alpha = 0.65f),
      shape = RoundedCornerShape(999.dp),
      border = BorderStroke(1.dp, NothingBorder.copy(alpha = 0.7f)),
    ) {
      val idLabel = telemetry.activePhysicalId?.let { "ID $it" } ?: "CAM ${telemetry.logicalCameraId}"
      val focalLabel =
        telemetry.focalLengthMm?.let { String.format(Locale.US, " • %.1fmm", it) }.orEmpty()
      val zoomLabel = String.format(Locale.US, " • %.1fx", telemetry.zoomRatio)
      Text(
        text = "$idLabel$focalLabel$zoomLabel",
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 10.sp,
        color = NothingWhite.copy(alpha = 0.9f),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      )
    }

    if (isPortrait) {
      Surface(
        color =
          if (portraitSubjectDetected) {
            StatusCautionAmber.copy(alpha = 0.92f)
          } else {
            NothingObsidian.copy(alpha = 0.65f)
          },
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, NothingBorder.copy(alpha = 0.7f)),
      ) {
        Text(
          text = if (portraitSubjectDetected) "SUBJECT READY" else "FIND SUBJECT",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (portraitSubjectDetected) NothingObsidian else NothingWhite,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }
  }
}

@Composable
fun PhotographicStyleDrawer(
  activeStyle: PhotographicStyle,
  intensity: Float,
  onSelectStyle: (PhotographicStyle) -> Unit,
  onIntensityChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = NothingSurface.copy(alpha = 0.94f),
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, NothingBorder),
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("styles_drawer"),
  ) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      ) {
        Text(
          text = "PHOTOGRAPHIC STYLE • ${activeStyle.name.uppercase()}",
          style = MaterialTheme.typography.labelMedium,
          color = NothingWhite,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "${(intensity * 100).roundToInt()}%",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          color = NothingRed,
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(PhotographicStyleCatalog.allStyles, key = { it.id }) { style ->
          val selected = style.id == activeStyle.id
          Surface(
            color = if (selected) NothingWhite else NothingSurfaceElevated,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (selected) NothingWhite else NothingBorder),
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .clickable { onSelectStyle(style) }
                .testTag("style_chip_${style.id}"),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
              Box(
                modifier =
                  Modifier.size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) NothingRed else stylePreviewColor(style)),
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = style.shortCode,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) NothingObsidian else NothingWhite,
              )
              Text(
                text = style.name,
                fontSize = 10.sp,
                color = if (selected) NothingObsidian.copy(alpha = 0.78f) else NothingGrey,
              )
            }
          }
        }
      }

      if (activeStyle.id != PhotographicStyleCatalog.Natural.id) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
          Text(
            text = "INTENSITY",
            style = MaterialTheme.typography.labelSmall,
            color = NothingGrey,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Slider(
            value = intensity,
            onValueChange = onIntensityChange,
            valueRange = 0f..1f,
            colors =
              SliderDefaults.colors(
                thumbColor = NothingRed,
                activeTrackColor = NothingRed,
                inactiveTrackColor = NothingBorder,
              ),
            modifier = Modifier.weight(1f).testTag("style_intensity_slider"),
          )
        }
      }
    }
  }
}

private fun stylePreviewColor(style: PhotographicStyle): Color =
  when {
    style.monochrome -> Color(0xFFB0B0B6)
    style.temperature > 0.1f -> Color(0xFFFFB347)
    style.temperature < -0.1f -> Color(0xFF6EC6FF)
    style.blackLift > 0.01f -> Color(0xFF9E9487)
    style.saturation > 1.1f -> StatusVerifiedGreen
    else -> NothingWhite
  }
