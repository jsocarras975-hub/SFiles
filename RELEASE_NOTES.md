# SuperFiles 0.6.2

## Signature verification, for real

The inspector's Overview tab now cryptographically verifies the APK's signatures instead of just listing which schemes are present — it tells you the signatures are valid, invalid, or that the APK isn't signed at all, plus the signing certificates.

## Strip & sign

New in the inspector's Edit tab: **Strip & sign** strips the old signatures and re-signs with your device's key in one tap, and the result installs straight away — no more unsigned dead-end file. The plain **Strip signatures** is still there when you want the unsigned copy.

## Everything from 0.6.1

- New launcher icon: neon cyan folder + wrench on dark navy.
- Self-installer: installs APKs, split APKs, and app bundles (.xapk, .apks, .apkm — base + splits, plus OBB data for XAPK) through PackageInstaller sessions, with its own progress dialog. The system installer is never opened. Needs the one-time "Install unknown apps" permission for SuperFiles; silent on Android 12+, on Android 11 the system still asks once.
- File manager: dual-pane mode, archives open like folders (ZIP, RAR, 7Z, TAR), root explorer on rooted devices, FTP server over local Wi-Fi (off by default), hex editor, syntax highlighting, file diff, search inside files, bookmarks, media viewers, batch extract/sign/install, trash with 30-day auto-cleanup.
- APK workshop: inspector (manifest, certificates, signature schemes, DEX classes, resource table), editor (package rename/clone, version, permissions, quick manifest edits, smali editing with DEX rebuild, resource value editing, binary XML editing, file extract/replace, v1/v2/v3 signing), structured DEX editor, full decompile/rebuild, translation mode with .tsv export/import, strip signatures, APK optimization, split APK install, build history.

Signing keys are generated per device and protected by the Android Keystore. No network, no tracking, no ads.

Licensed under the MIT License — Copyright (c) 2026 N3xUs Konc3ptz. See LICENSE in the repo.
