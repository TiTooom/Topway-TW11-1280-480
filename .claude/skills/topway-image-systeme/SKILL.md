---
name: topway-image-systeme
description: Récupérer une image reflashable de l'autoradio Topway TW11 (partitions boot, recovery, system, vendor, bootloader, env… de l'eMMC) — voies possibles sans root (firmware vendeur PODOFO, fastboot boot d'un boot Magisk en RAM, mode FEL Allwinner), commandes dd, contrôle MD5, garde-fous anti-brique, firmware OTA « Allwinner T3-P1 ». À charger pour toute sauvegarde ou restauration d'image, avant de toucher au boot ou au root.
---

# Récupérer l'image système de l'autoradio

But : obtenir une **copie reflashable** des partitions de l'eMMC (au minimum `boot` et `recovery`, idéalement toutes sauf `UDISK`), pour pouvoir restaurer l'unité et préparer le root sans risque.
Matériel, partitions et état du root : voir `topway-systeme`.

## Point de départ (sûr, 07/11/2026)
- Pas de root : `adb root` n'élève pas, `su` AOSP non élévateur. Shell ADB = `uid=2000`.
- `/dev/block/by-name/*` refusé au shell et à Sandero → **aucun `dd` possible** tant qu'on n'a pas le root.
- La « Sauvegarde complète » d'Outils et `adb pull` donnent des **fichiers** (APK, /system lisible), **pas une image reflashable**.
- **Prises USB-A de l'autoradio toujours en mode hôte** (09/11, sûr) : même en fastboot (`adb reboot bootloader` → logo figé) et en FEL (`adb reboot efex` → écran noir), le PC ne voit rien ; un téléphone branché à la place est reconnu. Voies B et C bloquées tant qu'on n'accède pas à l'USB0/OTG (interne, probable). **Voie restante : A.**
- `tools/Sauvegarde_ADB.bat` suppose `adb root` (`exec-out dd`) → **ne marche pas tel quel** sur cette unité ; avec un root Magisk, remplacer par `exec-out su -c "dd …"`.

## Partitions à sauver (tailles sûres, 07/11/2026)
| Priorité | Partition | Taille | Pourquoi |
|---|---|---|---|
| 1 | `boot` p4 | 32 Mo | noyau + ramdisk, c'est elle que Magisk modifie |
| 1 | `recovery` p8 | 32 Mo | réinstallation |
| 2 | `bootloader` p2, `env` p3, `dto` p14, `private` p11, `misc` p7 | 16–32 Mo | U-Boot, config écran (`sys_config`), numéros propres à l'unité |
| 2 | `mmcblk0boot0/boot1` | 4 Mo | zones de démarrage eMMC (boot0 Allwinner) |
| 3 | `system` p5, `vendor` p6 | 1024 / 240 Mo | système complet |
| — | `UDISK` p1 (/data) | 26 Go | données perso : seulement si demandé, jamais dans le dépôt |
`private`, `env` et `misc` peuvent contenir des identifiants : images **hors dépôt** (ignorées par `.gitignore` dans `autoradio/images/`).

## Voies, de la plus sûre à la plus technique

### A. Firmware complet du vendeur (aucun risque)
L'unité est vendue sous la marque **PODOFO** (appli « PODOFO Voice » d'origine), logiciel Topway « TW » (`ro.fota.oem=TOPWAY`). « Topway Display » (écrans LCD) est sans doute une autre société.
- Demander le firmware de la version exacte `V8.1.1_20220907.180036_TW11-1280x480-X`, de préférence l'**image PhoenixSuit (`.img`)** complète (bootloader + config écran), pas un simple OTA.
- Mail envoyé au support PODOFO le 09/11/2026 : **réponse en attente**.
- Autres sources : 4PDA, XDA (« TW11 1280x480 », « T3L Topway »).

### B. `fastboot boot` d'un boot Magisk, en RAM seulement (essayé 09/11 : bloqué, le PC ne voit pas l'unité en fastboot)
Principe : démarrer une seule fois avec un `boot.img` patché par Magisk **sans l'écrire**, puis copier les partitions avec ce root temporaire. Un redémarrage remet le boot d'origine.
1. **Relevés sans risque** (ADB Wi-Fi) : `adb shell cat /proc/cmdline`, `adb shell ls -l /dev/block/by-name/`, `adb shell getprop | findstr /i "boot fastboot"`.
2. **Fastboot existe-t-il ?** Câble PC ↔ prise USB de l'unité, `adb reboot bootloader`, puis `fastboot devices`.
   - numéro affiché → étape 3 ;
   - périphérique inconnu dans Windows → pilote WinUSB via Zadig ;
   - redémarrage normal ou écran noir → pas de fastboot : couper/remettre le contact, passer à la voie C.
