# SuperFiles 0.6.1

## New launcher icon

SuperFiles finally has its own face: a neon cyan folder + wrench on dark navy, drawn for the app. Adaptive icon, so it fits every launcher shape.

## Everything from 0.6.0

A file manager and APK workshop that rivals MT Manager — and installs apps itself.

- **Self-installer:** installs APKs, split APKs, and app bundles (.xapk, .apks, .apkm — base + splits, plus OBB data for XAPK) through PackageInstaller sessions, with its own progress dialog. The system installer is never opened. Needs the one-time "Install unknown apps" permission for SuperFiles; silent on Android 12+, on Android 11 the system still asks once.
- **File manager:** dual-pane mode, archives open like folders (ZIP, RAR, 7Z, TAR), root explorer on rooted devices, FTP server over local Wi-Fi (off by default), hex editor, syntax highlighting, file diff, search inside files, bookmarks, media viewers, batch extract/sign/install, trash with 30-day auto-cleanup.
- **APK workshop:** inspector (manifest, certificates, signature schemes, DEX classes, resource table), editor (package rename/clone, version, permissions, quick manifest edits, smali editing with DEX rebuild, resource value editing, binary XML editing, file extract/replace, v1/v2/v3 signing), structured DEX editor, full decompile/rebuild, translation mode with .tsv export/import, strip signatures, APK optimization, split APK install, build history.

Signing keys are generated per device and protected by the Android Keystore. No network, no tracking, no ads.

Licensed under the MIT License — Copyright (c) 2026 N3xUs Konc3ptz. See LICENSE in the repo.
