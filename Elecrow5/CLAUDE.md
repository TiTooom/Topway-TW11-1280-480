# Elecrow5 : instructions pour Claude

**Projet séparé** du lanceur Android Sandero : un autoradio libre construit autour d'un
écran **Elecrow CrowPanel 5.0" ESP32-S3** (SKU DIS07050H), avec un firmware écrit de zéro
(ESP-IDF + LVGL). Il ne dépend ni de TWUtil ni de l'autoradio Topway.

Il partage seulement le dépôt GitHub (et donc ses règles : commits au nom de `TiTooom`,
sans `Co-Authored-By` ni mention de Claude ; journaux et identifiants jamais poussés).

Interface, commentaires, journal et documentation **en français**.

## 1. Séparation des sujets (règle du propriétaire, 11/11/2026)

- Tout ce qui concerne ce projet va **dans ce dossier** : code, scripts, doc
  (`README.md`), skills (`.claude/skills/elecrow-*`).
- **Ne rien reporter** dans les skills Topway / Sandero (`../.claude/skills/`), dans
  `../docs/` ni dans `../README.md`, et inversement. Seule exception : la ligne qui
  signale ce dossier dans l'arborescence de `../CLAUDE.md`.
- Le savoir Topway (TWUtil, MCU, Bluetooth `com.unisound.*`) ne s'applique pas ici.

## 2. Règles

1. Distinguer **sûr** (lu ou mesuré sur la carte, avec la date) et **probable** (wiki,
   fiche technique, jamais vu). Ne jamais présenter du probable comme sûr.
2. Toute fonction **journalise** par `ESP_LOGI/W/E` avec une étiquette courte en
   majuscules (`ECRAN`, `TACTILE`, `UI`, `BT`, `MCU`, `RADIO`, `OBD`, `ALIM`…), textes
   sans accent (moniteur série).
3. Rien ne doit pouvoir planter ni bloquer l'affichage : tester les retours `esp_err_t`,
   se replier proprement (ex. tactile absent → affichage seul).
4. Tout appel LVGL hors de la tâche d'affichage se fait entre `ecran_verrouiller()` et
   `ecran_deverrouiller()`.
5. Fluidité d'abord : aplats, pas d'ombre / transparence / dégradé / animation plein écran.

## 3. Skills (`.claude/skills/`)

| Skill | Quand le charger |
|---|---|
| `elecrow-dev` | Avant toute modification du code : carte du code, compiler/flasher, pièges, **état du projet**, **historique des bugs** |
| `elecrow-crowpanel5` | Matériel de la carte : brochage, dalle RGB, GT911/PCA9557, rétroéclairage, mémoire, réglages de fluidité |
| `elecrow-autoradio` | Architecture visée et composants (ESP32 « MCU », Bluetooth, ampli, tuner, alimentation, câblage voiture, budget) |

**Après chaque modification du code ou essai sur la carte** : mettre à jour le skill
concerné (valeurs observées datées, probable → sûr, pièges), l'état du projet et
l'historique dans `elecrow-dev`, et une ligne datée dans son « Journal des vérifications ».
Si le journal série contredit un skill, le journal gagne.

## 4. Compiler et flasher (résumé, détails dans `elecrow-dev`)

- **Double-clic sur `Flasher.bat`** (compile, téléverse, journal série), ou
  `Compiler.bat [upload|monitor|clean]`.
- Les boutons PlatformIO de VS Code **ne marchent pas** : ESP-IDF refuse l'espace de
  « SOFT SANDERO » ; les scripts passent par un lecteur virtuel `subst`.
- Depuis Claude : `& "<chemin>\Elecrow5\Compiler.bat" upload` en PowerShell.
