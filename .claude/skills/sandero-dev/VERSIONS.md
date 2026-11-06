# Journal des versions de Sandero

Une entrée par version livrée, la plus récente en haut. Nom de l'APK : `release/Sandero_VXXX.apk` (XXX = `versionCode` sur 3 chiffres).

Pour chaque version :
- **Contenu** : ce que la version ajoute ou corrige.
- **Retours** : ce que l'utilisateur ou son journal a dit, avec la date.
- **Marche** / **Ne marche pas** / **À corriger** : à remplir dès qu'on a un retour de la voiture.

Une ligne « (sûr) » = vu sur la voiture ou dans le journal ; « (émulateur) » = vu seulement sur l'émulateur ; « (à vérifier) » = pas encore essayé.

---

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
