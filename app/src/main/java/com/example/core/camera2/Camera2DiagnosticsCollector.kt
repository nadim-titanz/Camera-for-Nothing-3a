package com.example.core.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.core.content.ContextCompat
import com.example.core.common.DeviceEnvironmentInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Camera2DiagnosticsCollector(private val context: Context) {

  private val cameraManager: CameraManager? =
    context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

  suspend fun collectDiagnostics(
    hasCameraPermission: Boolean,
    runActiveProbe: Boolean,
  ): HardwareDiagnosticsReport =
    withContext(Dispatchers.IO) {
      val warnings = mutableListOf<String>()
      val probeLogs = mutableListOf<String>()
      val grantedNow =
        hasCameraPermission &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

      val manager = cameraManager
      if (manager == null) {
        return@withContext HardwareDiagnosticsReport(
          generatedAtIso = utcTimestamp(),
          deviceInfo = DeviceEnvironmentInfo.current(),
          hasCameraPermission = grantedNow,
          activeProbeExecuted = false,
          publicCameraIds = emptyList(),
          allDiscoveredCameraIds = emptyList(),
          cameras = emptyList(),
          concurrentCameraGroups = emptyList(),
          zoomProbeLogicalCameraId = null,
          zoomProbeSamples = emptyList(),
          warnings = listOf("CameraManager system service is unavailable on this device."),
          probeLogs = emptyList(),
        )
      }

      val publicIds =
        try {
          manager.cameraIdList.toList()
        } catch (e: Exception) {
          warnings.add("Failed to read cameraIdList: ${e.message}")
          emptyList()
        }

      if (publicIds.isEmpty()) {
        warnings.add("CameraManager.cameraIdList returned 0 cameras.")
      }

      // Discover physical sub-camera IDs referenced by any logical multi-camera
      val physicalToParents = mutableMapOf<String, MutableList<String>>()
      val logicalToPhysicals = mutableMapOf<String, List<String>>()

      for (id in publicIds) {
        try {
          val chars = manager.getCameraCharacteristics(id)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physIds = chars.physicalCameraIds.toList().sorted()
            logicalToPhysicals[id] = physIds
            for (physId in physIds) {
              physicalToParents.getOrPut(physId) { mutableListOf() }.add(id)
            }
          }
        } catch (e: Exception) {
          warnings.add("Could not inspect physical IDs for camera $id: ${e.message}")
        }
      }

      val allDiscoveredIds =
        (publicIds + physicalToParents.keys).distinct().sortedWith(
          compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }
        )

      // Concurrent cameras (API 30+)
      val concurrentGroups =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          try {
            manager.concurrentCameraIds.map { set ->
              ConcurrentCameraGroup(set.toList().sorted())
            }
          } catch (e: Exception) {
            warnings.add("Failed to query concurrentCameraIds: ${e.message}")
            emptyList()
          }
        } else {
          emptyList()
        }

      // Build initial passive Camera2 reports BEFORE CameraX/ExtensionsManager initialization so
      // vendor CameraX extension services never contend with Camera2 openCamera() probes.
      val initialReports =
        allDiscoveredIds.map { id ->
          inspectSingleCamera(
            manager = manager,
            cameraId = id,
            inPublicList = id in publicIds,
            physicalSubIds = logicalToPhysicals[id].orEmpty(),
            parentLogicalIds = physicalToParents[id].orEmpty(),
            cameraXInfo = null,
            cameraXExtensions = emptyList(),
            warnings = warnings,
          )
        }

      var zoomLogicalId: String? = null
      var zoomSamples: List<ZoomProbeSample> = emptyList()
      var probedReports = initialReports

      if (runActiveProbe) {
        if (!grantedNow) {
          warnings.add("Active camera probe skipped: CAMERA runtime permission was denied.")
          probedReports =
            initialReports.map { it.copy(accessibilityStatus = AccessibilityStatus.PermissionRequired) }
        } else if (publicIds.isNotEmpty()) {
          val probeOutcome = runActiveCameraProbes(manager, initialReports)
          probedReports = probeOutcome.updatedReports
          zoomLogicalId = probeOutcome.zoomLogicalCameraId
          zoomSamples = probeOutcome.zoomSamples
          warnings.addAll(probeOutcome.warnings)
          probeLogs.addAll(probeOutcome.logs)
        }
      } else {
        probedReports =
          initialReports.map { cam ->
            val status =
              if (!grantedNow) {
                AccessibilityStatus.PermissionRequired
              } else {
                AccessibilityStatus.NotProbedYet
              }
            cam.copy(accessibilityStatus = status)
          }
      }

      // Collect CameraX metadata, VideoCapabilities & Extensions AFTER Camera2 probes finish
      val cameraXData = collectCameraXMetadata(warnings)
      val finalReports =
        probedReports.map { report ->
          val cxInfo = cameraXData.infoById[report.cameraId]
          val cxExts = cameraXData.extensionsById[report.cameraId].orEmpty()
          val mergedVideoQualities =
            if (cxInfo != null) {
              collectVideoQualities(report.cameraId, cxInfo)
            } else {
              report.videoQualities
            }
          val updatedStatus =
            when (val status = report.accessibilityStatus) {
              is AccessibilityStatus.AccessibleDirect ->
                status.copy(viaCameraX = cxInfo != null || cxExts.isNotEmpty())
              else -> status
            }
          report.copy(
            videoQualities = mergedVideoQualities,
            cameraXExtensions = cxExts,
            accessibilityStatus = updatedStatus,
          )
        }

      HardwareDiagnosticsReport(
        generatedAtIso = utcTimestamp(),
        deviceInfo = DeviceEnvironmentInfo.current(),
        hasCameraPermission = grantedNow,
        activeProbeExecuted = runActiveProbe && grantedNow && publicIds.isNotEmpty(),
        publicCameraIds = publicIds,
        allDiscoveredCameraIds = allDiscoveredIds,
        cameras = finalReports,
        concurrentCameraGroups = concurrentGroups,
        zoomProbeLogicalCameraId = zoomLogicalId,
        zoomProbeSamples = zoomSamples,
        warnings = warnings.distinct(),
        probeLogs = probeLogs,
      )
    }

  private fun inspectSingleCamera(
    manager: CameraManager,
    cameraId: String,
    inPublicList: Boolean,
    physicalSubIds: List<String>,
    parentLogicalIds: List<String>,
    cameraXInfo: CameraInfo?,
    cameraXExtensions: List<ExtensionSupportReport>,
    warnings: MutableList<String>,
  ): CameraDeviceReport {
    val chars =
      try {
        manager.getCameraCharacteristics(cameraId)
      } catch (e: Exception) {
        warnings.add("Failed reading CameraCharacteristics for ID $cameraId: ${e.message}")
        return fallbackEmptyReport(cameraId, inPublicList, physicalSubIds, parentLogicalIds, e.message)
      }

    val facing = CameraCharacteristicsMapper.mapLensFacing(chars.get(CameraCharacteristics.LENS_FACING))
    val capabilities =
      CameraCharacteristicsMapper.mapCapabilities(
        chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
      )
    val isLogicalMultiCam =
      "LOGICAL_MULTI_CAMERA" in capabilities || physicalSubIds.isNotEmpty()

    val classification =
      when {
        isLogicalMultiCam -> CameraClassification.LOGICAL_MULTI_CAMERA
        parentLogicalIds.isNotEmpty() && inPublicList ->
          CameraClassification.PHYSICAL_PUBLIC_SUB_CAMERA
        parentLogicalIds.isNotEmpty() && !inPublicList ->
          CameraClassification.PHYSICAL_SUB_CAMERA
        else -> CameraClassification.STANDALONE_PHYSICAL
      }

    val focalLengths =
      chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
    val sensorSizeF = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val sensorSizeMm = sensorSizeF?.let { SizeFloat(it.width, it.height) }
    val eq35mm =
      CameraCharacteristicsMapper.compute35mmEquivalentFocalLength(focalLengths, sensorSizeMm)

    val pixelArray =
      chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
        SizeInt(it.width, it.height)
      }
    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString()
    val apertures =
      chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
    val minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    val minFocusCm =
      if (minFocusDiopters != null && minFocusDiopters > 0f) 100f / minFocusDiopters else null

    val hardwareLevel =
      CameraCharacteristicsMapper.mapHardwareLevel(
        chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
      )

    val zoomRange =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
          range.lower..range.upper
        }
      } else {
        null
      }
    val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)

    val oisModes =
      CameraCharacteristicsMapper.mapOisModes(
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
      )
    val eisModes =
      CameraCharacteristicsMapper.mapEisModes(
        chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
      )
    val afModes =
      CameraCharacteristicsMapper.mapAfModes(
        chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
      )
    val aeModes =
      CameraCharacteristicsMapper.mapAeModes(
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
      )
    val awbModes =
      CameraCharacteristicsMapper.mapAwbModes(
        chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
      )
    val flashSupported = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    val isoRangeRaw = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val exposureRangeRaw = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val evRangeRaw = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val evStepRaw = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

    val manualExposure =
      ManualExposureCapabilities(
        hasManualSensorCapability = "MANUAL_SENSOR" in capabilities,
        isoRange = isoRangeRaw?.let { it.lower..it.upper },
        exposureTimeRangeNs = exposureRangeRaw?.let { it.lower..it.upper },
        evRange = evRangeRaw?.let { it.lower..it.upper },
        evStep = evStepRaw?.toFloat(),
      )

    val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val jpegSizes =
      streamMap
        ?.getOutputSizes(ImageFormat.JPEG)
        ?.map { SizeInt(it.width, it.height) }
        ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
        ?: emptyList()
    val yuvSizes =
      streamMap
        ?.getOutputSizes(ImageFormat.YUV_420_888)
        ?.map { SizeInt(it.width, it.height) }
        ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
        ?: emptyList()
    val rawSizes =
      streamMap
        ?.getOutputSizes(ImageFormat.RAW_SENSOR)
        ?.map { SizeInt(it.width, it.height) }
        ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
        ?: emptyList()
    val videoSizes =
      streamMap
        ?.getOutputSizes(MediaRecorder::class.java)
        ?.map { SizeInt(it.width, it.height) }
        ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
        ?: emptyList()

    val maxResSizes =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        chars
          .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
          ?.getOutputSizes(ImageFormat.JPEG)
          ?.map { SizeInt(it.width, it.height) }
          ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
          ?: emptyList()
      } else {
        emptyList()
      }

    val fpsRanges =
      chars
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.map { FpsRangeData(it.lower, it.upper) }
        ?.sortedWith(compareBy<FpsRangeData> { it.maxFps }.thenBy { it.minFps })
        ?: emptyList()

    val highSpeedConfigs =
      try {
        streamMap
          ?.highSpeedVideoSizes
          ?.map { size ->
            val ranges =
              streamMap
                .getHighSpeedVideoFpsRangesFor(size)
                .map { FpsRangeData(it.lower, it.upper) }
                .sortedBy { it.maxFps }
            HighSpeedVideoConfig(size.width, size.height, ranges)
          }
          ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
          ?: emptyList()
      } catch (_: Exception) {
        emptyList()
      }

    val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
    val supportsSceneHdr = sceneModes.any { it == CameraCharacteristics.CONTROL_SCENE_MODE_HDR }
    val dynamicProfiles =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val cap = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        if (cap != null) {
          CameraCharacteristicsMapper.mapDynamicRangeProfiles(cap.supportedProfiles)
        } else {
          emptyList()
        }
      } else {
        emptyList()
      }

    val videoQualities = collectVideoQualities(cameraId, cameraXInfo)

    return CameraDeviceReport(
      cameraId = cameraId,
      inPublicCameraIdList = inPublicList,
      inferredName =
        CameraCharacteristicsMapper.inferCameraDisplayName(
          cameraId = cameraId,
          facing = facing,
          classification = classification,
          eq35mm = eq35mm,
          physicalCount = physicalSubIds.size,
        ),
      lensFacing = facing,
      classification = classification,
      physicalCameraIds = physicalSubIds,
      parentLogicalIds = parentLogicalIds,
      focalLengthsMm = focalLengths,
      equivalentFocalLength35mm = eq35mm,
      sensorPhysicalSizeMm = sensorSizeMm,
      pixelArraySize = pixelArray,
      activeArraySize = activeArray,
      apertures = apertures,
      minimumFocusDistanceDiopters = minFocusDiopters,
      estimatedMinFocusDistanceCm = minFocusCm,
      hardwareLevel = hardwareLevel,
      zoomRatioRange = zoomRange,
      maxDigitalZoom = maxDigitalZoom,
      oisModes = oisModes,
      eisModes = eisModes,
      afModes = afModes,
      aeModes = aeModes,
      awbModes = awbModes,
      flashSupported = flashSupported,
      manualExposure = manualExposure,
      rawCapabilities =
        RawCapabilities(
          hasRawCapability = "RAW" in capabilities || rawSizes.isNotEmpty(),
          rawOutputSizes = rawSizes,
        ),
      hdrCapabilities =
        HdrCapabilities(supportsSceneModeHdr = supportsSceneHdr, dynamicRangeProfiles = dynamicProfiles),
      jpegOutputSizes = jpegSizes,
      yuvOutputSizes = yuvSizes,
      maxResolutionOutputSizes = maxResSizes,
      videoOutputSizes = videoSizes,
      supportedFpsRanges = fpsRanges,
      highSpeedConfigs = highSpeedConfigs,
      videoQualities = videoQualities,
      cameraXExtensions = cameraXExtensions,
      requestedCapabilities = capabilities,
      accessibilityStatus = AccessibilityStatus.NotProbedYet,
    )
  }

  private fun collectVideoQualities(
    cameraId: String,
    cameraXInfo: CameraInfo?,
  ): List<VideoQualityReport> {
    val results = mutableListOf<VideoQualityReport>()
    val numericId = cameraId.toIntOrNull()

    if (cameraXInfo != null) {
      try {
        val videoCaps = Recorder.getVideoCapabilities(cameraXInfo)
        val hdrRanges = videoCaps.supportedDynamicRanges.filter { it != DynamicRange.SDR }
        val qualities =
          listOf(
            Quality.UHD to "4K UHD",
            Quality.FHD to "1080p FHD",
            Quality.HD to "720p HD",
            Quality.SD to "480p SD",
          )
        for ((quality, label) in qualities) {
          val sdrProfile = videoCaps.getProfiles(quality, DynamicRange.SDR)
          if (sdrProfile != null) {
            val videoProf = sdrProfile.videoProfiles.firstOrNull()
            val fpsList = sdrProfile.videoProfiles.map { it.frameRate }.distinct().sorted()
            val hasHdr = hdrRanges.any { dr -> videoCaps.getProfiles(quality, dr) != null }
            if (videoProf != null) {
              results.add(
                VideoQualityReport(
                  qualityLabel = label,
                  width = videoProf.width,
                  height = videoProf.height,
                  frameRates = fpsList,
                  source = "CameraX VideoCapabilities",
                  supportsHdr10Bit = hasHdr,
                )
              )
            }
          }
        }
      } catch (_: Exception) {}
    }

    if (numericId != null) {
      val profileSpecs =
        listOf(
          CamcorderProfile.QUALITY_2160P to "4K (2160p)",
          CamcorderProfile.QUALITY_1080P to "1080p",
          CamcorderProfile.QUALITY_720P to "720p",
          CamcorderProfile.QUALITY_HIGH_SPEED_1080P to "1080p High-Speed",
          CamcorderProfile.QUALITY_HIGH_SPEED_720P to "720p High-Speed",
        )
      for ((qualityConst, label) in profileSpecs) {
        if (CamcorderProfile.hasProfile(numericId, qualityConst)) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val enc =
              CamcorderProfile.getAll(cameraId, qualityConst)?.videoProfiles?.firstOrNull()
            if (enc != null) {
              results.add(
                VideoQualityReport(
                  qualityLabel = label,
                  width = enc.width,
                  height = enc.height,
                  frameRates = listOf(enc.frameRate),
                  source = "EncoderProfiles (API 31+)",
                  supportsHdr10Bit =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && enc.bitDepth >= 10,
                )
              )
            }
          } else {
            @Suppress("DEPRECATION")
            val cp = CamcorderProfile.get(numericId, qualityConst)
            results.add(
              VideoQualityReport(
                qualityLabel = label,
                width = cp.videoFrameWidth,
                height = cp.videoFrameHeight,
                frameRates = listOf(cp.videoFrameRate),
                source = "CamcorderProfile",
              )
            )
          }
        }
      }
    }

    return results.distinctBy {
      "${it.qualityLabel}|${it.width}x${it.height}|${it.frameRates}|${it.source}"
    }
  }

  private data class CameraXCollection(
    val infoById: Map<String, CameraInfo>,
    val extensionsById: Map<String, List<ExtensionSupportReport>>,
  )

  @OptIn(ExperimentalCamera2Interop::class)
  private suspend fun collectCameraXMetadata(warnings: MutableList<String>): CameraXCollection {
    return try {
      val provider =
        suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
          val future = ProcessCameraProvider.getInstance(context)
          future.addListener(
            {
              try {
                if (cont.isActive) cont.resume(future.get())
              } catch (e: Exception) {
                if (cont.isActive) cont.cancel(e)
              }
            },
            ContextCompat.getMainExecutor(context),
          )
        }

      val extManager =
        try {
          suspendCancellableCoroutine<ExtensionsManager> { cont ->
            val future = ExtensionsManager.getInstanceAsync(context, provider)
            future.addListener(
              {
                try {
                  if (cont.isActive) cont.resume(future.get())
                } catch (e: Exception) {
                  if (cont.isActive) cont.cancel(e)
                }
              },
              ContextCompat.getMainExecutor(context),
            )
          }
        } catch (e: Exception) {
          warnings.add("ExtensionsManager unavailable: ${e.message}")
          null
        }

      val infoMap = mutableMapOf<String, CameraInfo>()
      val extMap = mutableMapOf<String, List<ExtensionSupportReport>>()
      val modes =
        listOf(
          ExtensionMode.AUTO to "AUTO",
          ExtensionMode.BOKEH to "BOKEH",
          ExtensionMode.HDR to "HDR",
          ExtensionMode.NIGHT to "NIGHT",
          ExtensionMode.FACE_RETOUCH to "FACE_RETOUCH",
        )

      for (info in provider.availableCameraInfos) {
        val cam2Id = Camera2CameraInfo.from(info).cameraId
        infoMap[cam2Id] = info

        if (extManager != null) {
          val selector =
            CameraSelector.Builder()
              .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == cam2Id }
              }
              .build()
          val modeReports =
            modes.map { (mode, name) ->
              val available =
                try {
                  extManager.isExtensionAvailable(selector, mode)
                } catch (_: Exception) {
                  false
                }
              ExtensionSupportReport(name, available)
            }
          extMap[cam2Id] = modeReports
        }
      }
      CameraXCollection(infoMap, extMap)
    } catch (e: Exception) {
      warnings.add("CameraX initialization skipped/failed: ${e.message}")
      CameraXCollection(emptyMap(), emptyMap())
    }
  }

  private data class ActiveProbeOutcome(
    val updatedReports: List<CameraDeviceReport>,
    val zoomLogicalCameraId: String?,
    val zoomSamples: List<ZoomProbeSample>,
    val warnings: List<String>,
    val logs: List<String>,
  )

  @SuppressLint("MissingPermission")
  private suspend fun runActiveCameraProbes(
    manager: CameraManager,
    reports: List<CameraDeviceReport>,
  ): ActiveProbeOutcome {
    val probeWarnings = mutableListOf<String>()
    val logs = mutableListOf<String>()
    val thread = HandlerThread("CameraDiagnosticsProbe").apply { start() }
    val handler = Handler(thread.looper)

    fun recordLog(msg: String) {
      Log.d(TAG, msg)
      logs.add(msg)
    }

    try {
      val accessResults = mutableMapOf<String, AccessibilityStatus>()

      // Probe cameras strictly one at a time, fully closing each CameraDevice & session
      // (awaiting onClosed) before opening the next camera.
      for (cam in reports) {
        if (cam.inPublicCameraIdList) {
          recordLog("Probing public camera ID=${cam.cameraId} via direct openCamera()")
          val openError = probeDirectOpen(manager, cam.cameraId, handler, ::recordLog)
          accessResults[cam.cameraId] =
            if (openError == null) {
              AccessibilityStatus.AccessibleDirect(viaCameraX = false)
            } else {
              probeWarnings.add("Camera ${cam.cameraId}: $openError")
              AccessibilityStatus.RestrictedOrFailed(openError)
            }
        } else {
          recordLog("Probing hidden physical camera ID=${cam.cameraId}")
          val directErr = probeDirectOpen(manager, cam.cameraId, handler, ::recordLog)
          if (directErr == null) {
            accessResults[cam.cameraId] = AccessibilityStatus.AccessibleDirect(viaCameraX = false)
          } else {
            val parentId = cam.parentLogicalIds.firstOrNull()
            if (parentId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              recordLog(
                "Direct open failed for physical ID=${cam.cameraId} ($directErr); probing physical stream via parent ID=$parentId"
              )
              val streamErr =
                probePhysicalSubStream(manager, parentId, cam.cameraId, handler, ::recordLog)
              accessResults[cam.cameraId] =
                if (streamErr == null) {
                  AccessibilityStatus.AccessibleViaPhysicalStream(parentId)
                } else {
                  probeWarnings.add("Physical camera ${cam.cameraId} via $parentId: $streamErr")
                  AccessibilityStatus.RestrictedOrFailed(
                    "Direct: $directErr | PhysicalStream: $streamErr"
                  )
                }
            } else {
              probeWarnings.add("Camera ${cam.cameraId}: $directErr")
              accessResults[cam.cameraId] = AccessibilityStatus.RestrictedOrFailed(directErr)
            }
          }
        }
      }

      // Live Zoom Ratio Sweep on primary rear logical (or rear main) camera after all open probes closed
      val rearCandidate =
        reports.firstOrNull {
          it.lensFacing == LensFacingType.BACK &&
            it.inPublicCameraIdList &&
            it.classification == CameraClassification.LOGICAL_MULTI_CAMERA
        }
          ?: reports.firstOrNull {
            it.lensFacing == LensFacingType.BACK && it.inPublicCameraIdList
          }

      val zoomSamples =
        if (rearCandidate != null) {
          recordLog("Starting zoom sweep probe on rear camera ID=${rearCandidate.cameraId}")
          runZoomSweepProbe(manager, rearCandidate, handler, ::recordLog)
        } else {
          emptyList()
        }

      val updated =
        reports.map { cam ->
          cam.copy(accessibilityStatus = accessResults[cam.cameraId] ?: cam.accessibilityStatus)
        }
      return ActiveProbeOutcome(
        updatedReports = updated,
        zoomLogicalCameraId = rearCandidate?.cameraId,
        zoomSamples = zoomSamples,
        warnings = probeWarnings,
        logs = logs,
      )
    } finally {
      thread.quitSafely()
    }
  }

  @SuppressLint("MissingPermission")
  private suspend fun probeDirectOpen(
    manager: CameraManager,
    cameraId: String,
    handler: Handler,
    log: (String) -> Unit,
  ): String? {
    val closeState = AtomicBoolean(false)
    val timedOutOrCancelled = AtomicBoolean(false)
    var openedCameraRef: CameraDevice? = null
    val terminalError = AtomicReference<String?>(null)

    val openResult =
      withTimeoutOrNull(OPEN_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
          handler.post {
            if (cont.isCancelled) return@post
            try {
              manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                  override fun onOpened(camera: CameraDevice) {
                    log("Camera $cameraId: onOpened()")
                    openedCameraRef = camera
                    if (timedOutOrCancelled.get() || !cont.isActive) {
                      log("Camera $cameraId: opened after timeout/cancellation, closing immediately")
                      camera.close()
                      return
                    }
                    try {
                      camera.close()
                    } catch (e: Exception) {
                      Log.w(TAG, "Camera $cameraId: close() exception: ${e.message}")
                    }
                  }

                  override fun onClosed(camera: CameraDevice) {
                    log("Camera $cameraId: onClosed()")
                    closeState.set(true)
                    if (cont.isActive) cont.resume(terminalError.get())
                  }

                  override fun onDisconnected(camera: CameraDevice) {
                    val msg = "onDisconnected(camera=$cameraId)"
                    Log.w(TAG, msg)
                    log("Camera $cameraId: $msg")
                    openedCameraRef = camera
                    val errText = "Disconnected during probe ($msg)"
                    terminalError.set(errText)
                    try {
                      camera.close()
                    } catch (_: Exception) {}
                    handler.postDelayed(
                      { if (cont.isActive) cont.resume(terminalError.get()) },
                      CLOSE_FALLBACK_DELAY_MS,
                    )
                  }

                  override fun onError(camera: CameraDevice, error: Int) {
                    val errorName = cameraDeviceErrorName(error)
                    val msg = "onError(camera=$cameraId, code=$error [$errorName])"
                    Log.e(TAG, msg)
                    log("Camera $cameraId: $msg")
                    openedCameraRef = camera
                    val errText = "CameraDevice $errorName (code $error)"
                    terminalError.set(errText)
                    try {
                      camera.close()
                    } catch (_: Exception) {}
                    handler.postDelayed(
                      { if (cont.isActive) cont.resume(terminalError.get()) },
                      CLOSE_FALLBACK_DELAY_MS,
                    )
                  }
                },
                handler,
              )
            } catch (e: CameraAccessException) {
              val msg = "CameraAccessException(reason=${e.reason}): ${e.message}"
              Log.e(TAG, "Camera $cameraId: $msg", e)
              log("Camera $cameraId: $msg")
              if (cont.isActive) cont.resume(msg)
            } catch (e: IllegalArgumentException) {
              val msg = "IllegalArgumentException (not directly openable): ${e.message}"
              Log.w(TAG, "Camera $cameraId: $msg")
              log("Camera $cameraId: $msg")
              if (cont.isActive) cont.resume(msg)
            } catch (e: SecurityException) {
              val msg = "SecurityException: ${e.message}"
              Log.e(TAG, "Camera $cameraId: $msg", e)
              log("Camera $cameraId: $msg")
              if (cont.isActive) cont.resume(msg)
            } catch (e: Exception) {
              val msg = "Exception(${e.javaClass.simpleName}): ${e.message}"
              Log.e(TAG, "Camera $cameraId: $msg", e)
              log("Camera $cameraId: $msg")
              if (cont.isActive) cont.resume(msg)
            }
          }

          cont.invokeOnCancellation {
            timedOutOrCancelled.set(true)
            handler.post {
              openedCameraRef?.let { cam ->
                try {
                  log("Camera $cameraId: closing in invokeOnCancellation")
                  cam.close()
                } catch (_: Exception) {}
              }
            }
          }
        }
      }

    if (openResult == null && !closeState.get()) {
      timedOutOrCancelled.set(true)
      awaitCloseOnHandler(openedCameraRef, null, null, handler, cameraId, log)
      val err =
        terminalError.get() ?: "Timed out opening camera $cameraId after ${OPEN_TIMEOUT_MS}ms"
      Log.w(TAG, err)
      log(err)
      return err
    }

    return openResult
  }

  @SuppressLint("MissingPermission")
  private suspend fun probePhysicalSubStream(
    manager: CameraManager,
    logicalCameraId: String,
    physicalCameraId: String,
    handler: Handler,
    log: (String) -> Unit,
  ): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "Requires API 28+"
    val closedDone = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    var deviceRef: CameraDevice? = null
    var sessionRef: CameraCaptureSession? = null
    var readerRef: ImageReader? = null

    val result =
      withTimeoutOrNull(SESSION_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
          handler.post {
            if (cont.isCancelled) return@post
            try {
              val chars = manager.getCameraCharacteristics(physicalCameraId)
              val size =
                chars
                  .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                  ?.getOutputSizes(ImageFormat.YUV_420_888)
                  ?.minByOrNull { it.width * it.height }
                  ?: android.util.Size(640, 480)
              val reader =
                ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
              readerRef = reader
              val surface = reader.surface

              manager.openCamera(
                logicalCameraId,
                object : CameraDevice.StateCallback() {
                  override fun onOpened(camera: CameraDevice) {
                    log("PhysicalProbe($physicalCameraId via $logicalCameraId): onOpened()")
                    deviceRef = camera
                    if (cancelled.get() || !cont.isActive) {
                      camera.close()
                      reader.close()
                      return
                    }
                    try {
                      val outputConfig =
                        OutputConfiguration(surface).apply {
                          setPhysicalCameraId(physicalCameraId)
                        }
                      val sessionConfig =
                        SessionConfiguration(
                          SessionConfiguration.SESSION_REGULAR,
                          listOf(outputConfig),
                          { runnable -> handler.post(runnable) },
                          object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                              log("PhysicalProbe($physicalCameraId): session onConfigured()")
                              sessionRef = session
                              closeResources(camera, session, null)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                              val msg =
                                "SessionConfiguration failed for physical ID $physicalCameraId"
                              Log.w(TAG, msg)
                              log("PhysicalProbe($physicalCameraId): $msg")
                              sessionRef = session
                              closeResources(camera, session, null)
                              if (cont.isActive) cont.resume(msg)
                            }
                          },
                        )
                      camera.createCaptureSession(sessionConfig)
                    } catch (e: Exception) {
                      val msg = "Session error: ${e.message}"
                      Log.e(TAG, "PhysicalProbe($physicalCameraId): $msg", e)
                      log("PhysicalProbe($physicalCameraId): $msg")
                      closeResources(camera, null, reader)
                      if (cont.isActive) cont.resume(msg)
                    }
                  }

                  override fun onClosed(camera: CameraDevice) {
                    log("PhysicalProbe($physicalCameraId via $logicalCameraId): onClosed()")
                    closedDone.set(true)
                    try {
                      readerRef?.close()
                    } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(null)
                  }

                  override fun onDisconnected(camera: CameraDevice) {
                    val msg = "onDisconnected(logical=$logicalCameraId, physical=$physicalCameraId)"
                    Log.w(TAG, msg)
                    log(msg)
                    deviceRef = camera
                    closeResources(camera, sessionRef, readerRef)
                    if (cont.isActive) cont.resume(msg)
                  }

                  override fun onError(camera: CameraDevice, error: Int) {
                    val errName = cameraDeviceErrorName(error)
                    val msg = "onError(logical=$logicalCameraId, physical=$physicalCameraId, $errName [$error])"
                    Log.e(TAG, msg)
                    log(msg)
                    deviceRef = camera
                    closeResources(camera, sessionRef, readerRef)
                    if (cont.isActive) cont.resume("CameraDevice $errName (code $error)")
                  }
                },
                handler,
              )
            } catch (e: Exception) {
              val msg = "Probe error: ${e.message}"
              Log.e(TAG, "PhysicalProbe($physicalCameraId): $msg", e)
              log(msg)
              closeResources(deviceRef, sessionRef, readerRef)
              if (cont.isActive) cont.resume(msg)
            }
          }

          cont.invokeOnCancellation {
            cancelled.set(true)
            handler.post { closeResources(deviceRef, sessionRef, readerRef) }
          }
        }
      }

    if (result == null && !closedDone.get()) {
      cancelled.set(true)
      awaitCloseOnHandler(deviceRef, sessionRef, readerRef, handler, logicalCameraId, log)
      val timeoutMsg =
        "Timed out configuring physical stream $physicalCameraId via $logicalCameraId after ${SESSION_TIMEOUT_MS}ms"
      Log.w(TAG, timeoutMsg)
      log(timeoutMsg)
      return timeoutMsg
    }

    return result
  }

  @SuppressLint("MissingPermission")
  private suspend fun runZoomSweepProbe(
    manager: CameraManager,
    cameraReport: CameraDeviceReport,
    handler: Handler,
    log: (String) -> Unit,
  ): List<ZoomProbeSample> {
    val targetRatios = listOf(0.5f, 0.6f, 0.8f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f)
    val supportedRange =
      cameraReport.zoomRatioRange ?: (1.0f..(cameraReport.maxDigitalZoom ?: 1.0f))
    val validRatios =
      targetRatios.map { it.coerceIn(supportedRange.start, supportedRange.endInclusive) }.distinct()

    val closedDone = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    var deviceRef: CameraDevice? = null
    var sessionRef: CameraCaptureSession? = null
    var readerRef: ImageReader? = null

    val samples =
      withTimeoutOrNull(ZOOM_SWEEP_TIMEOUT_MS) {
        suspendCancellableCoroutine<List<ZoomProbeSample>> { cont ->
          handler.post {
            if (cont.isCancelled) return@post
            try {
              val yuvSize =
                cameraReport.yuvOutputSizes.lastOrNull { it.width >= 640 && it.height >= 480 }
                  ?: SizeInt(640, 480)
              val reader =
                ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3)
              readerRef = reader
              reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
              val surface = reader.surface

              var collectedSamples: List<ZoomProbeSample>? = null

              manager.openCamera(
                cameraReport.cameraId,
                object : CameraDevice.StateCallback() {
                  override fun onOpened(camera: CameraDevice) {
                    log("ZoomSweep(${cameraReport.cameraId}): onOpened()")
                    deviceRef = camera
                    if (cancelled.get() || !cont.isActive) {
                      camera.close()
                      reader.close()
                      return
                    }
                    try {
                      val sessionCallback =
                        object : CameraCaptureSession.StateCallback() {
                          override fun onConfigured(session: CameraCaptureSession) {
                            log("ZoomSweep(${cameraReport.cameraId}): session onConfigured()")
                            sessionRef = session
                            captureZoomStepsSequentially(
                              camera = camera,
                              session = session,
                              surface = surface,
                              ratios = validRatios,
                              handler = handler,
                              log = log,
                              onComplete = { resultSamples ->
                                collectedSamples = resultSamples
                                // Close session & camera; cont.resume happens in onClosed()
                                closeResources(camera, session, null)
                              },
                            )
                          }

                          override fun onConfigureFailed(session: CameraCaptureSession) {
                            log("ZoomSweep(${cameraReport.cameraId}): session onConfigureFailed()")
                            sessionRef = session
                            collectedSamples =
                              listOf(
                                ZoomProbeSample(
                                  requestedZoomRatio = 1.0f,
                                  reportedZoomRatio = null,
                                  activePhysicalCameraId = null,
                                  reportedFocalLengthMm = null,
                                  cropRegion = null,
                                  statusMessage = "CaptureSession configure failed",
                                )
                              )
                            closeResources(camera, session, null)
                          }
                        }

                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val sessionConfig =
                          SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(surface)),
                            { runnable -> handler.post(runnable) },
                            sessionCallback,
                          )
                        camera.createCaptureSession(sessionConfig)
                      } else {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(surface), sessionCallback, handler)
                      }
                    } catch (e: Exception) {
                      val msg = "ZoomSweep session error: ${e.message}"
                      Log.e(TAG, msg, e)
                      log(msg)
                      closeResources(camera, sessionRef, reader)
                      if (cont.isActive) cont.resume(emptyList())
                    }
                  }

                  override fun onClosed(camera: CameraDevice) {
                    log("ZoomSweep(${cameraReport.cameraId}): onClosed()")
                    closedDone.set(true)
                    try {
                      readerRef?.close()
                    } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(collectedSamples ?: emptyList())
                  }

                  override fun onDisconnected(camera: CameraDevice) {
                    val msg = "ZoomSweep(${cameraReport.cameraId}): onDisconnected()"
                    Log.w(TAG, msg)
                    log(msg)
                    deviceRef = camera
                    closeResources(camera, sessionRef, readerRef)
                    if (cont.isActive) {
                      cont.resume(
                        collectedSamples
                          ?: listOf(
                            ZoomProbeSample(
                              requestedZoomRatio = 1.0f,
                              reportedZoomRatio = null,
                              activePhysicalCameraId = null,
                              reportedFocalLengthMm = null,
                              cropRegion = null,
                              statusMessage = "Camera disconnected during zoom sweep",
                            )
                          )
                      )
                    }
                  }

                  override fun onError(camera: CameraDevice, error: Int) {
                    val errName = cameraDeviceErrorName(error)
                    val msg = "ZoomSweep(${cameraReport.cameraId}): onError($errName [$error])"
                    Log.e(TAG, msg)
                    log(msg)
                    deviceRef = camera
                    closeResources(camera, sessionRef, readerRef)
                    if (cont.isActive) {
                      cont.resume(
                        collectedSamples
                          ?: listOf(
                            ZoomProbeSample(
                              requestedZoomRatio = 1.0f,
                              reportedZoomRatio = null,
                              activePhysicalCameraId = null,
                              reportedFocalLengthMm = null,
                              cropRegion = null,
                              statusMessage = "Camera error: $errName ($error)",
                            )
                          )
                      )
                    }
                  }
                },
                handler,
              )
            } catch (e: Exception) {
              val msg = "ZoomSweep openCamera error: ${e.message}"
              Log.e(TAG, msg, e)
              log(msg)
              closeResources(deviceRef, sessionRef, readerRef)
              if (cont.isActive) cont.resume(emptyList())
            }
          }

          cont.invokeOnCancellation {
            cancelled.set(true)
            handler.post { closeResources(deviceRef, sessionRef, readerRef) }
          }
        }
      }

    if (samples == null) {
      cancelled.set(true)
      awaitCloseOnHandler(deviceRef, sessionRef, readerRef, handler, cameraReport.cameraId, log)
      val timeoutMsg =
        "ZoomSweep(${cameraReport.cameraId}) timed out after ${ZOOM_SWEEP_TIMEOUT_MS}ms"
      Log.w(TAG, timeoutMsg)
      log(timeoutMsg)
      return listOf(
        ZoomProbeSample(
          requestedZoomRatio = 1.0f,
          reportedZoomRatio = null,
          activePhysicalCameraId = null,
          reportedFocalLengthMm = null,
          cropRegion = null,
          statusMessage = timeoutMsg,
        )
      )
    }

    return samples
  }

  private fun captureZoomStepsSequentially(
    camera: CameraDevice,
    session: CameraCaptureSession,
    surface: android.view.Surface,
    ratios: List<Float>,
    handler: Handler,
    log: (String) -> Unit,
    onComplete: (List<ZoomProbeSample>) -> Unit,
  ) {
    val samples = mutableListOf<ZoomProbeSample>()

    fun captureIndex(idx: Int) {
      if (idx >= ratios.size) {
        onComplete(samples)
        return
      }
      val ratio = ratios[idx]
      try {
        val builder =
          camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
            }
          }
        val requests = List(3) { builder.build() }
        var handledCount = 0
        var recordedForStep = false
        session.captureBurst(
          requests,
          object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
              sess: CameraCaptureSession,
              request: CaptureRequest,
              result: TotalCaptureResult,
            ) {
              handledCount++
              if (!recordedForStep && handledCount == requests.size) {
                recordedForStep = true
                val activePhys =
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    result.get(TotalCaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                  } else {
                    null
                  }
                val reportedRatio =
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    result.get(TotalCaptureResult.CONTROL_ZOOM_RATIO)
                  } else {
                    null
                  }
                val focal = result.get(TotalCaptureResult.LENS_FOCAL_LENGTH)
                val crop = result.get(TotalCaptureResult.SCALER_CROP_REGION)?.toShortString()
                log(
                  "ZoomSweep step ${ratio}x -> activePhysicalId=$activePhys, reportedZoom=$reportedRatio, focal=$focal"
                )
                samples.add(
                  ZoomProbeSample(
                    requestedZoomRatio = ratio,
                    reportedZoomRatio = reportedRatio,
                    activePhysicalCameraId = activePhys,
                    reportedFocalLengthMm = focal,
                    cropRegion = crop,
                    statusMessage = "Captured",
                  )
                )
                captureIndex(idx + 1)
              }
            }

            override fun onCaptureFailed(
              sess: CameraCaptureSession,
              request: CaptureRequest,
              failure: CaptureFailure,
            ) {
              handledCount++
              if (!recordedForStep && handledCount == requests.size) {
                recordedForStep = true
                val msg = "CaptureFailed reason=${failure.reason}"
                log("ZoomSweep step ${ratio}x -> $msg")
                samples.add(
                  ZoomProbeSample(
                    requestedZoomRatio = ratio,
                    reportedZoomRatio = null,
                    activePhysicalCameraId = null,
                    reportedFocalLengthMm = null,
                    cropRegion = null,
                    statusMessage = msg,
                  )
                )
                captureIndex(idx + 1)
              }
            }

            override fun onCaptureSequenceAborted(sess: CameraCaptureSession, sequenceId: Int) {
              if (!recordedForStep) {
                recordedForStep = true
                val msg = "CaptureSequenceAborted(seq=$sequenceId)"
                log("ZoomSweep step ${ratio}x -> $msg")
                samples.add(
                  ZoomProbeSample(
                    requestedZoomRatio = ratio,
                    reportedZoomRatio = null,
                    activePhysicalCameraId = null,
                    reportedFocalLengthMm = null,
                    cropRegion = null,
                    statusMessage = msg,
                  )
                )
                captureIndex(idx + 1)
              }
            }
          },
          handler,
        )
      } catch (e: Exception) {
        val msg = "Failed: ${e.message}"
        log("ZoomSweep step ${ratio}x -> $msg")
        samples.add(
          ZoomProbeSample(
            requestedZoomRatio = ratio,
            reportedZoomRatio = null,
            activePhysicalCameraId = null,
            reportedFocalLengthMm = null,
            cropRegion = null,
            statusMessage = msg,
          )
        )
        captureIndex(idx + 1)
      }
    }

    captureIndex(0)
  }

  private fun closeResources(
    camera: CameraDevice?,
    session: CameraCaptureSession?,
    reader: ImageReader?,
  ) {
    try {
      session?.close()
    } catch (_: Exception) {}
    try {
      camera?.close()
    } catch (_: Exception) {}
    try {
      reader?.close()
    } catch (_: Exception) {}
  }

  private suspend fun awaitCloseOnHandler(
    camera: CameraDevice?,
    session: CameraCaptureSession?,
    reader: ImageReader?,
    handler: Handler,
    cameraId: String,
    log: (String) -> Unit,
  ) {
    withTimeoutOrNull(1500L) {
      suspendCancellableCoroutine<Unit> { cont ->
        handler.post {
          closeResources(camera, session, reader)
          log("Camera $cameraId: forced cleanup executed on HandlerThread")
          if (cont.isActive) cont.resume(Unit)
        }
      }
    }
  }

  private fun fallbackEmptyReport(
    cameraId: String,
    inPublicList: Boolean,
    physicalSubIds: List<String>,
    parentLogicalIds: List<String>,
    errorMessage: String?,
  ): CameraDeviceReport =
    CameraDeviceReport(
      cameraId = cameraId,
      inPublicCameraIdList = inPublicList,
      inferredName = "Camera ID $cameraId",
      lensFacing = LensFacingType.UNKNOWN,
      classification = CameraClassification.STANDALONE_PHYSICAL,
      physicalCameraIds = physicalSubIds,
      parentLogicalIds = parentLogicalIds,
      focalLengthsMm = emptyList(),
      equivalentFocalLength35mm = null,
      sensorPhysicalSizeMm = null,
      pixelArraySize = null,
      activeArraySize = null,
      apertures = emptyList(),
      minimumFocusDistanceDiopters = null,
      estimatedMinFocusDistanceCm = null,
      hardwareLevel = HardwareSupportLevel.UNKNOWN,
      zoomRatioRange = null,
      maxDigitalZoom = null,
      oisModes = emptyList(),
      eisModes = emptyList(),
      afModes = emptyList(),
      aeModes = emptyList(),
      awbModes = emptyList(),
      flashSupported = false,
      manualExposure = ManualExposureCapabilities(false, null, null, null, null),
      rawCapabilities = RawCapabilities(false, emptyList()),
      hdrCapabilities = HdrCapabilities(false, emptyList()),
      jpegOutputSizes = emptyList(),
      yuvOutputSizes = emptyList(),
      maxResolutionOutputSizes = emptyList(),
      videoOutputSizes = emptyList(),
      supportedFpsRanges = emptyList(),
      highSpeedConfigs = emptyList(),
      videoQualities = emptyList(),
      cameraXExtensions = emptyList(),
      requestedCapabilities = emptyList(),
      accessibilityStatus = AccessibilityStatus.RestrictedOrFailed(errorMessage ?: "Unavailable"),
    )

  private fun utcTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
  }

  companion object {
    private const val TAG = "CameraDiagnosticsProbe"
    private const val OPEN_TIMEOUT_MS = 8000L
    private const val SESSION_TIMEOUT_MS = 8000L
    private const val ZOOM_SWEEP_TIMEOUT_MS = 18000L
    private const val CLOSE_FALLBACK_DELAY_MS = 1500L

    fun cameraDeviceErrorName(errorCode: Int): String =
      when (errorCode) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
        else -> "ERROR_UNKNOWN($errorCode)"
      }
  }
}
