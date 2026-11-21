package com.example.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cameraPrefsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "nothing_camera_prefs")

class CameraPreferencesRepository(private val context: Context) {

  private object Keys {
    val GRID_MODE = stringPreferencesKey("grid_mode")
    val SHOW_LEVEL = booleanPreferencesKey("show_level")
    val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
    val MIRROR_SELFIE = booleanPreferencesKey("mirror_selfie")
    val SAVE_ORIGINAL = booleanPreferencesKey("save_original_with_style")
    val CAPTURE_RAW = booleanPreferencesKey("capture_raw")
    val VIDEO_STAB = booleanPreferencesKey("video_stabilization")
    val HAPTICS = booleanPreferencesKey("haptics_enabled")
    val STYLE_ID = stringPreferencesKey("style_id")
    val STYLE_INTENSITY = floatPreferencesKey("style_intensity")
    val VIDEO_RES = stringPreferencesKey("video_resolution")
    val VIDEO_FPS = intPreferencesKey("video_fps")
  }

  val settingsFlow: Flow<CameraSettingsState> =
    context.cameraPrefsDataStore.data.map { prefs ->
      CameraSettingsState(
        gridMode =
          prefs[Keys.GRID_MODE]?.let { runCatching { GridMode.valueOf(it) }.getOrNull() }
            ?: GridMode.GRID_3X3,
        showLevelIndicator = prefs[Keys.SHOW_LEVEL] ?: true,
        aspectRatio =
          prefs[Keys.ASPECT_RATIO]?.let {
            runCatching { CameraAspectRatio.valueOf(it) }.getOrNull()
          } ?: CameraAspectRatio.RATIO_4_3,
        mirrorFrontSelfie = prefs[Keys.MIRROR_SELFIE] ?: true,
        saveOriginalWithStyle = prefs[Keys.SAVE_ORIGINAL] ?: true,
        captureRawWhenSupported = prefs[Keys.CAPTURE_RAW] ?: false,
        videoStabilizationEnabled = prefs[Keys.VIDEO_STAB] ?: true,
        hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
        selectedStyleId = prefs[Keys.STYLE_ID] ?: "natural",
        styleIntensity = prefs[Keys.STYLE_INTENSITY] ?: 1.0f,
        preferredVideoResolution =
          prefs[Keys.VIDEO_RES]?.let {
            runCatching { VideoResolutionOption.valueOf(it) }.getOrNull()
          } ?: VideoResolutionOption.FHD_1080P,
        preferredVideoFps = prefs[Keys.VIDEO_FPS] ?: 30,
      )
    }

  suspend fun setGridMode(mode: GridMode) {
    context.cameraPrefsDataStore.edit { it[Keys.GRID_MODE] = mode.name }
  }

  suspend fun setShowLevel(enabled: Boolean) {
    context.cameraPrefsDataStore.edit { it[Keys.SHOW_LEVEL] = enabled }
  }

  suspend fun setAspectRatio(ratio: CameraAspectRatio) {
    context.cameraPrefsDataStore.edit { it[Keys.ASPECT_RATIO] = ratio.name }
  }

  suspend fun setMirrorFrontSelfie(enabled: Boolean) {
    context.cameraPrefsDataStore.edit { it[Keys.MIRROR_SELFIE] = enabled }
  }

  suspend fun setSaveOriginalWithStyle(enabled: Boolean) {
    context.cameraPrefsDataStore.edit { it[Keys.SAVE_ORIGINAL] = enabled }
  }

  suspend fun setCaptureRaw(enabled: Boolean) {
    context.cameraPrefsDataStore.edit { it[Keys.CAPTURE_RAW] = enabled }
  }

  suspend fun setVideoStabilization(enabled: Boolean) {
    context.cameraPrefsDataStore.edit { it[Keys.VIDEO_STAB] = enabled }
  }

  suspend fun setHapticsEnabled(enabled: Boolean) {
    context.cameraPrefsDataStore.edit { it[Keys.HAPTICS] = enabled }
  }

  suspend fun setPhotographicStyle(styleId: String, intensity: Float) {
    context.cameraPrefsDataStore.edit {
      it[Keys.STYLE_ID] = styleId
      it[Keys.STYLE_INTENSITY] = intensity.coerceIn(0f, 1f)
    }
  }

  suspend fun setVideoQuality(resolution: VideoResolutionOption, fps: Int) {
    context.cameraPrefsDataStore.edit {
      it[Keys.VIDEO_RES] = resolution.name
      it[Keys.VIDEO_FPS] = fps
    }
  }
}
