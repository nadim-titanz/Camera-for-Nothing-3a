package com.example.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CapturedMediaItem(
  val uri: Uri,
  val displayName: String,
  val isVideo: Boolean,
  val dateAddedSec: Long,
  val originalUneditedUri: Uri? = null,
)

class MediaStoreRepository(private val context: Context) {

  private val originalSidecars = ConcurrentHashMap<Uri, Uri>()

  fun registerOriginalSidecar(editedUri: Uri, originalUri: Uri) {
    originalSidecars[editedUri] = originalUri
  }

  fun getOriginalSidecar(editedUri: Uri): Uri? = originalSidecars[editedUri]

  fun hasEnoughStorage(minFreeBytes: Long = 50L * 1024L * 1024L): Boolean {
    return try {
      val path = Environment.getExternalStorageDirectory() ?: context.filesDir
      val stat = StatFs(path.absolutePath)
      stat.availableBytes >= minFreeBytes
    } catch (_: Exception) {
      true
    }
  }

  fun createPhotoOutputValues(prefix: String = "IMG"): ContentValues {
    val name = "${prefix}_${timestampString()}"
    return ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/NothingCameraPro")
      }
    }
  }

  fun createVideoOutputOptions(): MediaStoreOutputOptions {
    val name = "VID_${timestampString()}"
    val contentValues =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/NothingCameraPro")
        }
      }
    return MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
      )
      .setContentValues(contentValues)
      .build()
  }

  suspend fun saveBitmapToMediaStore(
    bitmap: Bitmap,
    prefix: String = "IMG",
    quality: Int = 95,
  ): Uri? =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      val values = createPhotoOutputValues(prefix)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.put(MediaStore.Images.Media.IS_PENDING, 1)
      }
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
      try {
        resolver.openOutputStream(uri)?.use { out ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
          resolver.update(uri, update, null, null)
        }
        uri
      } catch (e: Exception) {
        resolver.delete(uri, null, null)
        null
      }
    }

  suspend fun copyUriToBackupFile(uri: Uri): File? =
    withContext(Dispatchers.IO) {
      try {
        val dir = File(context.filesDir, "originals").apply { mkdirs() }
        val file = File(dir, "orig_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
          file.outputStream().use { output -> input.copyTo(output) }
        }
        file
      } catch (_: Exception) {
        null
      }
    }

  suspend fun loadAppCapturedMedia(limit: Int = 40): List<CapturedMediaItem> =
    withContext(Dispatchers.IO) {
      val items = mutableListOf<CapturedMediaItem>()
      val resolver = context.contentResolver

      // Query app-created images (zero broad permissions required under scoped storage)
      val imageProjection =
        arrayOf(
          MediaStore.Images.Media._ID,
          MediaStore.Images.Media.DISPLAY_NAME,
          MediaStore.Images.Media.DATE_ADDED,
        )
      val selection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
          null
        }
      val selectionArgs =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          arrayOf("%NothingCameraPro%")
        } else {
          null
        }

      try {
        resolver
          .query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
          )
          ?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext() && items.size < limit) {
              val id = cursor.getLong(idCol)
              val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
              items.add(
                CapturedMediaItem(
                  uri = uri,
                  displayName = cursor.getString(nameCol).orEmpty(),
                  isVideo = false,
                  dateAddedSec = cursor.getLong(dateCol),
                  originalUneditedUri = originalSidecars[uri],
                )
              )
            }
          }
      } catch (_: Exception) {}

      val videoProjection =
        arrayOf(
          MediaStore.Video.Media._ID,
          MediaStore.Video.Media.DISPLAY_NAME,
          MediaStore.Video.Media.DATE_ADDED,
        )
      try {
        resolver
          .query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
          )
          ?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext() && items.size < limit * 2) {
              val id = cursor.getLong(idCol)
              val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
              items.add(
                CapturedMediaItem(
                  uri = uri,
                  displayName = cursor.getString(nameCol).orEmpty(),
                  isVideo = true,
                  dateAddedSec = cursor.getLong(dateCol),
                )
              )
            }
          }
      } catch (_: Exception) {}

      items.sortedByDescending { it.dateAddedSec }.take(limit)
    }

  suspend fun deleteMedia(uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
      try {
        context.contentResolver.delete(uri, null, null) > 0
      } catch (_: Exception) {
        false
      }
    }

  private fun timestampString(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
