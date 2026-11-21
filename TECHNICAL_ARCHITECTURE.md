# TECHNICAL ARCHITECTURE — Nothing Camera Pro

## 1. Architectural Principles
1. **Camera-First Navigation (`MainActivity`):** Launches directly into the live `CameraScreen` (`ROUTE_CAMERA`). Hardware Diagnostics is preserved under **Settings → Developer Tools → Camera Diagnostics** (`ROUTE_SETTINGS` → `ROUTE_DIAGNOSTICS`).
2. **Hardware-Driven Capabilities (`CameraEngineController` & `CameraCapabilitiesRepository`):** Zero hardcoded resolutions, FPS values, zoom stops, or RAW flags. Every UI selector and capture pipeline inspects live `CameraInfo` (`ZoomState`, `ExposureState`, `Recorder.getVideoCapabilities`, `ImageCapture.getImageCaptureCapabilities`, `ExtensionsManager`).
3. **Hybrid CameraX + Camera2 Interop Engine:** Uses CameraX (`ProcessCameraProvider`, `Preview`, `ImageCapture`, `VideoCapture`, `ImageAnalysis`, `UseCaseGroup` + `ViewPort`) augmented with `Camera2Interop` / `Camera2CameraInfo` for real-time `TotalCaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` and `LENS_FOCAL_LENGTH` telemetry.
4. **GPU-First Real-Time Processing:** Real-time OpenGL ES 2.0 `SurfaceProcessor` (`PhotographicStyleGlProcessor`) executes color grading + Portrait background bokeh masking on GPU preview frames (`CameraEffect.PREVIEW`) without CPU pixel stalls.

---

## 2. Module & Package Layout

```text
app/src/main/java/com/example/
├── MainActivity.kt                  # NavHost: camera -> gallery / settings -> diagnostics
├── ui/theme/                        # Nothing OS obsidian/white/red palette + typography
├── core/
│   ├── common/                      # UiState, DeviceEnvironmentInfo, HorizonSensorMonitor
│   ├── camera/                      # CameraEngineController, CameraCapabilitiesRepository
│   ├── camera2/                     # Camera2DiagnosticsCollector, CharacteristicsMapper, MarkdownGenerator
│   ├── imageprocessing/             # PhotographicStyles (10 styles), PhotographicStyleGlProcessor, PortraitBokehEngine
│   ├── settings/                    # DataStore CameraPreferencesRepository & CameraSettingsModels
│   └── storage/                     # Scoped MediaStoreRepository (DCIM/NothingCameraPro + original sidecars)
└── feature/
    ├── camera/                      # CameraScreen, CameraOverlays, CameraViewModel, CameraModels
    ├── gallery/                     # GalleryScreen + non-destructive post-capture Style/Bokeh editor
    ├── settings/                    # SettingsScreen (Composition, Capture, Video, Developer Tools)
    └── diagnostics/                 # DiagnosticsScreen & DiagnosticsViewModel
```

---

## 3. Data & Camera Pipeline Design

### A. Logical Multi-Camera & Zoom Architecture
- On **Nothing Phone (3a)** (`Nothing A059`, Android 17 SDK 37), public `cameraIdList` exposes `ID 0` (Rear Main Wide) and `ID 1` (Front Selfie).
- `CameraEngineController` drives optical/logical zoom via `CameraControl.setZoomRatio(...)` across `ZoomState.minZoomRatio..maxZoomRatio`, exposes only supported quick stops (`0.6x`, `1x`, `2x`, `5x`), and reads `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` per captured frame.

### B. Photographic Styles Engine (10 Styles)
- Parameterized color-science model (`contrast`, `saturation`, `vibrance`, `temperature`, `tint`, `highlights`, `shadows`, `blackLift`, `monochrome`, `skinToneProtection`) executed identically in GLSL (`PhotographicStyleGlProcessor`) for live preview and `PhotographicStyleBitmapProcessor` for captured JPEGs and post-capture edits.
- When **Save Original Copy** is enabled, unedited captures are saved and linked via `MediaStoreRepository.registerOriginalSidecar(...)` for non-destructive re-editing.

### C. Portrait Bokeh Pipeline
- Uses Google ML Kit Selfie Segmentation (`STREAM_MODE` on `ImageAnalysis` feeding GPU `uMaskTex` in `PhotographicStyleGlProcessor`) for real-time adjustable `f/1.4–f/16` preview bokeh, and `SINGLE_IMAGE_MODE` + separable stack blur + bilinear hair-edge feathering for full-resolution Portrait capture.
