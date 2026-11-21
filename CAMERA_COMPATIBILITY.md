# CAMERA COMPATIBILITY — Nothing Phone (3a)

> **Device Reported:** `Nothing A059` — **Android 17 (SDK 37)**  
> **App Architecture:** Full Camera Application at launch (`CameraScreen`), with Hardware Diagnostics available under **Settings → Developer Tools → Camera Diagnostics**.

---

## 1. Confirmed Hardware Findings (Nothing Phone 3a / A059, Android 17 SDK 37)

- **Public `CameraManager.cameraIdList`:** `2` cameras
  - **`ID 0`**: Rear Main Wide (logical camera entry)
  - **`ID 1`**: Front Selfie
- **Auxiliary Lenses (`0.6x` Ultra-Wide & `2x` Telephoto):**
  - Not listed as separate top-level entries in `cameraIdList`.
  - When accessing via third-party Camera2 / CameraX APIs, auxiliary focal stops must operate through **`ID 0` (`CONTROL_ZOOM_RATIO_RANGE` / `ZoomState.minZoomRatio`..`maxZoomRatio`)** and/or `CameraCharacteristics.physicalCameraIds` if exposed by Nothing OS HAL.

---

## 2. How Nothing Camera Pro Adapts Safely (No Fake Hardware Assumptions)

1. **Dynamic Capability-Aware Zoom Presets:**
   - The live camera UI inspects `cameraInfo.zoomState.value`:
     - **`0.6x`** pill is displayed **only if** `minZoomRatio <= 0.72f`.
     - **`1x`** pill is always shown (`1.0f`).
     - **`2x`** pill is displayed **only if** `maxZoomRatio >= 2.0f`.
     - **`5x`** pill is displayed **only if** `maxZoomRatio >= 5.0f`.
   - Every captured preview frame inspects `TotalCaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` and `CaptureResult.LENS_FOCAL_LENGTH` via `Camera2Interop` and renders the active physical ID live in the viewfinder telemetry pill (`ID X • Ymm • Zx`).
2. **Dynamic Video Matrix Filtering:**
   - Top Video Quality selector (`4K` / `1080p` / `720p` @ `24/30/60/120fps`) is filtered strictly against `Recorder.getVideoCapabilities(cameraInfo)` and `cameraInfo.supportedFrameRateRanges`.
3. **RAW (DNG + JPEG) Gating:**
   - `Settings → RAW (DNG + JPEG)` is enabled strictly when `ImageCapture.getImageCaptureCapabilities(cameraInfo)` advertises `OUTPUT_FORMAT_RAW_JPEG`.
4. **Portrait Bokeh Pipeline:**
   - Runs GPU OpenGL ES 2.0 preview segmentation + background bokeh (`ML Kit Selfie Segmentation` + `PhotographicStyleGlProcessor`) so live adjustable `f/1.4–f/16` depth blur and Photographic Styles compose cleanly together on Nothing Phone (3a) without vendor extension conflicts.

---

## 3. Running Deep Inspection on Device

Open **Settings (gear icon) → Developer Tools → Camera Diagnostics** and tap **RUN FULL PROBE** to inspect:
- **CAMERAS:** Full characteristics for every public & sub-sensor ID.
- **ZOOM PROBE:** Live active physical camera ID & focal length at `0.5x–5.0x`.
- **VIDEO MATRIX:** `Recorder.getVideoCapabilities`, `EncoderProfiles`, and High-Speed slow-motion configs.
- **REPORT .MD:** Copy or export full Markdown report.
