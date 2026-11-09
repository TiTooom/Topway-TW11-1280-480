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
| `CarView` | Sandero 1 vue de 3/4 avant : `res/drawable-nodpi/car_photo.png` (photo constructeur détourée par `tools/car3d/PhotoCut`, **versionnée depuis le 09/11/2026**, prioritaire) sinon `car_34.png` (rendu du modèle 3D par `tools/car3d/Car3D`), reflet et ombre compris ; repères par image (`Spec`) ; ombre et halo au sol, phares / faisceaux / feu arrière animés (positions en fraction de l'image, voir `tools/car3d/car_34_points.txt`) |
| `UiScale` | Taille des caractères de l'accueil x1 à x3 (pref `tools`/`ui_scale`, index dans `STEPS`) ; `MainActivity.sc(cap)`, `ph(h)`, `text(size,color,cap)` |
| `Theme` | Palettes nuit / jour, mode auto 18 h–9 h, choix forcé |
| `Clock` | Heure Europe/Paris indépendante du fuseau système, recalage GPS, `Clock.View` |
| `Journal` | 10 000 dernières lignes (v0.11), écrites par ajout en fin de fichier (réécriture complète au-delà de 12 000 lignes ou sur `flushNow`), `files/journal.txt` + `Android/data/fr.sandero.launcher/files/journal.txt` |
| `Safe` | Marqueurs d'étapes risquées, mode sans échec, journal des plantages |
| `ToolsActivity` | Outils par catégories (menu à gauche, panneau à droite) : export USB, sauvegardes, explorateur, journal, heure, thème, intégration Topway, caméra |
| `AdbDiag` | Diagnostic ADB sans root (propriétés, ports en écoute, mode USB), essais d'ouverture d'ADB Wi-Fi, écran de choix USB ; journal `[ADB]` |
| `SystemActivity` | Allègement : mémoire, applis à désactiver, animations, root |
| `TwMonitorActivity` | Moniteur TWUtil (écoute, simulations) |
| `Obd`, `ObdPids`, `ObdUsb`, `ObdActivity` | OBD2 par module ELM327 (Bluetooth / Wi-Fi / USB), écran Outils → OBD2 — voir skill `sandero-obd` |
| `VolumeSlider`, `SeekLine`, `Icon` | Composants dessinés (curseur vertical, progression, icônes vectorielles) |
| `MediaListener`, `BootReceiver`, `Root` | Accès notifications (MediaSession), démarrage, `su` |

## Conventions
- Java pur, **sans AndroidX**, UI construite en code, `minSdk 21`, `targetSdk 27`, paquet `fr.sandero.launcher`.
- Tailles en « unités » 1280×480 : `px(v) = v * min(largeur/1280, hauteur/480)`. Les icônes dans un ImageView : `new Icon(type, couleur).sized(px(N))` + `CENTER_INSIDE` (jamais de grosse marge fixe, ça déborde sur d'autres écrans).
- Couleurs de l'accueil : **toujours `Theme.*`** (text, dim, glass, stroke, track, card*, accent*). Les écrans Outils/Moniteur/Système/caméra restent sombres (`MainActivity.TEXT` etc. = palette nuit fixe).
- Textes en `setIfChanged` dans ce qui est rafraîchi toutes les 500 ms (évite des mises en page inutiles). Liste des applis chargée hors du fil principal (`appCache`).
- Chaque action utilisateur, commande MCU, annonce reçue, erreur : `Journal.log(cat, msg)` / `Journal.error`.
- Préférences : `dock` (`mode` 0 radio / 1 Bluetooth, `slot_N`), `tools` (`tw_enabled`, `bt_channel`, `boot_start`, `wake_front`, `watch_launcher`, `cam_in_sandero`, `cam_id`, `cam_id_auto`, `theme_mode`, `ui_scale`, `kiosk`), `clock` (`tz`, `gps`, `offset`), `obd` (voir `sandero-obd`), `safe` (marqueurs, `safe_mode`, `obd_bt`, `obd_usb`).

## Compiler
```bash
export JAVA_HOME="$(cd ~/.jdks/jbr-21.0.11 && pwd -W)"      # JDK 21 ; le jbr d'Android Studio est en 25 → « Unsupported class file major version 69 »
G=$(ls -d ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7)/bin/gradle
cd SanderoLauncher && "$G" assembleDebug --offline -q
# copie automatique : release/Sandero_VXXX.apk (XXX = versionCode sur 3 chiffres, tâche copySanderoApk)
```
Cache de configuration Gradle activé (`gradle.properties`, demandé par Android Studio, 06/11). Incrémenter `versionCode`/`versionName` (`app/build.gradle`). Les mises à jour par-dessus exigent la **même clé** de signature (clé debug du PC) ; pour des Releases publiques, créer une clé de release (jamais dans le dépôt).

## Tester sur émulateur (AVD `Medium_Phone_API_37.0`)
- **Rendu graphique : `hw.gpu.mode=host`** dans `~/.android/avd/Medium_Phone.avd/config.ini` (Intel Iris Xe). En `auto`, l'émulateur retombait sur SwiftShader (rendu logiciel) : très lent, captures noires. Vérifier : `adb shell "dumpsys SurfaceFlinger | grep ^GLES"` doit citer Intel.
- Émulateur en veille → Sandero passe aussitôt « accueil masqué » : `adb shell svc power stayon true` + `input keyevent KEYCODE_WAKEUP`.
- PC à 16 Go : émulateur 2 Go + Android Studio 1,8 Go + navigateurs saturent la mémoire (alertes de performance) ; fermer les navigateurs pendant les essais.
- Outils = **appui long sur la voiture** de l'accueil ; Moniteur en bas de la colonne de gauche d'Outils (faire défiler). Activités non exportées : pas d'`am start` direct.
- **Sans Android Studio** : `tools/Emulateur_Sandero.bat` (double-clic) lance l'émulateur en GPU host, installe le plus récent `release/Sandero_V*.apk`, écran allumé, 1280×480, ouvre Sandero ; une touche remet `wm size/density reset`.
- Un seul émulateur par AVD : si Android Studio l'a lancé, s'y connecter (`adb devices`) au lieu d'en lancer un autre. Émulateur « offline » = figé → Cold Boot (Device Manager) ou `-no-snapshot-load`.
- Écran autoradio : `adb shell wm size 1280x480 ; adb shell wm density 160` … puis **`wm size reset ; wm density reset`** à la fin. Tester aussi l'écran par défaut (2400×1080, 420 dpi) : détecte les débordements.
- `export MSYS_NO_PATHCONV=1` sous Git Bash avant `adb shell` avec des chemins `/sdcard`.
- Ne pas glisser depuis le bas de l'écran (y > ~430 à 480 px) : geste « Accueil » de l'émulateur.
- Trouver un bouton : `uiautomator dump /sdcard/ui.xml` puis extraire `text=…bounds=…` et taper au centre.
- Journal : `adb shell run-as fr.sandero.launcher cat files/journal.txt` (écrit avec ~2 s de retard).
- Simuler l'autoradio : Moniteur → « Simuler phares » / « Simuler marche arrière » ; annonces BT : `adb shell "am broadcast -a com.unisound.intent.action.SEND_PAIR_INFO --es pairName 'iPhone de Test'"` (guillemets **dans** la commande, sinon les espaces coupent). `CONNECTION_STATE_CHANGED` est protégé : non simulable.
- Caméra : lancer l'émulateur avec `-camera-back virtualscene`.
- Préférences persistantes entre tests (ex. thème forcé) : penser à les remettre (Outils, ou `run-as … sed` sur `shared_prefs/tools.xml`).

## État du projet (v0.20 — 9 nov. 2026)
**v0.20 (compilée, non testée)** : allègement aligné sur le choix « Bluetooth seulement » (services Google, Maps, Waze, menu flottant et lecteurs Topway dans la liste ; plus d'exception Google/Waze/Maps dans « Libérer la mémoire ») ; journal `[SYS]`. Scripts ADB `tools/Allegement_ADB.bat` et `tools/Boot_Origine.bat` prêts, jamais lancés.

**Vérifié (v0.19, voiture)** : « Essayer d'ouvrir ADB par Wi-Fi » (`AdbDiag.tryEnable`) ouvre le port 5555 sans root ; le PC le joint (`adb connect 192.168.0.103:5555`), reste à accepter la fenêtre RSA sur l'écran. Prise USB en mode hôte (`otg_role=usb_host`), donc pas d'ADB par câble.
**À faire** : ADB Wi-Fi relancé au démarrage (le port est perdu au redémarrage).
**Root (sûr, 07/11)** : pas de root par ADB — `adb root` n'élève pas (adbd sans `ALLOW_ADBD_ROOT`), `su` AOSP non élévateur. Image des partitions bloquée. Pistes : Magisk sur boot.img du firmware, mode FEL Allwinner, menu usine (voir `topway-systeme`).
**Vérifié (v0.18, voiture)** : « Options développeur / débogage USB » ouvre l'écran Android sans mot de passe ; `adb_enabled` passe à 1 ; l'écriture directe est refusée (`WRITE_SECURE_SETTINGS`). Mais le PC ne voit rien en USB (prise en mode hôte) et `adb connect IP:5555` est refusé (adbd n'écoute pas en TCP).

Détail version par version (contenu, retours, marche / ne marche pas, à corriger) : **[VERSIONS.md](VERSIONS.md)**, à tenir à jour à chaque version et à chaque retour.

**Vérifié sur la voiture** : remplace l'interface d'origine, démarre seul, revient au premier plan ; dessin de la Sandero ; caméra de recul intégrée (caméra 4), **son conservé** (« Inverse le muet » = open), retour à l'accueil ; appairage Bluetooth (sans canal 8) ; son BT après source 8 ; boutons BT 0x9e08 (suivant) ; pastille téléphone (SEND_PAIR_INFO, CONNECTION_STATE 0/2) ; titre/artiste ; curseur de volume en Bluetooth (STREAM_MUSIC) ; thème jour/nuit auto ; radio : bascule de source (0x0301 = 1) ; OBD2 par Bluetooth Android + ELM327 (KWP fast, 13 PID, voir `sandero-obd`) ; journal.

**À vérifier sur la voiture (v0.17)** :
- **Outils par catégories** (`ToolsActivity` : `showMenu`, `openCategory`, `fillXxx`, panneau de droite `showRight`) : tous les boutons marchent, retour, avancement des copies, journal à droite.
**(v0.16)** :
- **Plein écran de l'accueil** (`tools`/`kiosk`, Outils → Affichage) : plus de panneau de notifications en glissant le curseur de volume par le haut ; barres toujours accessibles dans Outils.
**(v0.15)** :
- Titre / artiste / album longs : défilement (marquee) au lieu de « … ». Piège : ne jamais refaire `setText` à l'identique sur ces vues (`setIfChanged`), ça relance le défilement.
- **Taille des caractères** (Outils → Affichage, x1 à x3) : lisible de loin, rien ne déborde, x1 identique à avant, choix gardé après redémarrage.
- Fréquence radio en MHz (v0.14) et lignes `[BT] session Topway`.

**À vérifier (v0.13)** :
- **Volume** : le curseur doit maintenant régler le volume autoradio (0-30) en Radio et en Bluetooth par `write(0x0203, 1, n)` ; chercher `[VOL] le MCU a suivi` après un vrai changement de niveau, puis `volume Android … → 15/15`.
- Caméra : « Écran Topway » ou « Retour » pendant le recul → plus de boucle, la caméra Sandero ne revient pas avant le point mort (`[CAM] caméra quittée par l'utilisateur`). Plus d'« accueil affiché » au milieu d'un recul.
- OBD « Chercher la conso » : `service 21 non pris en charge` puis essai de l'en-tête 817AF1, en quelques secondes au lieu de ~70 s.
- S'il ne suit pas (`n'a pas renvoyé`), le curseur repasse sur le volume Android. Le curseur suit aussi le bouton de façade.
- Retour Radio → Bluetooth : la musique reprend-elle (`0x9e08 = 5` 0,9 s après) ?
- Curseur élargi (75 unités au lieu de 30).
- OBD : connexion auto au démarrage, allumage du Bluetooth Android, reprise après arrêt du moteur, « Chercher la conso » (blocs Renault service 21 dans le journal).
- Export : nom du dossier à l'heure de Paris, taille affichée.
- Voiture 3/4 sur l'accueil (taille, lisibilité au soleil) ; phares : `[MCU] 0x0204` reçu quand on allume les feux ?

**À faire / inconnu** : conso diesel (décoder un bloc Renault après la recherche) ; fréquence / stations radio (0x0401 reçu `00 f8 23 d7`, non décodé) ; phares (0x0204 jamais reçu) ; état lecture/pause BT réel ; touches volant (laissées à Topway) ; root non testé ; valeurs OBD sur l'accueil.

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
| 0.10 | Pas de données moteur | — | Écran OBD2 (ELM327 BT/Wi-Fi/USB), lecture seule, CSV |
| 0.9 | Émulateur « rien ne répond » ; logo BT énorme | Émulateur figé (pas Sandero) ; marge fixe calibrée 1280×480 | Cold boot ; `Icon.sized` + `CENTER_INSIDE` |
| 0.10 | Radio « à fond », curseur sans effet | Le curseur réglait STREAM_MUSIC ; la radio ne passe que par le volume MCU (0x0203) | 0.11 : curseur → `write(0x0203, n)` pour toutes les sources (un seul volume, comme Topway), vérifié par l'écho ; STREAM_MUSIC au maximum |
| 0.10 | Voiture de profil (dessin à la main) | — | 0.11 : vue 3/4 calculée depuis `3D/Sandero.stl` (`tools/car3d`), reflet calculé (le reflet retourné ne marche pas en 3/4 : roues à des hauteurs différentes) |
| 0.11 | Rendu 3D « jeu vidéo », mal texturé (avis utilisateur) | Modèle STL sans matières, bosselé | Photo réelle détourée (`PhotoCut`) : fond blanc → transparent, ombre de la photo → ombre noire transparente, antenne retirée, contraste + netteté, reflet calculé par colonne ; le rendu 3D reste en secours |
| 0.11 | Détourage : pneu avant coupé en segments droits, pointillés clairs au bord du pneu arrière, bas du bouclier noirci (surtout la nuit) | Ligne du bas en segments à travers le pneu ; remontée de l'ombre dans le flanc gris ; pixels presque blancs isolés (bruit JPEG) ; remontée sous le bouclier | Contour réel des pneus (`PhotoCut.TIRES`, bord adouci), transparence des bords selon la couleur voisine, pixels isolés supprimés, pas de remontée sous le bouclier |
| 0.10 | Journal trop court (1000 lignes) pour suivre des données CAN | — | 0.11 : 10 000 lignes, écriture par ajout |
| 0.10 | Retour sur Bluetooth : musique arrêtée | com.tw.bt met le téléphone en pause hors source 8 | 0.11 : `selectBt` = source 8 + `0x9e08 = 5` |
| 0.10 | OBD : à reconnecter à chaque fois ; « Bluetooth éteint » en boucle | L'autoradio éteint le Bluetooth Android au démarrage ; pas d'option auto | 0.11 : `enable()` à la connexion, pref `auto`, `Obd.autoStart` dans le service |
| 0.10 | OBD : valeurs figées, 0 requête/s | PID abandonnés pour toujours après 3 NO DATA (moteur coupé) | 0.11 : PID redemandés toutes les 15 s ; 8 s sans réponse → ATPC + nouvelle recherche |
| 0.10 | OBD : « essence » sur un diesel ; 65535 km / 255 semblent figés | Bit du PID 01 faux sur ce calculateur ; compteurs plafonnés par la norme | 0.11 : bit masqué ; « (plafond) » |
| 0.11 | Caméra : boucle caméra / accueil / écran Topway après « Écran Topway » | 0x020b (envoyé par l'écran Topway, 0x9f1c toujours à 1) pris pour la fin du recul ; retours à l'accueil différés qui recouvraient la caméra suivante | 0.12 : 0x020b/0x020c ignorés ; choix de l'utilisateur respecté jusqu'au point mort (`ReverseCamActivity.dismissed`) ; `bringToFront` et retour accueil bloqués en marche arrière |
| 0.11 | OBD : recherche conso ~70 s pour rien | `7F 21 11` (service inconnu) pris pour une réponse → 255 blocs interrogés ; en-tête 817AF1 laissé en place s'il échouait | 0.12 : `service21()` (code 11 = refus, autres codes = service présent), session 10C0 seulement si `50` reçu ; en-tête remis dès qu'il a été changé |
| 0.11–0.12 | Curseur sans effet sur le volume autoradio (bloqué à 15/30) | Commande devinée `write(0x0203, n)` (2 valeurs) ; la barre Topway envoie `write(0x0203, 1, n)` (lu dans com.tw.service) ; bit 31 de arg1 (sourdine) non masqué | 0.13 : `write(0x0203, 1, n)`, niveau = arg1 & 0x7fffffff, `mcuMuted` = bit 31 |
| 0.12 | « le MCU a suivi » alors que la commande volume ne marche pas → volume Android forcé à 15/15 | Curseur demandé = volume déjà en place (15) : l'écho périodique 0x0203=15 pris pour une réponse | Pas de vérification d'écho si le niveau demandé = niveau actuel |
| 0.12 | Voiture de l'accueil redevenue le rendu 3D gris | `car_photo.png` non versionnée, perdue quand `drawable-nodpi/` a été recréé (06/11 8 h 01) → secours `car_34.png` | Photo extraite de `V5_3D+slider.apk` (racine) : `unzip -p V5_3D+slider.apk res/drawable-nodpi-v4/car_photo.png > …/drawable-nodpi/car_photo.png`. **Avant chaque APK livré, vérifier qu'elle est là** (`unzip -l … \| grep car_photo`) |
| 0.14 | Radio : tirets au lieu de la fréquence | Format 0x0401 mal supposé (c'est un `byte[4]`, pas arg1/arg2) | 0.14 : fréquence lue dans `{drapeaux, 0xf8, haut, bas}`, en 10 kHz |
| 0.15 | Curseur de volume pris par le haut : le panneau des notifications s'ouvre | Barre d'état visible, glissement vers le bas capté par le système | 0.16 : accueil en plein écran immersif collant, remasquage des barres, bouton dans Outils |
| 0.15 | (demande) Écran petit, texte illisible de loin | — | `UiScale` x1–x3 dans Outils, plafonds par élément, hauteurs libres dès x>1 |
| 0.18 | ADB injoignable depuis le PC (rien en USB, 5555 refusé en Wi-Fi) | Prise USB en mode hôte ; adbd sans port TCP | 0.19 : `setprop service.adb.tcp.port 5555` + `ctl.restart adbd` depuis l'appli (accepté sans root, build eng) → ADB Wi-Fi |
| 0.18 | `car_photo.png` de nouveau absente de `drawable-nodpi/` (APK 537 Ko au lieu de 1,15 Mo) | Fichier non versionné, perdu à nouveau | Restaurée depuis `Sandero_V018.apk` ; **comparer la taille de l'APK à la précédente avant livraison** |
| 0.19 | Allègement : Google, Waze et Maps épargnés alors que non utilisés | Liste écrite pour un usage « type » avec navigation | 0.20 : ajoutés à la liste, plus épargnés par « Libérer la mémoire » |
| 0.10 | Export : « 0 o » ; dossier à l'heure de Shanghai ; « impossible de créer /storage/usb0 » | `writeText` ne comptait pas les octets ; `new Date()` système ; clé pas encore montée | 0.11 : comptage, `Clock.format`, message « clé absente ou pas prête » |

## Journal des vérifications
- 2026-11-05 : photo détourée testée sur émulateur 1280×480, jour, nuit, phares simulés : OK (lueur des phares discrète de jour).
- 2026-11-05 : voiture 3/4 testée sur émulateur 1280×480, jour et nuit, phares simulés (Moniteur) : lueur des phares, faisceaux, feu arrière OK.
- 2026-11-05 : journal 10 000 lignes testé sur émulateur (redémarrage forcé : aucune ligne en double). Volume unique radio + BT (demande utilisateur).
- 2026-11-05 : journal voiture v0.10 analysé (voir les bugs 0.10 ci-dessus) ; v0.11 compilée, accueil vérifié sur émulateur 1280×480 (curseur élargi).
- 2026-11-04 : v0.10 OBD2 testé sur émulateur avec faux module Wi-Fi (10.0.2.2:35000).
- 2026-11-04 : v0.9 compilée, testée émulateur 1280×480 et 2400×1080 (nuit et jour).
- 2026-11-06 (v0.11, journal) : bugs relevés : (1) `write(0x0203, n)` sans effet → curseur retombé sur le volume Android 0-15, indépendant du volume autoradio 0-30 ; (2) 0x020b traité comme fin de recul → boucle caméra après « Écran Topway » ; (3) recherche conso : 7F 21 11 / 7F 10 11 (service non pris en charge) pris pour une réponse → balayage inutile de 255 blocs (~70 s). Corrigés en 0.11 : export journal 0 o, Bluetooth Android allumé automatiquement par l'OBD.
- 2026-11-06 : v0.12 compilée (bugs caméra 0x020b et recherche conso corrigés), non testée sur émulateur (TWUtil absent : caméra Topway et OBD réel non simulables).
- 2026-11-06 : v0.12 testée sur émulateur 1280×480 (GPU host) : marche arrière simulée → caméra ; « Écran Topway » → caméra non ramenée (`caméra quittée par l'utilisateur`) ; point mort → accueil ; nouveau recul → caméra rouverte. OK.
- 2026-11-06 : car_photo.png restaurée depuis V5_3D+slider.apk, v0.12 recompilée, accueil vérifié sur émulateur 1280×480 (photo).
- 2026-11-06 (journal V6, voiture) : faux « le MCU a suivi » (15 demandé, déjà 15) puis write(0x0203, 8) sans effet → corrigé ; APK recompilée.
- 2026-11-06 : v0.13 compilée (commande volume corrigée d'après com.tw.service), non vérifiable sur émulateur (TWUtil absent).
- 2026-11-06 (journal v0.13, voiture) : volume 0x0203 OK (write(0x0203,1,n) suivi d'un écho = n, plage 0-30, curseur Bluetooth/Radio). Caméra OK une fois la permission CAMÉRA accordée (avant : « Fail to connect to camera service » sur les 8 caméras, indiscernable d'une caméra occupée). Défauts relevés : (1) aucun diagnostic de permission, 8 caméras × 6 essais ≈ 18 s de noir ; (2) chaînes d'essais caméra parallèles (« caméra 0 essai 1 » plusieurs fois par seconde) ; (3) caméra apprise non encore enregistrée au 1er succès (appris -1 → 14 s de recherche avant la 4). À faire : vérifier checkSelfPermission(CAMERA) et le dire dans le journal / à l'écran ; annuler la chaîne en cours dans startCamera.
- 2026-11-06 : v0.14 compilée (fréquence radio en MHz lue dans le byte[] de 0x0401), non testée sur la voiture. Bug v0.13 : tirets à la place de la fréquence (format 0x0401 mal supposé).
- 2026-11-06 : v0.14 : observation de la session média Topway (journal `[BT] session Topway`), icône lecture/pause fiable seulement si la session a changé. Règle projet : autoradio jamais en ligne.
- 2026-11-06 : règle de traçabilité (demande de l'utilisateur) : `release/` garde **toutes** les versions livrées (Sandero_V013.apk = base, puis V014…). Jamais de suppression ni d'écrasement ; tout changement testé sur la voiture = nouveau versionCode. Nettoyage fait : anciens V1_NOK, V2_IN_TEST et Sandero.apk supprimés. Piège : V014 a été compilée deux fois sous le même numéro (fréquence radio, puis session Bluetooth) → l'APK actuel contient les deux changements.
- 2026-11-07 : v0.15 compilée (taille des caractères x1–x3 sur l'accueil), non testée sur émulateur (aucun lancé) ni sur la voiture. VERSIONS.md complété avec le détail de la V014.
- 2026-11-07 : défilement des textes longs ajouté à la v0.15 (pas de v0.16 : rien n'avait été poussé), non testé.
- 2026-11-07 : v0.16 compilée (plein écran de l'accueil), non testée. V015 poussée sur GitHub le même jour.
- 2026-11-07 : v0.17 compilée (Outils par catégories), non testée. Piège : j'ai écrasé Sandero_V016.apk (déjà poussée) en recompilant avant d'avoir changé le versionCode ; restaurée par `git checkout`. Toujours incrémenter le versionCode AVANT de compiler après un push.
- 2026-11-07 : v0.18 compilée (bouton Options développeur : contournement du mot de passe Topway, 123456 refusé), non testée.
- 2026-11-07 : v0.19 compilée (`AdbDiag` : diagnostic ADB, essai ADB Wi-Fi, choix du mode USB), non testée. Photo de la voiture restaurée depuis V018 avant compilation.
- 2026-11-07 (V019, voiture) : ADB Wi-Fi OK (`AdbDiag.tryEnable` ouvre 5555 sans root, PC connecté après RSA). Shell uid 2000. **Root non obtenu** : `adb root` inopérant (adbd sans ALLOW_ADBD_ROOT), `su` AOSP non élévateur. `dd` des partitions impossible sans root ; `Sauvegarde_ADB.bat` à revoir (dépend de `adb root`). Pistes root notées dans `topway-systeme`.
- 2026-11-09 : **restructuration du dépôt** (règle 5 de CLAUDE.md réécrite) : tout le firmware de l'unité fusionné dans `autoradio/systeme_TW11_20220907/` (dump ADB + 220 fichiers de `/system/etc` + TWCore/menu flottant), OTA dans `autoradio/firmware_T3-P1_THEME1_20231116/` (LFS), APK Google/Waze/Torque/DAB/MuPDF supprimés, `APP/INFOS/Backup From autoradio` et `autoradio_TW11_systeme/` supprimés, `car_photo.png` et `tools/externes/` versionnés. Secret réseau et licence OTA trouvés en clair dans l'archive poussée le 07/11 (`d1a6afd`) : masqués.
- 2026-11-09 : v0.20 compilée (allègement aligné, journal `[SYS]`), APK 1,15 Mo avec `car_photo.png` ; non testée.
- 2026-11-07 : voie root choisie = **mode FEL Allwinner**. Étudié (voir `topway-systeme` → Mode FEL) : FEL atteint le T3 mais ne lit pas l'eMMC sans un U-Boot sun8iw11 chargé ; xfel écarté (ni T3 ni eMMC). Pièce manquante commune à toutes les voies : firmware/BSP T3 (U-Boot + boot.img). Rien redémarré sur la voiture (un FEL sans image à exécuter = écran noir). Prochaine étape : outillage PC (sunxi-tools + Zadig) + firmware de secours.
