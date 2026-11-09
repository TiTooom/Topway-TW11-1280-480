# Journal des versions de Sandero

Une entrée par version livrée, la plus récente en haut. Nom de l'APK : `release/Sandero_VXXX.apk` (XXX = `versionCode` sur 3 chiffres).

Pour chaque version :
- **Contenu** : ce que la version ajoute ou corrige.
- **Retours** : ce que l'utilisateur ou son journal a dit, avec la date.
- **Marche** / **Ne marche pas** / **À corriger** : à remplir dès qu'on a un retour de la voiture.

Une ligne « (sûr) » = vu sur la voiture ou dans le journal ; « (émulateur) » = vu seulement sur l'émulateur ; « (à vérifier) » = pas encore essayé.

---

## 020 · Sandero_V020.apk · 09/11/2026

**Contenu** : allègement aligné sur le choix du propriétaire (Bluetooth seulement, ni navigation ni services Google).
- Outils → Système et allègement (`SystemActivity`) : ajout à la liste des services Google (`gms`, `gsf`, `partnersetup`, vert), Google Maps et Waze (orange), menu flottant Topway `com.tw.assistivetouch` (vert), lecteurs Topway musique/vidéo/AUX (orange).
- « Libérer la mémoire » n'épargne plus les services Google, Waze ni Maps.
- Journal `[SYS]` : libération de mémoire (nombre d'applis, gain) et chaque désactivation/réactivation par root. SystemActivity n'écrivait rien avant.
- Hors appli : `tools/Allegement_ADB.bat` + `tools/allegement_liste.txt` (35 paquets) + `tools/Allegement_Annuler.bat`, et `tools/Boot_Origine.bat` (sauvegarde du boot d'origine, voir `topway-image-systeme`).

**À vérifier (voiture)** : la liste affiche les nouvelles lignes ; « Libérer la mémoire » sans effet de bord ; lignes `[SYS]` dans le journal. Sans root, une désactivation ouvre toujours la page Android de l'appli.

## 019 · Sandero_V019.apk · 07/11/2026

**Contenu** : ADB. Nouvelle classe `AdbDiag` ; trois boutons dans Outils → Système et démarrage :
- « Diagnostic ADB (USB et Wi-Fi) » : lit sans root `adb_enabled`, les propriétés (`init.svc.adbd`, `service.adb.tcp.port`, `persist.adb.tcp.port`, `sys.usb.config`, `sys.usb.state`, `ro.adb.secure`…), les adresses IPv4, les ports TCP en écoute (`/proc/net/tcp`, port 5555 ouvert ou non), un essai de connexion à soi-même sur 5555, l'état USB (annonce `USB_STATE`, périphériques branchés = mode hôte), le sysfs du contrôleur (`/sys/class/android_usb`, `/sys/class/udc`, `/sys/devices/platform/soc/usbc0`).
- « Essayer d'ouvrir ADB par Wi-Fi » : tente `setprop service.adb.tcp.port 5555` puis `ctl.restart adbd` (refus attendus sans root), puis avec `su` si présent ; relit ensuite le port.
- « Choix du mode USB (Android) » : essaie d'ouvrir `UsbModeChooserActivity`, `UsbSettings`, `USB_SETTINGS`.
Tout est journalisé en `[ADB]`. Aucun accès à TWUtil, rien d'écrit au MCU ni dans le sysfs.
- Ajout : `car_photo.png` (non versionnée) avait de nouveau disparu de `drawable-nodpi/` ; restaurée depuis `Sandero_V018.apk` avant de compiler (APK de 537 Ko sans, 1,15 Mo avec).

**Retours** : (07/11, avant V019) `adb connect 192.168.0.103:5555` → « refusée » : le Wi-Fi marche, mais `adbd` n'écoute pas en TCP (sûr). Prise USB en mode hôte : le PC voit aucun périphérique (sûr).
**À vérifier (voiture)** : lancer « Diagnostic ADB » Wi-Fi connecté, puis exporter le journal (lignes `[ADB]`). Savoir surtout : `init.svc.adbd`, `sys.usb.config`, contenu de `/sys/devices/platform/soc/usbc0`, et si l'essai d'ouverture de 5555 réussit.

**Retours (07/11/2026, journal 16:17-16:23, sûr)** :
- **Marche** : « Essayer d'ouvrir ADB par Wi-Fi » **réussit sans root** : `SystemProperties.set("service.adb.tcp.port","5555")` puis `ctl.restart adbd` sont acceptés ; relu = 5555, port 5555 en écoute, `192.168.0.103:5555` répond. Depuis le PC, `adb connect` donne `unauthorized` (attente de la fenêtre RSA sur l'écran de l'autoradio).
- **Marche** : « Diagnostic ADB » : `init.svc.adbd=running`, `sys.usb.config=adb`, `ro.adb.secure=1`, `ro.debuggable=1`, `ro.build.type=eng`, `su` présent en `/system/xbin/su` (mais « Avec root : indisponible »), ports en écoute avant l'essai [5038, 14035].
- Prise USB : `/sys/devices/platform/soc/usbc0/otg_role = usb_host` (fichiers `usb_host`, `usb_device`, `usb_null` présents mais illisibles) ; un périphérique branché (VID 058f PID 6387, clé USB) ; `USB_STATE connected=false`. Contrôleur `1c13000.udc-controller`.
- « Choix du mode USB » : `UsbModeChooserActivity` refusé (SecurityException), `UsbSettings` s'ouvre.
- **Suite (connexion PC + root, sûr)** : `adb connect 192.168.0.103:5555` → `device` après acceptation de la fenêtre RSA. Shell = `uid=2000(shell)`. **Pas de root** : `adb root` n'élève pas (`service.adb.root` reste vide, adbd sans `ALLOW_ADBD_ROOT`) ; le `su` de `/system/xbin/su` est le `su` AOSP non élévateur (`setgid failed` depuis shell). `/system`,`/vendor` en ro, partitions interdites au shell → **image `dd` impossible sans root** ; `Sauvegarde_ADB.bat` ne peut pas marcher tel quel. Voir skill `topway-systeme` → Root pour les pistes (Magisk/FEL/menu usine).
- **Déjà gagné sans root** : `adb shell pm disable-user com.android.launcher` (virer le lanceur d'origine) et logcat/screencap deviennent possibles par Wi-Fi.
- **À corriger / idée V020** : le port 5555 est perdu au redémarrage de l'autoradio (propriété non persistante) → bouton « ADB Wi-Fi au démarrage » (relancer le `setprop` dans `BootReceiver` si l'option est cochée) ; la fenêtre RSA doit être acceptée une fois (« Toujours autoriser »).

## 018 · Sandero_V018.apk · 07/11/2026

**Contenu** : Outils → Système et démarrage → « Options développeur / débogage USB » (`ToolsActivity.openDeveloper`). Contourne le mot de passe Topway (123456 refusé) : (1) tente `Settings.Global.putInt(adb_enabled, 1)` et `development_settings_enabled` (permission `WRITE_SECURE_SETTINGS` déclarée dans le manifeste, accordée seulement si le système l'autorise) ; (2) ouvre l'écran Android des options développeur (`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, puis `DevelopmentSettings`, puis `DevelopmentSettingsActivity`), à défaut « À propos » (7 appuis sur Numéro de build). Tout est journalisé en `[DEV]`.
- **À vérifier (voiture)** : l'écran Android s'ouvre-t-il sans mot de passe ? `[DEV] écriture adb_enabled=1 acceptée` ou `refusée` ? Aucun ordinateur n'est nécessaire pour cette étape ; il en faut un ensuite pour `adb`.
- Non testé sur la voiture ni sur l'émulateur.

**Retours (07/11/2026, journal)** :
- **Marche (sûr)** : le bouton ouvre l'écran Android des options développeur sans mot de passe (`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`). L'utilisateur y a activé le débogage USB : `adb_enabled` passe de 0 à 1 (vu à 15:38).
- **Ne marche pas (sûr)** : l'écriture directe de `adb_enabled` est refusée (`WRITE_SECURE_SETTINGS` non accordée), comme prévu. Le passage par l'écran Android suffit.
- Malgré `adb_enabled=1`, le PC (câble USB-C vers USB-A de manette Xbox) se recharge mais ne voit aucun périphérique : la prise USB de l'autoradio est en mode hôte. Pas de root (Outils).
- Plantage 15:13:34 `DeadSystemException` (`ViewRootImpl.performTraversals`) : c'est le système Android qui est tombé, puis il a redémarré (BOOT_COMPLETED à 15:14:05). Sandero subit le plantage, il n'en est pas la cause.
- Caméra : 0 à 3 en échec (`Fail to connect to camera service`), caméra 4 donne l'image, comme attendu.

## 0.17 · Sandero_V017.apk · 07/11/2026
**Contenu**
- Écran Outils refait : le menu de gauche ne montre plus que les catégories (Copie système, Véhicule, Caméra de recul, Affichage, Journal et heure, Autoradio, Système et démarrage). Un appui remplace le menu par le contenu de la catégorie, avec « ‹ Outils » pour revenir (le bouton Retour d'Android aussi).
- Colonne de droite utilisée selon la catégorie : explorateur et avancement des copies (Copie système), aperçu de l'accueil à la taille choisie (Affichage), journal affiché en direct avec Actualiser / Effacer (Journal et heure), texte d'aide ailleurs.
- Aucune fonction retirée : mêmes boutons qu'en V016, simplement rangés.

**Retours** : aucun pour l'instant.
**Marche** : compile ; non vu sur l'émulateur ni sur la voiture (à vérifier).
**À vérifier** : chaque bouton fonctionne depuis sa catégorie ; retour au menu ; l'avancement d'une copie s'affiche (Copie système) ; l'export du journal affiche son état sous le journal.

## 0.16 · Sandero_V016.apk · 07/11/2026
**Contenu**
- Accueil en plein écran « kiosque » : barre d'état et barre de navigation masquées (immersif collant), pour que le curseur de volume pris par le haut ne tire plus le panneau des notifications. Si une barre réapparaît (glissement depuis le bord), elle est remasquée après 0,6 s.
- Accès aux barres : Outils (appui long sur la voiture) n'est pas en plein écran, la barre d'état et les notifications y restent accessibles. Bouton Outils → Affichage « Accueil en plein écran » (oui par défaut / non) ; préférence `tools`/`kiosk`.
- Limite : ce n'est pas un verrouillage total (pas de `startLockTask` : il bloquerait le lancement des applis Topway). Un glissement depuis le bord peut encore faire apparaître la barre un instant.

**Retours** : aucun pour l'instant.
**Marche** : compile ; non vu sur l'émulateur ni sur la voiture (à vérifier).
**À vérifier** : le glissement du curseur ne tire plus les notifications ; accueil non décalé sans barre d'état ; la barre Topway éventuelle disparaît-elle ?

## 0.15 · Sandero_V015.apk · 07/11/2026
**Contenu**
- Taille des caractères de l'accueil réglable : Outils → Affichage → « Taille des caractères de l'accueil », x1 (défaut, rien ne change) · x1,25 · x1,5 · x2 · x2,5 · x3 (un appui = cran suivant). Choix mémorisé (préférence `tools`/`ui_scale`, classe `UiScale`), conservé après redémarrage ; l'accueil se reconstruit tout seul au retour.
- Plafonds par élément pour que tout tienne dans 480 px : heure et date ×1,5 max, titre ×2,5, artiste ×2,2, album ×1,6, temps / présélections ×1,5–1,6, dock ×1,5 (sous-titre masqué dès x1,5), autres textes ×2. Les boutons et pastilles à hauteur fixe deviennent « hauteur libre » dès que le texte grossit (à x1 : identiques à la V014).
- Seul l'accueil grossit pour l'instant ; Outils, OBD, Système, Moniteur et caméra gardent leur taille.

**Retours** : aucun pour l'instant.
**Marche** : compile (V015, APK mis à jour le 07/11) ; non vu sur l'émulateur ni sur la voiture (à vérifier).
**Ne marche pas** : —
**À corriger / à vérifier** : défilement des titres longs fluide ; lisibilité de loin à x2 / x3 ; débordements possibles (titre long coupé par « … », dock, pastille téléphone) ; x1 doit être strictement identique à la V014.

## 0.14 · Sandero_V014.apk · 06/11/2026
**Contenu**
- Radio : fréquence FM lue dans le `byte[4]` du message 0x0401 `{drapeaux, 0xf8, haut, bas}` (10 kHz : 0x23d7 → 91,75 MHz). Affichage au pas de 50 kHz (« 91.75 », « 93.4 »). Corrige les tirets de la V013 (format 0x0401 mal supposé).
- Bluetooth : observation de la session média de l'appli Bluetooth Topway (`[BT] session Topway …` dans le journal : état, position, métadonnées). L'icône lecture / pause ne se fie à cette session qu'après l'avoir vue changer d'état, sinon son état peut être figé.
- Règle projet notée : l'autoradio n'est jamais en ligne (pas de pochettes ni d'API web).
- Piège : V014 a été compilée deux fois sous le même numéro (fréquence radio, puis session Bluetooth) ; l'APK livré contient les deux.

**Retours** : aucun pour l'instant (journal de la voiture attendu).
**Marche** : — (non essayée sur la voiture).
**Ne marche pas** : —
**À corriger / à vérifier** : fréquence affichée en MHz sur la voiture ; lignes `[BT] session Topway` du journal ; icône lecture / pause juste. Restent de la V013 : vérifier la permission CAMÉRA et le dire à l'écran, annuler la chaîne d'essais caméra en cours, enregistrer la caméra apprise dès le 1er succès.

## 0.13 · Sandero_V013.apk · 06/11/2026
**Contenu**
- Volume : le curseur envoie `write(0x0203, 1, niveau)`, la commande de la barre de son Topway (lue dans `com.tw.service`). Le niveau reçu est lu sans le bit de sourdine (bit 31).
- Plus de faux « le MCU a suivi » quand le niveau demandé est déjà en place (ce faux positif forçait le volume Android à 15/15).
- APK nommé automatiquement `Sandero_VXXX.apk` (tâche Gradle `copySanderoApk`). Script `tools/Emulateur_Sandero.bat`.

**Retours** : aucun pour l'instant.
**Marche** : démarre sur l'émulateur (émulateur).
**Ne marche pas** : —
**À corriger / à vérifier** : le curseur règle-t-il le volume autoradio 0-30 en Radio et en Bluetooth ? Chercher `[VOL] le MCU a suivi` après un vrai changement de niveau (à vérifier).

## 0.12 · Sandero.apk (non numéroté) · 06/11/2026
**Contenu**
- Caméra : 0x020b / 0x020c ne sont plus pris pour une fin de recul. « Retour » ou « Écran Topway » pendant le recul : la caméra Sandero n'est plus ramenée avant le point mort. L'accueil ne passe plus par-dessus la caméra pendant un recul.
- OBD « Chercher la conso » : `7F 21 11` (service absent) arrête la recherche au lieu de balayer 255 blocs (~70 s) ; en-tête normal remis après un essai d'en-tête Renault.
- Photo de la voiture restaurée (`car_photo.png`, perdue du projet le 06/11).
- Cache de configuration Gradle.

**Retours** (06/11, journal « V6 ») : le journal affichait `version 0.11`, donc l'APK installé n'était pas encore cette version. Le curseur a fait `write(0x0203, 15)` puis `write(0x0203, 8)` : le second est resté sans effet ; faux « le MCU a suivi » sur le premier.
**Marche** : caméra + « Écran Topway » + point mort + nouveau recul (émulateur, simulation).
**Ne marche pas** : volume autoradio par le curseur (sûr) → corrigé en 0.13.
**À vérifier** : la boucle caméra a-t-elle disparu sur la voiture ? Recherche conso en quelques secondes ?

## 0.11 · V5_3D+slider.apk (nom donné par l'utilisateur) · 05/11/2026
**Contenu**
- Curseur de volume élargi (75 unités au lieu de 30) et branché sur le volume autoradio par `write(0x0203, n)` (commande devinée).
- Voiture de l'accueil : photo détourée en vue 3/4 (rendu 3D en secours).
- Retour sur Bluetooth : source 8 + `0x9e08 = 5` pour relancer la musique.
- OBD : Bluetooth Android allumé automatiquement, connexion auto, PID redemandés après un arrêt moteur, « essence » masqué sur le diesel.
- Journal de 10 000 lignes ; export avec taille et heure de Paris.

**Retours** (06/11, journal) : « le slider fait monter le son mais jusqu'à 15, le max semble être 30 ; la valeur du slider n'est pas liée au son système ».
**Marche** : export du journal (132 Ko) (sûr) ; Bluetooth Android allumé seul par l'OBD (sûr) ; OBD connecté, 13 PID, code P0670 lu (sûr) ; caméra de recul et retour à l'accueil (sûr).
**Ne marche pas** : `write(0x0203, n)` sans effet, le curseur retombait sur le volume Android 0-15 (sûr) ; boucle caméra / accueil / écran Topway après « Écran Topway » (sûr) ; recherche conso ~70 s pour rien (sûr).
**Corrigé dans** : 0.12 (caméra, conso), 0.13 (volume).

## 0.10 · 05/11/2026
**Contenu** : écran OBD2 (ELM327 Bluetooth / Wi-Fi / USB, lecture seule, CSV).
**Retours** (05/11, journal) : radio « à fond » et curseur sans effet ; voiture de profil peu réaliste ; OBD à reconnecter à chaque fois, « Bluetooth éteint » en boucle ; valeurs figées après arrêt moteur ; export « 0 o ».
**Marche** : OBD en KWP fast, 13 PID, relevés cohérents (sûr).
**Corrigé dans** : 0.11.

## 0.9 · 04/11/2026
**Contenu** : dock à 2 cartes (Réglages, Applis) ; thème jour / nuit automatique (`Theme`).
**Marche** : thème jour / nuit auto (sûr).

## 0.8 · 04/11/2026
**Contenu** : source audio unique Radio / Bluetooth ; curseur de volume (`VolumeSlider`) ; nom et état du téléphone (`BtInfo`, annonces `com.unisound.*`).
**Marche** : pastille téléphone, titre / artiste (sûr).

## 0.7 · 04/11/2026
**Contenu** : son Bluetooth (source 8 par `0x9e11`) et boutons `0x9e08` ; caméra ouverte tout de suite (plus de flash noir).
**Marche** : son BT et bouton suivant (sûr).

## 0.6 · 04/11/2026
**Contenu** : heure Europe/Paris indépendante du fuseau système, recalage GPS (`Clock`).
**Retours** : BT sans son, boutons inactifs ; flash noir avant la caméra → corrigés en 0.7.

## 0.3 · 2026
**Contenu** : premières versions sur la voiture (lanceur, caméra, Moniteur).
**Retours** : Moniteur → plantage et appairage BT bloqué (canal `TWUtil(8)`) ; caméras noires ; pas toujours de retour à l'accueil → corrigés (voir l'historique des bugs dans `SKILL.md`).
