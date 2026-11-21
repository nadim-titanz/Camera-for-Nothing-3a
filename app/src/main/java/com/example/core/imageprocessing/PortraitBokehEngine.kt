package com.example.core.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Subject-aware Portrait segmentation and adjustable bokeh compositing engine using
 * Google ML Kit Selfie Segmentation. Supports both low-latency stream masking for
 * live GPU preview and full-resolution single-image segmentation for captured photos.
 */
class PortraitBokehEngine {

  private val streamSegmenter by lazy {
    Segmentation.getClient(
      SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
        .enableRawSizeMask()
        .build()
    )
  }

  private val singleImageSegmenter by lazy {
    Segmentation.getClient(
      SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .enableRawSizeMask()
        .build()
    )
  }

  private val isAnalyzing = AtomicBoolean(false)

  data class StreamMaskResult(
    val maskBytes: ByteArray,
    val width: Int,
    val height: Int,
    val subjectDetected: Boolean,
  )

  @OptIn(ExperimentalGetImage::class)
  fun analyzePreviewFrame(
    imageProxy: ImageProxy,
    onMaskReady: (StreamMaskResult) -> Unit,
  ) {
    val mediaImage: Image = imageProxy.image ?: run {
      imageProxy.close()
      return
    }
    if (!isAnalyzing.compareAndSet(false, true)) {
      imageProxy.close()
      return
    }

    val inputImage =
      InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    streamSegmenter
      .process(inputImage)
      .addOnSuccessListener { mask ->
        val result = extractMaskBytes(mask)
        onMaskReady(result)
      }
      .addOnCompleteListener {
        isAnalyzing.set(false)
        imageProxy.close()
      }
  }

  private fun extractMaskBytes(mask: SegmentationMask): StreamMaskResult {
    val w = mask.width
    val h = mask.height
    val rawBuf = mask.buffer
    rawBuf.rewind()
    val buffer: FloatBuffer = rawBuf.asFloatBuffer()
    val bytes = ByteArray(w * h)
    var fgCount = 0
    for (i in bytes.indices) {
      val conf = buffer.get().coerceIn(0f, 1f)
      if (conf >= 0.55f) fgCount++
      bytes[i] = (conf * 255f).toInt().toByte()
    }
    val fgRatio = fgCount.toFloat() / max(1, bytes.size)
    return StreamMaskResult(
      maskBytes = bytes,
      width = w,
      height = h,
      subjectDetected = fgRatio in 0.03f..0.95f,
    )
  }

