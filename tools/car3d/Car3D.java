import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;

/**
 * Rendu de la Sandero 1 à partir du modèle 3D (3D/Sandero.stl) pour l'écran d'accueil.
 * Outil PC (Java 21, sans bibliothèque) : java Car3D.java <stl> <sortie.png> <mode> [options]
 *
 *   mode debug : gris + lignes de coordonnées peintes sur la carrosserie (x vert, y rouge, z bleu,
 *                plus épaisses tous les 5) pour relever la position des vitres, phares, etc.
 *   mode final : couleurs de la Sandero (argent, vitres teintées, garnitures noires…), fond
 *                transparent ; écrit aussi <sortie>.txt avec la position des phares à l'écran.
 *
 * Repère du modèle : x = largeur (0–33,5), y = longueur (0 = arrière, 70 = avant), z = hauteur
 * (0 = sol). Composantes : 0 caisse, 1 essieu avant + roues, 2 essieu arrière + roues, 3 roue de
 * secours sur le hayon (cachée dans le rendu final).
 *
 * Options : W=900 H=520 SS=3 yaw=-48 pitch=11 fov=22
 */
public class Car3D {

    // ---------------------------------------------------------------- Maillage
    static int nv, nt;
    static float[] V;          // x, y, z par sommet
    static int[] T;            // 3 sommets par triangle
    static int[] comp;         // composante par triangle
    static float[] FN;         // normale par triangle
    static float[] CN;         // normale lissée par coin (3 par coin, 9 par triangle)

    static void load(String path) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
        nt = bb.getInt(80);
        HashMap<Long, Integer> ids = new HashMap<>();
        float[] tmp = new float[nt * 9];
        T = new int[nt * 3];
        int count = 0;
        for (int i = 0; i < nt; i++) {
            for (int v = 0; v < 3; v++) {
                int o = 84 + 50 * i + 12 + v * 12;
                float x = bb.getFloat(o), y = bb.getFloat(o + 4), z = bb.getFloat(o + 8);
                long k = (Math.round(x * 2000L) & 0x1FFFFF) | ((Math.round(y * 2000L) & 0x1FFFFFL) << 21)
                        | ((Math.round(z * 2000L) & 0x1FFFFFL) << 42);
                Integer id = ids.get(k);
                if (id == null) {
                    id = count++;
                    ids.put(k, id);
                    tmp[id * 3] = x; tmp[id * 3 + 1] = y; tmp[id * 3 + 2] = z;
                }
                T[i * 3 + v] = id;
            }
        }
        nv = count;
        V = java.util.Arrays.copyOf(tmp, nv * 3);
        // Composantes connexes
        int[] par = new int[nv];
        for (int i = 0; i < nv; i++) par[i] = i;
        for (int i = 0; i < nt; i++) {
            int a = find(par, T[i * 3]), b = find(par, T[i * 3 + 1]), c = find(par, T[i * 3 + 2]);
            par[b] = a;
            par[find(par, c)] = a;
        }
        comp = new int[nt];
        for (int i = 0; i < nt; i++) {
            float y = V[T[i * 3] * 3 + 1];
            int r = find(par, T[i * 3]);
            comp[i] = r;
        }
        // Renumérote : la plus grosse = caisse (0), essieux par position, petite = 3
        HashMap<Integer, int[]> stat = new HashMap<>();
        for (int i = 0; i < nt; i++) {
            int[] s = stat.computeIfAbsent(comp[i], q -> new int[2]);
            s[0]++;
            s[1] += Math.round(V[T[i * 3] * 3 + 1]);
        }
        int body = -1, best = -1;
        for (var e : stat.entrySet()) if (e.getValue()[0] > best) { best = e.getValue()[0]; body = e.getKey(); }
        HashMap<Integer, Integer> map = new HashMap<>();
        for (var e : stat.entrySet()) {
            int k = e.getKey();
            double meanY = e.getValue()[1] / (double) e.getValue()[0];
            if (k == body) map.put(k, 0);
            else if (e.getValue()[0] < 20000) map.put(k, 3);
            else map.put(k, meanY > 35 ? 1 : 2);
        }
        for (int i = 0; i < nt; i++) comp[i] = map.get(comp[i]);

