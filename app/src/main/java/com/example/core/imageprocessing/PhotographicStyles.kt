package com.example.core.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PhotographicStyle(
  val id: String,
  val name: String,
  val shortCode: String,
  val contrast: Float = 1.0f,        // 1.0 = neutral
  val saturation: Float = 1.0f,      // 1.0 = neutral
  val vibrance: Float = 0.0f,        // -0.5 .. +0.6
  val temperature: Float = 0.0f,     // -0.35 (cool) .. +0.35 (warm)
  val tint: Float = 0.0f,            // -0.2 (green) .. +0.2 (magenta)
  val highlights: Float = 0.0f,      // -0.4 .. +0.4
  val shadows: Float = 0.0f,         // -0.4 .. +0.4
  val blackLift: Float = 0.0f,       // 0.0 .. 0.14 (Matte fade)
  val monochrome: Boolean = false,
  val skinToneProtection: Float = 0.65f,
  val sharpness: Float = 0.0f,
)

object PhotographicStyleCatalog {
  val Natural =
    PhotographicStyle(
      id = "natural",
      name = "Natural",
      shortCode = "NAT",
      contrast = 1.0f,
      saturation = 1.0f,
      vibrance = 0.0f,
      skinToneProtection = 1.0f,
    )

  val Vivid =
    PhotographicStyle(
      id = "vivid",
      name = "Vivid",
      shortCode = "VIV",
      contrast = 1.12f,
      saturation = 1.16f,
      vibrance = 0.28f,
      highlights = -0.05f,
      shadows = 0.06f,
      skinToneProtection = 0.80f,
      sharpness = 0.12f,
    )

  val VividWarm =
    PhotographicStyle(
      id = "vivid_warm",
      name = "Vivid Warm",
      shortCode = "V-W",
      contrast = 1.12f,
      saturation = 1.14f,
      vibrance = 0.25f,
      temperature = 0.16f,
      tint = 0.02f,
      shadows = 0.05f,
      skinToneProtection = 0.78f,
      sharpness = 0.10f,
    )

  val VividCool =
    PhotographicStyle(
      id = "vivid_cool",
      name = "Vivid Cool",
      shortCode = "V-C",
      contrast = 1.12f,
      saturation = 1.12f,
      vibrance = 0.24f,
      temperature = -0.15f,
      tint = -0.02f,
      highlights = 0.04f,
      skinToneProtection = 0.78f,
      sharpness = 0.10f,
    )

  val RichContrast =
    PhotographicStyle(
      id = "rich_contrast",
      name = "Rich Contrast",
      shortCode = "RCH",
      contrast = 1.24f,
      saturation = 1.04f,
      vibrance = 0.12f,
      highlights = -0.12f,
      shadows = -0.14f,
      skinToneProtection = 0.72f,
      sharpness = 0.16f,
    )

  val Dramatic =
    PhotographicStyle(
      id = "dramatic",
      name = "Dramatic",
      shortCode = "DRM",
      contrast = 1.32f,
      saturation = 0.86f,
      vibrance = -0.06f,
      temperature = -0.04f,
      highlights = -0.18f,
      shadows = -0.22f,
      skinToneProtection = 0.65f,
      sharpness = 0.22f,
    )

  val Warm =
    PhotographicStyle(
      id = "warm",
      name = "Warm",
      shortCode = "WRM",
      contrast = 1.04f,
      saturation = 1.03f,
      vibrance = 0.08f,
      temperature = 0.22f,
      tint = 0.03f,
      shadows = 0.04f,
      skinToneProtection = 0.75f,
    )

  val Cool =
    PhotographicStyle(
      id = "cool",
      name = "Cool",
      shortCode = "COL",
      contrast = 1.04f,
      saturation = 0.96f,
      vibrance = 0.06f,
      temperature = -0.22f,
      tint = -0.02f,
      highlights = 0.03f,
      skinToneProtection = 0.75f,
    )

  val Matte =
    PhotographicStyle(
      id = "matte",
      name = "Matte",
      shortCode = "MAT",
      contrast = 0.90f,
      saturation = 0.84f,
      vibrance = -0.04f,
      temperature = 0.04f,
      highlights = -0.14f,
      shadows = 0.14f,
      blackLift = 0.085f,
      skinToneProtection = 0.70f,
    )