  suspend fun applyPortraitBokeh(
    source: Bitmap,
    blurIntensity: Float,
  ): Pair<Bitmap, Boolean> =
    withContext(Dispatchers.Default) {
      val clamped = blurIntensity.coerceIn(0f, 1f)
      if (clamped <= 0.02f) {
        return@withContext source to false
      }

      val mask = runSingleImageSegmentation(source) ?: return@withContext source to false
      val maskW = mask.width
      val maskH = mask.height
      val rawBuf = mask.buffer
      rawBuf.rewind()
      val buf = rawBuf.asFloatBuffer()
      val maskFloats = FloatArray(maskW * maskH)
      var fgCount = 0
      for (i in maskFloats.indices) {
        val v = buf.get().coerceIn(0f, 1f)
        maskFloats[i] = v
        if (v >= 0.5f) fgCount++
      }

      val fgRatio = fgCount.toFloat() / max(1, maskFloats.size)
      val subjectFound = fgRatio in 0.02f..0.96f
      if (!subjectFound) {
        return@withContext source to false
      }

      // Build blurred background via downsampled box-stack blur for clean, creamy bokeh
      val blurScale = 4
      val smallW = max(16, source.width / blurScale)
      val smallH = max(16, source.height / blurScale)
      val downscaled = Bitmap.createScaledBitmap(source, smallW, smallH, true)
      val radius = (3 + clamped * 16f).toInt().coerceIn(2, 20)
      val blurredSmall = fastStackBlur(downscaled, radius)
      val blurredBg = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
      if (downscaled !== blurredSmall) downscaled.recycle()
      blurredSmall.recycle()

      val width = source.width
      val height = source.height
      val fgPixels = IntArray(width * height)
      val bgPixels = IntArray(width * height)
      source.getPixels(fgPixels, 0, width, 0, 0, width, height)
      blurredBg.getPixels(bgPixels, 0, width, 0, 0, width, height)
      blurredBg.recycle()

      // Composite with smooth bilinear mask interpolation + hair-edge feathering
      for (y in 0 until height) {
        val my = (y.toFloat() / height) * (maskH - 1)
        val y0 = my.toInt().coerceIn(0, maskH - 1)
        val y1 = min(y0 + 1, maskH - 1)
        val fy = my - y0

        for (x in 0 until width) {
          val mx = (x.toFloat() / width) * (maskW - 1)
          val x0 = mx.toInt().coerceIn(0, maskW - 1)
          val x1 = min(x0 + 1, maskW - 1)
          val fx = mx - x0

          val m00 = maskFloats[y0 * maskW + x0]
          val m10 = maskFloats[y0 * maskW + x1]
          val m01 = maskFloats[y1 * maskW + x0]
          val m11 = maskFloats[y1 * maskW + x1]
          val interp =
            (m00 * (1f - fx) + m10 * fx) * (1f - fy) +
              (m01 * (1f - fx) + m11 * fx) * fy

          // Hair-edge feather curve: smoothstep(0.28, 0.76)
          val t = ((interp - 0.28f) / (0.76f - 0.28f)).coerceIn(0f, 1f)
          val fgAlpha = t * t * (3f - 2f * t)

          val idx = y * width + x
          val fgC = fgPixels[idx]
          val bgC = bgPixels[idx]
          val r = (Color.red(bgC) + (Color.red(fgC) - Color.red(bgC)) * fgAlpha).toInt()
          val g = (Color.green(bgC) + (Color.green(fgC) - Color.green(bgC)) * fgAlpha).toInt()
          val b = (Color.blue(bgC) + (Color.blue(fgC) - Color.blue(bgC)) * fgAlpha).toInt()
          fgPixels[idx] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
      }

      val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      output.setPixels(fgPixels, 0, width, 0, 0, width, height)
      output to true
    }

  private suspend fun runSingleImageSegmentation(bitmap: Bitmap): SegmentationMask? =
    suspendCancellableCoroutine { cont ->
      val input = InputImage.fromBitmap(bitmap, 0)
      singleImageSegmenter
        .process(input)
        .addOnSuccessListener { mask -> if (cont.isActive) cont.resume(mask) }
        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

  private fun fastStackBlur(bitmap: Bitmap, radius: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)

    // 2-pass separable box blur approximation (fast & creamy)
    repeat(2) {
      // Horizontal pass
      for (y in 0 until h) {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0
        val rowOffset = y * w
        for (dx in 0..min(radius, w - 1)) {
          val c = pix[rowOffset + dx]
          rSum += Color.red(c)
          gSum += Color.green(c)
          bSum += Color.blue(c)
          count++
        }
        for (x in 0 until w) {
          tmp[rowOffset + x] = Color.argb(255, rSum / count, gSum / count, bSum / count)
          val removeIdx = x - radius
          if (removeIdx >= 0) {
            val c = pix[rowOffset + removeIdx]
            rSum -= Color.red(c)
            gSum -= Color.green(c)
            bSum -= Color.blue(c)
            count--
          }
          val addIdx = x + radius + 1
          if (addIdx < w) {
            val c = pix[rowOffset + addIdx]
            rSum += Color.red(c)
            gSum += Color.green(c)
            bSum += Color.blue(c)
            count++
          }
        }
      }
      // Vertical pass
      for (x in 0 until w) {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0
        for (dy in 0..min(radius, h - 1)) {
          val c = tmp[dy * w + x]
          rSum += Color.red(c)
          gSum += Color.green(c)
          bSum += Color.blue(c)
          count++
        }
        for (y in 0 until h) {
          pix[y * w + x] = Color.argb(255, rSum / count, gSum / count, bSum / count)
          val removeY = y - radius
          if (removeY >= 0) {
            val c = tmp[removeY * w + x]
            rSum -= Color.red(c)
            gSum -= Color.green(c)
            bSum -= Color.blue(c)
            count--
          }
          val addY = y + radius + 1
          if (addY < h) {
            val c = tmp[addY * w + x]
            rSum += Color.red(c)
            gSum += Color.green(c)
            bSum += Color.blue(c)
            count++
          }
        }
      }
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pix, 0, w, 0, 0, w, h)
    return out
  }

  fun close() {
    runCatching { streamSegmenter.close() }
    runCatching { singleImageSegmenter.close() }
  }
}
