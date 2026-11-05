/**
 * Zones de la caisse (vitres, phares, calandre…) en coordonnées du modèle, relevées sur les
 * rendus « debug » quasi orthogonaux (côté : 22,4 px/unité ; face : 26,3 px/unité).
 * x : 0–33,5 (largeur, axe 16,75), y : 0 arrière – 70 avant, z : hauteur (0 = sol).
 * Les deux côtés sont symétriques : on travaille avec ax = distance au bord le plus proche.
 */
final class Zones {

    static final double CX = 16.75;

    static int body(double x, double y, double z, double nx, double ny, double nz) {
        double side = Math.min(x, 33.5 - x);        // 0 au bord de la caisse
        double anx = Math.abs(nx);

        // --- Rétroviseurs : couleur carrosserie (avant tout le reste)
        if (side < 3.7 && y > 43.5 && y < 48.8 && z > 15.2 && z < 18.8) return Car3D.BODY;

        // --- Passages de roue : intérieur sombre
        for (double wy : new double[]{56.55, 14.05}) {
            double r = Math.hypot(y - wy, z - 5.25);
            if (r < 6.3 && z < 10.8 && anx < 0.55 && side < 6.5) return Car3D.ARCH;
        }

        // --- Vitres latérales (custode, porte arrière, porte avant)
        // Le vitrage rentre vers l'intérieur en montant (3,9 du bord à z 17, 6 à z 23).
        if (anx > 0.45 && side < 4.6 + Math.max(0, z - 17) * 0.38) {
            double belt = 17.65 - (y - 17.6) * 0.061;            // bas des vitres, descend vers l'avant
            double top = y < 32.5 ? 22.55 + (y - 16.9) * 0.033    // haut, presque horizontal
                    : 23.05 - Math.max(0, y - 34.5) * 0.43;       // puis suit le montant de pare-brise
            if (y > 17.0 && y < 47.0 && z > belt + 0.15 && z < top - 0.15) {
                if (y > 19.7 && y < 20.3) return Car3D.TRIM;      // montant entre custode et vitre
                if (y > 30.0 && y < 32.4) return Car3D.TRIM;      // montant central noir
                return Car3D.GLASS;
            }
        }

        // --- Pare-brise (entre les montants)
        if (y > 39.6 && y < 52.6 && z > 16.4 && nz > 0.25 && ny > 0.15) {
            double inner = 5.9 + (z - 17) * 0.30;                 // les montants se rapprochent en haut
            if (side > inner) return Car3D.GLASS;
        }

        // --- Lunette arrière
        if (y < 9.5 && z > 16.8 && z < 23.2 && ny < -0.2 && side > 4.2) return Car3D.GLASS;

        // --- Feux arrière (verticaux, aux coins)
        if (y < 6.8 && z > 12.8 && z < 18.6 && side < 5.8 && (ny < -0.1 || anx > 0.5)) return Car3D.TAIL;

        // --- Face avant
        if (y > 61.5) {
            // Phares : en haut des coins, sous la ligne du capot
            if (side < 10.6 && y > 63.0 && z > 12.0 && z < 15.0) {
                double low = 12.25 + Math.max(0, side - 4.5) * 0.10;  // le bas remonte vers la calandre
                double high = 14.85 - Math.max(0, side - 5.0) * 0.18;
                if (z > low && z < high && (ny > -0.15)) return Car3D.HEAD;
            }
            if (ny > 0.35) {
                // Calandre et logo
                if (Math.abs(x - CX) < 7.2 && z > 10.3 && z < 12.9) {
                    if (Math.abs(x - CX) < 0.55 && z > 11.35 && z < 12.4) return Car3D.LOGO;
                    return Car3D.GRILLE;
                }
                // Plaque
                if (Math.abs(x - CX) < 3.1 && z > 6.45 && z < 9.15) return Car3D.PLATE;
                // Antibrouillards
                for (double fx : new double[]{7.1, 26.4}) {
                    if (Math.hypot(x - fx, z - 6.4) < 0.95) return Car3D.FOG;
                }
                // Prise d'air basse
                if (Math.abs(x - CX) < 6.9 && z > 4.9 && z < 6.4) return Car3D.GRILLE;
            }
            // Lèvre basse du bouclier
            if (z < 3.3) return Car3D.TRIM;
        }

        // --- Baguettes de protection noires sur les portes
        if (anx > 0.35 && side < 2.6 && z > 5.0 && z < 7.6 && y > 21.3 && y < 49.2) return Car3D.TRIM;

        // --- Poignées noires
        if (anx > 0.3 && side < 2.4) {
            if (y > 17.6 && y < 21.2 && z > 13.8 && z < 14.95) return Car3D.TRIM;
            if (y > 33.0 && y < 36.6 && z > 12.9 && z < 14.05) return Car3D.TRIM;
        }

        // --- Bas de caisse arrière / diffuseur
        if (y < 3.5 && z < 4.5) return Car3D.TRIM;
        return Car3D.BODY;
    }

    /** Points à repérer dans l'image : nom, x, y, z. */
    static final double[][] POINTS = {
            {0, 16.75, 70, 0}, {1, 16.75, 36, 0}, {2, 5.5, 95, 0}, {3, 28.0, 95, 0}, {4, 16.75, 82, 0},
            {5, 0, 36, 0}, {6, 33.5, 36, 0}, {7, 16.75, 0, 0},
    };

    static String pointName(double[] q) {
        String[] n = {"sol_avant", "sol_centre", "faisceau_gauche", "faisceau_droit", "tache_devant", "sol_cote_gauche", "sol_cote_droit", "sol_arriere"};
        return n[(int) q[0]];
    }
}
