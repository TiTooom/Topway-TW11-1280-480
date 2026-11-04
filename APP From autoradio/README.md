# APP From autoradio (non versionné)

Dossier local pour les **APK récupérés sur votre autoradio**. Son contenu est exclu de Git (`.gitignore`) : ces applis (Topway, Google, Spotify…) sont protégées par le droit d'auteur, et plusieurs dépassent la limite de 100 Mo de GitHub.

Pour le remplir : dans Sandero, Outils → **Choisir la clé USB** → **Exporter toutes les applis (APK)**, puis copier ici le dossier `SanderoExport_…`.

Organisation conseillée :
- `export_applis_<date>/applis_systeme/` et `applis_utilisateur/` : export de Sandero (avec `liste_applis.csv`)
- `apk_carte_sd/` : APK trouvés sur la carte SD / clé d'origine
- `apk_divers/` : autres APK

Claude Code n'a pas le droit de lire ce dossier (voir `CLAUDE.md`) : les informations utiles sont déjà dans les skills `.claude/skills/`.
