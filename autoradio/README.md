# Autoradio Topway TW11-1280x480-X : firmware et sauvegardes

Tout ce qui vient de l'autoradio de référence (Allwinner T3, Android 8.1 eng, monté dans une
Dacia Sandero 1), rangé en un seul endroit. Sert à la rétro-ingénierie, à la restauration et au root.

| Dossier | Contenu |
|---|---|
| `systeme_TW11_20220907/` | Firmware de l'unité, version `V8.1.1_20220907.180036_TW11-1280x480-X`, copié par ADB Wi-Fi sans root le 07/11/2026 (fusion des exports du 06/11 et du dump du 07/11). |
| `systeme_TW11_20220907/system/`, `vendor/` | Fichiers lisibles de `/system` et `/vendor` : APK système AOSP et Topway (`priv-app/com.tw.*`), `framework/` (dont `framework.jar` avec `android.tw.john.TWUtil`), `lib/`, `bin/`, `etc/`… |
| `systeme_TW11_20220907/data_app/` | APK installés dans `/data/app`, absents de `/system` : `com.tw.core` (TWCore), `com.tw.assistivetouch` (menu flottant), et le Sandero installé le 06/11. |
| `systeme_TW11_20220907/infos/` | `getprop`, paquets, partitions, montages, services, CPU/mémoire, `build.prop` reconstruit. **Identifiants masqués** (`MASQUE`). |
| `firmware_T3-P1_THEME1_20231116/` | Paquet OTA Topway trouvé sur GitHub (même appareil `t3-p1`, variante THEME1 du 16/11/2023) : `system`/`vendor` compressés + `boot.img`. `system.new.dat.br` est stocké par **Git LFS**. |
| `images/` | Sorties de `tools/Boot_Origine.bat` : images des partitions (`boot`, `recovery`, `bootloader`…) une fois le root temporaire obtenu. Les partitions propres à l'unité (`env`, `private`, `misc`…) restent hors dépôt. |
| `allegement/` | Mesures et suivi de `tools/Allegement_ADB.bat`. |
| `dumps/` | Dumps ADB **bruts** de `tools/Recuperer_Systeme_ADB.bat` : **ignorés par Git** tant que les identifiants ne sont pas masqués. |

## Exclu volontairement
- **APK Google** (Play Store, services Google, Chrome, WebView, GoogleServicesFramework…), **Waze**,
  **Torque**, **DAB+**, **MuPDF** : non utilisés, et sans droit de redistribution pour la plupart.
- **Identifiants de l'unité** : numéro de série, licence OTA (`ro.sys.ota.license`), secret réseau
  (`persist.netd.stable_secret`), adresses MAC, UID/UUID.
- **Journaux** exportés par Sandero (titres de musique, noms de téléphone).

## Ce qui manque encore
- `/data` (UDISK) et quelques fichiers refusés au shell (`/system/build.prop`, `/vendor/default.prop`…).
- Images reflashables : en attente du root temporaire (voir le skill `topway-image-systeme`).

## Sur un nouveau PC
Installer Git LFS (`git lfs install`) **avant** de cloner, sinon `system.new.dat.br` n'est qu'un petit
fichier pointeur.
