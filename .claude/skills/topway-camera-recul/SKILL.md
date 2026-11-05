---
name: topway-camera-recul
description: Caméra de recul de l'autoradio Topway et sa reprise dans Sandero (ReverseCamActivity) — détection marche arrière 0x9f1c, séquence 0x0304 + Camera.open + setAnalogInputColor, numéro de caméra (4 sur la voiture de référence), passage par-dessus l'écran Topway, coupure du son. À charger pour tout ce qui touche à la marche arrière ou à la caméra.
---

# Caméra de recul

## Comment fait Topway (`com.tw.service`)
- Marche arrière : message **0x9f1c** arg1 = 1 → `TWService` lance `ReverseActivity` (si sa config de caméra ≠ -1) ; arg1 = 0 → la ferme. Variantes : `LRReverseActivity`, `ReverseNissanActivity` (fermées sur 0x020c / 0x020b).
- `ReverseActivity.onResume` → `write(0x0304, 1)` ; `onPause` → `write(0x0304, 0)` **et libère la caméra** (aussi dans `surfaceDestroyed`). Une appli qui passe par-dessus récupère donc la caméra.
- Ouverture : `Camera.open(d.R)` (numéro lu dans la config : AHD…), `setAnalogInputColor(lum+32, contraste+102, saturation+100)` bornés 0–255 (valeurs par défaut 32/102/100), `setCameraFlipStatus` si miroir, aperçu sur `SurfaceView` (`setType(3)`), `startPreview` quand le signal vidéo est présent (`/sys/class/tw/av/signal`). Si caméra id 3 (AHD) : `write(0x9f11/12/13, valeur)`.
- **Son coupé en marche arrière** : réglage Topway Paramètres → Son → « Reversing mute » (en français « Inverse le muet »). **Sûr (voiture, 05/11/2026)** : il faut le mettre sur **« open »** (il était sur « close », ce qui coupait le son). Sandero ne coupe rien lui-même.

## Ce que fait Sandero
- `SanderoService` écoute 0x9f1c → ouvre **tout de suite** `ReverseCamActivity` (tâche séparée, singleTask, sans animation), puis vérifie à 0,25 / 0,6 / 1 / 1,6 s qu'elle est devant (`isFront` = activité reprise) et la ramène si l'écran Topway passe dessus.
- Séquence (fil « camera ») : `write(0x0304, 1)` **avant** l'ouverture (via le fil principal) → `Camera.open(id)` → `setAnalogInputColor(32,102,100)` par réflexion → `setPreviewDisplay` → `startPreview`.
- Ordre des numéros : choix manuel (`cam_id`), sinon le numéro appris (`cam_id_auto`), puis 0…n-1. Chaque échec (« Fail to connect to camera service » = occupée/inexistante) est réessayé 6 fois à 250 ms puis on passe au suivant.
- Auto-détection : luminosité moyenne (plan Y NV21, 1 octet sur 97) ; < 10 trois fois → caméra « noire » écartée ; ≥ 2 images claires → numéro retenu.
- Point mort (0x9f1c = 0) : fermeture + `write(0x0304, 0)` + retour à l'accueil (Handler statique, **pas** celui de l'activité, sinon annulé par onPause). Filet : fermeture d'office après 10 min. Boutons : Retour, Caméra suivante (retient le choix), Écran Topway.
- Au retour devant après avoir été recouvert, la caméra est rouverte (`startCamera()` dans onResume si la surface est valide).

## Valeurs de la voiture de référence
- 8 caméras déclarées ; **0 à 3 : « Fail to connect to camera service »** ; **4 = caméra de recul** (luminosité 20–24 en intérieur) — vu le 04/11/2026.
- `/sys/class/tw/av/signal` renvoie -1 depuis Sandero.

## Pièges
- 0x0304 envoyé après l'ouverture (ou pendant que Topway le remet à 0) → **image noire**.
- Attendre 0,4 s avant de passer devant → ~0,9 s d'écran noir Topway visible.
- `onWindowFocusChanged` arrive tard : ne pas s'en servir pour dire « recouvert », sinon fermeture/réouverture inutile de la caméra.

## Journal des vérifications
- 2026-11-04 v0.6 (voiture) : caméra 4 OK, son conservé (Reversing mute décoché), retour accueil OK, flash noir ~0,9 s.
- 2026-11-04 v0.7 (émulateur) : fenêtre ouverte 30 ms après la marche arrière simulée.
- 2026-11-05 v0.10 (voiture, 3 marches arrière) : fenêtre ouverte ~150 ms après 0x9f1c, caméra 4 image OK (luminosité 71–108 de jour), **son conservé** avec « Inverse le muet » = open, retour accueil OK. 0x9f1c répété toutes les 500 ms confirmé (×39 en 20 s).