  val BlackAndWhite =
    PhotographicStyle(
      id = "bw",
      name = "Black & White",
      shortCode = "B&W",
      contrast = 1.24f,
      saturation = 0.0f,
      highlights = -0.06f,
      shadows = -0.08f,
      monochrome = true,
      skinToneProtection = 0.50f,
      sharpness = 0.18f,
    )

  val allStyles: List<PhotographicStyle> =
    listOf(
      Natural,
      Vivid,
      VividWarm,
      VividCool,
      RichContrast,
      Dramatic,
      Warm,
      Cool,
      Matte,
      BlackAndWhite,
    )

  fun findById(id: String): PhotographicStyle = allStyles.firstOrNull { it.id == id } ?: Natural
}

object PhotographicStyleBitmapProcessor {

  fun applyStyle(
    source: Bitmap,
    style: PhotographicStyle,
    intensity: Float = 1.0f,
  ): Bitmap {
    val alpha = intensity.coerceIn(0f, 1f)
    if (style.id == PhotographicStyleCatalog.Natural.id || alpha <= 0.01f) {
      return source.copy(Bitmap.Config.ARGB_8888, false)
    }

    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val contrast = lerp(1.0f, style.contrast, alpha)
    val saturation = lerp(1.0f, style.saturation, alpha)
    val vibrance = style.vibrance * alpha
    val temp = style.temperature * alpha
    val tint = style.tint * alpha
    val highlights = style.highlights * alpha
    val shadows = style.shadows * alpha
    val blackLift = style.blackLift * alpha
    val monoMix = if (style.monochrome) alpha else 0f

    for (i in pixels.indices) {
      val c = pixels[i]
      val r0 = Color.red(c) / 255f
      val g0 = Color.green(c) / 255f
      val b0 = Color.blue(c) / 255f

      // 1. White balance temperature & tint
      var r = (r0 + temp * 0.24f + tint * 0.08f).coerceIn(0f, 1f)
      var g = (g0 - tint * 0.14f + temp * 0.03f).coerceIn(0f, 1f)
      var b = (b0 - temp * 0.24f + tint * 0.08f).coerceIn(0f, 1f)

      // 2. Highlights & shadows tonal curve
      val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
      val shadowMask = (1f - lum) * (1f - lum)
      val highlightMask = lum * lum
      val toneDelta = shadows * shadowMask * 0.35f + highlights * highlightMask * 0.35f
      r = (r + toneDelta).coerceIn(0f, 1f)
      g = (g + toneDelta).coerceIn(0f, 1f)
      b = (b + toneDelta).coerceIn(0f, 1f)

      // 3. Contrast pivot at 0.5 + black point lift (Matte)
      r = ((r - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
      g = ((g - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
      b = ((b - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)

      if (blackLift > 0f) {
        r = blackLift + r * (1f - blackLift)
        g = blackLift + g * (1f - blackLift)
        b = blackLift + b * (1f - blackLift)
      }

      // 4. Skin-tone-protected saturation & vibrance
      val lumAfter = 0.2126f * r + 0.7152f * g + 0.0722f * b
      val maxCh = max(r, max(g, b))
      val minCh = min(r, min(g, b))
      val curSat = if (maxCh > 0.001f) (maxCh - minCh) / maxCh else 0f

      // Heuristic skin mask: warm red>green>blue region with moderate saturation
      val isSkinLike = r > g && g > b && (r - b) > 0.06f && (r - g) < 0.36f
      val skinShield = if (isSkinLike) 1f - style.skinToneProtection * 0.65f else 1f

      val satBoost = 1f + ((saturation - 1f) + vibrance * (1f - curSat)) * skinShield
      r = (lumAfter + (r - lumAfter) * satBoost).coerceIn(0f, 1f)
      g = (lumAfter + (g - lumAfter) * satBoost).coerceIn(0f, 1f)
      b = (lumAfter + (b - lumAfter) * satBoost).coerceIn(0f, 1f)

      // 5. Monochrome mix if Black & White
      if (monoMix > 0f) {
        val bwLum = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
        r = lerp(r, bwLum, monoMix)
        g = lerp(g, bwLum, monoMix)
        b = lerp(b, bwLum, monoMix)
      }

      pixels[i] =
        Color.argb(
          255,
          (r * 255f).toInt().coerceIn(0, 255),
          (g * 255f).toInt().coerceIn(0, 255),
          (b * 255f).toInt().coerceIn(0, 255),
        )
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
  }

  private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
