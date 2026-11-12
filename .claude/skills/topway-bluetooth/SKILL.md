---
name: topway-bluetooth
description: Bluetooth de l'autoradio Topway (appli com.tw.bt) — piloter la musique du téléphone (0x9e08, source 8), lire titre/artiste/nom du téléphone/état de connexion via les annonces com.unisound.* et 0x9f00, et dépanner un appairage bloqué. À charger pour toute fonction Bluetooth de Sandero.
---

# Bluetooth Topway

## À NE JAMAIS FAIRE
**Ouvrir `TWUtil(8)`** (canal série du module Bluetooth, `open(ids, 0x1c200)`) pendant que l'appli Bluetooth Topway tourne. Constaté le 04/11/2026 : plantage du Moniteur, puis **appairage impossible même après redémarrage**, tant que Sandero ouvrait ce canal à chaque démarrage. Option « Bluetooth direct (expert) » dans Outils : désactivée par défaut, avec avertissement.

Si l'appairage est bloqué : installer une version sans canal 8 → forcer l'arrêt de Sandero et de « Bluetooth » (com.tw.bt) → redémarrage complet (Reset, pas seulement contact) → oublier l'autoradio sur le téléphone → réappairer.

## Piloter la musique (sans canal 8) — `HeadUnit.btControl`
- Son vers les haut-parleurs : `write(0x9e11, 0xc0, 8)` sur le canal principal (sinon la source reste sur 3 = Android et **aucun son**). Fait en choisissant « Bluetooth » et avant une commande.
- Commandes : `write(0x9e08, code)` sur le canal principal → reçu par `BaseBTService.controlBtMusic(code)` : **1** lecture/pause, **3** suivant, **4** précédent, **5** lecture (si en pause), **6** pause (si en lecture), **11** sourdine, **12** fin de sourdine. 5/6 n'agissent que si la source BT (8) est active.
- Les touches média Android (`ACTION_MEDIA_BUTTON`, `dispatchMediaKeyEvent`) **n'atteignent pas** l'appli Topway (constaté v0.6). L'appli a une MediaSession sans métadonnées.
- Pas d'état lecture/pause disponible sans canal 8 : Sandero le déduit de ses propres commandes (`btPlayingGuess`).
- **Retour radio → Bluetooth** : l'appli Topway met le téléphone en pause quand la source quitte 8 ; revenir sur la source 8 ne relance pas la musique (vu 05/11). Sandero (`HeadUnit.selectBt`, v0.11) envoie `0x9e08 = 5` (lecture) 0,9 s après la demande de source 8.

