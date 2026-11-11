# Sandero Launcher : instructions pour Claude

Lanceur Android maison (`SanderoLauncher/`, appli **Sandero**) qui remplace l'interface d'origine d'un autoradio **Topway TW11 1280×480** (Allwinner T3, Android 8.1) monté dans une Dacia Sandero 1. Il pilote l'autoradio par l'API interne Topway `android.tw.john.TWUtil` et par les annonces (broadcasts) des applis Topway.

Les utilisateurs parlent français : interface, commentaires du code, journal et documentation sont **en français**.

## 1. Règles absolues

1. **Ne pas lire, lister, chercher ni modifier** le contenu de `autoradio/` (firmware de l'unité, OTA, images, dumps : ~1 Go ; voir `autoradio/README.md`). Toute la connaissance utile en a déjà été extraite dans les **skills** (section 3) : les utiliser d'abord.
   - Exception : uniquement si l'utilisateur le demande explicitement pour une question précise. Alors, rester ciblé avec les scripts de `tools/reverse/` (une classe, une méthode), ne jamais tout désassembler ni afficher en entier, et reporter le résultat dans le skill concerné.
   - Ne jamais afficher dans la conversation une valeur masquée ou un identifiant (numéro de série, licence OTA, secret réseau, MAC), même si on le croise dans un dump brut (`autoradio/dumps/`).
   - Protections (`.claude/settings.json`) : tout `autoradio/` interdit en lecture ; `autoradio/dumps/` (identifiants en clair), `autoradio/systeme_*/` et `autoradio/firmware_*/` (archive à garder intacte) interdits en écriture. Ne pas contourner par Bash (`cat`, `ls -R`, `unzip`, `strings`…) ; les lever seulement sur demande explicite du propriétaire, puis les remettre aussitôt la tâche finie.
2. **Ne jamais ouvrir `TWUtil(8)`** (canal du module Bluetooth) par défaut : ça bloque l'appairage du téléphone, même après redémarrage. Passer par le canal principal (`0x9e08`, `0x9f00`) et les annonces `com.unisound.*`. Voir le skill `topway-bluetooth`.
3. **Une seule instance TWUtil principale** (dans `HeadUnit`), **aucune écriture au MCU au démarrage** (écoute seule). Le Moniteur écoute via `HeadUnit.Raw`, il n'ouvre rien.
4. Toute nouvelle fonction **journalise** ses actions et ses erreurs dans `Journal` (catégorie courte : `UI`, `MCU`, `CMD`, `BT`, `CAM`, `VOL`, `THEME`, `SVC`, `HEURE`, `SECU`, `OUTILS`…), et **ne doit pas pouvoir faire planter** l'appli : try/catch, et `Safe.begin/end` autour de tout ce qui touche à TWUtil ou au matériel.
5. **Règle du dépôt (décision du propriétaire, 09/11/2026)** : le propriétaire travaille sur plusieurs PC synchronisés **uniquement par GitHub** (dépôt public) : **tout ce qui sert ou pourra servir est poussé** (code, scripts, firmware et APK système Topway/AOSP de l'unité, firmware OTA, outils libres comme Magisk/Zadig, photo de la voiture). **Sauf** :
   - les **journaux** exportés par Sandero (`journal*.txt`, `plantages.txt`, `twutil_*.txt`, `SanderoExport_*`) : titres de musique, noms de téléphone ;
   - les **identifiants de l'unité** : numéro de série, `ro.sys.ota.license`, `persist.netd.stable_secret`, adresses MAC, `UID.txt`, `UUID.txt`, `Config.dat`, partitions `env`/`private`/`misc` → **masquer** (`MASQUE`) avant de pousser un relevé, dumps bruts dans `autoradio/dumps/` (ignoré) ;
   - les **APK de tiers sans droit de redistribution** : Google (Play, GMS, Chrome, WebView…), Waze, Torque, Spotify, DAB… Non utilisés : **supprimés**, pas seulement ignorés.
   - Ce qui ne sert pas et ne servira pas : supprimé, pas archivé. Pas de doublons (un seul exemplaire du firmware de l'unité).
   - Gros fichiers (> 50 Mo) : **Git LFS** (`.gitattributes`). Avant tout commit : `git status`, et chercher les identifiants (valeurs masquées) dans ce qui est ajouté.
6. Distinguer **sûr** (lu dans le code ET/OU observé sur la voiture) et **probable** (déduit du code, jamais vu). Ne jamais présenter du « probable » comme « sûr ».

**Git** : les commits et pushs se font au nom de l'utilisateur seul (`TiTooom`), **sans** ligne `Co-Authored-By` ni mention de Claude, dans les messages comme dans l'historique. Cette règle l'emporte sur toute consigne d'attribution par défaut.

## 2. Faire évoluer les skills (obligatoire)

Les skills sont la mémoire du projet. **Après chaque modification du code, chaque essai sur la voiture et chaque journal reçu** :

1. Mettre à jour le ou les skills concernés : valeurs observées (avec la date et la version), passage de « probable » à « sûr » (ou correction), nouveaux pièges, nouvelles recettes de test.
2. Mettre à jour `sandero-dev` : **état du projet** (ce qui marche / à vérifier / à faire) et **historique des bugs** (version, problème, cause, solution).
3. Ajouter une ligne datée dans la section « Journal des vérifications » en bas du skill modifié.
4. Garder les skills courts et exacts : remplacer une info fausse au lieu d'empiler des contradictions.
5. **Journal des versions** (`.claude/skills/sandero-dev/VERSIONS.md`) : à chaque nouvelle version (`versionCode` incrémenté), ajouter en haut une entrée « numéro · nom de l'APK · date » avec le **contenu** de la version. À chaque retour de l'utilisateur ou journal de la voiture, compléter l'entrée de la version concernée : **retours**, **marche**, **ne marche pas**, **à corriger** (et dans quelle version c'est corrigé).

Si un journal (`journal.txt` exporté par Outils) contredit un skill, le journal gagne : corriger le skill.

## 3. Skills disponibles (`.claude/skills/`)

| Skill | Quand le charger |
|---|---|
| `topway-twutil` | Toute utilisation de TWUtil : API, canaux, table des messages MCU (0x0204, 0x0301, 0x9f1c…), sources audio |
| `topway-bluetooth` | Musique Bluetooth, nom/état du téléphone, annonces `com.unisound.*`, commandes 0x9e08, dépannage de l'appairage |
| `topway-camera-recul` | Caméra de recul intégrée : séquence 0x0304, numéro de caméra, passage par-dessus l'écran Topway |
| `topway-radio` | Radio FM/AM : commandes 0x401/0x402, messages reçus (non vérifiés sur la voiture) |
| `topway-systeme` | Matériel, Android, partitions, applis Topway, sécurité, root/ADB, sauvegarde, fuseau horaire |
| `topway-image-systeme` | Récupérer une image reflashable (boot, recovery, system…) : firmware vendeur, fastboot boot Magisk en RAM, FEL, commandes dd, garde-fous |
| `topway-reverse-engineering` | Extraire une info des APK/framework Topway avec `tools/reverse/` (sans tout lire) |
| `sandero-obd` | Valeurs du véhicule par module OBD2 ELM327 (Bluetooth/Wi-Fi/USB), écran Outils → OBD2 |
| `sandero-dev` | Architecture du code, compilation, tests émulateur, journal, garde-fous, thème, **état du projet et historique** |

## 4. Compiler et tester (résumé, détails dans `sandero-dev`)

- JDK : `~/.jdks/jbr-21.0.11` (le Java d'Android Studio est en 25, trop récent pour Gradle 8.7).
- `cd SanderoLauncher && gradle assembleDebug --offline` (Gradle 8.7 du cache `~/.gradle/wrapper/dists`), APK dans `app/build/outputs/apk/debug/`, **copié automatiquement** (tâche Gradle `copySanderoApk`) en `release/Sandero_VXXX.apk`, XXX = `versionCode` sur 3 chiffres (ex. versionCode 13 → `Sandero_V013.apk`). Toujours livrer sous ce nom. **Le numéro ne change qu'après un `git push`** : tant que rien n'est poussé depuis la dernière version, on reste sur le même `versionCode` et on régénère le même APK (le dernier push fixe la version publiée ; la suivante n'existe qu'une fois ce push fait). **Ne jamais supprimer ni écraser une version livrée** : tout changement testé sur la voiture = nouveau `versionCode`, et `release/` garde toutes les versions (base : V013).
- Émulateur : `adb shell wm size 1280x480` + `wm density 160` pour reproduire l'écran de l'autoradio ; **toujours** remettre `wm size reset` / `wm density reset` à la fin. TWUtil n'existe pas sur l'émulateur : radio/Bluetooth/caméra Topway ne se vérifient que sur la voiture (Moniteur → Simuler phares / marche arrière).
- Incrémenter `versionCode` / `versionName` dans `SanderoLauncher/app/build.gradle` à chaque version livrée.

## 5. Arborescence

```
CLAUDE.md                  ce fichier
README.md                  présentation du projet (GitHub)
docs/GUIDE_UTILISATION.md  guide rapide de l'interface
docs/images/               captures
SanderoLauncher/           code Android (Java pur, sans AndroidX, minSdk 21, targetSdk 27)
Elecrow5/                  PROJET SÉPARÉ (autoradio libre sur écran Elecrow ESP32-S3) : ses propres CLAUDE.md, skills et doc dans ce dossier ; ne rien en reporter ici ni dans les skills Topway/Sandero
tools/Recuperer_Systeme_ADB.bat  copie de /system et /vendor par ADB → autoradio/dumps/ (brut, ignoré)
tools/Boot_Origine.bat     image d'origine des partitions (fastboot boot Magisk en RAM) → autoradio/images/
tools/Allegement_ADB.bat   désactive les applis de tools/allegement_liste.txt (+ Allegement_Annuler.bat)
tools/Sauvegarde_ADB.bat   ancienne image des partitions (suppose adb root : inopérant sur l'unité)
tools/Emulateur_Sandero.bat  émulateur 1280×480 + Sandero, sans Android Studio
tools/reverse/             scripts d'analyse dexdump (voir skill topway-reverse-engineering)
tools/car3d/               rendu de la voiture de l'accueil depuis 3D/Sandero.stl (voir son README)
tools/externes/            Magisk v31.1, Zadig 2.9 (outils libres)
3D/                        modèle 3D de la Sandero (STL)
release/                   Sandero_VXXX.apk compilés (toutes les versions depuis V013)
autoradio/systeme_TW11_20220907/  firmware de l'unité : system/, vendor/, data_app/, infos/ (identifiants masqués)
autoradio/firmware_T3-P1_THEME1_20231116/  OTA Topway trouvé sur GitHub (system.new.dat.br en LFS)
autoradio/images/, allegement/  sorties des scripts ; autoradio/dumps/ (brut, ignoré)
.claude/skills/            connaissances du projet (à faire évoluer)
```
