---
name: topway-reverse-engineering
description: Méthode et scripts (tools/reverse/) pour extraire une information précise des APK et du framework Topway (dexdump, cas d'un switch sur msg.what, tableaux d'abonnement TWUtil.open, constantes, broadcasts) sans lire les APK en entier. À charger uniquement si l'utilisateur demande explicitement d'analyser une appli Topway ; sinon, utiliser les autres skills.
---

# Rétro-ingénierie ciblée des applis Topway

**Règle** : le firmware de l'unité est dans `autoradio/systeme_TW11_20220907/` (APK système dans `system/priv-app/`, `system/app/`, TWCore et menu flottant dans `data_app/`, `framework.jar` dans `system/framework/`). Skills d'abord ; n'y aller que pour une question précise, sur demande de l'utilisateur, pour UNE question, et on reporte la réponse dans le skill concerné. Ne jamais afficher un désassemblage complet (des dizaines de Mo).

## Outils
- `dexdump.exe` : `%LOCALAPPDATA%/Android/Sdk/build-tools/34.0.0/dexdump.exe -d classes.dex > app.txt` (travailler dans le dossier temporaire, jamais dans le dépôt).
- `aapt.exe dump xmltree app.apk AndroidManifest.xml` (activités, services, `sharedUserId`, HOME) ; `aapt dump badging` (permissions).
- `apksigner verify --print-certs` (signature ; lancer via `cmd //c` sous Git Bash, avec un JDK 21).
- Python : toujours `python -I`. Scripts de `tools/reverse/` (à lancer dans le dossier où se trouvent `<app>.txt` et `<app>/classes.dex`) :
  - `meth.py <app> 'Lpaquet/Classe;' [regex_méthode]` : désassemblage lisible d'une classe ou de quelques méthodes.
  - `sw.py <app> <n°_de_ligne> [profondeur]` : pour la méthode contenant cette ligne, liste chaque `case` des `switch` (ex. `handleMessage` : `msg.what` → méthodes appelées). Indispensable pour associer un id de message à une action.
  - `blk.py <app> <n°_de_ligne> 0x37,0x47 <n>` : instructions complètes de quelques `case`.
  - `cases.py <app> 0x0204,0x0301,…` : dans toute l'appli, les `switch` qui traitent ces ids.

## Recettes
- **Ids auxquels une appli s'abonne** : chercher `fill-array-data` juste avant `.open:([S)I` ou `.open:([SI)I` ; lire le tableau directement dans le dex (ident 0x0300, largeur 2, taille, valeurs) — dexdump tronque l'affichage (`…`).
- **Cible d'un `fill-array-data` / `switch`** : dans la sortie dexdump, la valeur affichée `0000xxxx` est l'**index absolu** dans la méthode (pas relatif) ; adresse fichier = début du code (`en-tête + 16`) + 2 × index. Les cibles d'un `sparse/packed-switch-data` sont relatives à l'instruction `switch`.
- **Framework** (`boot-framework.vdex`) : dex « quickened » ; le découpage du vdex donne des dex au checksum faux et parfois illisibles. Préférer étudier les **appels** à `Landroid/tw/john/TWUtil;` depuis les applis (sous-classes `TWRadio`, `TWMusic`, `TWAT`, `com/tw/service/d`).
- **Bibliothèque lisible** : `com.eckom.xtlibrary.twproject` (non obfusquée) dans radio/musique/vidéo/BT — commencer par là (`RadioModel`, `BTModel`, `BaseBTService`, `BroadcastManager`). `com.tw.service` est obfusqué (classes `a`, `B`, `d`…) mais ses chaînes restent lisibles.
- **Broadcasts** : chercher `const-string … "com.unisound…"`, `"android.bluetooth…"` puis `putExtra` / `sendBroadcast` dans la même méthode.
- Encodage : forcer `PYTHONIOENCODING=utf-8` (chaînes chinoises).
- Pièges Git Bash : les heredocs contenant des apostrophes françaises cassent la commande → écrire le script Python dans un fichier puis l'exécuter.

## Journal des vérifications
- 2026-11-04 : méthode utilisée pour TWUtil, BT (0x9e08 → controlBtMusic), caméra (ReverseActivity), radio (RadioModel), annonces com.unisound.
- 2026-11-06 : APK présents dans `APP From autoradio/applis_systeme/` (nom `TW_com.tw.service.apk` etc., 82 APK) et `applis_utilisateur/`. `cases.py` exige au moins 2 ids trouvés dans un même switch : en passer plusieurs. Résultats reportés dans `topway-twutil`.
- 2026-11-07 : recherche d'un interrupteur root sur la sauvegarde ADB (dexdump de carchoose/service/settings/eq/keypad) : **aucun** (ni su, ni Magisk, ni toggle). `com.tw.service` exécute `mount rw,remount /system` + `chown root.root` + symlinks gocsdk au démarrage (contexte root d'init, pas un réglage). Reporté dans `topway-systeme`.
