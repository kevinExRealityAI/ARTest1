# AR Depth Object Measurement — Overview

An Android prototype that estimates a standalone object's **Length, Width, and Height**
automatically, using the **ARCore Depth API**. The surveyor points the phone at an object sitting on
the floor and slowly walks around it; the bounding box stabilizes and measurements appear. No manual
corner tapping.

Part of the **Packing & Moving Survey** experiment. Goal: the *smallest* software that measures
accurately while keeping smooth AR performance — not a general-purpose scanner.

## Tech stack

Kotlin · ARCore (Depth API, plane detection, camera pose) · Coroutines · OpenGL ES 2.0.
No TensorFlow / MediaPipe / OpenCV. Min build: Gradle wrapper 8.9 + JBR 17.

## Pipeline

Each stage is a small, independently testable unit. Data flows once per processed frame
(throttled to ~4×/sec on the GL thread to keep rendering smooth):

```
Camera / Frame          CameraRenderer.kt      Draws camera background, drives session.update(),
                                               overlays the point cloud.
   ↓
Depth Acquisition       DepthViewModel.kt      Owns the ARCore Session; acquires raw depth +
                        DepthStats.kt          confidence images; summarizes them.
   ↓
Point Cloud             DepthPointCloud.kt      Back-projects depth pixels → world-space 3D points,
                                               dropping low-confidence and below-floor points.
   ↓
Object Isolation        ObjectIsolator.kt       Keeps points within a sphere of the nearest point
                                               (the target object); drops walls/furniture.
   ↓
Temporal Accumulator    MeasurementStabilizer   EMA-smooths each dimension across frames, tracks
+ Measurement Smoothing .kt                     angular coverage (12 wedges), gates on stability.
   ↓
Bounding Box            BoundingBox.kt          Gravity-aligned box: height = top − floor;
                                               footprint = world X/Z spread.
   ↓
UI                      MainActivity.kt         Overlays L/W/H + confidence + coverage.
```

Supporting GL code: `PointCloudRenderer.kt`, `GlProgram.kt`.

## Key design choices

- **Gravity-aligned box, no PCA.** ARCore's world is +Y up, so height reads directly off the floor
  plane and the footprint is a simple axis-aligned X/Z spread. A rotated object over-estimates its
  footprint; the min-area-rectangle fix is deliberately deferred until measurements prove it matters.
- **Stability over instant readings.** Dimensions are exponential moving averages; a measurement is
  only marked `stable` after it holds within 5 mm for several consecutive updates.
- **Coverage as trust.** Coverage % = how many of 12 wedges around the object the camera has viewed —
  a face never seen can't be trusted. Cheap: one `atan2` + a bitmask, no per-frame allocation.
- **Floor-gated.** No measurement is produced until a horizontal plane (the floor) is found, since
  without it the object can't be separated from the ground.
- **Performance.** Buffers are reused; the per-frame path allocates nothing beyond small result
  objects; heavy work is throttled off the render cadence. Every measurement carries confidence,
  coverage, and a timestamp.

## Status

- Full pipeline is built and **compiles clean** (`:app:compileDebugKotlin`).
- **Not yet validated on hardware** — accuracy is unproven; needs a depth-capable Android device.
- No automated tests yet (`BoundingBox` and `MeasurementStabilizer` are pure and easy to unit-test).

## Building

```
export JAVA_HOME="…/jbr-17.0.14/Contents/Home"
./gradlew :app:assembleDebug
```
Requires an ARCore-supported device with the Depth API; a standard emulator won't exercise the
depth path.
