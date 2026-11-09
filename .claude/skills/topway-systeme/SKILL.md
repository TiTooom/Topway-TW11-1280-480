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
- Build `eng`, `ro.debuggable=1`, **SELinux permissive**, `ro.adb.secure=1`.
- Système signé avec la **clé Topway** (`CN=John, OU=Android, O=TW, L=Shenzhen`, SHA-1 `409d305d6bb9d9108c48179e5e05641ca29b9cd0`), pas la clé de test AOSP : **impossible** d'obtenir `android.uid.system` pour Sandero.

### ADB (sûr, relevé 07/11/2026 via V019)
- **ADB par Wi-Fi marche**, sans root : l'appli (Sandero V019, Outils → Système et démarrage → « Essayer d'ouvrir ADB par Wi-Fi ») fait `SystemProperties.set("service.adb.tcp.port","5555")` + `ctl.restart adbd` (accepté sur build eng). Puis depuis le PC : `adb connect <IP>:5555`, accepter la fenêtre RSA (« Toujours autoriser ») → `device`. Le shell est **`uid=2000(shell)`**, SELinux `u:r:shell:s0`.
- Le port 5555 est **perdu à chaque redémarrage** (propriété non persistante) : refaire le bouton, ou V020 le relancera au démarrage.
- Déjà utile en `shell` (sans root) : `pm disable-user com.android.launcher` (désactiver le lanceur d'origine), `logcat`, `pm`, `screencap`, `input`, pull de `/sdcard`.

### Root : NON obtenu par ADB (sûr, 07/11/2026)
- **`adb root` n'élève pas** : après `adb root`, `service.adb.root` reste vide et le shell reste `uid=2000`, malgré `ro.debuggable=1`. L'adbd de Topway est compilé **sans `ALLOW_ADBD_ROOT`** (debuggable mis à part) → pas de root par cette voie.
- **`su` ne donne pas le root non plus** : `/system/xbin/su` (root:root, `-rwxr-xr-x`, **pas setuid**, 16 Ko, 2021-08-17) est le `su` AOSP **qui ne fait que descendre** depuis un appelant déjà root ; appelé depuis `shell` il échoue (`su: setgid failed: Operation not permitted`). Ce n'est pas un `su` type Magisk/SuperSU. C'est pour ça qu'Outils affiche « pas de root ».
- `/system` et `/vendor` montés **ro** ; `/dev/block/by-name/*` en `Permission denied` pour le shell. **L'image des partitions par `dd` reste donc impossible sans root.**
- **Pistes de root légitimes (appareil personnel, non essayées)** : (1) **Magisk** — récupérer le `boot.img` d'origine dans le firmware Topway (TW11-1280x480-X, T3L), le patcher avec Magisk, le reflasher ; (2) **mode FEL Allwinner** (boot ROM USB du T3) avec `sunxi-tools` (`sunxi-fel`) — standard pour dumper/booter un Allwinner qu'on possède, entré par un bouton/pont au démarrage ; (3) **menu usine/ingénierie Topway** s'il expose un interrupteur root. Les voies (1) et (2) demandent le firmware et/ou PhoenixSuit (voir Sauvegarde).

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

### Allègement par ADB (préparé 09/11/2026, jamais lancé sur la voiture)
- **Choix du propriétaire** : seul le **Bluetooth** est utilisé ; garder l'explorateur de fichiers ; **aucune** navigation (ni Waze ni Maps), ni Play Store, ni Chrome, ni lecteurs USB/AUX/DAB, ni Torque, ni MuPDF, ni menu flottant. Méthode : **désactiver** (`pm disable-user --user 0`, réversible), pas supprimer (supprimer de /system demande le root et ne gagne que de l'espace disque).
- `tools/allegement_liste.txt` (35 paquets, commentés, + liste « ne jamais ajouter »), `tools/Allegement_ADB.bat` (désactive, animations 0,5x par `settings put global`, mesure `dumpsys meminfo` + nombre de processus avant/après, suivi dans `autoradio/allegement/suivi_allegement.txt`), `tools/Allegement_Annuler.bat` (tout réactiver).
- **À garder absolument** : `com.tw.service`, `bt`, `keypad`, `eq`, `carchoose`, `com.tw.radio` (ouvert par `MainActivity`), SystemUI, Settings, Shell (ADB), `com.android.bluetooth` (OBD2), téléphonie/Telecom (risque de boucle de plantage), Contacts (répertoire BT), MediaProvider, ExternalStorage, PackageInstaller, WebView, ExtServices, clavier, FusedLocation.
- Sandero n'utilise **pas** les services Google (heure par `GPS_PROVIDER` seulement) : désactiver GMS est sans effet sur lui (lu dans le code).
- Vague 2 à tester : `com.tw.core` (rôle exact inconnu).
- Liste d'Outils → Système et allègement (`SystemActivity`) alignée sur ce choix en **V020** (GMS, Maps, Waze, menu flottant, lecteurs Topway ajoutés ; plus épargnés par « Libérer la mémoire »).
- RAM : 1 Go, ~430 Mo disponibles au relevé du 07/11 (avant allègement).

