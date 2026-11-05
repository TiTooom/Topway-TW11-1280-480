import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Détoure une photo de la Sandero sur fond blanc pour l'accueil (res/drawable-nodpi/car_photo.png).
 * java PhotoCut.java <photo.png> <sortie.png> [largeur=900]
 *
 * - fond blanc → transparent (remplissage depuis les bords, bords adoucis par « dé-mélange » du blanc) ;
 * - sous la ligne du bas de caisse : l'ombre de la photo devient une ombre noire transparente
 *   (marche sur fond clair comme sur fond sombre) ;
 * - antenne retirée, léger contraste et netteté (rendu plus moderne) ;
 * - reflet au sol calculé colonne par colonne, symétrique par rapport à la ligne du sol
 *   (contact des pneus), qui s'efface en descendant ;
 * - recadrage, réduction, et sortie des repères (phares…) en fraction de l'image : <sortie>.txt.
 *
 * Les repères ci-dessous sont en pixels de la photo de référence (1360 × 840, Sandero 1 grise,
 * 3/4 avant gauche). Pour une autre photo, les relever avec une grille et les changer ici.
 */
public class PhotoCut {

    /** Bas de la voiture : au-dessus = voiture (ou fond blanc), en dessous = sol (ombre). */
    static final int[][] BOTTOM = {{0, 690}, {165, 690}, {300, 700}, {440, 708}, {587, 712}, {592, 731}, {602, 748},
            {620, 764}, {643, 776}, {671, 784}, {699, 784}, {723, 776}, {744, 764}, {758, 748}, {767, 724},
            {771, 695}, {773, 660}, {800, 652}, {950, 632}, {1090, 613}, {1100, 642},
            {1130, 660}, {1170, 665}, {1205, 652}, {1226, 622}, {1260, 600}, {1360, 600}};
    /**
     * Contour des pneus visibles (relevé à ×2,5 sur la photo) : toujours « voiture », jamais ombre,
     * avec un bord adouci. Sans ça, la ligne du bas coupait le pneu avant en segments droits
     * (visible la nuit, 05/11).
     */
    static final int[][][] TIRES = {
            {{590, 640}, {590, 694}, {587, 712}, {591, 730}, {601, 746}, {619, 762}, {643, 774}, {671, 782},
                    {699, 782}, {723, 774}, {743, 762}, {757, 746}, {766, 722}, {770, 694}, {771, 660}, {772, 600},
                    {700, 560}, {620, 570}},
            {{1113, 560}, {1113, 590}, {1110, 620}, {1125, 645}, {1150, 660}, {1183, 658}, {1205, 645},
                    {1214, 627}, {1219, 597}, {1222, 537}, {1170, 500}},
    };

    /** Part du pixel (x, y) couverte par un pneu (0 à 1, 4 × 4 sous-pixels). */
    static double tireCover(java.awt.geom.Path2D[] tires, int x, int y) {
        int n = 0;
        for (java.awt.geom.Path2D t : tires) {
            if (!t.intersects(x, y, 1, 1)) continue;
            for (int j = 0; j < 4; j++) for (int k = 0; k < 4; k++) if (t.contains(x + (k + 0.5) / 4, y + (j + 0.5) / 4)) n++;
        }
        return Math.min(16, n) / 16.0;
    }
    /** Sol (contact des pneus, côté visible et côté caché) : axe du reflet. */
    static final int[][] FLOOR = {{0, 735}, {330, 748}, {690, 782}, {1170, 665}, {1360, 640}};
    /** Au-dessus du toit : antenne retirée. */
    static final int ROOF = 204;
    /** Repères : nom, x, y. */
    static final Object[][] POINTS = {
            {"phare_proche", 505, 508}, {"phare_proche_largeur", 200, 0}, {"phare_loin", 187, 492},
            {"phare_loin_largeur", 40, 0}, {"feu_arriere", 1207, 360},
            {"sol_avant_proche", 690, 781}, {"sol_arriere_proche", 1170, 665}, {"sol_avant_loin", 300, 740},
            {"sol_arriere_loin", 1010, 640}, {"faisceau_proche", 120, 900}, {"faisceau_loin", -260, 800},
            {"tache_devant", -60, 830},
    };

