---
name: topway-systeme
description: Autoradio Topway TW11 1280x480 (Allwinner T3, Android 8.1 eng) — matériel, propriétés, partitions, applis Topway et leur rôle, sécurité (signature, SELinux, root/ADB), fuseau horaire, remplacement du lanceur d'origine, allègement, sauvegarde et export. À charger pour toute question système, installation, sauvegarde ou performance.
---

# Système de l'autoradio

## Matériel et Android (relevé le 04/11/2026 via Outils → Exporter infos)
| Élément | Valeur |
|---|---|
| Plateforme | Allwinner **T3** (`sun8iw11p1`, `ro.board.platform=t3`), 4 × Cortex-A7 32 bits (`armeabi-v7a`) |
| Android | **8.1** (API 27), build **`t3_p1-eng`** (test-keys), noyau 4.9.56 du 23/08/2022 |
| Firmware Topway | `ro.tw.version = V8.1.1_20220907.180036_TW11-1280x480-X` ; FOTA : OEM TOPWAY, plateforme T3L, appareil `TW11-1280x480-X_0000` |
| Écran | 1280 × 480, densité 160, `ro.sf.rotation=270` |
| Stockage | eMMC 32 Go (`/data` = partition UDISK ~26 Go) ; clé USB en `/storage/usb0` (`/mnt/media_rw/usb0`, FAT) |
| Radios | Wi-Fi xradio ; Bluetooth via service natif `gocsdk` ; GPS `at6558` |
| Divers | Langue fr-FR ; **fuseau `Asia/Shanghai`** ; Z-Link (CarPlay/Android Auto filaire, `com.zjinnova.zlink`) |

## Sécurité
- Build `eng`, `ro.debuggable=1`, **SELinux permissive**, `ro.adb.secure=1`, adbd arrêté. Si ADB est activable (options développeur / réglages usine / port USB en mode périphérique, contrôleur `1c13000.udc-controller` présent), `adb root` devrait marcher.
- Système signé avec la **clé Topway** (`CN=John, OU=Android, O=TW, L=Shenzhen`, SHA-1 `409d305d6bb9d9108c48179e5e05641ca29b9cd0`), pas la clé de test AOSP : **impossible** d'obtenir `android.uid.system` pour Sandero.
- Root via `su` : non testé sur l'appareil (Outils → Système et allègement l'affiche).

## Partitions (`/dev/block/by-name`)
bootloader p2 · env p3 · boot p4 · system p5 (1 Go) · vendor p6 · misc p7 · recovery p8 · cache p9 · metadata p10 · private p11 · frp p12 · empty p13 · dto p14 · media_data p15 · **UDISK p1 = /data**. Illisibles sans root.

## Applis Topway (`/system/priv-app`, signées Topway)
| Paquet | Rôle |
|---|---|
| `com.android.launcher` (Launcher2) | **Lanceur d'origine** (seule appli HOME hors Réglages/GMS) |
| `com.tw.service` (uid système) | Service central : marche arrière / caméra, jour-nuit, volume, extinction |
| `com.tw.core` | Démon TWCore (messages, notifications, MQTT) |
| `com.tw.bt` | Bluetooth (téléphone, musique) — voir `topway-bluetooth` |
| `com.tw.radio` | Radio — voir `topway-radio` |
| `com.tw.music` / `com.tw.video` | Lecteurs USB/SD |
| `com.tw.eq` | Égaliseur/DSP (Rohm, AK7602/7604, PT2313) |
| `com.tw.keypad` | Commandes au volant |
| `com.tw.carchoose` | Véhicule / CANbox, debug MCU (`com.tw.mcudebug.MCUActivity`) |
| `com.tw.auxin` | Entrée AUX/AV |
| `com.tw.twfileexplore`, `com.tw.bootanimation`, `com.abupdate.fota_demo_iot` | Fichiers, animation de démarrage, mises à jour OTA |
Citées mais absentes ici : `com.tw.carsettings`, `com.tw.ac`, `com.tw.fcam`, `com.tw.dvd`, `com.tw.ipod`, `com.tw.canuart`.
Applis utilisateur d'origine : TXZ / PODOFO Voice (assistant vocal, gourmand), CarbitLink, DAB+, Torque, Waze, Maps, HERE, YouTube, Spotify, GPS Test, MuPDF.

## Heure
Fuseau système `Asia/Shanghai` (+7 h l'hiver). Sandero affiche Europe/Paris (`Clock.java`) et peut se recaler sur le GPS (écart mesuré : ~1 s, horloge interne juste). Corriger le système : Paramètres → Date et heure → Fuseau (risque de retour à Shanghai au redémarrage).

## Remplacer le lanceur d'origine
1. Accueil → Sandero → **Toujours** (ou Paramètres → Applis → Applis par défaut → Appli d'accueil).
2. Sandero démarre seul (BootReceiver + SanderoService) et reprend la main si `com.android.launcher` revient (nécessite « Accès à l'utilisation »).
3. Définitif : désactiver `com.android.launcher` (Système et allègement → dernière ligne ; sans root via la page de l'appli si le bouton Désactiver existe, sinon `adb shell pm disable-user com.android.launcher`).
Vérifié le 04/11/2026 : Sandero remplace bien l'interface d'origine.

## Allègement
Outils → Système et allègement : libérer la mémoire (`killBackgroundProcesses`, épargne com.tw.*, GMS, Waze, Maps), liste commentée des applis à désactiver (vert : TXZ, Google, impression, écrans de veille… ; rouge : FOTA, Play Store, lanceur d'origine), animations 0,5x/0x (root, sinon Options développeur).

## Sauvegarde / export
- Outils → Exporter toutes les applis (APK) / infos + framework / tout /system / **Sauvegarde complète (sans root)** (applis + /system + infos + réglages Android) — dossiers `SanderoExport_*` sur la clé.
- Image des partitions : Outils (root, `dd` vers `/mnt/media_rw/<clé>`, morceaux de 3 Go, MD5) ou `tools/Sauvegarde_ADB.bat` (PC, `adb root` + `exec-out dd`).
- Plantage complet : seul le firmware Topway (TW11-1280x480-X, T3L) + PhoenixSuit (Allwinner) permet de réinstaller.
- Les exports contiennent des données personnelles (UID, licence, MAC) : à garder hors du dépôt (`INFOS From autoradio/`, ignoré par Git).

## Journal des vérifications
- 2026-11-04 : propriétés, partitions, signatures relevées ; lanceur remplacé avec succès ; fuseau Shanghai confirmé dans le journal.
