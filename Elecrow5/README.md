# Elecrow5 : interface d'autoradio libre sur CrowPanel 5.0" ESP32-S3

Piste « autoradio libre » à petit budget, pour remplacer un jour le Topway (root impossible
sans le firmware PODOFO). Matériel : **Elecrow CrowPanel 5.0" ESP32-S3 HMI** (SKU
DIS07050H), 800×480 RGB, tactile capacitif GT911, module ESP32-S3-WROOM-1-N4R8.

**Étape 1 (ce dossier)** : écran, tactile et interface de démonstration réglés pour la
fluidité. **Essayé le 11/11/2026 sur la carte** : démarrage en < 1 s, dalle à 14 MHz,
GT911 trouvé (0x5D, derrière un PCA9557), tactile OK. À confirmer : stabilité de l'image,
images/s du test d'animation.

## Compiler et téléverser

**Double-cliquer sur `Flasher.bat`** (carte branchée en USB, port CH340 détecté tout
seul) : compile, téléverse, puis affiche le journal.

ESP-IDF refuse les chemins qui contiennent un espace (« SOFT SANDERO ») : erreur
`Detected a whitespace character in project paths`. Déplacer `build_dir` ne suffit pas
(le compilateur casse aussi sur l'espace). Les scripts montent donc le dossier parent sur
un lecteur virtuel (`subst`) le temps de la commande :

```
Flasher.bat             compile, téléverse et affiche le journal (double-clic)
Compiler.bat            compile
Compiler.bat upload     compile et téléverse (carte en USB)
Compiler.bat monitor    journal série (Ctrl+C pour quitter)
Compiler.bat clean      efface la compilation
```

Les boutons « Build » / « Upload » de l'extension PlatformIO de VS Code échouent tant que
le projet est sous un chemin avec espace : utiliser les scripts. Première compilation :
PlatformIO télécharge ESP-IDF 5.5 (~1 Go) et LVGL 9.2.

**RAM interne** : après l'écran (bounce buffers) et LVGL (2 bandes de 30 lignes), il
reste ~26 Ko. Le Wi-Fi (ELM327) en demandera plus : passer alors `LIGNES_PAR_BANDE` à 20
dans `ecran.c` (le code s'y replie aussi tout seul si la place manque).

## Pourquoi c'est plus fluide que les démos Elecrow

| Réglage | Démos Elecrow (Arduino 2.x) | Ici |
|---|---|---|
| Framework | Arduino 2.x (ESP-IDF 4.4) | ESP-IDF 5.5 |
| Écran | image lue directement en PSRAM | « bounce buffer » de 10 lignes en RAM interne |
| Dessin LVGL | souvent tampon plein écran en PSRAM | 2 bandes de 30 lignes en RAM interne, zones modifiées seulement |
| PSRAM / processeur | souvent 40 MHz / 160 MHz | octale 80 MHz / 240 MHz, cache 64 o |
| Décrochage de l'image | possible | resynchronisation à chaque VSYNC |

Réglages dans `sdkconfig.defaults` (supprimer `sdkconfig.elecrow5` pour qu'ils soient
repris après une modification) et `src/carte.h` (horloge pixel).

## Fichiers

| Fichier | Rôle |
|---|---|
| `src/carte.h` | brochage, réglages de la dalle (horloge pixel 14 MHz, porches) |
| `src/ecran.c` | dalle RGB, LVGL, tâche d'affichage (cœur 1), rétroéclairage PWM |
| `src/tactile.c` | GT911 (I2C 19/20), réarmement par PCA9557 s'il existe |
| `src/interface.c` | démo : accueil 4 tuiles, pages, Outils (luminosité, test d'animation) |
| `src/main.c` | démarrage |

## Premier essai sur la carte : quoi regarder

1. `Compiler.bat upload`, puis `Compiler.bat monitor` : lignes `[ECRAN]`, `[TACTILE]`, `[UI]`.
2. Image stable ? Sinon, horloge pixel à 12 MHz dans `carte.h`. Si elle est stable, essayer 16.
3. Écran noir mais journal OK : rétroéclairage, essayer `BROCHE_RETRO` = 38.
4. Couleurs fausses (rouge/bleu inversés) : l'ordre des broches de `BROCHES_DONNEES`.
5. Outils → « Test animation » : compteur d'images/s en bas à droite.
6. Tactile décalé ou inversé : relever les coordonnées dans le journal.

## Règles pour l'interface

- Aplats de couleur ; pas d'ombre, de transparence ni de dégradé ; pas d'animation
  plein écran ; changement de page sans glissement.
- Les polices intégrées de LVGL n'ont pas les accents : textes sans accent pour l'instant.
  À faire : police Montserrat accentuée générée avec `lv_font_conv` (plage 0x20-0xFF).

## Suite prévue

ESP32 classique (WROOM) en « MCU » relié par liaison série : musique Bluetooth (A2DP) →
DAC PCM5102 → ampli TDA7388, tuner Si4703, contact / phares / marche arrière par
optocoupleurs. OBD2 par ELM327 Wi-Fi.
