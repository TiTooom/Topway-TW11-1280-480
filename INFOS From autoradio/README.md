# INFOS From autoradio (non versionné)

Dossier local pour les **informations système de votre autoradio** : export « infos + framework » de Sandero (`getprop`, partitions, services, `/system/framework`, `/system/etc`), configuration Topway de la carte d'origine (`Tcfg/`), identifiants (`UID.txt`, `UUID.txt`, `Config.dat`) et manuel Topway.

Son contenu est exclu de Git : il contient des **données personnelles** (identifiants de l'appareil, adresse Bluetooth, licence OTA, secret réseau) et des fichiers protégés.

Pour le remplir : Sandero → Outils → **Exporter infos + framework** (ou **Sauvegarde complète**), puis copier le dossier `SanderoExport_…` ici.

Claude Code n'a pas le droit de lire ce dossier (voir `CLAUDE.md`) : ce qu'on en a appris est dans les skills `.claude/skills/` (notamment `topway-systeme` et `topway-twutil`).
