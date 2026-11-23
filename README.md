# SuperFiles

Native Android file manager with an APK inspection and editing suite. Kotlin, Jetpack Compose, min SDK 30, target SDK 36.

## Build
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync. The wrapper jar isn't included; Studio fetches Gradle 8.11.1.
2. Run on a device and grant All-files access on first launch.

## Features
- File manager: browse, search, sort, hidden files, multi-select, copy/cut/paste (with progress), rename, delete, new folder, open with other apps. Dual-pane mode with copy/move between panes. Favorites and bookmarks, image/video thumbnails, file properties with MD5/SHA-256, built-in text editor with syntax highlighting, hex editor, file diff, search inside files, zip/unzip, share, and a trash that empties itself after 30 days. Batch actions: extract, sign, or install-split multiple APKs at once.
- Archives: tap a .zip, .rar, .7z or .tar to browse it like a folder — extract entries, and add or remove entries in ZIP, TAR and 7Z archives (RAR is read-only).
- Root explorer (rooted devices only): browse the whole filesystem as root, with copy/move/delete/rename/mkdir through su.
- FTP server: share internal storage over the local Wi-Fi network from a built-in FTP server (off by default; needs the INTERNET permission for the server only).
- Media: in-app image viewer, audio player, and font preview.
- Installed apps: list what's on the phone and extract any app's base APK to Downloads.
- APK inspector (tap any .apk): overview, certificates and signature schemes (v1/v2/v3), decoded manifest, file list with image thumbnails, DEX classes, resource table across all configurations. Tap a resource to edit its value — strings, numbers, booleans, colors, floats and references; edits go into the next build. Search the whole APK (manifest, dex, resources, file names) from the Search tab.
- APK editor: package rename (clone-safe), version name/code, add/remove permissions, quick manifest edits (app name, icon, install location, min/target SDK), smali editing with find/replace, go-to-line and a methods list plus DEX rebuild, resource editing in every config, binary XML attribute editing, file extraction and replacement, aligned rewrite, v1/v2/v3 signing, install. One-tap Clone builds a ".clone" copy and installs it side-by-side. Every build is recorded in Build history. Strip signatures, optimize (shrink/dedupe), install split APKs as one package, and translate all strings side by side with .tsv export/import.
- Self-installer: SuperFiles installs APKs, split APKs, and app bundles (.xapk, .apks, .apkm — base + splits, plus OBB data for XAPK) itself through PackageInstaller sessions, with its own progress dialog — the system installer is never opened. Needs the one-time "Install unknown apps" permission for SuperFiles; on Android 12+ installs run silently, on Android 11 the system still asks once.
- DEX editor: browse classes, add or remove methods and fields, rename a class — no smali needed.
- Decompile / rebuild: decodes the manifest and every resource XML to readable text, disassembles all dex files to smali/, extracts res/ and assets/; rebuild reassembles edited smali into a signed APK. (Edits to decoded XML files are not recompiled yet.)
- Signing: generated RSA-2048 key, or import a PKCS12/BKS keystore. The keystore password is
  generated per device and protected by the Android Keystore; imported keys are re-wrapped with it.
- Trash: deleted items go to a hidden .Trash folder and can be restored or deleted forever.

## Known limits
- Staged smali/resource/XML/file edits are held in memory until you build.
- JKS keystores must be converted to PKCS12.
- The decompiler does not recompile edits to decoded XML files yet (smali edits do rebuild).
- RAR archives are read-only (browse and extract only).
- The FTP server needs the INTERNET permission; it only runs while you start it, on the local Wi-Fi network.
- On Android 11, self-installs still show the system's confirmation once; Android 12+ installs silently.

## License

SuperFiles is open source under the MIT License — see [LICENSE](LICENSE).