## Sauvegarde / export
- Outils → Exporter toutes les applis (APK) / infos + framework / tout /system / **Sauvegarde complète (sans root)** (applis + /system + infos + réglages Android) — dossiers `SanderoExport_*` sur la clé.
- Image des partitions : Outils (root, `dd` vers `/mnt/media_rw/<clé>`, morceaux de 3 Go, MD5) ou `tools/Sauvegarde_ADB.bat` (PC, `adb root` + `exec-out dd`). **Bloqué faute de root** (voir Sécurité → Root) : `adb root` n'élève pas, `dd` sur `/dev/block/by-name/*` = Permission denied. Reste faisable sans root : la **Sauvegarde complète** d'Outils (applis + /system lisible + infos + réglages).
- Plantage complet : seul le firmware Topway (TW11-1280x480-X, T3L) + PhoenixSuit (Allwinner) permet de réinstaller.

### Firmware « Allwinner T3-P1 » trouvé sur GitHub (analysé 09/11/2026, jamais flashé)
Dossier `autoradio/firmware_T3-P1_THEME1_20231116/` (versionné, `system.new.dat.br` en Git LFS). C'est un **paquet OTA Android par blocs** (zip de recovery décompressé), pas une image PhoenixSuit.
- **Contenu** : `system` (827 Mo) + `vendor` (56 Mo) + `boot.img` (16 Mo). **Ne touche pas** bootloader, env, dto, recovery, misc, private ni /data (seulement `resize2fs` sur UDISK, sans effacement).
- **Identité (sûr, lu dans les images)** : `ro.product.device=t3-p1` (le script refuse tout autre appareil), `t3_p1-eng` test-keys, Android 8.1 OPM1.171019.026, `ro.product.firmware=T3-o-mr1-v1.0rc1`, noyau **4.9.56** (compilé 16/11/2023), `selinux permissive`, `ro.fota.oem=TOPWAY`, plateforme T3L, densité 160, ressources `drawable-1280x480`. **Version Topway `V8.1.1_20231116.185024_THEME1`**, FOTA `THEME1_0000` (unité : `V8.1.1_20220907.180036_TW11-1280x480-X`, `TW11-1280x480-X_0000`).
- **Comparaison avec l'unité** : même base Android/BSP, mêmes dossiers d'applis Topway (`com.tw.bt_305c`, `radio_78cc`, `service_a5a4`, `carchoose_3f18`…). Identiques octet par octet : auxin, ba, carchoose, keypad. Différents (version plus récente sous le même nom) : bt, eq, music, radio, service, video, **Launcher2**, Settings, SystemUI. En plus : `com.tw.color`, `dvd`, `recorder` (dashcam), `tv`, `Zlinkw`.
- **Écart à vérifier (probable)** : l'unité a `ro.sf.rotation=270`, aucune valeur `ro.sf.rotation=` n'est écrite dans system/vendor de ce firmware → soit elle vient d'une partition que l'OTA ne touche pas (env/bootloader), soit l'écran serait mal orienté après flash.
- **Sauvegarder le `boot` d'origine avant tout** : voir le skill `topway-image-systeme` (firmware vendeur PODOFO, `fastboot boot` d'un boot Magisk en RAM, FEL).
- **Usage recommandé** : (1) source du **`boot.img` à patcher par Magisk** (même noyau 4.9.56, même appareil, risque modéré : ramdisk de 2023) ; (2) secours en cas de système cassé. **Pas** comme mise à jour de confort : variante « THEME1 » (autre interface, autre client), passage de 2022 à 2023, sans retour possible sans le firmware TW11 d'origine.
- **Flash** : par le recovery (zip resigné ou recovery acceptant les test-keys ; l'`otacert` est la clé de test AOSP `61:ED:37:7E…`, le recovery Topway risque de refuser). Ne rien écrire avant d'avoir une sauvegarde de `boot` (32 Mo).
- Les exports bruts contiennent des identifiants (numéro de série, licence OTA, secret réseau, MAC) : les **masquer** avant de les verser dans `autoradio/systeme_*/infos/` ; dumps bruts dans `autoradio/dumps/` (ignoré). Firmware de l'unité : `autoradio/systeme_TW11_20220907/`.

## Journal des vérifications
- 2026-11-04 : propriétés, partitions, signatures relevées ; lanceur remplacé avec succès ; fuseau Shanghai confirmé dans le journal.
- 2026-11-07 : le mot de passe 123456 du menu Topway ne donne pas accès au débogage USB. **L'écran Android des options développeur s'ouvre sans mot de passe** depuis Sandero V018 (Outils → Système et démarrage), et le débogage USB s'y active : `adb_enabled=1` (sûr). L'écriture directe de `adb_enabled` par l'appli est refusée (`WRITE_SECURE_SETTINGS`).
- 2026-11-07 : avec `adb_enabled=1`, PC relié par un câble USB-C vers USB-A (câble de manette Xbox) : le PC se recharge, mais Windows ne voit **aucun périphérique** (ni ADB ni inconnu). La prise USB-A de l'autoradio est en mode **hôte** (sûr). Pas de root d'après Outils (sûr). Pistes (probables, non vues) : ADB par Wi-Fi si `service.adb.tcp.port` ou `persist.adb.tcp.port` est déjà réglé ; option « USB OTG / périphérique » dans les réglages usine Topway ; passage du port OTG en mode périphérique par `/sys/devices/platform/soc/usbc0/` (sans doute réservé au root).
- 2026-11-07 : plantage du système Android (`DeadSystemException` dans les applis, redémarrage complet en ~30 s). Cause inconnue.
- 2026-11-07 : `adb connect 192.168.0.103:5555` (Wi-Fi) → « refusée » : adbd n'écoute pas en TCP (sûr). V019 ajoute le diagnostic ADB ([ADB]) ; résultat à relever sur la voiture.
- 2026-11-07 (V019, sûr) : **ADB par Wi-Fi sans root** : une appli ordinaire peut faire `SystemProperties.set("service.adb.tcp.port","5555")` puis `ctl.restart adbd` (build eng) ; le port 5555 s'ouvre, perdu au redémarrage. Première connexion PC : `unauthorized` jusqu'à acceptation de la fenêtre RSA sur l'écran. Prise USB : `otg_role=usb_host`, contrôleur `1c13000.udc-controller`, `sys.usb.config=adb`, `ro.adb.secure=1`, `su` en `/system/xbin/su` présent mais refusé à l'appli. Câble PC ↔ prise USB inutile.
- 2026-11-07 (V019, sûr) : **connexion ADB Wi-Fi réussie** (`adb connect 192.168.0.103:5555` → `device` après RSA). Shell = `uid=2000(shell)`, `u:r:shell:s0`. **`adb root` n'élève pas** (`service.adb.root` vide, shell reste 2000 malgré `ro.debuggable=1` → adbd sans `ALLOW_ADBD_ROOT`). **`su` AOSP non élévateur** (`/system/xbin/su`, pas setuid → `setgid failed: Operation not permitted` depuis shell). `/system` et `/vendor` en ro, `/dev/block/by-name/*` interdit au shell. Conclusion : **pas de root par ADB** ; image des partitions impossible sans root. Pistes root restantes : Magisk sur boot.img du firmware, mode FEL Allwinner (sunxi-fel), menu usine. `ro.bootmode=unknown`.

### Mode FEL Allwinner (T3 = sun8iw11) — faisabilité (étudié 07/11/2026, non essayé)
But : accès bas niveau par le BootROM USB du T3, où la **prise USB devient périphérique** (le câble PC marche enfin) → dump des partitions et/ou root sans dépendre d'`adb root`.
- **Entrée en FEL** : `adb reboot efex` (nom Allwinner, **non confirmé sur ce modèle**) ; sinon méthode matérielle (bouton/pont FEL au démarrage, ou média de boot illisible). Device USB en FEL : `1f3a:efe8`.
- **PC** : `sunxi-tools` (`sunxi-fel`) — build Windows + pilote **WinUSB via Zadig** sur `1f3a:efe8` (sinon ERROR -12). Projet : github.com/linux-sunxi/sunxi-tools ; Zadig : voir github.com/zador-blood-stained/fel-mass-storage.
- **xfel (xboot) : écarté** — sa liste ne contient ni T3 ni sun8iw11, et il **ne gère pas l'eMMC** (seulement RAM, SPI NOR/NAND). R40/A40i y sont mais sans eMMC. Donc inutile ici.
- **Piège central** : FEL = RAM + BootROM seulement. **Lire l'eMMC exige de charger d'abord un U-Boot sun8iw11** (init DDR + MMC) via `sunxi-fel spl`/`write`+`exec`, puis `mmc read` côté U-Boot. Le T3 étant **sun8iw11 comme R40/A40i (supportés par le U-Boot mainline)**, un **U-Boot-with-SPL mainline R40/A40i** est la piste réaliste (risque de blocage si DDR/PMIC diffèrent, mais **récupérable par coupure de courant tant qu'on ne fait que lire**).
- **Ordre sûr** : outillage PC (sunxi-tools + Zadig) → **firmware Topway TW11-1280x480-X d'origine en filet de secours** (donne aussi le `boot.img` pour Magisk et un U-Boot T3) → entrer en FEL → `sunxi-fel version` → liveboot U-Boot → dump eMMC (lecture, sûr) → seulement ensuite l'écriture pour le root.
- **Ne jamais écrire en FEL sans le firmware de secours** (bootloader/boot/env), sous peine de brique sans image de restauration.
- 2026-11-07 (sûr, shell ADB uid 2000) : **sauvegarde sans root = fichiers seulement, partielle**. Lisibles : les **APK** (`/system/app`, `/system/priv-app`), le **framework** (`framework.jar`), la plupart de `/system` et `/vendor`, `getprop`, listes de paquets. **Refusés** : partitions brutes `/dev/block/by-name/*` (donc **aucune image reflashable**), `/data` (UDISK), et `/system/build.prop` lui-même (mais `getprop` en redonne les valeurs). Conclusion : sans root on **conserve et étudie** le système, on ne peut pas en faire une image de restauration. Image reflashable = root/FEL, ou **firmware d'origine TW11-1280x480-X du vendeur** (sans root, = meilleur filet).

### Relevé de la sauvegarde ADB du 07/11/2026 (sûr)
**Tailles des partitions** (eMMC `mmcblk0`, 29,8 Go ; mapping par `by-name`) :
`UDISK p1 = 26792 Mo (/data)` · bootloader p2 = 32 · env p3 = 16 · **boot p4 = 32** · system p5 = 1024 · vendor p6 = 240 · misc p7 = 16 · recovery p8 = 32 · cache p9 = 512 · metadata p10 = 1024 · private p11 = 16 · frp p12 = 0,5 · empty p13 = 15,5 · dto p14 = 16 · media_data p15 = 16. Plus `mmcblk0boot0/boot1 = 4 Mo` (zones boot eMMC). → **boot et recovery ne font que 32 Mo** (dump rapide une fois le root/FEL obtenu).
**Réglages Topway (props)** : `persist.sys.tw.link=1` (Z-Link), `persist.tw.disp=0`, `persist.tw.iis=1`, `sys.tw.location=2`, `sys.tw.location.at6558=1` (GPS AT6558), `persist.display.smart_backlight=1`, `persist.display.enhance_mode=0`. `ro.hardware=ro.boot.hardware=sun8iw11p1`.
**Inventaire** : 79 paquets (68 en `/system`, 9 en `/data/app`). 
- **Nouvelle appli Topway non documentée** : `com.tw.assistivetouch` (menu tactile flottant), en `/data/app` → **mise à jour par-dessus le système, donc illisible sans root** (comme `com.tw.core`, aussi en `/data/app`).
- APK Topway **lisibles** (en `/system/priv-app`, récupérés) : `com.tw.service`, `com.tw.bt`, `com.tw.radio`, `com.tw.carchoose`, `com.tw.eq`, `com.tw.music`, `com.tw.video`, `com.tw.auxin`, `com.tw.keypad`, `com.tw.bootanimation`. `Launcher2.apk` = `com.android.launcher` (lanceur d'origine).
- Tiers : Waze, Maps, Torque (`org.prowl.torque`), DAB (`com.ex.dabplayer.pad`), MuPDF, Pico TTS, FOTA.
**Piège de copie (outil)** : `adb pull /system` (ou `/system/priv-app`) **avorte au premier fichier refusé** (ex. `build.prop`) et ne copie rien → copier **sous-dossier par sous-dossier**, et les APK système **un par un** par leur chemin (`grep /system/priv-app/*.apk` dans `pm list packages -f`). Toujours `export MSYS_NO_PATHCONV=1` sous Git Bash (sinon `/system` → `C:\Program Files\Git\system`).

### Recherche d'un « interrupteur root » dans les applis Topway (07/11/2026, dexdump sur la sauvegarde) — AUCUN
Analysé : `com.tw.carchoose`, `com.tw.service`, `Settings`, `com.tw.eq`, `com.tw.keypad`.
- **Aucune** référence à `su`, Magisk, SuperUser, `setprop ro.secure 0`, ni toggle « activer root ». Pas de menu usine/ingénierie qui donnerait le root.
- `Settings.apk` : seulement le toggle ADB standard AOSP (`EnableAdbPreferenceController`) et Factory Reset — rien de custom.
- `com.tw.carchoose` : `android.uid.system`, contient `MCUActivity`/`MCUService` (debug MCU) et les activités `canboxupdate.*` (mise à jour CANbox), pas de root.
- `com.tw.service` (uid système) : contient des commandes **exécutées en contexte root au démarrage** — `mount -o rw,remount /system`, `chown root.root /system/bin/gocsdk`, `ln /system/etc/goc/gocsdk… /system/bin/gocsdk`, `"OTA UPDATE FILE EXISTS! DO RM-FILE"`. C'est l'init/setup du système (symlinks gocsdk, droits), **pas un interrupteur accessible à l'utilisateur**. Pose aussi `persist.tw.kill`, `persist.tw.sleepmode`, `persist.systemnavi`, `persist.camera.mirror1/2`.
- 2026-11-09 : analyse du firmware OTA « Allwinner T3-P1 » (GitHub) : Topway, même appareil `t3-p1` et même noyau 4.9.56, variante THEME1 du 16/11/2023 ; compatible au niveau matériel/Android, interface différente. Voir Sauvegarde → Firmware « Allwinner T3-P1 ».
- 2026-11-09 : scripts d'allègement ADB préparés (35 paquets, choix « Bluetooth seulement »), pas encore lancés. Voir Allègement par ADB.

**Conclusion** : le root ne s'obtient pas par un réglage de l'interface. Il reste les voies d'amorçage : **Magisk sur `boot.img`** (32 Mo) ou **FEL**, toutes deux via le firmware TW11.
