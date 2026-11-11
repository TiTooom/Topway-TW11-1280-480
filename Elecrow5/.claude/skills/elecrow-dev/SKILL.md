---
name: elecrow-dev
description: Développement du firmware Elecrow5 (autoradio libre sur CrowPanel 5.0" ESP32-S3, ESP-IDF 5.5 + LVGL 9.2, PlatformIO) — carte du code, compiler/flasher (Flasher.bat, piège de l'espace dans le chemin), lecture du journal série, pièges, ÉTAT DU PROJET et HISTORIQUE DES BUGS. À charger avant toute modification du code d'Elecrow5, et à mettre à jour après chaque modification ou essai sur la carte.
---

# Développer Elecrow5

## Carte du code (`Elecrow5/`)

| Fichier | Rôle |
|---|---|
| `platformio.ini` | env `elecrow5`, espressif32@6.12.0, framework **espidf** (IDF 5.5), board esp32-s3-devkitc-1, flash 4 Mo QIO 80 MHz |
| `sdkconfig.defaults` | réglages de fluidité (240 MHz, PSRAM octale 80 MHz, cache 64 o, ISR RGB en IRAM…) et LVGL (Kconfig) |
| `partitions.csv` | nvs + appli unique de ~4 Mo |
| `src/idf_component.yml` | LVGL `~9.2.2` (gestionnaire de composants IDF → `managed_components/`) |
| `src/CMakeLists.txt` | **liste explicite** des `.c` (ajouter chaque nouveau fichier ici) |
| `src/carte.h` | brochage et réglages de la dalle (voir `elecrow-crowpanel5`) |
| `src/ecran.c/.h` | dalle RGB (esp_lcd, bounce buffer), LVGL (2 bandes en RAM interne, rafraîchissement calé sur la dalle), tâche `lvgl` cœur 1, verrou récursif, rétroéclairage LEDC |
| `src/tactile.c/.h` | GT911 par le nouveau pilote `i2c_master`, réarmement par PCA9557 |
| `src/interface.c/.h` | **accueil copié de Sandero** (thème nuit, 800×480 : heure, voiture + halo, dock Réglages/Applis, carte audio Radio/Bluetooth, 5 boutons, curseur de volume) ; pages de la démo (Applis = ancienne grille, Radio, Musique, OBD2, Outils) |
| `src/ressources/` | **généré** par `outils/generer.mjs` : polices Roboto avec accents (Thin 96, Regular 12-23, Medium 16/36), icônes A8 (d'après `Icon.java`), voiture RGB565A8 + halo A8 (d'après `CarView.java`) |
| `outils/generer.mjs` | `cd outils && npm install && node generer.mjs` (Node, `@napi-rs/canvas`, `lv_font_conv`) ; TTF Roboto dans `outils/polices/` ; lit la photo dans `../SanderoLauncher/.../car_photo.png` |
| `src/main.c` | démarrage : écran → interface → rétroéclairage allumé après la 1re image |
| `Compiler.bat`, `Flasher.bat` | compilation / téléversement par lecteur virtuel |

## Compiler, flasher, lire le journal

- `Flasher.bat` (double-clic) = `Compiler.bat upload` puis `Compiler.bat monitor`.
- Port auto-détecté : la carte apparaît en **CH340** (`USB VID:PID=1A86:7523`, COM7 sur le
  PC du propriétaire). `pio device list` pour vérifier.
- Depuis Claude (pas de terminal interactif) : téléverser par
  `& "...\Elecrow5\Compiler.bat" upload` (PowerShell), puis lire le journal en ouvrant
  le port avec `System.IO.Ports.SerialPort` à 115200 bauds : **ouvrir le port suffit à
  réinitialiser la carte** (ne pas basculer RTS : le journal s'arrête après le démarrage).
  Juste après un téléversement, la 1re ouverture ne montre que le démarrage : rouvrir.
- 1re compilation ~3 min (IDF + LVGL), ensuite ~10-30 s.

## Pièges

- **Espace dans le chemin** (« SOFT SANDERO ») : ESP-IDF s'arrête sur
  `Detected a whitespace character in project paths`. Déplacer `build_dir` ne suffit pas
  (gcc casse ensuite sur `-fmacro-prefix-map`). Seule solution : `subst` d'un lecteur sur
  le **dossier parent** (projet en `X:\Elecrow5`). Projet à la racine du lecteur
  (`X:\`) : PlatformIO perd les sources (`...\rc\main.c` introuvable).
- Le `GLOB` dans `src/CMakeLists.txt` est aussi fragile : liste explicite.
- Modifier `sdkconfig.defaults` ne suffit pas : supprimer `sdkconfig.elecrow5` (régénéré).
- **Tampons LVGL à réserver avant la dalle** : les bounce buffers morcellent la RAM
  interne, sinon les tampons partent en PSRAM (lent).
- Le « FPS » du moniteur LVGL (sysmon) = passages du minuteur de rafraîchissement, plafonné
  par sa période ; il ne dit rien des images réellement affichées (cadence de la dalle).
- Polices LVGL intégrées (Montserrat) **sans accents** : accents seulement avec les polices
  Roboto générées (`roboto_*`, Latin-1 + ’ … Œ €). `lv_font_conv` n'a pas d'option de nom :
  le nom de la police vient du nom du fichier de sortie.
- Une console (Bash) laissée dans `managed_components/` verrouille le dossier : la
  compilation échoue sur `PermissionError [WinError 32]` (rmtree). En sortir.
- Sous un élément qui bouge : ni dégradé complexe (diagonale, calcul par point), ni image en
  PSRAM (recopier une image RGB565 depuis la PSRAM coûte plus cher que le dégradé : 51 %
  contre 40 %, mesure du 11/11). Couleur unie ou dégradé vertical/horizontal simple.
- Texte défilant (`LV_LABEL_LONG_SCROLL_CIRCULAR`) : ~14 % de processeur pour le titre
  (36 px) et l'artiste (23 px) défilant ensemble.
- Journal : `ECRAN: stats 5 s` = bandes envoyées, points redessinés/s, % du temps dans
  LVGL et dans la copie vers la dalle. Outil de diagnostic de la charge.
- Image A8 : LVGL la dessine avec `image_recolor` (couleur à volonté, ex. halo à l'accent).
- PowerShell `Remove-Item` sur une jonction demande confirmation (risque de vider la
  cible) : supprimer un lien avec `cmd /c rmdir`.

## État du projet (11/11/2026)

**Marche (sûr, carte du propriétaire)** : démarrage en < 1 s jusqu'à « pret » ; dalle RGB
800×480 à 14 MHz ; GT911 en 0x5D derrière le PCA9557 ; navigation tactile entre les pages ;
tampons LVGL 2 × 30 lignes en RAM interne ; reste ~26 Ko de RAM interne.

**Mesuré (sûr, 11/11, rafraîchissement LVGL à 15 ms)** : compteur 66 « FPS » au repos
(1 % CPU), 60 au changement de page, 63 avec le test d'animation (arc + barre) à ~33 % CPU.
Ce compteur (sysmon) compte les **passages du minuteur de rafraîchissement**, pas les images
vues : la dalle n'en affiche que ~32,5/s à 14 MHz.

**À vérifier** : stabilité de l'image à 14 MHz (sinon 12) ; CPU du test d'animation avec
le rafraîchissement calé sur la dalle (31 ms, attendu ~32 « FPS » et ~15-17 % CPU) ;
fluidité ressentie face à la démo Elecrow ; curseur de luminosité (LEDC sur GPIO2).

**À vérifier (accueil Sandero, V du 11/11)** : rendu à l'œil face à Sandero (positions,
dégradés, voiture), défilement des titres, curseur de volume, CPU au repos et au changement
de source (fondu de 700 ms de la couleur).

**À faire** : thème jour (Sandero l'a, pas encore porté) ; heure réelle (aujourd'hui : heure
de compilation + temps écoulé, fausse après un redémarrage) ; liaison série avec l'ESP32 « MCU » (voir
`elecrow-autoradio`) ; pages Radio / Musique / OBD2 réelles ; Wi-Fi pour l'ELM327
(prévoir la RAM interne : bandes à 20 lignes).

## Historique des bugs

| Date | Problème | Cause | Solution |
|---|---|---|---|
| 11/11/2026 | Flash impossible depuis VS Code : `whitespace character in project paths` | ESP-IDF refuse l'espace de « SOFT SANDERO » | `Flasher.bat` / `Compiler.bat` avec `subst` |
| 11/11/2026 | `W ECRAN: RAM interne insuffisante : tampons LVGL en PSRAM` | tampons réservés après la dalle (RAM morcelée) | réservation avant la dalle, repli 30 → 20 → 12 lignes |
| 11/11/2026 | Accueil Sandero : 32 FPS mais ~40 % CPU en Bluetooth, 25 % en Radio, 1 % sur Applis (mesure du propriétaire) | titres défilants redessinés à chaque image sur le dégradé en diagonale de la carte (calcul par point) | essais au journal (stats 5 s) : diagonale 40 %, image précalculée en PSRAM **51 %** (pire : lecture PSRAM lente, alignement 64 sans effet), dégradé vertical 18 %, couleur unie 16 %, sans défilement 2 % → **carte en dégradé vertical** (seul écart visuel avec Sandero) |
| 11/11/2026 | Compilation : `PermissionError [WinError 32]` | console Bash ouverte dans `managed_components/lvgl__lvgl` | sortir de ce dossier avant de compiler |
| 11/11/2026 | Test d'animation à 33 % CPU pour 63 « FPS » | LVGL dessinait toutes les 15 ms, la dalle n'affiche que ~32,5 images/s | période du minuteur de rafraîchissement = durée d'une image de la dalle (31 ms à 14 MHz), calculée depuis `carte.h` |

## Journal des vérifications
- 2026-11-11 : projet créé, compilé, flashé (COM7) ; journal : écran + tactile OK, tampons en RAM interne après correction.
- 2026-11-11 : mesures du propriétaire (66/60/63 FPS, 33 % CPU en animation) ; rafraîchissement LVGL calé sur la dalle (31 ms), compilé, à flasher et remesurer.
- 2026-11-11 : accueil copié de Sandero (sans Outils de Sandero), dégradés complexes LVGL activés (3 couleurs), compilé (flash 25 %), flashé COM7 ; journal : démarrage OK, RAM interne libre 23 Ko, « interface prete ». Rendu à confirmer à l'œil.
- 2026-11-11 : mesures accueil 32 FPS / ~40 % CPU (BT), 25 % (Radio), 1 % (Applis) ; fond de carte précalculé en PSRAM, flashé ; journal : dessin du fond ~110 ms au démarrage. CPU après correction et fluidité du fondu Radio/Bluetooth à mesurer.
- 2026-11-11 : essais de charge par le journal (stats 5 s), voir historique ; carte en dégradé vertical : accueil Bluetooth à 18 % (LVGL), flashé.
