# DEVELOPMENT ROADMAP — Nothing Camera Pro

## Milestone 1: Hardware Research & Diagnostics Foundation
- [x] Inspect build environment & verify device camera API capabilities
- [x] Configure Nothing OS dark-first theme, typography (`Space Grotesk` + `JetBrains Mono`), and custom adaptive launcher icon
- [x] Implement `Camera2DiagnosticsCollector` (Camera2 `CameraCharacteristics`, `CameraX`, `ExtensionsManager`, `VideoCapabilities`, `EncoderProfiles`, `concurrentCameraIds`)
- [x] Implement active hardware open probe (`openCamera` on `HandlerThread`, `OutputConfiguration.setPhysicalCameraId`) and live zoom sweep (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`)
- [x] Implement `CameraCapabilitiesRepository` + `DiagnosticsScreen` under **Settings → Developer Tools → Camera Diagnostics**
- [x] Confirmed Nothing Phone 3a (`Nothing A059`, Android 17 / SDK 37) public IDs: `0` (Rear Main Wide), `1` (Front Selfie)

## Milestone 2: Core Working Camera (Launch Screen)
- [x] Runtime permissions flow (`CAMERA`, `RECORD_AUDIO`) with Settings fallback
- [x] Edge-to-edge live `PreviewView` with Nothing OS minimalist viewfinder (`CameraScreen` as main launch route)
- [x] Photo capture (`ImageCapture`) to `MediaStore` (`DCIM/NothingCameraPro`) + live Gallery thumbnail
- [x] Capability-aware Video recording (`VideoCapture` + `Recorder`) with audio, timer, pause/resume, torch, and stabilization
- [x] Tap-to-focus, long-press AE/AF lock, inline EV slider, Flash (`Auto`/`On`/`Off`), Pinch zoom, Front/Rear toggle

## Milestone 3: Camera Intelligence & Photographic Styles
- [x] Capability-aware zoom pills (`0.6x` / `1x` / `2x` / `5x` gated by `ZoomState.minZoomRatio` & `maxZoomRatio`)
- [x] Live active physical lens telemetry badge (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`, focal length, zoom)
- [x] OpenGL ES 2.0 `SurfaceProcessor` real-time shader pipeline (`PhotographicStyleGlProcessor`)
- [x] 10 iPhone-inspired Photographic Styles (`Natural` -> `Black & White`) with live preview, intensity slider, skin-tone protection, and non-destructive original sidecar saving
- [x] Aspect ratios (`4:3`, `16:9`, `1:1`, `Full`), Timer (`3s`, `5s`, `10s`), Grid (`3x3`, `4x4`), Gyro Level indicator, RAW+JPEG (when supported)

## Milestone 4: Computational Portrait & Post-Capture Editing
- [x] Portrait Photo mode with ML Kit Selfie Segmentation (`STREAM_MODE` live preview mask -> GPU bokeh + `SINGLE_IMAGE_MODE` full-res hair-edge feathering + `f/1.4–f/16` aperture slider)
- [x] Full in-app Gallery viewer & non-destructive post-capture Style / Portrait Bokeh re-editor (`GalleryScreen`)
- [ ] On-device verification on physical Nothing Phone (3a) for 0.6x / 2x physical switching & video thermal profiling
