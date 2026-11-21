package com.example.core.common

import android.os.Build

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>

  data class Success<T>(val data: T) : UiState<T>

  data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
}

data class DeviceEnvironmentInfo(
  val manufacturer: String,
  val model: String,
  val device: String,
  val brand: String,
  val androidRelease: String,
  val sdkInt: Int,
  val buildDisplay: String,
  val hardware: String,
  val board: String,
) {
  companion object {
    fun current(): DeviceEnvironmentInfo =
      DeviceEnvironmentInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        buildDisplay = Build.DISPLAY.orEmpty(),
        hardware = Build.HARDWARE.orEmpty(),
        board = Build.BOARD.orEmpty(),
      )
  }
}
