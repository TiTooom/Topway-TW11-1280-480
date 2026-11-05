# Image de la Sandero pour l'accueil

## Photo détourée (utilisée en priorité)

`PhotoCut.java` transforme une photo sur fond blanc en `res/drawable-nodpi/car_photo.png` (fond transparent, ombre transparente, reflet au sol, antenne retirée) :

```bash
"$JAVA_HOME/bin/java" -Xmx2g PhotoCut.java photo.png ../../SanderoLauncher/app/src/main/res/drawable-nodpi/car_photo.png 900
```

Les repères (bas de caisse, sol, phares) sont en pixels de la photo de référence (1360 × 840) en tête du fichier ; pour une autre photo, les relever avec une grille. Les fractions écrites dans `car_photo.png.txt` vont dans `CarView.photoSpec()` (référence : `car_photo_points.txt`). `car_photo.png` n'est **pas** sur GitHub (photo constructeur, voir `.gitignore`) : sans elle, l'appli prend le rendu 3D.

## Rendu 3D (secours)

Calcule `SanderoLauncher/app/src/main/res/drawable-nodpi/car_34.png` (vue 3/4 avant, reflet au sol compris) à partir du modèle `3D/Sandero.stl`. Java 21, sans bibliothèque.

```bash
export JAVA_HOME="$(cd ~/.jdks/jbr-21.0.11 && pwd -W)"
cd tools/car3d && "$JAVA_HOME/bin/javac" -d /tmp/car3d *.java
"$JAVA_HOME/bin/java" -Xmx3g -cp /tmp/car3d Car3D ../../3D/Sandero.stl \
    ../../SanderoLauncher/app/src/main/res/drawable-nodpi/car_34.png final W=900 H=560 SS=3 yaw=48 pitch=10 reflet=0.22
```

- Il écrit aussi `car_34.png.txt` : position des phares, du feu arrière, du sol et des faisceaux **en fraction de l'image**. Recopier ces valeurs dans les constantes en tête de `CarView.java` (le fichier de référence actuel est `car_34_points.txt`).
- `debug` au lieu de `final` : gris + lignes de coordonnées peintes sur la carrosserie (`lines=xyz`, épaisses tous les 5) pour relever des zones. Vues quasi plates : `yaw=90 pitch=0 fov=1` (côté), `yaw=0` (face), `pitch=89` (dessus).
- `Zones.java` : vitres, phares, calandre, plaque, antibrouillards, baguettes, poignées, passages de roue (coordonnées du modèle). `Shade.java` : couleurs (argent, vitres fumées, noir) et éclairage « studio ».
- Modèle : x = largeur 0–33,5, y = longueur (0 arrière, 70 avant), z = hauteur. 4 morceaux : caisse, essieu avant, essieu arrière, roue de secours (cachée).
