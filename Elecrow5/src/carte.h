// Brochage de l'Elecrow CrowPanel 5.0" ESP32-S3 HMI (SKU DIS07050H), module N4R8.
// Sources (11/11/2026) : tutoriel Arduino du wiki Elecrow (LovyanGFX) et fiche ESPHome ;
// les deux concordent sur les broches et l'horloge.
// SÛR (essai du 11/11/2026 sur la carte du propriétaire) : dalle à 14 MHz, GT911 en 0x5D
// derrière un PCA9557, tactile qui répond. Stabilité de l'image à 14 MHz : à confirmer.
// La version de la carte (v1.x, v2.x…) est sérigraphiée au dos.
#pragma once

// --- Écran RGB 800x480, 16 bits (RGB565) -------------------------------------------
#define ECRAN_LARGEUR       800
#define ECRAN_HAUTEUR       480

// Horloge pixel : Elecrow et ESPHome utilisent 12 MHz (~28 images/s à l'écran).
// Avec le « bounce buffer », 14 MHz (~32 images/s) devrait tenir. Si l'image scintille
// ou glisse, revenir à 12 ; si elle est stable, essayer 16 (~37 images/s).
#define ECRAN_PCLK_HZ       (14 * 1000 * 1000)

#define ECRAN_HSYNC_PULSE   4
#define ECRAN_HSYNC_BACK    43
#define ECRAN_HSYNC_FRONT   8
#define ECRAN_VSYNC_PULSE   4
#define ECRAN_VSYNC_BACK    12
#define ECRAN_VSYNC_FRONT   8

#define BROCHE_PCLK         0
#define BROCHE_HSYNC        39
#define BROCHE_VSYNC        41
#define BROCHE_DE           40

// Ordre attendu par esp_lcd : B0..B4, G0..G5, R0..R4.
#define BROCHES_DONNEES { \
    8, 3, 46, 9, 1,          /* B0..B4 */ \
    5, 6, 7, 15, 16, 4,      /* G0..G5 */ \
    45, 48, 47, 21, 14 }     /* R0..R4 */

// Rétroéclairage (variation par PWM). ESPHome : GPIO2 ; une démo Elecrow touche aussi
// GPIO38 : à essayer si l'écran reste noir.
#define BROCHE_RETRO        2

// --- Tactile GT911 (bus I2C) -------------------------------------------------------
#define BROCHE_I2C_SDA      19
#define BROCHE_I2C_SCL      20
#define I2C_FREQ_HZ         400000

// Certaines CrowPanel ont un PCA9557 qui pilote RESET (IO0) et INT (IO1) du GT911.
// Le code le cherche et s'en passe s'il est absent.
#define ADR_PCA9557         0x18
#define ADR_GT911_A         0x5D
#define ADR_GT911_B         0x14
