#pragma once
#include <stdbool.h>
#include <stdint.h>

// Initialise le bus I2C, réarme le GT911 par le PCA9557 (s'il existe) et le détecte.
bool tactile_demarrer(void);

// Lit le premier point d'appui. Renvoie true si le doigt est posé.
bool tactile_lire(uint16_t *x, uint16_t *y);
