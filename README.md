# ScanLite

Offline-first Android document scanner focused on privacy, legibility, and compact local PDFs.

## Current implementation

- Kotlin, Jetpack Compose, and CameraX Android app.
- Manual camera capture with runtime permission handling and torch control.
- Multi-image import through the system document picker.
- Local filters: Original, Color, Grayscale, Black & White, and Document.
- Bounded working resolution during export to reduce memory pressure.
- Multi-page page manager with delete and page reordering.
- A4-style portrait/landscape PDF pages with preserved aspect ratio and centered margins.
- Small, Balanced, and High Quality export presets with different working resolutions.
- System save destination via Storage Access Framework followed by the Android share chooser.
- No internet permission, account, cloud upload, analytics, or paid service.
- Initial unit tests for export settings and deterministic file naming.

## Build and verification

The repository contains the Android/Gradle source configuration. This environment does not expose an Android SDK, Gradle executable, emulator, or device, so no build or test command has been claimed as successful here.

With Android Studio or a local Android SDK/JDK 17 installation:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If a local Gradle wrapper is desired, open the project in Android Studio and let it generate/sync the wrapper, or run `gradle wrapper` using a locally installed Gradle version. The wrapper JAR is intentionally not fabricated in this repository.

## Known limitations

- The current edge handling is conservative image processing; automatic quadrilateral edge detection, perspective handles, and deskew are not yet implemented.
- OCR is not bundled, so PDFs are image-only and do not contain selectable text.
- Drafts and document metadata are held in the current session; Room-backed library, folders, recycle bin, and crash recovery remain future work.
- Export quality is controlled by bounded source resolution and PDF rendering; exact byte-size targets are not yet exposed.
- Test/device verification must be run in an Android development environment.

## Privacy and cost

All core processing happens on-device. ScanLite does not upload documents, require a server, include analytics, or require network access. Storage and sharing use Android system intents.