        // Normales de faces, puis lissées par coin (angle de cassure 40°)
        FN = new float[nt * 3];
        float[] acc = new float[nv * 3];
        for (int i = 0; i < nt; i++) {
            int a = T[i * 3], b = T[i * 3 + 1], c = T[i * 3 + 2];
            float ux = V[b * 3] - V[a * 3], uy = V[b * 3 + 1] - V[a * 3 + 1], uz = V[b * 3 + 2] - V[a * 3 + 2];
            float wx = V[c * 3] - V[a * 3], wy = V[c * 3 + 1] - V[a * 3 + 1], wz = V[c * 3 + 2] - V[a * 3 + 2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0) { FN[i * 3] = nx / l; FN[i * 3 + 1] = ny / l; FN[i * 3 + 2] = nz / l; }
        }
        // Adjacence sommet → triangles
        int[] deg = new int[nv + 1];
        for (int i = 0; i < nt * 3; i++) deg[T[i] + 1]++;
        for (int i = 0; i < nv; i++) deg[i + 1] += deg[i];
        int[] adj = new int[nt * 3], fill = java.util.Arrays.copyOf(deg, nv);
        for (int i = 0; i < nt * 3; i++) adj[fill[T[i]]++] = i / 3;
        CN = new float[nt * 9];
        double cosLimit = Math.cos(Math.toRadians(55));
        for (int i = 0; i < nt; i++) {
            for (int v = 0; v < 3; v++) {
                int vert = T[i * 3 + v];
                double sx = 0, sy = 0, sz = 0;
                for (int k = deg[vert]; k < deg[vert + 1]; k++) {
                    int f = adj[k];
                    double d = FN[f * 3] * FN[i * 3] + FN[f * 3 + 1] * FN[i * 3 + 1] + FN[f * 3 + 2] * FN[i * 3 + 2];
                    // Le modèle a des triangles retournés : un voisin opposé compte comme retourné.
                    double sg = d < 0 ? -1 : 1;
                    if (Math.abs(d) < cosLimit) continue;
                    sx += sg * FN[f * 3]; sy += sg * FN[f * 3 + 1]; sz += sg * FN[f * 3 + 2];
                }
                double l = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (l == 0) { sx = FN[i * 3]; sy = FN[i * 3 + 1]; sz = FN[i * 3 + 2]; l = 1; }
                CN[i * 9 + v * 3] = (float) (sx / l);
                CN[i * 9 + v * 3 + 1] = (float) (sy / l);
                CN[i * 9 + v * 3 + 2] = (float) (sz / l);
            }
        }
    }

    // ---------------------------------------------------------------- Normales lissées (champ flou)
    static final double CELL = 1.0;
    static final int GX = 36, GY = 72, GZ = 28;
    static float[] grid;   // somme des normales orientées vers l'extérieur, par case

    /** Normales des faces de la caisse, orientées vers l'extérieur, accumulées par case de 1 unité. */
    static void buildBlur() {
        float[] g = new float[GX * GY * GZ * 3];
        for (int i = 0; i < nt; i++) {
            if (comp[i] != 0) continue;
            int a = T[i * 3], b = T[i * 3 + 1], c = T[i * 3 + 2];
            double x = (V[a * 3] + V[b * 3] + V[c * 3]) / 3, y = (V[a * 3 + 1] + V[b * 3 + 1] + V[c * 3 + 1]) / 3,
                    z = (V[a * 3 + 2] + V[b * 3 + 2] + V[c * 3 + 2]) / 3;
            double ux = V[b * 3] - V[a * 3], uy = V[b * 3 + 1] - V[a * 3 + 1], uz = V[b * 3 + 2] - V[a * 3 + 2];
            double wx = V[c * 3] - V[a * 3], wy = V[c * 3 + 1] - V[a * 3 + 1], wz = V[c * 3 + 2] - V[a * 3 + 2];
            double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;   // longueur = 2 × aire
            // vers l'extérieur : à l'opposé de l'axe de la voiture
            double ox = x - 16.75, oy = y - Math.max(16, Math.min(54, y)), oz = z - 9;
            if (nx * ox + ny * oy + nz * oz < 0) { nx = -nx; ny = -ny; nz = -nz; }
            int cx = (int) (x / CELL), cy = (int) (y / CELL), cz = (int) (z / CELL);
            if (cx < 0 || cy < 0 || cz < 0 || cx >= GX || cy >= GY || cz >= GZ) continue;
            int k = ((cz * GY + cy) * GX + cx) * 3;
            g[k] += nx; g[k + 1] += ny; g[k + 2] += nz;
        }
        // Flou 3 × 3 × 3
        grid = new float[g.length];
        for (int cz = 0; cz < GZ; cz++) for (int cy = 0; cy < GY; cy++) for (int cx = 0; cx < GX; cx++) {
            double sx = 0, sy = 0, sz = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int X = cx + dx, Y = cy + dy, Z = cz + dz;
                if (X < 0 || Y < 0 || Z < 0 || X >= GX || Y >= GY || Z >= GZ) continue;
                int k = ((Z * GY + Y) * GX + X) * 3;
                double w = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1) * (dz == 0 ? 2 : 1);
                sx += w * g[k]; sy += w * g[k + 1]; sz += w * g[k + 2];
            }
            int k = ((cz * GY + cy) * GX + cx) * 3;
            grid[k] = (float) sx; grid[k + 1] = (float) sy; grid[k + 2] = (float) sz;
        }
    }

    /** Normale lissée au point (interpolation trilinéaire du champ), null si vide. */
    static double[] blurNormal(double x, double y, double z) {
        if (grid == null) buildBlur();
        double fx = x / CELL - 0.5, fy = y / CELL - 0.5, fz = z / CELL - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
        double tx = fx - x0, ty = fy - y0, tz = fz - z0, sx = 0, sy = 0, sz = 0;
        for (int dz = 0; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) for (int dx = 0; dx <= 1; dx++) {
            int X = x0 + dx, Y = y0 + dy, Z = z0 + dz;
            if (X < 0 || Y < 0 || Z < 0 || X >= GX || Y >= GY || Z >= GZ) continue;
            double w = (dx == 1 ? tx : 1 - tx) * (dy == 1 ? ty : 1 - ty) * (dz == 1 ? tz : 1 - tz);
            int k = ((Z * GY + Y) * GX + X) * 3;
            sx += w * grid[k]; sy += w * grid[k + 1]; sz += w * grid[k + 2];
        }
        double l = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (l < 1e-9) return null;
        return new double[]{sx / l, sy / l, sz / l};
    }

    static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    // ---------------------------------------------------------------- Caméra
    static double camX, camY, camZ, fx, fy, fz, rx, ry, rz, ux, uy, uz, focal, offX, offY, scale;

    static void camera(double yawDeg, double pitchDeg, double fovDeg) {
        double cx = 16.75, cy = 36, cz = 11;
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double dist = 75 / Math.tan(Math.toRadians(fovDeg) / 2) / 2;
        // Direction caméra → voiture (yaw 0 = caméra devant la voiture, regardant vers -y)
        double dx = Math.sin(yaw) * Math.cos(pitch), dy = Math.cos(yaw) * Math.cos(pitch), dz = Math.sin(pitch);
        camX = cx + dx * dist; camY = cy + dy * dist; camZ = cz + dz * dist;
        fx = -dx; fy = -dy; fz = -dz;
        // droite = avant × haut
        rx = fy * 1 - fz * 0; ry = fz * 0 - fx * 1; rz = 0;
        double l = Math.sqrt(rx * rx + ry * ry);
        rx /= l; ry /= l;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;
        focal = 1;
    }

    /** Projette (x, y, z) → {sx, sy, profondeur} avant mise à l'échelle. */
    static void project(double x, double y, double z, double[] out) {
        double px = x - camX, py = y - camY, pz = z - camZ;
        double cxv = px * rx + py * ry + pz * rz, cyv = px * ux + py * uy + pz * uz, czv = px * fx + py * fy + pz * fz;
        out[0] = cxv / czv * focal;
        out[1] = -cyv / czv * focal;
        out[2] = czv;
    }

    // ---------------------------------------------------------------- Matières
    static final int BODY = 0, GLASS = 1, TRIM = 2, TIRE = 3, RIM = 4, HEAD = 5, TAIL = 6, GRILLE = 7,
            CHROME = 8, ARCH = 9, PLATE = 10, FOG = 11, HIDE = 12, LOGO = 13;

    static int material(int tri, double x, double y, double z, double nx, double ny, double nz) {
        int c = comp[tri];
        if (c == 3) return HIDE;
        if (c == 1 || c == 2) {
            double wy = c == 1 ? 56.55 : 14.05, wz = 5.25;   // centres des roues (boîtes des essieux)
            double r = Math.hypot(y - wy, z - wz);
            if (Math.abs(nx) > 0.6 && r < 3.55) return RIM;
            if (r < 3.2) return RIM;
            return TIRE;
        }
        return Zones.body(x, y, z, nx, ny, nz);
    }

    /** Pixellise le modèle (ou son reflet sous le sol) : profondeur, triangle et barycentriques par pixel. */
    static void raster(boolean mirror, boolean debug, int BW, int BH, float[] zb, int[] tid, float[] b0, float[] b1) {
        java.util.Arrays.fill(zb, Float.MAX_VALUE);
        java.util.Arrays.fill(tid, -1);
        double[] A = new double[3], B = new double[3], C = new double[3];
        double sz = mirror ? -1 : 1;
        for (int i = 0; i < nt; i++) {
            if (comp[i] == 3 && !debug) continue;
            int ka = T[i * 3], kb = T[i * 3 + 1], kc = T[i * 3 + 2];
            project(V[ka * 3], V[ka * 3 + 1], sz * V[ka * 3 + 2], A);
            project(V[kb * 3], V[kb * 3 + 1], sz * V[kb * 3 + 2], B);
            project(V[kc * 3], V[kc * 3 + 1], sz * V[kc * 3 + 2], C);
            double ax = A[0] * scale + offX, ay = A[1] * scale + offY, bx = B[0] * scale + offX, by = B[1] * scale + offY,
                    cx = C[0] * scale + offX, cy = C[1] * scale + offY;
            double den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
            if (Math.abs(den) < 1e-12) continue;
            int x0 = (int) Math.max(0, Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = (int) Math.min(BW - 1, Math.ceil(Math.max(ax, Math.max(bx, cx))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = (int) Math.min(BH - 1, Math.ceil(Math.max(ay, Math.max(by, cy))));
            double ia = 1 / A[2], ib = 1 / B[2], ic = 1 / C[2];
            for (int yy = y0; yy <= y1; yy++) {
                double py = yy + 0.5;
                for (int xx = x0; xx <= x1; xx++) {
                    double px = xx + 0.5;
                    double w0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den;
                    double w1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den;
                    double w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    double iz = w0 * ia + w1 * ib + w2 * ic;
                    float depth = (float) (1 / iz);
                    int idx = yy * BW + xx;
                    if (depth < zb[idx]) {
                        zb[idx] = depth;
                        tid[idx] = i;
                        // barycentriques corrigées de la perspective
                        b0[idx] = (float) (w0 * ia / iz);
                        b1[idx] = (float) (w1 * ib / iz);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Rendu
    public static void main(String[] a) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String stl = a[0], out = a[1], mode = a[2];
        HashMap<String, String> o = new HashMap<>();
        for (int i = 3; i < a.length; i++) {
            String[] kv = a[i].split("=", 2);
            o.put(kv[0], kv[1]);
        }
        int W = Integer.parseInt(o.getOrDefault("W", "900")), H = Integer.parseInt(o.getOrDefault("H", "520"));
        int SS = Integer.parseInt(o.getOrDefault("SS", mode.equals("debug") ? "1" : "3"));
        double yaw = Double.parseDouble(o.getOrDefault("yaw", "-48"));
        double pitch = Double.parseDouble(o.getOrDefault("pitch", "11"));
        double fov = Double.parseDouble(o.getOrDefault("fov", "22"));
        boolean debug = mode.equals("debug");
        Shade.LINES = o.getOrDefault("lines", "xyz");
        load(stl);
        camera(yaw, pitch, fov);

        // Mise à l'échelle : la voiture remplit l'image (marge 2 %), reflet sous le sol en plus
        double refl = debug ? 0 : Double.parseDouble(o.getOrDefault("reflet", "0.30"));
        double[] p = new double[3];
        double mnx = 1e9, mxx = -1e9, mny = 1e9, mxy = -1e9;
        for (int i = 0; i < nt; i++) {
            if (comp[i] == 3 && !debug) continue;
            for (int v = 0; v < 3; v++) {
                int k = T[i * 3 + v];
                project(V[k * 3], V[k * 3 + 1], V[k * 3 + 2], p);
                mnx = Math.min(mnx, p[0]); mxx = Math.max(mxx, p[0]);
                mny = Math.min(mny, p[1]); mxy = Math.max(mxy, p[1]);
            }
        }
        int BW = W * SS, BH = H * SS;
        double spanY = (mxy - mny) * (1 + refl);
        scale = Math.min(BW * 0.98 / (mxx - mnx), BH * 0.98 / spanY);
        offX = (BW - (mxx - mnx) * scale) / 2 - mnx * scale;
        offY = BH - 0.01 * BH - (mxy + (mxy - mny) * refl) * scale;   // voiture + reflet posés en bas

        float[] zb = new float[BW * BH];
        int[] tid = new int[BW * BH];
        float[] b0 = new float[BW * BH], b1 = new float[BW * BH];
        int[] img = new int[BW * BH];
        double[][] acc = new double[4][];
        for (int g = 0; g < 4; g++) acc[g] = new double[]{0, 0, 0, 1e9, 1e9, -1, -1};
        int groundPx = 0;
        int[] mirrorImg = null;
        // Passe 1 : reflet (modèle retourné sous le sol), passe 2 : la voiture
        for (int pass = refl > 0 ? 0 : 1; pass < 2; pass++) {
            boolean mirror = pass == 0;
            raster(mirror, debug, BW, BH, zb, tid, b0, b1);
            int[] target = mirror ? new int[BW * BH] : img;
            for (int idx = 0; idx < BW * BH; idx++) {
                int i = tid[idx];
                if (i < 0) continue;
                double w0 = b0[idx], w1 = b1[idx], w2 = 1 - w0 - w1;
                int ka = T[i * 3], kb = T[i * 3 + 1], kc = T[i * 3 + 2];
                double x = w0 * V[ka * 3] + w1 * V[kb * 3] + w2 * V[kc * 3];
                double y = w0 * V[ka * 3 + 1] + w1 * V[kb * 3 + 1] + w2 * V[kc * 3 + 1];
                double z = w0 * V[ka * 3 + 2] + w1 * V[kb * 3 + 2] + w2 * V[kc * 3 + 2];
                double nx = w0 * CN[i * 9] + w1 * CN[i * 9 + 3] + w2 * CN[i * 9 + 6];
                double ny = w0 * CN[i * 9 + 1] + w1 * CN[i * 9 + 4] + w2 * CN[i * 9 + 7];
                double nz = w0 * CN[i * 9 + 2] + w1 * CN[i * 9 + 5] + w2 * CN[i * 9 + 8];
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= l; ny /= l; nz /= l;
                // Vue : depuis la caméra, ou depuis la caméra retournée pour le reflet
                double cz = mirror ? -camZ : camZ;
                double vx = camX - x, vy = camY - y, vz = cz - z, vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                vx /= vl; vy /= vl; vz /= vl;
                if (nx * vx + ny * vy + nz * vz < 0) { nx = -nx; ny = -ny; nz = -nz; }
                int m = material(i, x, y, z, nx, ny, nz);
                if (!debug && (m == BODY || m == GLASS)) {
                    // Lissage : le modèle a de petites bosses que le reflet métallisé exagère.
                    double[] s = blurNormal(x, y, z);
                    if (s != null) {
                        if (s[0] * nx + s[1] * ny + s[2] * nz < 0) { s[0] = -s[0]; s[1] = -s[1]; s[2] = -s[2]; }
                        double k = m == GLASS ? 0.85 : 0.6;
                        nx = (1 - k) * nx + k * s[0]; ny = (1 - k) * ny + k * s[1]; nz = (1 - k) * nz + k * s[2];
                        l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        nx /= l; ny /= l; nz /= l;
                    }
                }
                int col = debug ? Shade.debug(x, y, z, nx, ny, nz, comp[i])
                        : Shade.color(m, x, y, z, nx, ny, nz, vx, vy, vz);
                if (mirror) {
                    // Reflet : fort près du sol, effacé à mi-hauteur de la voiture
                    double t = Math.min(1, Math.max(0, z / 11.0));
                    double al = 0.42 * Math.pow(1 - t, 2) * (col >>> 24) / 255.0;
                    target[idx] = ((int) Math.round(al * 255) << 24) | (col & 0xFFFFFF);
                    continue;
                }
                target[idx] = col;
                // Repérage des phares (gauche / droite) et feux arrière dans l'image
                int g = m == HEAD ? (x < 16.75 ? 0 : 1) : m == TAIL ? (x < 16.75 ? 2 : 3) : -1;
                if (g >= 0) {
                    int px = idx % BW, py = idx / BW;
                    acc[g][0] += px; acc[g][1] += py; acc[g][2]++;
                    acc[g][3] = Math.min(acc[g][3], px); acc[g][4] = Math.min(acc[g][4], py);
                    acc[g][5] = Math.max(acc[g][5], px); acc[g][6] = Math.max(acc[g][6], py);
                }
                if (comp[i] == 1 || comp[i] == 2) groundPx = Math.max(groundPx, idx / BW);
            }
            if (mirror) mirrorImg = target;
        }
        if (mirrorImg != null) {
            for (int idx = 0; idx < BW * BH; idx++) if ((img[idx] >>> 24) == 0) img[idx] = mirrorImg[idx];
        }
        // Recadrage sur la voiture (plus de marges vides : l'appli l'agrandit au mieux)
        int cx0 = 0, cy0 = 0, cw = W, ch = H;
        if (!debug) {
            int mnX = BW, mnY = BH, mxX = -1, mxY = -1;
            for (int idx = 0; idx < BW * BH; idx++) {
                if ((img[idx] >>> 24) < 6) continue;
                int px = idx % BW, py = idx / BW;
                mnX = Math.min(mnX, px); mxX = Math.max(mxX, px); mnY = Math.min(mnY, py); mxY = Math.max(mxY, py);
            }
            if (mxX > 0) {
                cx0 = Math.max(0, mnX / SS - 2); cy0 = Math.max(0, mnY / SS - 2);
                cw = Math.min(W, mxX / SS + 3) - cx0; ch = Math.min(H, mxY / SS + 3) - cy0;
            }
        }
        final int fcx = cx0, fcy = cy0, fcw = cw, fch = ch, fss = SS;
        java.util.function.DoubleUnaryOperator FX = v -> (v / fss - fcx) / fcw, FY = v -> (v / fss - fcy) / fch;

        StringBuilder lamps = new StringBuilder();
        lamps.append(String.format("image %d x %d%n", cw, ch));
        String[] ln = {"phare_gauche", "phare_droit", "feu_ar_gauche", "feu_ar_droit"};
        for (int g = 0; g < 4; g++) {
            double[] q = acc[g];
            if (q[2] < 1) { lamps.append(ln[g]).append(" absent").append(System.lineSeparator()); continue; }
            lamps.append(String.format("%s centre %.4f %.4f boite %.4f %.4f %.4f %.4f pixels %d%n", ln[g],
                    FX.applyAsDouble(q[0] / q[2]), FY.applyAsDouble(q[1] / q[2]), FX.applyAsDouble(q[3]),
                    FY.applyAsDouble(q[4]), FX.applyAsDouble(q[5]), FY.applyAsDouble(q[6]), (long) q[2]));
        }
        lamps.append(String.format("sol %.4f%n", FY.applyAsDouble(groundPx + 1.0)));

        // Réduction (anticrénelage) de la zone recadrée
        BufferedImage res = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < ch; yy++) {
            for (int xx = 0; xx < cw; xx++) {
                long sa = 0, sr = 0, sg = 0, sb = 0;
                for (int j = 0; j < SS; j++) for (int k = 0; k < SS; k++) {
                    int c = img[((yy + cy0) * SS + j) * BW + (xx + cx0) * SS + k];
                    int al = c >>> 24;
                    sa += al;
                    sr += ((c >> 16) & 255) * al; sg += ((c >> 8) & 255) * al; sb += (c & 255) * al;
                }
                int n = SS * SS;
                int al = (int) (sa / n);
                int col = 0;
                if (sa > 0) col = (al << 24) | ((int) (sr / sa) << 16) | ((int) (sg / sa) << 8) | (int) (sb / sa);
                if (debug && al < 255) col = 0xFF202226;
                res.setRGB(xx, yy, col);
            }
        }
        ImageIO.write(res, "png", new File(out));

        // Points utiles pour l'appli (en fraction de l'image recadrée) : sol, faisceaux
        StringBuilder sb = new StringBuilder(lamps);
        for (double[] q : Zones.POINTS) {
            project(q[1], q[2], q[3], p);
            double px = p[0] * scale + offX, py = p[1] * scale + offY;
            sb.append(String.format("%s %.4f %.4f%n", Zones.pointName(q), FX.applyAsDouble(px), FY.applyAsDouble(py)));
        }
        Files.writeString(Paths.get(out + ".txt"), sb.toString());
        System.out.print(sb);
    }
}
