# SuperFiles 0.6.0

A file manager and APK workshop that rivals MT Manager — and installs apps itself.

## Installs apps by itself

SuperFiles installs APKs, split APKs, and app bundles (.xapk, .apks, .apkm — base + splits, plus OBB data for XAPK) through PackageInstaller sessions, with its own progress dialog. The system installer is never opened. Needs the one-time "Install unknown apps" permission for SuperFiles; silent on Android 12+, on Android 11 the system still asks once.

## File manager

- Dual-pane mode with copy/move between panes
- Archives open like folders: ZIP, RAR, 7Z, TAR — browse, extract, add/remove entries (RAR read-only)
- Root explorer on rooted devices, through `su`
- FTP server sharing internal storage over local Wi-Fi (off by default)
- Hex editor, syntax highlighting, file diff, search inside files, bookmarks, media viewers (image, audio, font)
- Batch extract, sign, or install multiple APKs at once; trash with 30-day auto-cleanup

## APK workshop

- Inspector: manifest, certificates and signature schemes (v1/v2/v3), DEX classes, resource table across all configs
- Editor: package rename (clone-safe), version, permissions, quick manifest edits, smali editing with DEX rebuild, resource value editing in every config, binary XML attribute editing, file extract/replace, aligned rewrite, v1/v2/v3 signing
- Structured DEX editor: add/remove methods and fields, rename a class
- Full decompile/rebuild: manifest and resource XMLs decoded to text, all dex disassembled to smali, rebuild reassembles edited smali into a signed APK
- Translation mode with .tsv export/import, strip signatures, APK optimization, split APK install, build history

Signing keys are generated per device and protected by the Android Keystore. No network, no tracking, no ads.