    static double lerp(int[][] pts, double x) {
        if (x <= pts[0][0]) return pts[0][1];
        for (int i = 1; i < pts.length; i++) {
            if (x <= pts[i][0]) {
                double t = (x - pts[i - 1][0]) / (double) (pts[i][0] - pts[i - 1][0]);
                return pts[i - 1][1] + t * (pts[i][1] - pts[i - 1][1]);
            }
        }
        return pts[pts.length - 1][1];
    }

    public static void main(String[] a) throws Exception {
        Locale.setDefault(Locale.ROOT);
        BufferedImage src = ImageIO.read(new File(a[0]));
        int outW = a.length > 2 ? Integer.parseInt(a[2]) : 900;
        int W = src.getWidth(), H = src.getHeight();
        int[] px = src.getRGB(0, 0, W, H, null, 0, W);

        // --- 1. Fond blanc relié aux bords (au-dessus du sol)
        boolean[] bg = new boolean[W * H];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < W; x++) { q.add(x); q.add((H - 1) * W + x); }
        for (int y = 0; y < H; y++) { q.add(y * W); q.add(y * W + W - 1); }
        while (!q.isEmpty()) {
            int i = q.poll();
            if (bg[i]) continue;
            int x = i % W, y = i / W;
            if (y < lerp(BOTTOM, x) && minC(px[i]) < 246 && y >= ROOF) continue;   // pas du fond
            bg[i] = true;
            if (x > 0) q.add(i - 1);
            if (x < W - 1) q.add(i + 1);
            if (y > 0) q.add(i - W);
            if (y < H - 1) q.add(i + W);
        }

