# Sauvegarde fichiers de l'autoradio (sans root)

Copie faite par ADB (Wi-Fi) de ce que le shell `uid=2000` peut lire sur le Topway TW11.
**Exclu de Git** (`.gitignore`) : contient des identifiants personnels (getprop, licences, MAC).

Contenu d'un dossier `SanderoDump_<date>/` :
- `getprop.txt`, `packages.txt`, `mounts.txt`, `partitions.txt`, `cpuinfo/meminfo`, `build.prop` reconstruit depuis getprop.
- `system/` : APK (`app`, `priv-app`), `framework/`, et les fichiers lisibles de `/system`.
- `vendor/` : fichiers lisibles de `/vendor`.

**Ce que ça ne contient pas** (refusé sans root) : images reflashables des partitions
(`boot`, `system`, `vendor`, `bootloader`, `recovery`…), `/data` (UDISK), `/system/build.prop` en fichier.
Pour une image reflashable : root/FEL, ou le firmware d'origine TW11-1280x480-X du vendeur.
