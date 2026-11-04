---
name: sandero-dev
description: Développement de l'appli Sandero (SanderoLauncher/) — carte du code, conventions, compilation (JDK 21 + Gradle 8.7 hors ligne), tests sur émulateur (écran 1280x480, simulations, pièges), journal et garde-fous, thème jour/nuit, publication, ÉTAT DU PROJET et HISTORIQUE DES BUGS. À charger avant toute modification du code, et à mettre à jour après chaque modification ou essai.
---

# Développer Sandero

## Carte du code (`SanderoLauncher/app/src/main/java/fr/sandero/launcher/`)
| Fichier | Rôle |
|---|---|
| `SanderoApp` | Démarrage du processus : `Clock.init`, `Journal.init`, gestionnaire de plantages |
| `MainActivity` | Écran d'accueil : heure, voiture, dock (Réglages, Applis), carte audio (Radio / Bluetooth), pastille téléphone, curseur de volume, thème |
| `HeadUnit` | Singleton : canal TWUtil principal, état (phares, marche arrière, source, radio, Bluetooth), commandes, relais `Raw` pour le Moniteur |
| `Tw` | Pont réflexion vers `android.tw.john.TWUtil` + constantes d'ids |
| `BtInfo` | Annonces de l'appli Bluetooth Topway (nom, état, titre) |
| `SanderoService` | Service permanent : marche arrière → caméra, retour au premier plan (réveil, lanceur d'origine), GPS, BtInfo |
| `ReverseCamActivity` | Caméra de recul intégrée + mini-lecteur |
| `CarView` | Dessin de la Sandero 1 (points relevés sur photo), reflet, halo, phares animés |
| `Theme` | Palettes nuit / jour, mode auto 18 h–9 h, choix forcé |
| `Clock` | Heure Europe/Paris indépendante du fuseau système, recalage GPS, `Clock.View` |
| `Journal` | 1000 dernières lignes, `files/journal.txt` + `Android/data/fr.sandero.launcher/files/journal.txt` |
| `Safe` | Marqueurs d'étapes risquées, mode sans échec, journal des plantages |
| `ToolsActivity` | Outils : export USB, sauvegardes, explorateur, journal, heure, thème, intégration Topway, caméra |
| `SystemActivity` | Allègement : mémoire, applis à désactiver, animations, root |
| `TwMonitorActivity` | Moniteur TWUtil (écoute, simulations) |
| `VolumeSlider`, `SeekLine`, `Icon` | Composants dessinés (curseur vertical, progression, icônes vectorielles) |
| `MediaListener`, `BootReceiver`, `Root` | Accès notifications (MediaSession), démarrage, `su` |

## Conventions
- Java pur, **sans AndroidX**, UI construite en code, `minSdk 21`, `targetSdk 27`, paquet `fr.sandero.launcher`.
- Tailles en « unités » 1280×480 : `px(v) = v * min(largeur/1280, hauteur/480)`. Les icônes dans un ImageView : `new Icon(type, couleur).sized(px(N))` + `CENTER_INSIDE` (jamais de grosse marge fixe, ça déborde sur d'autres écrans).
- Couleurs de l'accueil : **toujours `Theme.*`** (text, dim, glass, stroke, track, card*, accent*). Les écrans Outils/Moniteur/Système/caméra restent sombres (`MainActivity.TEXT` etc. = palette nuit fixe).
- Textes en `setIfChanged` dans ce qui est rafraîchi toutes les 500 ms (évite des mises en page inutiles). Liste des applis chargée hors du fil principal (`appCache`).
- Chaque action utilisateur, commande MCU, annonce reçue, erreur : `Journal.log(cat, msg)` / `Journal.error`.
- Préférences : `dock` (`mode` 0 radio / 1 Bluetooth, `slot_N`), `tools` (`tw_enabled`, `bt_channel`, `boot_start`, `wake_front`, `watch_launcher`, `cam_in_sandero`, `cam_id`, `cam_id_auto`, `theme_mode`), `clock` (`tz`, `gps`, `offset`), `safe` (marqueurs, `safe_mode`).

## Compiler
```bash
export JAVA_HOME="$(cd ~/.jdks/jbr-21.0.11 && pwd -W)"      # JDK 21 ; le jbr d'Android Studio est en 25 → « Unsupported class file major version 69 »
G=$(ls -d ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7)/bin/gradle
cd SanderoLauncher && "$G" assembleDebug --offline -q
cp app/build/outputs/apk/debug/app-debug.apk ../release/Sandero.apk
```
Incrémenter `versionCode`/`versionName` (`app/build.gradle`). Les mises à jour par-dessus exigent la **même clé** de signature (clé debug du PC) ; pour des Releases publiques, créer une clé de release (jamais dans le dépôt).

## Tester sur émulateur (AVD `Medium_Phone_API_37.0`)
- Un seul émulateur par AVD : si Android Studio l'a lancé, s'y connecter (`adb devices`) au lieu d'en lancer un autre. Émulateur « offline » = figé → Cold Boot (Device Manager) ou `-no-snapshot-load`.
- Écran autoradio : `adb shell wm size 1280x480 ; adb shell wm density 160` … puis **`wm size reset ; wm density reset`** à la fin. Tester aussi l'écran par défaut (2400×1080, 420 dpi) : détecte les débordements.
- `export MSYS_NO_PATHCONV=1` sous Git Bash avant `adb shell` avec des chemins `/sdcard`.
- Ne pas glisser depuis le bas de l'écran (y > ~430 à 480 px) : geste « Accueil » de l'émulateur.
- Trouver un bouton : `uiautomator dump /sdcard/ui.xml` puis extraire `text=…bounds=…` et taper au centre.
- Journal : `adb shell run-as fr.sandero.launcher cat files/journal.txt` (écrit avec ~2 s de retard).
- Simuler l'autoradio : Moniteur → « Simuler phares » / « Simuler marche arrière » ; annonces BT : `adb shell "am broadcast -a com.unisound.intent.action.SEND_PAIR_INFO --es pairName 'iPhone de Test'"` (guillemets **dans** la commande, sinon les espaces coupent). `CONNECTION_STATE_CHANGED` est protégé : non simulable.
- Caméra : lancer l'émulateur avec `-camera-back virtualscene`.
- Préférences persistantes entre tests (ex. thème forcé) : penser à les remettre (Outils, ou `run-as … sed` sur `shared_prefs/tools.xml`).

## État du projet (v0.9 — 4 nov. 2026)
**Vérifié sur la voiture** : remplace l'interface d'origine, démarre seul, revient au premier plan ; dessin de la Sandero ; caméra de recul intégrée (caméra 4) sans coupure du son (Reversing mute décoché) et retour à l'accueil ; appairage Bluetooth (sans canal 8) ; titre BT via 0x9f00 ; journal.

**À vérifier sur la voiture** :
- v0.7 : son Bluetooth après source 8 ; boutons via 0x9e08 ; transition caméra sans flash noir.
- v0.8 : pastille téléphone (SEND_PAIR_INFO, CONNECTION_STATE_CHANGED et ses valeurs) ; curseur de volume (STREAM_MUSIC agit-il sur le son BT ?) ; source unique Bluetooth.
- v0.9 : dock Réglages/Applis ; thème jour/nuit et sa bascule ; correction de la vignette (icône taille fixe).

**À faire / inconnu** : radio jamais testée ; phares (0x0204 jamais reçu) ; état lecture/pause BT réel ; touches volant (laissées à Topway) ; root non testé.

## Historique des bugs
| Version | Problème | Cause | Solution |
|---|---|---|---|
| 0.3 | Moniteur → plantage, puis appairage BT impossible même après redémarrage | Canal `TWUtil(8)` ouvert en double avec l'appli BT Topway | Canal 8 jamais ouvert par défaut ; Moniteur sans canal ; `Safe` |
| 0.3 | Caméra : 8 caméras, toutes noires | `0x0304` trop tard ; mauvais numéro | `0x0304 = 1` avant ouverture, `setAnalogInputColor`, auto-détection (→ 4) |
| 0.3 | Pas toujours de retour à l'accueil au point mort | Retour programmé puis annulé dans `onPause` | Handler statique + fermeture par le service |
| 0.6 | Heure jamais juste | Fuseau système Asia/Shanghai | `Clock` (Europe/Paris) + GPS |
| 0.6 | BT sans son, boutons inactifs | Source restée sur 3 ; touches média ignorées par com.tw.bt | 0.7 : `0x9e11 …8` + `0x9e08` |
| 0.6 | Flash noir ~0,9 s avant la caméra | Attente 0,4 s + écran Topway visible | 0.7 : ouverture immédiate + retours devant |
| 0.7 | BT et Applis séparés, boutons volume, pas de nom de téléphone | — | 0.8 : source unique, `VolumeSlider`, `BtInfo` |
| 0.8 | Dock redondant ; interface toujours sombre | — | 0.9 : dock 2 cartes ; `Theme` |
| 0.9 | Émulateur « rien ne répond » ; logo BT énorme | Émulateur figé (pas Sandero) ; marge fixe calibrée 1280×480 | Cold boot ; `Icon.sized` + `CENTER_INSIDE` |

## Journal des vérifications
- 2026-11-04 : v0.9 compilée, testée émulateur 1280×480 et 2400×1080 (nuit et jour).