3. **Patcher** : Magisk officiel (github.com/topjohnwu/Magisk) installé sur l'unité (`adb install`), `adb push "Allwinner T3-P1\boot.img" /sdcard/Download/`, Magisk → Installer → Sélectionner et patcher un fichier, puis `adb pull /sdcard/Download/magisk_patched-XXXX.img`.
   - **Variante sans PC (par clé USB, recommandée pour préparer)** :
     - Clé en **FAT32** (format lu par l'unité), pas besoin de la vider. Mettre **seulement** `Magisk-v31.1.apk` et `boot.img` dans un dossier à part (ex. `Sandero_boot\`). **Ne pas** copier tout `Allwinner T3-P1/` (`META-INF`, `*.new.dat.br`), ni `update.zip` / image de firmware à la racine : les unités Topway peuvent détecter seules une mise à jour sur clé (probable, `com.tw.service` gère un fichier OTA).
     - Sur l'unité : explorateur Topway → toucher l'APK (autoriser les « sources inconnues » si demandé) → copier `boot.img` dans la mémoire interne `Download/` (le sélecteur de Magisk voit mal la clé) → Magisk → Installer → Sélectionner et patcher un fichier → `Download/boot.img` → résultat `Download/magisk_patched-XXXXX.img`. **Refuser** toute « installation directe » ou « configuration supplémentaire ». Rien n'est modifié dans le système.
     - Recopier le fichier patché sur la clé, puis au PC dans `autoradio\images\` sous le nom **`boot_magisk.img`** (nom attendu par l'étape 5 du script) : les étapes 3 et 4 du script sont alors faites.
     - La suite (étapes 1, 2, 5, 6) demande le PC dans la voiture.
4. **Démarrer en RAM** : `adb reboot bootloader` puis `fastboot boot magisk_patched-XXXX.img`.
5. **Copier** (voir « Commandes de copie »), puis couper/remettre le contact.
- **Script prêt** : `tools/Boot_Origine.bat` (menu 1 à 6 = relevés, test fastboot, installer Magisk + envoyer boot.img, récupérer le boot patché, `fastboot boot`, copie de toutes les partitions sauf UDISK + MD5 autoradio/PC). Sorties et suivi (`suivi_boot_origine.txt`) dans `autoradio/images/` ; versionnées sauf `releves.txt` et les partitions propres à l'unité (`env`, `private`, `misc`…), images `.img` en LFS. N'utilise jamais `flash`/`erase`. Menu et calcul MD5 testés sur le PC le 09/11/2026 ; **jamais lancé sur l'autoradio**.
- **Outils téléchargés** (`tools/externes/`, versionné : outils libres) : `Magisk-v31.1.apk` (SHA-256 `89cefac0…26ac6e7` vérifié, contient `armeabi-v7a`), `zadig-2.9.exe`. `adb`/`fastboot` : `%LOCALAPPDATA%\Android\Sdk\platform-tools`.
- **Piège Magisk v31** : plus de fenêtre de demande root ; il faut autoriser **Shell** à l'avance dans l'onglet Superutilisateur (une fois le boot Magisk démarré), sinon `adb shell su` échoue. Minimum Android 7.0 (8.1 OK).
- **Piège ADB Wi-Fi** : après `fastboot boot`, le port 5555 est perdu → rouvrir l'ADB Wi-Fi depuis Sandero avant la copie.
- Risque principal : le boot 2023 (THEME1) ne démarre pas le système 2022. Rien n'est écrit → coupure du contact et retour à l'état d'origine.

### C. Mode FEL Allwinner (dernier recours ; essayé 09/11 : bloqué, aucun `1f3a:efe8` vu)
Ni Magisk ni Zadig n'y changent rien : Windows ne voit même pas de « périphérique inconnu ». Il faudrait l'USB0/OTG du T3, sans doute sur un connecteur interne ou des pastilles de la carte (probable, non vu) : ouverture de l'autoradio et soudure, déconseillées tant que le firmware n'est pas là.
BootROM USB du T3 (`1f3a:efe8`), `sunxi-fel` + pilote WinUSB (Zadig), chargement d'un U-Boot R40/A40i (sun8iw11) en RAM, puis `mmc read`. Détails et pièges : `topway-systeme` → « Mode FEL ». Lecture seule = récupérable par coupure de courant.

## Commandes de copie (une fois root obtenu)
Sur la clé USB ou `/sdcard`, puis récupération au PC :
```
adb shell su -c "dd if=/dev/block/by-name/boot of=/sdcard/boot_origine.img bs=4M"
adb shell su -c "dd if=/dev/block/by-name/recovery of=/sdcard/recovery_origine.img bs=4M"
adb shell su -c "md5sum /dev/block/by-name/boot /sdcard/boot_origine.img"
adb pull /sdcard/boot_origine.img
```
Ou en flux direct (sans passer par /sdcard) : `adb exec-out su -c "dd if=/dev/block/by-name/boot bs=4M 2>/dev/null" > boot_origine.img`.
- Toujours comparer le **MD5 sur l'unité** (partition) et **sur le PC** (fichier).
- Vérifier l'en-tête : un `boot`/`recovery` valide commence par `ANDROID!`.
- Sous Git Bash : `export MSYS_NO_PATHCONV=1` (sinon les chemins `/dev/...` sont réécrits).

## Firmware OTA « Allwinner T3-P1 » (GitHub, analysé 09/11/2026)
Dossier `autoradio/firmware_T3-P1_THEME1_20231116/` (~430 Mo, versionné ; `system.new.dat.br` en Git LFS). Paquet OTA par blocs : `system` + `vendor` + `boot.img`, ne touche ni bootloader ni env ni /data.
- Même appareil `t3-p1`, même noyau 4.9.56, Topway T3L, mais variante **THEME1** du 16/11/2023 (unité : TW11-1280x480-X du 07/09/2022). Détails : `topway-systeme` → Sauvegarde.
- Utile comme **source du `boot.img` à patcher** (voie B) et comme secours. **Pas** comme mise à jour de confort.
- Extraction (sur le PC, dans le dossier temporaire) : `brotli -d` sur `*.new.dat.br`, puis reconstruction de l'image avec le `*.transfer.list` (script type sdat2img : écrire chaque plage `new` à `bloc × 4096`).

## Garde-fous
- **Jamais `fastboot flash`, `fastboot erase` ni écriture FEL** tant qu'on n'a pas une copie vérifiée (MD5) de `boot`, `recovery`, `bootloader` et `env` **de cette unité**, ou le firmware complet du vendeur.
- Ne pas ouvrir l'appli FOTA pendant ces essais : elle peut télécharger et installer une version plus récente.
- Ne pas déclencher `adb reboot efex` sans l'outillage FEL prêt sur le PC (sinon écran noir jusqu'à coupure du contact).
- Images et sorties de `cmdline`/`getprop` : à nettoyer des identifiants (numéro de série, MAC) avant de les mettre dans un skill ou le dépôt.

## Journal des vérifications
- 2026-11-09 : skill créé. État : aucune image obtenue ; mail au support PODOFO envoyé (attente) ; voie B prête à essayer (étapes 1–2 sans risque à faire en premier).
- 2026-11-09 : préparation PC : `tools/Boot_Origine.bat` écrit, Magisk v31.1 + Zadig 2.9 téléchargés, `tools/externes/` et `Allwinner T3-P1/` ajoutés au `.gitignore`.
- 2026-11-09 : ajout de la variante « patch Magisk par clé USB, sans PC » (précautions sur le contenu de la clé). Non encore faite sur la voiture.
- 2026-11-09 (sur la voiture, sûr) : étape 1 par ADB Wi-Fi : `boot` = `mmcblk0p4`, `recovery` = p8 (liste des 15 partitions confirmée), `/proc/cmdline` refusé au shell, boot actuel du 23/08/2022 (`ro.bootimage.build.date`), `ro.bootloader=unknown`. Étape 2 : `adb reboot bootloader` → **écran figé sur le logo Android**, mais avec le câble USB-A (prise de l'autoradio) → USB-C (PC), Windows ne voit **aucun périphérique** (ni fastboot, ni inconnu, ni `1f3a`). Retour par coupure du contact. Refait sur l'**autre prise USB** de l'autoradio : même résultat (logo figé, rien côté PC en 90 s). Sous Android, `usbc0/otg_role=usb_host`. Reste à vérifier : le câble transporte-t-il les données (essai avec un téléphone sur le PC) ? Si oui, fastboot par USB est inaccessible sur cette unité → voie A ; la voie C (FEL) passe aussi par l'OTG, donc même doute.
- 2026-11-09 (sur la voiture, sûr) : câble vérifié bon (données OK avec un téléphone). `adb reboot efex` accepté (code 0) → **écran noir**, mais Windows ne voit **aucun** `1f3a:efe8` ni périphérique inconnu en 90 s. Conclusion probable : les prises USB-A accessibles ne sont pas reliées à l'USB0/OTG du T3 en mode périphérique (OTG sans doute sur un connecteur interne ou des pastilles). **Voies B et C bloquées par le câblage USB** ; reste la voie A (firmware PODOFO), ou ouvrir l'autoradio pour trouver l'OTG (non tenté).
