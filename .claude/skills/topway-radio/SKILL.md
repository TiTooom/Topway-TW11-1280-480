---
name: topway-radio
description: Protocole radio FM/AM de l'autoradio Topway via TWUtil (commandes 0x401/0x402/0x404, source 1, messages 0x0401 fréquence/bande/nom, 0x0402 présélections, 0x0405 RDS). Relevé dans le code de com.tw.radio (RadioModel), jamais vérifié sur la voiture. À charger pour la carte Radio de Sandero.
---

# Radio Topway (non vérifiée sur la voiture)

Source : `com.eckom.xtlibrary.twproject.radio.model.RadioModel` / `TWRadio` dans `com.tw.radio`.

## Commandes (canal principal)
- Source : `write(0x9e11, 0xc0, 1)` (prendre), `0x81` (relâcher). Topway envoie aussi `write(0x9e00, 1 / 0x81)` (requestService) — pas utilisé par Sandero.
- Allumage (initRadioData) : `write(0x0301, 0xff)`, `write(0x0406, 0)`, `write(0x0401, 0xff)`, `write(0x0404, 0xff)`, `write(0x0203, 0xff)` (demandes d'état) — Topway envoie aussi `write(0x010a, 0xff, 0)`.
- Recherche ↑ / ↓ : `write(0x401, 1, 0)` / `write(0x401, 1, 1)` · Pas ↑ / ↓ : `write(0x401, 2, 0/1)`.
- Mémorisation auto : `write(0x401, 0, 0)` · LOC/DX : `write(0x401, 4, x)`.
- Bande : `write(0x401, 5, bande)` — bandFM envoie 0, bandAM envoie 2 (**à vérifier**).
- Présélection : `write(0x402, index, bande)` · Fréquence directe : `write(0x402, 0xff, fréquence)`.
- RDS/AF/TA : `write(0x404, …)` (setAF : `write(0x404, 0, drapeaux)`).

## Messages reçus
- `0x0401` : arg1 = type ; 0 drapeaux (arg2 : bits 0x04, 0x08, 0x10, 0x80 = recherche en cours ?), 1 bande, **2 fréquence** (arg2 ; FM en 10 kHz : 10120 = 101.2 MHz ; AM en kHz) + `obj` = nom de station (PS), 3 PTY, 4 présélection active.
- `0x0402` : présélection — arg1 & 0xff = index, arg1 >> 8 = PTY, arg2 = fréquence, obj = nom.
- `0x0405` : texte RDS (obj).
- `0x0201` touches : 3 présélection suivante, 4 précédente, 9 recherche ↑, 10 recherche ↓ (traitées par l'appli Radio Topway).
- Constantes : FM_MIN … FM_MAX = 11337, AM_MIN = 364.

## Dans Sandero
`HeadUnit.radioOn/Off/Seek/Step/Preset/AutoStore/ToggleBand`, carte Radio (fréquence, nom, RDS, 6 présélections). Journal : `[CMD] radio write(0x0401, a, b)`.

## Journal des vérifications
- (aucune vérification sur la voiture à ce jour) — premier essai : allumer la radio dans Sandero, exporter le journal, vérifier les `[MCU] 0x0401` reçus et le son.
