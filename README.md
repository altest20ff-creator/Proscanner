# ScanLite

Offline-first Android document scanner focused on privacy, legibility, and small PDFs.

## Build

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the project in Android Studio or use JDK 17 with the Android SDK installed. The core workflow is local and does not require internet permission, an account, a server, or a paid API.

## Implemented

- Jetpack Compose home screen with ScanLite branding.
- Runtime camera permission and CameraX preview/shutter/torch.
- Multi-image import through the system picker.
- Local grayscale, black-and-white, color, and document enhancement filters.
- Sequential bounded bitmap processing.
- Multi-page page manager with delete/reorder controls.
- Local multi-page PDF generation with Small, Balanced, and High Quality presets.
- Android share intent and user-selected PDF destination.
- Arabic and English resources, RTL-aware Compose layout, light/dark theme.
- Unit tests for compression settings and page ordering.

## Known limitations

- The first implementation uses a conservative crop-to-content heuristic rather than a native OpenCV edge detector; manual crop editing and perspective handles are planned.
- OCR is intentionally not bundled yet, so exports are image-only PDFs.
- Camera auto-capture, advanced folders/search, recycle bin, and full page replacement are not yet implemented.
- Build and device tests must be run in an Android/Gradle environment; this repository update was created without access to a local SDK or emulator.

## Privacy

ScanLite has no analytics, cloud upload, tracking, or mandatory network access. Images are processed locally. Export uses Android's Storage Access Framework and share intents.
