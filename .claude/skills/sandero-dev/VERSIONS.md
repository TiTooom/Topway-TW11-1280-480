# Journal des versions de Sandero

Une entrée par version livrée, la plus récente en haut. Nom de l'APK : `release/Sandero_VXXX.apk` (XXX = `versionCode` sur 3 chiffres).

Pour chaque version :
- **Contenu** : ce que la version ajoute ou corrige.
- **Retours** : ce que l'utilisateur ou son journal a dit, avec la date.
- **Marche** / **Ne marche pas** / **À corriger** : à remplir dès qu'on a un retour de la voiture.

Une ligne « (sûr) » = vu sur la voiture ou dans le journal ; « (émulateur) » = vu seulement sur l'émulateur ; « (à vérifier) » = pas encore essayé.

---

## 018 · Sandero_V018.apk · 07/11/2026

**Contenu** : Outils → Système et démarrage → « Options développeur / débogage USB » (`ToolsActivity.openDeveloper`). Contourne le mot de passe Topway (123456 refusé) : (1) tente `Settings.Global.putInt(adb_enabled, 1)` et `development_settings_enabled` (permission `WRITE_SECURE_SETTINGS` déclarée dans le manifeste, accordée seulement si le système l'autorise) ; (2) ouvre l'écran Android des options développeur (`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, puis `DevelopmentSettings`, puis `DevelopmentSettingsActivity`), à défaut « À propos » (7 appuis sur Numéro de build). Tout est journalisé en `[DEV]`.
- **À vérifier (voiture)** : l'écran Android s'ouvre-t-il sans mot de passe ? `[DEV] écriture adb_enabled=1 acceptée` ou `refusée` ? Aucun ordinateur n'est nécessaire pour cette étape ; il en faut un ensuite pour `adb`.
- Non testé sur la voiture ni sur l'émulateur.

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