## Lire les infos (annonces, sans risque) — `BtInfo.java`
L'appli BT (`BroadcastManager`) dialogue avec l'assistant vocal par broadcasts :
- **Demandes** (Sandero les envoie toutes les 5 s sans téléphone, 30 s une fois connecté et au retour à l'accueil) : `com.unisound.intent.action.GET_PAIR_INFO`, `GET_ID3_INFO`, `GET_DEVICE_INFO`, `CHECK_BTSTATUS`.
- **Réponses** : `com.unisound.intent.action.SEND_PAIR_INFO` (`pairName` = nom du téléphone, `pairMac`) · `SEND_ID3_INFO` (`musicTitle`, `musicArtist`) · `SEND_DEVICE_INFO` (`deviceName` = nom de l'autoradio, `devicePin`).
- **Spontanées** : `android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED` (int `android.bluetooth.adapter.extra.CONNECTION_STATE` : **0 déconnecté** (à l'extinction) et **2 connecté** (~5 s après le démarrage) vus le 05/11 ; 1 / 3 supposés), `…adapter.action.STATE_CHANGED`, `com.tw.bt_phone_info` (`singal`, `power` : réseau et batterie du téléphone, échelle inconnue), appels `com.unisound.intent.action.SEND_CALL_INFO` (`state`, `callName`, `callNumber`) et `SEND_CALL_INFO2`.
- Aussi écoutées par l'appli BT : `net.easyconn.a2dp.acquire/release`, `com.tw.launcher.btmsg`, `com.unisound.intent.action.ACC_ON/ACC_OFF`, `GET_CONTACTS`, `GET_CALL_INFO`.
- **0x9f00** (canal principal, arg1 = 8, obj = titre) : titre en cours, « Not Provided » si absent (ignoré par Sandero).

## Protocole du canal 8 (référence seulement, ne pas utiliser)
`TWUtil(8)`, ids `010b 0112 0201 0203 0301 0510 9e08 9e1d`, baud 0x1c200. Commandes `write(20, 0)` lecture/pause, `write(20, 2)` suivant, `write(20, 3)` précédent. Reçus : 0x0007 HFP (2 = connecté), 0x0015 lecture (≠0), 0x0037 ID3 (`TWObject.obj3` titre, `obj4` « artiste-#album »), 0x0047 progression (arg1 & 0x7fffffff position, arg2 durée), 0x0004 appairage, 0x001f nom, 0x0021 PIN, 0x0030 état appairage, 0x0036 MAC, 0x003b appel.

## Dans Sandero
- Une seule source « Bluetooth » = tout le son du téléphone (musique, YouTube…). L'ancien mode « Applis » est fusionné (v0.8).
- **Piège (V019)** : ne pas se fier au seul `CONNECTION_STATE_CHANGED` pour l'état : une annonce manquée laissait « déconnecté » pour toujours. Depuis v0.22, `pairName` non vide ou titre 0x9f00 = connecté ; `pairName` vide = déconnecté.
- Pastille en haut à droite : nom + état (point vert/orange/gris), appui → appli com.tw.bt (appairage).
- Journal : catégorie `BT` (chaque annonce avec ses extras), `CMD` (« Bluetooth write(0x9e08, n) », « demande de source 8 »).

## Journal des vérifications
- 2026-11-04 v0.6 : appairage OK sans canal 8 ; titre via 0x9f00 OK ; pas de son et boutons inactifs (source restée 3, touches média ignorées).
- 2026-11-04 v0.8 : annonces testées sur émulateur uniquement (am broadcast).
- 2026-11-05 v0.10 (voiture) : **sûr** : SEND_PAIR_INFO (nom + MAC, `null` après déconnexion), SEND_ID3_INFO (titre + artiste), SEND_DEVICE_INFO, CONNECTION_STATE_CHANGED 0/2, son après source 8, `0x9e08 = 3` (suivant : nouveau titre en 0,3 s). Musique arrêtée au retour de la radio → v0.11 envoie « lecture ».
- 2026-11-05 : allumer le **Bluetooth Android** (pour l'OBD) fait hacher la musique du téléphone 4-5 s puis tout repart (dit par l'utilisateur) ; l'appairage du téléphone n'est pas cassé. L'autoradio éteint le Bluetooth Android à chaque démarrage.
- 2026-11-06 (v0.14) : **L'autoradio n'est JAMAIS connecté à Internet** (décision de l'utilisateur) : aucune fonction ne doit dépendre du réseau (pochettes en ligne, API web…). Seules sources possibles : le Bluetooth du module Topway et les annonces. Désynchro lecture/pause constatée (téléphone ou façade → icône fausse jusqu'à l'appui) : v0.14 observe la session média com.tw.bt (`watchBtSession`) et journalise `[BT] session Topway : état N` / `métadonnées` / `pochette true|false` ; l'état de la session n'est utilisé qu'après un premier changement vu. **À vérifier dans le prochain journal** : la session suit-elle le téléphone ? expose-t-elle une pochette ?
- 2026-11-11 (V019, dit par l'utilisateur) : musique du téléphone jouée mais écran « non connecté » / « Aucun téléphone connecté ». Cause **probable** (code, pas encore de journal) : état 0 jamais corrigé par les réponses SEND_PAIR_INFO. Corrigé en v0.22 ; à confirmer par la ligne `déduit` du journal.
- 2026-11-12 (journal V019/V020, sûr) : à la fermeture de com.tw.bt (0x9e00 = 136) la source 0x0301 repasse à 0 → silence. Au démarrage le téléphone joue déjà en silence (titres 0x9f00 qui changent, source 0) : `0x9e08 = 1` (bascule) le met en pause, utiliser `5` (lecture). Corrigé en V023.
