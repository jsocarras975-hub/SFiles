# SuperFiles

Native Android file manager with an APK inspection and editing suite. Kotlin, Jetpack Compose, min SDK 30, target SDK 36.

## Build
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync. The wrapper jar isn't included; Studio fetches Gradle 8.11.1.
2. Run on a device and grant All-files access on first launch.

## Features
- File manager: browse, search, sort, hidden files, multi-select, copy/cut/paste (with progress), rename, delete, new folder, open with other apps. Favorites, image/video thumbnails, file properties with MD5/SHA-256, built-in text editor, zip/unzip, share, and a trash that empties itself after 30 days.
- Installed apps: list what's on the phone and extract any app's base APK to Downloads.
- APK inspector (tap any .apk): overview, certificates and signature schemes (v1/v2/v3), decoded manifest, file list with image thumbnails, DEX classes, resource table across all configurations. Tap a resource to edit its value — strings, numbers, booleans, colors, floats and references; edits go into the next build. Search the whole APK (manifest, dex, resources, file names) from the Search tab.
- APK editor: package rename (clone-safe), version name/code, add/remove permissions, smali editing with find/replace, go-to-line and a methods list plus DEX rebuild, resource editing in every config, binary XML attribute editing, file extraction and replacement, aligned rewrite, v1/v2/v3 signing, install. One-tap Clone builds a ".clone" copy and installs it side-by-side. Every build is recorded in Build history.
- Signing: generated RSA-2048 key, or import a PKCS12/BKS keystore. The keystore password is
  generated per device and protected by the Android Keystore; imported keys are re-wrapped with it.
- Trash: deleted items go to a hidden .Trash folder and can be restored or deleted forever.

## Known limits
- Untested first draft; built without access to the Android SDK.
- Staged smali/resource/XML/file edits are held in memory until you build.
- JKS keystores must be converted to PKCS12.
- No network access and no root: the app is fully offline.
