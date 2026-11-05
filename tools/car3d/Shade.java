/** Couleurs et éclairage du rendu (style de l'accueil Sandero : argent, vitres fumées, noir). */
final class Shade {

    // Lumière principale (haut, devant à droite) et lumière d'appoint
    static final double LX = 0.35, LY = 0.55, LZ = 0.76;

    static String LINES = "xyz";

    static int debug(double x, double y, double z, double nx, double ny, double nz, int comp) {
        double d = Math.max(0, nx * LX + ny * LY + nz * LZ);
        int g = (int) (70 + 150 * d);
        int r = g, gr = g, b = g;
        if (comp == 1 || comp == 2) { r = g * 7 / 10; gr = g * 8 / 10; }
        // Lignes de coordonnées
        double t = 0.05;
        if (LINES.indexOf('x') >= 0 && near(x, 1, t)) { gr = 230; r /= 2; b /= 2; if (near(x, 5, t * 1.6)) { r = 0; b = 0; gr = 255; } }
        if (LINES.indexOf('y') >= 0 && near(y, 1, t)) { r = 230; gr /= 2; b /= 2; if (near(y, 5, t * 1.6)) { r = 255; gr = 0; b = 0; } }
        if (LINES.indexOf('z') >= 0 && near(z, 1, t)) { b = 255; r /= 2; gr /= 2; if (near(z, 5, t * 1.6)) { r = 0; gr = 80; b = 255; } }
        return 0xFF000000 | (clamp(r) << 16) | (clamp(gr) << 8) | clamp(b);
    }

    static boolean near(double v, double step, double t) {
        double f = v / step - Math.floor(v / step);
        return f < t / step || f > 1 - t / step;
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /**
     * Couleur finale. Reflet d'un « studio » : ciel clair en haut, horizon, sol sombre,
     * plus une lumière principale (diffus + brillance) : rendu carrosserie métallisée.
     */
    static int color(int m, double x, double y, double z, double nx, double ny, double nz,
                     double vx, double vy, double vz) {
        if (m == Car3D.HIDE) return 0;
        double dif = Math.max(0, nx * LX + ny * LY + nz * LZ);
        // direction réfléchie
        double dn = nx * vx + ny * vy + nz * vz;
        double rx = 2 * dn * nx - vx, ry = 2 * dn * ny - vy, rz = 2 * dn * nz - vz;
        double hx = LX + vx, hy = LY + vy, hz = LZ + vz, hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double spec = Math.pow(Math.max(0, (nx * hx + ny * hy + nz * hz) / hl), 60);
        double env = studio(rz);
        double fres = 0.04 + 0.96 * Math.pow(1 - Math.max(0, dn), 5);
        double r, g, b;
        switch (m) {
            case Car3D.BODY: {
                // Argent métallisé, proche de la palette du dessin d'avant (DADDE0 → 73797F) :
                // clair sur le dessus, plus sombre vers le bas de caisse.
                double low = 0.72 + 0.28 * Math.min(1, Math.max(0, (z - 2) / 11));
                double base = 0.30 + 0.34 * dif;
                double spec2 = Math.pow(Math.max(0, (nx * hx + ny * hy + nz * hz) / hl), 12);
                double v = (base * 0.58 + env * 0.60) * low + spec * 0.5 + spec2 * 0.12;
                r = v * 0.965; g = v * 0.985; b = v * 1.01;
                break;
            }
            case Car3D.GLASS: {
                double v = 0.05 + 0.10 * dif + env * (0.12 + 0.5 * fres) + spec * 0.8;
                r = v * 0.85; g = v * 0.95; b = v * 1.10;
                break;
            }
            case Car3D.TRIM: case Car3D.ARCH: {
                double v = 0.05 + 0.10 * dif + env * 0.07 + spec * 0.12;
                r = g = b = v;
                break;
            }
            case Car3D.GRILLE: {
                double v = 0.04 + 0.08 * dif + env * 0.05 + spec * 0.15;
                r = g = b = v;
                break;
            }
            case Car3D.TIRE: {
                double v = 0.05 + 0.10 * dif + spec * 0.04;
                r = g = b = v;
                break;
            }
            case Car3D.RIM: case Car3D.CHROME: case Car3D.LOGO: {
                double v = 0.42 + 0.40 * dif + env * 0.35 + spec * 0.7;
                r = v; g = v * 1.01; b = v * 1.03;
                break;
            }
            case Car3D.HEAD: {
                // Verre de phare fumé (comme le dessin d'avant : C9D0D6 → 5E676F → 2C3238)
                double v = 0.10 + 0.16 * dif + env * 0.26 + spec * 1.1;
                r = v * 0.93; g = v * 0.98; b = v * 1.04;
                break;
            }
            case Car3D.FOG: {
                double v = 0.35 + 0.3 * dif + env * 0.3 + spec;
                r = v; g = v; b = v * 1.02;
                break;
            }
            case Car3D.TAIL: {
                double v = 0.25 + 0.35 * dif + spec * 0.8;
                r = 0.75 * v + 0.15 + spec * 0.3; g = 0.08 * v + spec * 0.6; b = 0.08 * v + spec * 0.6;
                break;
            }
            case Car3D.PLATE: {
                double v = 0.7 + 0.25 * dif;
                r = v; g = v; b = v;
                break;
            }
            default:
                r = g = b = 0.5;
        }
        return 0xFF000000 | (clamp(gamma(r) * 255) << 16) | (clamp(gamma(g) * 255) << 8) | clamp(gamma(b) * 255);
    }

    /** Ciel de studio selon la hauteur de la direction réfléchie (-1 bas … 1 haut). */
    static double studio(double rz) {
        // Transition douce : sol sombre → horizon → ciel clair (sans bande marquée, qui faisait des taches)
        double t = Math.max(0, Math.min(1, (rz + 0.25) / 0.85));
        t = t * t * (3 - 2 * t);
        return 0.20 + 0.66 * t;
    }

    static double gamma(double v) {
        return Math.pow(Math.max(0, v), 1 / 1.15);
    }
}
