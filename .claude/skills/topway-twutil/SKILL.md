---
name: topway-twutil
description: API interne Topway android.tw.john.TWUtil (canaux, open/addHandler/write, table des messages MCU 0x0204 éclairage, 0x0301 source, 0x9f1c marche arrière, 0x9e11 source audio, 0x9e08…). À charger avant d'écrire ou de lire quoi que ce soit vers le MCU de l'autoradio Topway, ou pour interpréter un journal Sandero (lignes [MCU]).
---

# TWUtil : l'API Topway du MCU

## API (classe du framework, absente du SDK → réflexion, voir `SanderoLauncher/.../Tw.java`)
- Classe `android.tw.john.TWUtil` dans `boot-framework.vdex` (avec `TWClient`, `InteractionController`, `PinyinConv`, `TWUtil$TWObject`, `TWUtil$IEvent`). **Aucune permission requise** (l'appli Radio Topway s'en sert sans être système).
- `new TWUtil()` = canal principal ; `new TWUtil(int canal)` : canal 8 = module Bluetooth (**ne pas ouvrir**, voir `topway-bluetooth`).
- `int open(short[] ids)` (0 = OK) ou `open(short[] ids, int baud)` (BT : 0x1c200) ; `start()` ; `addHandler(String tag, Handler h)` ; `removeHandler(tag)` ; `stop()` ; `close()`.
- `write(id)`, `write(id, a)`, `write(id, a, b)`, `write(id, a, b, Object)`.
- Message reçu : `msg.what` = id, `arg1`, `arg2`, `obj` (String, `byte[]`, ou `TWUtil$TWObject` avec champs `obj1`…`obj4`).
- Les messages **0x9exx sont diffusés à tous les canaux** (vu : 0x9e00 écrit par l'appli BT sur son canal 8, reçu sur notre canal principal). C'est ce qui permet de piloter l'appli Bluetooth sans ouvrir son canal.

## Règles Sandero
- Une seule instance principale (`HeadUnit.start`), protégée par `Safe.begin("tw_main")`.
- Abonnement (Tw.MAIN_IDS) : `0201 0203 0204 0205 020b 020c 0301 0302 0401 0402 0404 0405 0406 9e00 9e01 9f00 9f10 9f1c`.
- **Aucune écriture au démarrage.** Les demandes d'état `write(x, 0xff)` ne sont envoyées que sur action utilisateur (radio).
- Écrire depuis le fil principal (les applis Topway le font ainsi) ; la caméra passe par un `CountDownLatch` vers le fil principal.
- Tout message reçu est journalisé `[MCU] 0xXXXX (nom) arg1 arg2 obj` (doublons consécutifs regroupés « ×N »).

## Table des messages (canal principal)

| ID | Sens | Fiabilité |
|---|---|---|
| **0x0204** | Éclairage : arg1 == 1 → feux allumés (nuit). `com.tw.service` relaie `REQ_NIGHT_MODE_CMD`/`REQ_DAY_MODE_CMD` à Z-Link. Envoyé **au changement** : si les feux sont allumés avant le démarrage de l'autoradio, rien n'arrive (probable). Demande d'état possible par `write(0x0204, 0xff)` (même forme que les demandes de l'appli Radio, **probable**, jamais essayé : ce serait une écriture au démarrage, interdite sans accord). Il faut aussi que le fil ILL (éclairage) du faisceau de l'autoradio soit branché | sûr (code) — **jamais vu dans un journal** |
| **0x0301** | Source audio active : 0 au démarrage, 1 radio, 3 média Android, 8 Bluetooth | **sûr (vu)** |
| 0x0302 | arg1 = 0 au démarrage / extinction | inconnu |
| 0x0201 | Touches (arg1 : 1 pause, 3 suivant, 4 précédent, 5 lecture, 6 pause, 9 avance, 10 retour) | probable |
| **0x0203** | **Volume général** (bouton de façade) : arg1 = niveau, arg2 = 0x8000001E (bas = 30 = maximum). Le bouton fait monter arg1 de 17 à 29 pas à pas. arg1 = 0 au démarrage tant que le volume n'a pas bougé (pas le vrai niveau). Le volume Android (STREAM_MUSIC) ne le change pas et n'agit pas sur la radio. C'est le volume de la barre de son Topway, commun à toutes les sources. Réglage par Sandero (curseur de l'accueil, radio et Bluetooth) : `write(0x0203, niveau)` **sans effet (sûr, v0.11, 06/11 : demandé 29 puis 30, le MCU reste à 15)** ; la vraie commande d'écriture reste à trouver ; `write(0x0203, 0xff)` = demande d'état (appli Radio) | lecture **sûr (vu 05/11)**, écriture probable |
| 0x0205 | Frein à main (arg1 = 1 au démarrage) | probable (vu) |
| 0x020b / 0x020c | Envoyé autour de l'écran de recul Topway, **pas une fin de recul** : vu le 06/11 (v0.11) avec arg1 = 1 puis 0 alors que 0x9f1c restait à 1. Ne pas s'en servir pour « marche arrière relâchée » | sûr (vu) |
| **0x0304** | Vidéo de recul : écrit par l'écran Topway, 1 à l'affichage, 0 à la fermeture | **sûr (code + voiture)** |
| 0x0401–0x0406 | Radio (voir `topway-radio`) | probable |
| 0x0501, 0x0506, 0x0507, 0x050e | Données CAN / lignes de guidage caméra | probable |
| 0x010a | Version MCU (texte séparé par « - ») | probable |
| 0x0112 | Configuration (`/data/tw/config`) | probable |
| 0x0102–0x0112, 0x0603, 0x0605 | Touches volant / façade (abonnements de `com.tw.keypad`) | probable |
| **0x9e00** | requestService : l'appli BT envoie 8 à l'ouverture, 136 (8\|0x80) à la fermeture | **sûr (vu)** |
| **0x9e08** | Commande musique BT traitée par l'appli Bluetooth Topway (voir `topway-bluetooth`) | sûr (code) |
| **0x9e11** | Demande de source audio : `write(0x9e11, 0xc0, n)`, n = 1 radio, 3 média, 8 BT ; `n\|0x80` relâche | **sûr (vu : 3 → 0x0301 = 3)** |
| **0x9f00** | Afficheur MCU : l'appli BT y envoie le titre (arg1 = 8, obj = titre, « Not Provided » si vide) | **sûr (vu)** |
| 0x9f10 | Extinction, arg2 = 3000 juste avant redémarrage | **sûr (vu)** |
| 0x9f11/12/13 | Luminosité / contraste / saturation caméra (écrits si caméra AHD, id 3) | probable |
| 0x9f1a | Commande « tinymix » (mixage audio, envoyée par com.tw.service) | probable |
| **0x9f1c** | Marche arrière : arg1 = 1 enclenchée, 0 relâchée ; **répété toutes les 500 ms** pendant la marche arrière | **sûr (code + voiture)** |

