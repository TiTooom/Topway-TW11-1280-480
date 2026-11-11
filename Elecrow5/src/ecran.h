#pragma once
#include <stdbool.h>
#include <stdint.h>

// Démarre l'écran RGB, LVGL et la tâche d'affichage. Renvoie false en cas d'échec.
bool ecran_demarrer(void);

// Rétroéclairage, de 0 à 100 %.
void ecran_luminosite(uint8_t pourcent);

// À encadrer autour de tout appel LVGL fait hors de la tâche d'affichage.
void ecran_verrouiller(void);
void ecran_deverrouiller(void);
