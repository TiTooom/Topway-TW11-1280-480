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
| **0x0204** | Éclairage : arg1 == 1 → feux allumés (nuit). `com.tw.service` relaie `REQ_NIGHT_MODE_CMD`/`REQ_DAY_MODE_CMD` à Z-Link | sûr (code) — **jamais vu dans un journal** |
| **0x0301** | Source audio active : 0 au démarrage, 1 radio, 3 média Android, 8 Bluetooth | **sûr (vu)** |
| 0x0302 | arg1 = 0 au démarrage / extinction | inconnu |
| 0x0201 | Touches (arg1 : 1 pause, 3 suivant, 4 précédent, 5 lecture, 6 pause, 9 avance, 10 retour) | probable |
| 0x0203 | Sourdine ? Reçu très souvent, arg1 = 0, arg2 = -2147483618 constant | vu, sens inconnu |
| 0x0205 | Frein à main (arg1 = 1 au démarrage) | probable (vu) |
| 0x020b / 0x020c | Fin d'écran de recul (variantes Nissan / LR) | probable |
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