        // --- 1 bis. Sol : depuis la zone sous la ligne, on remonte (50 px au plus) dans le gris clair
        // de la photo. Les bas de caisse, pneus et lèvre du bouclier, noirs, arrêtent la remontée.
        java.awt.geom.Path2D[] tires = new java.awt.geom.Path2D[TIRES.length];
        for (int t = 0; t < TIRES.length; t++) {
            tires[t] = new java.awt.geom.Path2D.Double();
            tires[t].moveTo(TIRES[t][0][0], TIRES[t][0][1]);
            for (int k = 1; k < TIRES[t].length; k++) tires[t].lineTo(TIRES[t][k][0], TIRES[t][k][1]);
            tires[t].closePath();
        }
        float[] cover = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) cover[y * W + x] = (float) tireCover(tires, x, y);
        boolean[] ground = new boolean[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (y >= lerp(BOTTOM, x) && cover[y * W + x] < 1) { ground[y * W + x] = true; q.add(y * W + x); }
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % W, y = i / W;
            int[] nb = {x > 0 ? i - 1 : -1, x < W - 1 ? i + 1 : -1, y > 0 ? i - W : -1, y < H - 1 ? i + W : -1};
            for (int j : nb) {
                if (j < 0 || ground[j] || cover[j] >= 1) continue;
                int jx = j % W, jy = j / W;
                if (jy < lerp(BOTTOM, jx) - 50) continue;
                if (jx < 595) continue;   // sous le bouclier avant la ligne est exacte : pas de remontée
                int c = px[j];
                int mx = Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255));
                if (lum(c) < 90 || mx - minC(c) > 25) continue;
                ground[j] = true;
                q.add(j);
            }
        }

        // --- 2. Voiture : contraste léger + netteté (sur une copie)
        int[] car = sharpen(px, W, H, 0.45);

        // --- 3. Couches : voiture (avec bords adoucis), ombre au sol
        int RH = 210;                                  // place en bas pour le reflet
        int OH = H + RH;
        int[] out = new int[W * OH];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                int c = px[i];
                if (y < ROOF) continue;                 // antenne
                if (ground[i]) {
                    // Ombre de la photo → noir transparent (plus c'est sombre, plus c'est opaque)
                    double l = lum(c) / 255.0;
                    double al = Math.pow(Math.max(0, 1 - l), 0.9) * 0.92;
                    // part « pneu » : seulement si le pixel est vraiment sombre (pas le fond blanc)
                    double cv = cover[i] * Math.min(1, Math.max(0, (200 - minC(c)) / 80.0));
                    if (cv > 0) {
                        // bord du pneu : mélange pneu opaque / ombre (bord adouci)
                        int cc = car[i];
                        double oa = cv + (1 - cv) * al;
                        int r = (int) Math.round(((cc >> 16) & 255) * cv / oa), g = (int) Math.round(((cc >> 8) & 255) * cv / oa),
                                b = (int) Math.round((cc & 255) * cv / oa);
                        out[i] = ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | b;
                        continue;
                    }
                    out[i] = ((int) Math.round(al * 255) << 24);
                    continue;
                }
                if (bg[i]) continue;
                // Bord : pixel voisin du fond → dé-mélange du blanc
                boolean edge = false;
                for (int dy = -2; dy <= 2 && !edge; dy++) for (int dx = -2; dx <= 2; dx++) {
                    int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < W && yy < H && bg[yy * W + xx]) { edge = true; break; }
                }
                int cc = car[i];
                if (edge) {
                    // Part de voiture = écart au blanc rapporté à la couleur voisine de la voiture
                    // (pneu noir ou carrosserie argent) : évite les pointillés clairs au bord des pneus.
                    // Pixels presque blancs isolés (bruit JPEG autour des pneus) : supprimés.
                    int vcar = 255, near = 0;
                    for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if ((dx == 0 && dy == 0) || xx < 0 || yy < 0 || xx >= W || yy >= H || bg[yy * W + xx]) continue;
                        int m = minC(px[yy * W + xx]);
                        if (m < 235) { near++; vcar = Math.min(vcar, m); }
                    }
                    if (near < 4) continue;
                    vcar = Math.min(vcar, minC(c));
                    double al = Math.min(1, Math.max(0, (255 - minC(c)) / Math.max(30.0, 255 - vcar)));
                    if (al <= 0.02) continue;
                    int r = unmix((cc >> 16) & 255, al), g = unmix((cc >> 8) & 255, al), b = unmix(cc & 255, al);
                    out[i] = ((int) Math.round(al * 255) << 24) | (r << 16) | (g << 8) | b;
                } else {
                    out[i] = 0xFF000000 | (cc & 0xFFFFFF);
                }
            }
        }

        // --- 4. Reflet : la voiture retournée autour de la ligne du sol, sous l'ombre
        int[] refl = new int[W * OH];
        for (int x = 0; x < W; x++) {
            double f = lerp(FLOOR, x);
            for (int y = ROOF; y < H; y++) {
                int c = out[y * W + x];
                int al = c >>> 24;
                if (al == 0 || ground[y * W + x]) continue;
                double d = f - y;
                if (d < 0 || d > 200) continue;
                int ry = (int) Math.round(f + d);
                if (ry < 0 || ry >= OH) continue;
                double k = 0.26 * Math.pow(1 - d / 200.0, 2) * al / 255.0;
                refl[ry * W + x] = ((int) Math.round(k * 255) << 24) | (c & 0xFFFFFF);
            }
        }
        for (int i = 0; i < W * OH; i++) {
            int y = i / W, x = i % W;
            if (y < lerp(FLOOR, x)) continue;
            int r = refl[i], s = out[i];
            double ar = (r >>> 24) / 255.0, as = (s >>> 24) / 255.0;
            if (ar == 0) continue;
            if ((s >>> 24) == 255 && (s & 0xFFFFFF) != 0) continue;     // voiture opaque : rien
            boolean carPx = (s & 0xFFFFFF) != 0;
            if (carPx) continue;
            // ombre (noire) par-dessus le reflet
            double oa = as + ar * (1 - as);
            double k = ar * (1 - as) / oa;
            int cr = (int) Math.round(((r >> 16) & 255) * k), cg = (int) Math.round(((r >> 8) & 255) * k),
                    cb = (int) Math.round((r & 255) * k);
            out[i] = ((int) Math.round(oa * 255) << 24) | (cr << 16) | (cg << 8) | cb;
        }

        // --- 5. Recadrage, réduction
        int mnX = W, mnY = OH, mxX = -1, mxY = -1;
        for (int i = 0; i < W * OH; i++) {
            if ((out[i] >>> 24) < 10) continue;
            int x = i % W, y = i / W;
            mnX = Math.min(mnX, x); mxX = Math.max(mxX, x); mnY = Math.min(mnY, y); mxY = Math.max(mxY, y);
        }
        int cw = mxX - mnX + 1, ch = mxY - mnY + 1;
        double s = outW / (double) cw;
        int ow = outW, oh = (int) Math.round(ch * s);
        BufferedImage res = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                // moyenne de la zone source (réduction propre, alpha prémultiplié)
                double x0 = mnX + x / s, x1 = mnX + (x + 1) / s, y0 = mnY + y / s, y1 = mnY + (y + 1) / s;
                double sa = 0, sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int yy = (int) y0; yy < Math.ceil(y1) && yy < OH; yy++) {
                    for (int xx = (int) x0; xx < Math.ceil(x1) && xx < W; xx++) {
                        int c = out[yy * W + xx];
                        double al = (c >>> 24) / 255.0;
                        sa += al; sr += ((c >> 16) & 255) * al; sg += ((c >> 8) & 255) * al; sb += (c & 255) * al;
                        n++;
                    }
                }
                if (n == 0 || sa == 0) continue;
                int al = (int) Math.round(sa / n * 255);
                res.setRGB(x, y, (al << 24) | ((int) Math.round(sr / sa) << 16) | ((int) Math.round(sg / sa) << 8)
                        | (int) Math.round(sb / sa));
            }
        }
        ImageIO.write(res, "png", new File(a[1]));

        StringBuilder sb = new StringBuilder(String.format("image %d x %d%n", ow, oh));
        for (Object[] p : POINTS) {
            String n = (String) p[0];
            int x = (Integer) p[1], y = (Integer) p[2];
            if (n.endsWith("_largeur")) sb.append(String.format("%s %.4f%n", n, x / (double) cw));
            else sb.append(String.format("%s %.4f %.4f%n", n, (x - mnX) / (double) cw, (y - mnY) / (double) ch));
        }
        Files.writeString(Paths.get(a[1] + ".txt"), sb.toString());
        System.out.print(sb);
    }

    static int minC(int c) {
        return Math.min((c >> 16) & 255, Math.min((c >> 8) & 255, c & 255));
    }

    static double lum(int c) {
        return 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255);
    }

    /** Couleur d'avant le mélange avec le blanc (al = part de la voiture). */
    static int unmix(int v, double al) {
        return (int) Math.max(0, Math.min(255, Math.round((v - (1 - al) * 255) / al)));
    }

    /** Contraste doux (courbe en S) + netteté (masque flou 3 × 3). */
    static int[] sharpen(int[] px, int W, int H, double amount) {
        int[] o = new int[px.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                int[] ch = new int[3];
                for (int k = 0; k < 3; k++) {
                    int sh = 16 - 8 * k;
                    double sum = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(W - 1, Math.max(0, x + dx)), yy = Math.min(H - 1, Math.max(0, y + dy));
                        sum += (px[yy * W + xx] >> sh) & 255;
                        n++;
                    }
                    double v = (px[i] >> sh) & 255;
                    v = v + amount * (v - sum / n);
                    double t = v / 255.0;
                    t = t + 0.10 * (t - 0.5) * (1 - Math.abs(2 * t - 1));   // S léger
                    ch[k] = (int) Math.max(0, Math.min(255, Math.round(t * 255)));
                }
                o[i] = (ch[0] << 16) | (ch[1] << 8) | ch[2];
            }
        }
        return o;
    }
}
