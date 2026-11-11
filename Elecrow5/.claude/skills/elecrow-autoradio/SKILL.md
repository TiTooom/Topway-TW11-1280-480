---
name: elecrow-autoradio
description: Architecture visée de l'autoradio libre Elecrow5 et choix des composants à petit budget (~60-70 €) — écran CrowPanel ESP32-S3 pour l'interface, ESP32 classique en « MCU » (musique Bluetooth A2DP, tuner, entrées voiture), DAC, ampli, tuner FM, alimentation 12 V et extinction, caméra, OBD2, boîtier imprimé. À charger pour tout choix matériel, câblage ou répartition des rôles entre les cartes.
---

# Autoradio libre : architecture et composants

Tout ce qui suit est un **plan** (décidé le 11/11/2026 avec le propriétaire), rien n'est
encore monté : **probable** sauf mention contraire.

## Pourquoi deux ESP32
L'ESP32-S3 n'a que le Bluetooth basse consommation (BLE) : il **ne peut pas recevoir de
musique** (A2DP). Un ESP32 classique (WROOM-32) le fait (bibliothèque ESP32-A2DP).

```
CrowPanel 5" ESP32-S3 : interface LVGL, Wi-Fi (ELM327)
        │ liaison série (protocole maison, à définir)
ESP32 WROOM-32 « MCU » : A2DP → I2S, tuner Si4703 (I2C), contact / phares / marche
        │                arrière (optocoupleurs), veille de l'ampli, extinction
DAC PCM5102 → ampli TDA7388 (4×41 W) → faisceau ISO
```

## Composants retenus (prix AliExpress approximatifs, 11/2026)
| Rôle | Pièce | ~Prix |
|---|---|---|
| Écran + interface | CrowPanel 5.0" ESP32-S3 (**déjà possédé**) | 0 € |
| MCU + Bluetooth | ESP32 WROOM-32 DevKit | 4-5 € |
| Son | DAC I2S PCM5102 | 4 € |
| Ampli | TDA7388 4×41 W, alimenté en 12 V direct | 10 € |
| Radio | Si4703 (FM + RDS) | 7 € |
| Alimentation | abaisseur 12 → 5 V 5 A large entrée, TVS, fusible, 2200 µF | 8 € |
| Entrées voiture | 3 × PC817 (ACC, phares, marche arrière), MOSFET d'extinction | 3 € |
| OBD2 | ELM327 **Wi-Fi** (le Bluetooth de l'ESP32 est pris par la musique) | 8 € |
| Divers | faisceau ISO, fils | 5 € |

## Points durs connus
- **Caméra de recul** : un ESP32 ne lit pas une caméra analogique. Options : petit
  moniteur séparé, ou caméra USB sur l'ESP32-S3 (UVC, non vérifié). Non tranché.
  Sur la CrowPanel, l'USB natif de l'ESP32-S3 (GPIO19/20) est pris par l'I2C du tactile
  (sûr : `carte.h`) : caméra USB sur l'écran très probablement impossible.
- **Broches libres de la CrowPanel** : quasi toutes prises (dalle RGB 20, I2C 2,
  rétroéclairage) ; reste l'UART du programmateur (43/44) et peu d'autres (à relever au
  dos de la carte). D'où : toutes les entrées/sorties voiture côté « MCU », une seule
  liaison série vers l'écran.
- **Heure** : l'écran n'a pas d'horloge sauvegardée ; prévoir un DS3231 (I2C) sur le
  « MCU », qui envoie l'heure à l'écran.
- Son en stéréo seulement (4 HP sur le même signal) avec un seul DAC.
- Mains-libres (HFP) possible sur l'ESP32 classique, qualité modeste : bonus.
- Extinction : l'ESP32 voit l'ACC tomber par optocoupleur, le MOSFET garde le courant
  le temps de s'éteindre proprement.
- Démarreur : chute de tension → condensateur après l'abaisseur.
- Parasites FM du convertisseur : blindage (adhésif cuivre à la masse), tuner éloigné.

## Boîtier (après le choix des composants)
- PETG minimum pour le caisson caché, **ASA** pour la façade et le cadre exposés au soleil
  (PLA exclu : se déforme vers 55-60 °C).
- Garder la cage DIN métallique ; ampli sur plaque alu à l'arrière ; tiroir amovible ;
  inserts M3 à chaud ; prises regroupées à l'arrière.
- Un 1 DIN fait ~182 × 53 mm : l'écran sera flottant (cadre devant) ou dans une façade
  qui couvre aussi le vide-poche. À mesurer sur la voiture.

## Pistes écartées (et pourquoi)
- Raspberry Pi : prix en forte hausse, même d'occasion.
- Luckfox Lyra (RK3506) : sortie DSI seulement (écrans chers), pas de GPU, A2DP à vérifier.
- Luckfox Pico (RV1106) : écrans RGB 4" seulement.

## Journal des vérifications
- 2026-11-11 : architecture et composants arrêtés en discussion ; aucun montage.
- 2026-11-11 : répartition écran / MCU précisée (broches libres, USB pris par le tactile, horloge DS3231 sur le MCU) ; rien de monté.
