# SuperFiles

Android file manager (phase 1). Browse internal storage, search, sort, copy/cut/paste, rename, create folders, and delete, with All Files access.

- Package: `com.superfiles.app`
- Min SDK 30, target / compile SDK 36
- Kotlin + Jetpack Compose

## Getting the APK

Every push to `main` builds a debug APK in GitHub Actions (`.github/workflows/android.yml`). Download it from the run's artifacts as **SuperFiles-debug**.

## Building locally

Open in Android Studio, or run `./gradlew assembleDebug`. Requires JDK 17 and Android SDK 36.
