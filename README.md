# GyroLock V9 — V6 Gyro Tuning

This version is based on the V6 project that used gyro-only stabilization. It does not use the `deshake` fallback and does not pass `-preset` to FFmpeg's `mpeg4` encoder.

## Stabilization changes
- Uses the V6 gyro-based XYZ correction pipeline.
- Applies a stronger gyro dead-band and a slower low-pass filter to reduce stationary sensor noise.
- Integrates filtered gyro samples consistently instead of mixing filtered and raw samples, reducing small correction jumps.
- Smooths the camera path over a longer window and uses a moderate correction gain to balance shake reduction against intentional panning.
- Uses 120 trajectory key points to make correction curves smoother without making FFmpeg expressions excessively large.
- Outputs constant-frame-rate 30 FPS using FFmpeg options supported by the existing MPEG-4 encoder path (`-r 30 -vsync cfr`).
- Keeps the original recording if gyro stabilization fails. There is no fallback stabilization method.

## Build APK on GitHub
1. Upload the contents of this ZIP to the root of your GitHub repository.
2. Open **Actions** and run **Build GyroLock APK** (or push to `main`/`master`).
3. Download `GyroLock-debug-apk` from the completed workflow's Artifacts.

## Important notes
- Gyro EIS estimates rotational movement from the phone's gyroscope; it is not optical stabilization and cannot perfectly correct translation, rolling-shutter wobble, or fast motion blur.
- Test with a short clip first. The quality/performance balance can vary by phone and FFmpeg build.
