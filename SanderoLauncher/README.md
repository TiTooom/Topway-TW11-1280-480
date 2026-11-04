# SanderoLauncher : le code de l'appli Sandero

Projet Android en Java pur, sans AndroidX ni dépendance : `minSdk 21`, `targetSdk 27` (Android 8.1 de l'autoradio), paquet `fr.sandero.launcher`. L'interface est construite en code, à l'échelle d'un écran 1280×480.

- Mode d'emploi : [../docs/GUIDE_UTILISATION.md](../docs/GUIDE_UTILISATION.md)
- Connaissances sur l'autoradio (API TWUtil, messages, Bluetooth, caméra, radio, système) : [../.claude/skills/](../.claude/skills/)
- Carte du code, conventions, tests, état du projet : [../.claude/skills/sandero-dev/SKILL.md](../.claude/skills/sandero-dev/SKILL.md)

## Compiler

**Android Studio** : *File → Open* → ce dossier, puis *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. L'APK est dans `app/build/outputs/apk/debug/app-debug.apk`.

**En ligne de commande**, avec un JDK 21 et Gradle 8.7 :
```bash
export JAVA_HOME=/chemin/vers/jdk-21
gradle assembleDebug
```
Un JDK plus récent (25) fait échouer Gradle 8.7 avec « Unsupported class file major version 69 ».

`local.properties` (chemin du SDK Android) est propre à chaque PC : Android Studio le crée tout seul.

## Installer sur l'autoradio

1. Copier l'APK sur une clé USB et l'ouvrir avec le gestionnaire de fichiers de l'autoradio (autoriser les sources inconnues si demandé).
2. Tester d'abord en lançant **Sandero** depuis la liste des applis.
3. Écran d'accueil : appuyer sur Accueil → **Sandero** → **Toujours**.
4. Les mises à jour s'installent par-dessus, à condition d'être signées avec la même clé (clé debug du même PC, ou une clé de release dédiée). Sinon : désinstaller d'abord.

## Revenir en arrière

Paramètres → Applis → Applis par défaut → Appli d'accueil → reprendre le lanceur d'origine, ou désinstaller Sandero.
