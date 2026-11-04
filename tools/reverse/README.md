# Scripts d'analyse des applis Topway

À utiliser sur la sortie de `dexdump -d` d'une appli (`<app>.txt`) et son `classes.dex` (`<app>/classes.dex`), dans un dossier de travail **hors du dépôt**. Lancer avec `python -I` et `PYTHONIOENCODING=utf-8`.

| Script | Usage |
|---|---|
| `meth.py <app> 'Lpaquet/Classe;' [regex]` | Désassemblage lisible d'une classe ou de méthodes choisies |
| `sw.py <app> <ligne> [profondeur]` | Pour la méthode contenant la ligne : chaque `case` des `switch` et les méthodes appelées |
| `blk.py <app> <ligne> 0x37,0x47 <n>` | Instructions complètes de quelques `case` |
| `cases.py <app> 0x0204,0x0301` | Tous les `switch` de l'appli qui traitent ces identifiants |

Méthode détaillée, recettes et pièges : skill `.claude/skills/topway-reverse-engineering/SKILL.md`.