Abonnements relevés dans les applis Topway (utile pour deviner un id) :
- `com.tw.service` : `0104 0106 0107 010a 0112 0201 0202 0203 0204 0205 020b 020c 0301 0302 0304 0404 0501 0505–0509 050e 9e00 9f00 9e11 9f10 9f1b 9f1c`
- `TWRadio` : `0201 0203 0301 0401 0402 0404 0405 0406 9e00 9e01` · `TWMusic` : `0201 0202 0203 0301 0302 0304 0510 9e03 9e1f`
- `com.tw.keypad` : `0102 0103 0104 0106 0108 0109 010a 010b 0110 0112 0603 0605 0204`

## Fichiers système utiles
`/sys/class/tw/av/signal` (lu par la caméra Topway ; renvoie -1 depuis une appli), `/sys/class/tw/av/chip`, `/sys/class/tw/gtp/touch`, `/data/tw/*` (illisible sans root).

## Vérifier une hypothèse
Outils → **Moniteur TWUtil** (écoute le canal de Sandero, boutons « Simuler phares / marche arrière »), puis Outils → **Exporter le journal**. Chercher les lignes `[MCU]`.

## Journal des vérifications
- 2026-11-04 (v0.6, voiture) : 0x9f1c 1/0 et répétition 500 ms, 0x0301 = 3 après demande, 0x9f00 titres, 0x9e00 8/136, 0x9f10 arg2 3000, 0x0203 constant. 0x0204 jamais reçu (feux éteints pendant l'essai ?).
- 2026-11-05 (v0.10, voiture) : 0x0203 = volume (arg1 12→15, 17→29 au bouton). 0x0301 = 1 après demande radio, 8 après BT ; bouton ⏻ radio → 0x0301 = 0. 0x9e00 arg1 = 8 / 136 à l'ouverture / fermeture de com.tw.bt, 131 au démarrage. 0x8201 arg1=1 arg2=16 au retour sur l'accueil (sens inconnu). 0x0302 arg1=0 et 0x9f10 arg2=3000 à l'extinction. 0x0204 toujours jamais reçu (essais de jour).
- 2026-11-05 : recherche ciblée dans com.tw.service (commande d'écriture du volume) acceptée par l'utilisateur mais **bloquée par la règle deny de `.claude/settings.json`** ; non faite. À refaire si l'écho `write(0x0203, n)` ne marche pas.
- 2026-11-06 (v0.11, voiture) : `write(0x0203, 29/30)` sans écho, volume MCU resté à 15 → commande sans effet. 0x020b arg1=1/0 reçu pendant la marche arrière (0x9f1c=1) après passage sur l'écran Topway. 0x8201 arg1=1 arg2=16 = touche accueil de la façade (probable : suivi à chaque fois de « accueil affiché »). 0x9e00 arg1 = 7 / 135 à l'ouverture / fermeture de com.tw.auxin.
- 2026-11-06 : recherche de la commande d'écriture du volume dans com.tw.service autorisée et règle deny levée, mais **aucun APK Topway sur le PC** (`APP From autoradio/` ne contient que README.md) ; règle remise. À refaire après Outils → « Exporter toutes les applis (APK) » (besoin : com.tw.service, et si possible com.tw.radio pour comparaison).
