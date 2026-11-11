---
name: elecrow-crowpanel5
description: Matériel de l'Elecrow CrowPanel 5.0" ESP32-S3 HMI (SKU DIS07050H) — module N4R8, dalle RGB 800x480 (broches, porches, horloge pixel), tactile GT911 et PCA9557, rétroéclairage, budget mémoire, réglages de fluidité (bounce buffer, PSRAM, cache). À charger pour tout ce qui touche à l'écran, au tactile, aux broches ou aux performances de la carte.
---

# CrowPanel 5.0" ESP32-S3 (DIS07050H)

Sources : wiki Elecrow (tutoriel Arduino 5.0", LovyanGFX) et fiche ESPHome, concordantes
(relevé du 11/11/2026). Statut indiqué ligne par ligne.

## Processeur et mémoire
- ESP32-S3-WROOM-1-**N4R8** : 4 Mo de flash, 8 Mo de PSRAM octale (probable : fiche ESPHome ;
  le journal montre ~7,8 Mo de PSRAM libre, cohérent → sûr pour la PSRAM).
- Puce USB-série **CH340** (sûr, 11/11) ; ESP32-S3 révision v0.2 (sûr, esptool).

## Dalle RGB 800×480, 16 bits (sûr : affiche et répond, 11/11)
| Signal | Broche |
|---|---|
| PCLK | 0 |
| HSYNC | 39 |
| VSYNC | 41 |
| DE | 40 |
| B0..B4 | 8, 3, 46, 9, 1 |
| G0..G5 | 5, 6, 7, 15, 16, 4 |
| R0..R4 | 45, 48, 47, 21, 14 |

- Porches : HSYNC impulsion 4, arrière 43, avant 8 ; VSYNC impulsion 4, arrière 12, avant 8 ;
  `pclk_active_neg = 1`.
- Horloge pixel : 12 MHz chez Elecrow et ESPHome (~28 images/s) ; **14 MHz** dans
  Elecrow5 (~32 images/s, démarre ; stabilité à confirmer). 16 MHz ≈ 37 images/s, à
  essayer. Calcul : f / ((800+4+43+8) × (480+4+12+8)).
- ESPHome indique `invert_colors: True` et des puces « ILI6122 & ILI5960 » (non vérifié).

## Tactile (sûr, 11/11)
- GT911 sur I2C SDA 19 / SCL 20, 400 kHz, trouvé en **0x5D**, identifiant produit « 911 ».
- Un **PCA9557** (0x18) est présent : IO0 = RESET, IO1 = INT du GT911. Séquence : IO0/IO1
  sorties à 0, 20 ms, IO0 à 1, 100 ms, IO1 en entrée → adresse 0x5D.
- Lecture : état en 0x814E (bit 7 = mesure prête, bits 0-3 = nombre de points), point 1
  en 0x814F (x = octets 1-2, y = 3-4), puis écrire 0 en 0x814E.

## Rétroéclairage
- GPIO2 en PWM LEDC 5 kHz (probable : ESPHome ; l'écran s'allume → sûr pour l'allumage,
  variation à confirmer). Une démo Elecrow touche aussi GPIO38 (rôle inconnu).

## Fluidité : ce qui compte
- **Bounce buffer** (ESP-IDF ≥ 5.0, absent d'Arduino 2.x) : 10 lignes en RAM interne.
- Dessin LVGL en **RAM interne**, 2 bandes de 30 lignes (48 Ko chacune), mode partiel.
- PSRAM octale 80 MHz, processeur 240 MHz, cache données 64 Ko / lignes 64 o,
  `CONFIG_LCD_RGB_ISR_IRAM_SAFE`, `CONFIG_LCD_RGB_RESTART_IN_VSYNC`, code en PSRAM (XIP)
  pour que l'image tienne pendant une écriture flash.
- **Rafraîchissement LVGL calé sur la dalle** : au-delà de f / (855 × 504), les images
  dessinées ne sont jamais vues (sûr : 15 ms = 63 passages/s pour 32,5 affichées, 11/11).
- Budget RAM interne (sûr, 11/11) : ~124 Ko libres après tout si tampons en PSRAM ;
  ~26 Ko avec les tampons en RAM interne.

## Journal des vérifications
- 2026-11-11 : brochage, 14 MHz, GT911 0x5D + PCA9557 vérifiés au journal série et au doigt.
- 2026-11-11 : test d'animation à 15 ms = 33 % CPU ; période LVGL passée à la cadence de la dalle (31 ms à 14 MHz).
