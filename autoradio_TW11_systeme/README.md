# Firmware système de l'autoradio Topway TW11-1280x480-X

Archive des **APK système** et des **infos système** de l'unité de référence (Dacia Sandero 1),
extraites par ADB (Wi-Fi, sans root) le 07/11/2026. Sert à la rétro-ingénierie et à l'archivage
du firmware pour le projet Sandero Launcher.

Firmware : `V8.1.1_20220907.180036_TW11-1280x480-X` · Allwinner T3 (sun8iw11) · Android 8.1 (eng).

## Contenu
- `apk/app/`, `apk/priv-app/` : APK système **AOSP** et **Topway** (`com.tw.*`, `TWCore`, `TWFileExplore`).
- `infos/` : `getprop.txt` (identifiants et secrets masqués), `packages.txt`, `partitions.txt`,
  `mounts.txt`, `build.prop.reconstruit.txt`, `cpuinfo`/`meminfo`.

## Exclusions volontaires
- **APK Google** (Play Store, GMS, Chrome, WebView, GoogleServicesFramework…) : propriété de Google,
  redistribution interdite — non inclus.
- **Identifiants / secrets** (UID/UUID, MAC, `persist.netd.stable_secret`) : masqués ou retirés.
- Images reflashables des partitions (`boot`, `system`…) : nécessitent le root/FEL, non incluses.

La sauvegarde brute complète (`/system`, `/vendor`, bibliothèques, framework) reste hors dépôt.
