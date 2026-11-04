package fr.sandero.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Dacia Sandero 1 (2008-2012) grise, vue de profil, posée sur un sol réfléchissant
 * avec un halo de couleur, comme sur les écrans d'accueil des marques allemandes.
 *
 * Pour mettre une vraie photo à la place du dessin : placer une image PNG détourée
 * (fond transparent) dans app/src/main/res/drawable-nodpi/car_photo.png et recompiler.
 */
final class CarView extends View {

    // Gris argent (comme la photo de référence). Changer ces teintes pour une autre couleur.
    private static final int[] BODY = {
            0xFFDADDE0, 0xFFC2C7CB, 0xFFF3F5F6, 0xFFCDD1D5, 0xFFA9AFB4, 0xFF73797F};
    private static final float[] BODY_POS = {0f, .42f, .47f, .55f, .82f, 1f};

    // Repère du dessin : 1000 x 400, l avant à droite, le sol à y = 400.
    private static final float W = 1000f, H = 400f, REFLECTION = 0.30f;

    private int accent = 0xFF19B5FE;
    private Bitmap rendered;
    private float carW, carH;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);

    CarView(Context c) {
        super(c);
    }

    // Phares : 0 = éteints, 1 = allumés (animé en fondu).
    private float lights;
    private android.animation.ValueAnimator lightAnim;
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);

    void setAccent(int color) {
        accent = color;
        invalidate();
    }

    /** Allume ou éteint les phares du dessin (signal d'éclairage de l'autoradio). */
    void setLights(boolean on) {
        float target = on ? 1f : 0f;
        if (lightAnim != null) lightAnim.cancel();
        lightAnim = android.animation.ValueAnimator.ofFloat(lights, target);
        lightAnim.setDuration(on ? 900 : 500);
        lightAnim.addUpdateListener(a -> {
            lights = (Float) a.getAnimatedValue();
            invalidate();
        });
        lightAnim.start();
    }

    boolean lightsOn() {
        return lights > 0.5f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rebuild(w, h);
    }

    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0) return;
        preparedFor = 0;
        // Un peu de marge à droite pour le faisceau des phares.
        float scale = Math.min(w / W, h / (H * (1 + REFLECTION))) * 0.88f;
        carW = W * scale;
        carH = H * scale;
        int bw = Math.max(1, Math.round(carW));
        int bh = Math.max(1, Math.round(carH));
        int rh = Math.max(1, Math.round(carH * REFLECTION));

        Bitmap car = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(car);
        Bitmap photo = loadPhoto();
        if (photo != null) {
            float s = Math.min(bw / (float) photo.getWidth(), bh / (float) photo.getHeight());
            float pw = photo.getWidth() * s, ph = photo.getHeight() * s;
            RectF dst = new RectF((bw - pw) / 2f, bh - ph, (bw + pw) / 2f, bh);
            cc.drawBitmap(photo, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            cc.scale(scale, scale);
            drawCar(cc);
        }

        rendered = Bitmap.createBitmap(bw, bh + rh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(rendered);
        c.drawBitmap(car, 0, 0, null);
        // Reflet sur le sol : la voiture retournée, qui s'efface vers le bas.
        Matrix flip = new Matrix();
        flip.setScale(1, -1);
        flip.postTranslate(0, bh * 2f);
        c.drawBitmap(car, flip, new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint fade = new Paint();
        fade.setShader(new LinearGradient(0, bh, 0, bh + rh, 0x50000000, 0x00000000, Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, bh, bw, bh + rh, fade);
        car.recycle();
    }

    private Bitmap loadPhoto() {
        int id = getResources().getIdentifier("car_photo", "drawable", getContext().getPackageName());
        if (id == 0) return null;
        try {
            return BitmapFactory.decodeResource(getResources(), id);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // Dégradés préparés une fois (pas d'allocation à chaque image : animation fluide).
    private int preparedFor = 0;
    private int preparedAccent;
    private float pLeft, pTop, pGround, pCx, pS;
    private final Path cone = new Path();
    private Shader beamShader, poolShader, headShader, tailShader;

    private void prepare() {
        int key = getWidth() * 31 + getHeight();
        if (key == preparedFor && accent == preparedAccent) return;
        boolean geometry = key != preparedFor;
        preparedFor = key;
        preparedAccent = accent;
        pLeft = (getWidth() - rendered.getWidth()) * 0.25f;
        pTop = (getHeight() - rendered.getHeight()) / 2f;
        pGround = pTop + carH;
        pCx = pLeft + carW / 2f;
        pS = carW / W;

        glow.setShader(new RadialGradient(pCx, pGround, carW * 0.58f,
                new int[]{(accent & 0x00FFFFFF) | 0x99000000, (accent & 0x00FFFFFF) | 0x33000000, 0x00000000},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        floor.setShader(new LinearGradient(pLeft, 0, pLeft + carW, 0,
                new int[]{0x00FFFFFF, (accent & 0x00FFFFFF) | 0xAA000000, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        if (!geometry) return;

        float s = pS, hx = pLeft + 975 * s, hy = pTop + 196 * s, end = getWidth();
        cone.reset();
        cone.moveTo(hx, hy - 9 * s);
        cone.lineTo(end, hy - 30 * s);
        cone.lineTo(end, pGround + 4 * s);
        cone.lineTo(hx, hy + 12 * s);
        cone.close();
        beamShader = new LinearGradient(hx, 0, end, 0,
                new int[]{0x96FFF6D8, 0x1EFFF6D8, 0x00FFF6D8},
                new float[]{0f, 0.55f, 0.92f}, Shader.TileMode.CLAMP);
        poolShader = new RadialGradient(pLeft + carW * 1.02f, pGround, carW * 0.22f,
                0x6EFFF3CF, 0x00FFF3CF, Shader.TileMode.CLAMP);
        headShader = new RadialGradient(pLeft + 962 * s, pTop + 192 * s, 46 * s,
                new int[]{0xFFFFFFFF, 0x78FFF1C9, 0x00FFF1C9}, new float[]{0f, .3f, 1f}, Shader.TileMode.CLAMP);
        tailShader = new RadialGradient(pLeft + 30 * s, pTop + 140 * s, 52 * s,
                new int[]{0xE6FF3B3B, 0x5AFF1E1E, 0x00FF0000}, new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rendered == null) return;
        prepare();
        float s = pS;

        // Halo coloré et ligne de sol lumineuse.
        canvas.save();
        canvas.scale(1f, 0.17f, pCx, pGround);
        canvas.drawCircle(pCx, pGround, carW * 0.58f, glow);
        canvas.restore();
        canvas.drawRect(pLeft, pGround - 1, pLeft + carW, pGround + 1, floor);

        int alpha = Math.round(255 * lights);
        if (alpha > 2) {
            // Faisceau des phares et tache de lumière au sol.
            beam.setAlpha(alpha);
            beam.setShader(beamShader);
            canvas.drawPath(cone, beam);
            beam.setShader(poolShader);
            float px = pLeft + carW * 1.02f;
            canvas.save();
            canvas.scale(1f, 0.22f, px, pGround);
            canvas.drawCircle(px, pGround, carW * 0.22f, beam);
            canvas.restore();
        }

        canvas.drawBitmap(rendered, pLeft, pTop, null);

        if (alpha > 2) {
            // Phare avant et feu arrière allumés.
            lamp.setAlpha(alpha);
            lamp.setShader(headShader);
            canvas.drawCircle(pLeft + 962 * s, pTop + 192 * s, 46 * s, lamp);
            lamp.setShader(tailShader);
            canvas.drawCircle(pLeft + 30 * s, pTop + 140 * s, 52 * s, lamp);
        }
    }

    // ---------------------------------------------------------------- Dessin de la Sandero
    // Points relevés sur une photo de profil d'une Sandero 1 grise (avant à droite).
    // Repère 1000 x 400, sol à y = 400.

    private static final float RX = 141f, FX = 854f, WY = 317f, TIRE = 82f, ARCH = 101f, SILL = 328f;

    private static final float[] BODY_TOP = {32.5f, 288.5f, 7.8f, 277.2f, 0f, 245.7f, 1.3f, 207.9f, 7.8f, 176.4f, 20.8f, 132.3f, 39f, 104f, 61.7f, 81.9f, 90.9f, 56.7f, 123.4f, 34.7f, 162.3f, 18.9f, 201.3f, 12.6f, 292.2f, 8.2f, 409.1f, 7f, 487f, 10.1f, 539f, 22.1f, 590.9f, 47.3f, 649.4f, 81.9f, 707.8f, 116.6f, 779.2f, 132.3f, 850.6f, 146.2f, 918.8f, 162.5f, 964.3f, 182.7f, 988.3f, 207.9f, 998.7f, 239.4f, 1000f, 283.5f, 994.8f, 318.1f, 980.5f, 333.9f, 955.8f, 338.9f};
    private static final float[] WIN_R = {187f, 114.7f, 184.4f, 44.1f, 191.6f, 28.4f, 211f, 21.4f, 409.1f, 20.8f, 414.9f, 26.5f, 416.2f, 130.4f, 201.3f, 121.6f};
    private static final float[] WIN_F = {439f, 28.4f, 449.4f, 23.3f, 496.8f, 26.5f, 542.2f, 44.1f, 594.2f, 75.6f, 639.6f, 113.4f, 672.1f, 146.2f, 659.1f, 149.9f, 451.3f, 146.2f, 441.6f, 138.6f};
    private static final float[] PILLAR_Q = {241.6f, 18f, 250.6f, 18f, 250.6f, 126f, 241.6f, 125f};
    private static final float[] TAIL = {61.7f, 83.8f, 70.1f, 92f, 57.1f, 126f, 46.8f, 157.5f, 39f, 177.7f, 13f, 177.7f, 15.6f, 144.9f, 27.3f, 114.7f, 44.2f, 94.5f};
    private static final float[] HEAD = {915.6f, 162.5f, 949.4f, 170.1f, 975.3f, 184.6f, 987f, 206.6f, 966.2f, 200.3f, 936.4f, 184f};
    private static final float[] MIRROR = {662.3f, 105.9f, 681.8f, 100.8f, 696.1f, 110.3f, 703.9f, 127.3f, 698.7f, 142.4f, 676.6f, 146.2f, 663.6f, 133.6f};
    private static final float[] FUEL = {97.4f, 135.5f, 151.9f, 131f, 157.1f, 161.3f, 100.6f, 166.3f};
    private static final float[] HANDLE_R = {198.7f, 156.2f, 253.2f, 154.4f, 253.2f, 165.1f, 200f, 166.9f};
    private static final float[] HANDLE_F = {454.5f, 170.1f, 509.7f, 168.8f, 509.7f, 178.9f, 454.5f, 180.2f};
    private static final float[] REPEATER = {744.2f, 182.7f, 775.3f, 181.4f, 772.7f, 190.3f, 746.8f, 191.5f};
    private static final float[][] SEAMS = {
            {715.6f, 127.3f, 732.5f, 170.1f, 737.7f, 233.1f, 735.1f, 308.7f},                  // avant porte avant
            {443.5f, 147.4f, 445.5f, 220.5f, 448.1f, 306.8f},                                  // entre les portes
            {174f, 36.6f, 180.5f, 94.5f, 193.5f, 163.8f, 213f, 214.2f, 240.3f, 255.1f, 256.5f, 305.5f}, // arrière porte arrière
            {14.3f, 181.4f, 35.7f, 182.7f, 55.2f, 196.6f},                                     // bouclier arrière
            {935.1f, 185.2f, 949.4f, 226.8f, 959.7f, 283.5f, 962.3f, 330.7f},                  // bouclier avant
    };
    private static final float[] CREASE = {66.2f, 128f, 227.3f, 136.8f, 422.1f, 148.2f, 616.9f, 157f, 759.7f, 154.5f};
    private static final float[] LOWER = {259.7f, 277.2f, 487f, 282.2f, 733.8f, 287.3f};
    private static final float[] ROCKER = {256.5f, 306.8f, 487f, 309.9f, 737f, 315f};

    private static void drawCar(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);

        // Ombre au sol.
        p.setShader(new RadialGradient(500, 400, 520, 0xDD000000, 0x00000000, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, 0.075f, 500, 400);
        c.drawCircle(500, 400, 520, p);
        c.restore();
        p.setShader(null);

        // Passages de roue (fond sombre).
        p.setColor(0xFF0A0B0D);
        c.save();
        c.clipRect(0, 0, W, SILL + 4);
        c.drawCircle(FX, WY, ARCH - 1, p);
        c.drawCircle(RX, WY, ARCH - 1, p);
        c.restore();

        // Carrosserie.
        Path body = bodyPath();
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, 0, 0, 340, BODY, BODY_POS, Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(new LinearGradient(0, 0, W, 0,
                new int[]{0x30000000, 0x00000000, 0x00FFFFFF, 0x26FFFFFF, 0x10000000},
                new float[]{0f, .25f, .55f, .8f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(null);

        c.save();
        c.clipPath(body);
        // Bas de caisse plus sombre.
        p.setShader(new LinearGradient(0, 280, 0, 340, 0x00000000, 0x50000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 280, W, 345, p);
        p.setShader(null);
        // Nervure sous les vitres.
        Path crease = smooth(CREASE, false);
        line.setStrokeWidth(2.4f);
        line.setColor(0x80FFFFFF);
        c.drawPath(crease, line);
        crease.offset(0, 3f);
        line.setColor(0x22000000);
        c.drawPath(crease, line);
        // Creux en bas des portes, puis bas de caisse.
        Path lower = smooth(LOWER, false);
        line.setStrokeWidth(3f);
        line.setColor(0x22000000);
        c.drawPath(lower, line);
        lower.offset(0, 3f);
        line.setStrokeWidth(1.6f);
        line.setColor(0x50FFFFFF);
        c.drawPath(lower, line);
        Path rocker = smooth(ROCKER, false);
        line.setStrokeWidth(1.8f);
        line.setColor(0x90262A2F);
        c.drawPath(rocker, line);
        c.restore();

        // Lèvres des passages de roue.
        c.save();
        c.clipRect(0, 0, W, SILL);
        line.setStrokeWidth(3f);
        line.setColor(0x50000000);
        c.drawCircle(FX, WY, ARCH + 1.5f, line);
        c.drawCircle(RX, WY, ARCH + 1.5f, line);
        c.restore();

        // Joints de portes et boucliers.
        for (float[] s : SEAMS) {
            Path seam = smooth(s, false);
            line.setStrokeWidth(1.6f);
            line.setColor(0xA0262A2F);
            c.drawPath(seam, line);
            seam.offset(1.6f, 0);
            line.setColor(0x30FFFFFF);
            c.drawPath(seam, line);
        }

        // Baguettes de protection noires en bas des portes (une par porte).
        p.setPathEffect(new CornerPathEffect(4));
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, 280, 0, 312, 0xFF33373C, 0xFF0F1113, Shader.TileMode.CLAMP));
        c.drawPath(poly(new float[]{259, 280, 443, 284, 444, 307.5f, 257, 304}), p);
        c.drawPath(poly(new float[]{448, 284.5f, 734, 290, 736, 312, 449, 308}), p);
        p.setShader(null);
        p.setPathEffect(null);
        line.setStrokeWidth(1f);
        line.setColor(0x38FFFFFF);
        c.drawLine(262, 281, 441, 285, line);
        c.drawLine(451, 285.5f, 731, 291, line);

        // Antenne de toit.
        line.setStrokeWidth(2f);
        line.setColor(0xFF1A1C1F);
        c.drawLine(517, 0, 535.7f, 17.7f, line);

        // Vitres teintées dans leurs cadres noirs (montant central couleur carrosserie).
        drawWindow(c, p, line, rounded(WIN_R, 9), true);
        drawWindow(c, p, line, rounded(WIN_F, 9), false);

        // Trappe à carburant.
        Path fuel = poly(FUEL);
        p.setPathEffect(new CornerPathEffect(9));
        line.setPathEffect(new CornerPathEffect(9));
        line.setStrokeWidth(1.4f);
        line.setColor(0x90303439);
        p.setColor(0x14FFFFFF);
        c.drawPath(fuel, p);
        c.drawPath(fuel, line);
        p.setPathEffect(null);
        line.setPathEffect(null);

        // Lèvre sombre sous le bouclier avant.
        p.setColor(0xFF2A2D31);
        Path lip = new Path();
        lip.moveTo(945, 332);
        lip.lineTo(990, 328);
        lip.quadTo(980, 340, 956, 341);
        lip.close();
        c.drawPath(lip, p);

        // Feu arrière.
        Path tail = smooth(TAIL, true);
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(13, 84, 70, 178,
                new int[]{0xFFFF5A5F, 0xFFC8141F, 0xFF7A0A10}, null, Shader.TileMode.CLAMP));
        c.drawPath(tail, p);
        p.setShader(null);
        line.setStrokeWidth(1.2f);
        line.setColor(0xFF3A1215);
        c.drawPath(tail, line);
        p.setColor(0x50FFFFFF);
        Path tailShine = new Path();
        tailShine.moveTo(52, 96);
        tailShine.lineTo(56, 98);
        tailShine.lineTo(30, 172);
        tailShine.lineTo(26, 172);
        tailShine.close();
        c.drawPath(tailShine, p);

        // Phare avant (verre fumé).
        Path head = smooth(HEAD, true);
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(915, 162, 987, 207,
                new int[]{0xFFC9D0D6, 0xFF5E676F, 0xFF2C3238}, null, Shader.TileMode.CLAMP));
        c.drawPath(head, p);
        p.setShader(null);
        p.setColor(0xCCFFFFFF);
        c.drawCircle(964, 192, 5, p);
        line.setColor(0xFF2E3338);
        c.drawPath(head, line);

        // Clignotant latéral.
        p.setColor(0xFFE3E6E9);
        p.setPathEffect(new CornerPathEffect(4));
        c.drawPath(poly(REPEATER), p);
        p.setPathEffect(null);
        line.setStrokeWidth(1f);
        line.setColor(0x80303439);
        c.drawPath(poly(REPEATER), line);

        // Rétroviseur couleur carrosserie.
        Path mirror = smooth(MIRROR, true);
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, 100, 0, 146, 0xFFF0F2F4, 0xFF9AA1A8, Shader.TileMode.CLAMP));
        c.drawPath(mirror, p);
        p.setShader(null);
        line.setStrokeWidth(1.4f);
        line.setColor(0xA0303439);
        c.drawPath(mirror, line);
        p.setColor(0xFF15171A);
        c.drawRect(662, 138, 672, 147, p);

        // Poignées noires.
        drawHandle(c, p, line, poly(HANDLE_R));
        drawHandle(c, p, line, poly(HANDLE_F));
        p.setColor(0xFF6A7076);
        c.drawCircle(460, 186, 2.6f, p); // serrure

        // Roues avec enjoliveurs.
        drawWheel(c, RX, WY);
        drawWheel(c, FX, WY);
    }

    private static void drawWindow(Canvas c, Paint p, Paint line, Path win, boolean rear) {
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, 20, 0, 150,
                new int[]{0xFF46505B, 0xFF222930, 0xFF101317}, null, Shader.TileMode.CLAMP));
        c.drawPath(win, p);
        p.setShader(null);
        c.save();
        c.clipPath(win);
        Path shine = rear
                ? poly(new float[]{300, 10, 360, 10, 290, 150, 230, 150})
                : poly(new float[]{540, 10, 580, 10, 510, 160, 470, 160});
        p.setColor(0x1CFFFFFF);
        c.drawPath(shine, p);
        if (rear) {
            p.setColor(0xFF111315);
            c.drawPath(poly(PILLAR_Q), p);
        }
        c.restore();
        line.setStrokeWidth(6f);
        line.setColor(0xFF111315);
        c.drawPath(win, line);
    }

    private static void drawHandle(Canvas c, Paint p, Paint line, Path h) {
        p.setPathEffect(new CornerPathEffect(5));
        line.setPathEffect(new CornerPathEffect(5));
        p.setColor(0x40000000);
        h.offset(0, 2);
        c.drawPath(h, p);
        h.offset(0, -2);
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, 154, 0, 180, 0xFF3A3F45, 0xFF0F1113, Shader.TileMode.CLAMP));
        c.drawPath(h, p);
        p.setShader(null);
        line.setStrokeWidth(1f);
        line.setColor(0x40FFFFFF);
        c.drawPath(h, line);
        p.setPathEffect(null);
        line.setPathEffect(null);
    }

    private static Path bodyPath() {
        Path b = smooth(BODY_TOP, false);
        float dy = SILL - WY;
        float dx = (float) Math.sqrt(ARCH * ARCH - dy * dy);
        float a = (float) Math.toDegrees(Math.asin(dy / ARCH));
        b.lineTo(FX + dx, SILL);
        b.arcTo(new RectF(FX - ARCH, WY - ARCH, FX + ARCH, WY + ARCH), a, -(180 + 2 * a), false);
        b.lineTo(RX + dx, SILL);
        b.arcTo(new RectF(RX - ARCH, WY - ARCH, RX + ARCH, WY + ARCH), a, -(180 + 2 * a), false);
        b.lineTo(BODY_TOP[0], BODY_TOP[1]);
        b.close();
        return b;
    }

    private static void drawWheel(Canvas c, float cx, float cy) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Pneu.
        p.setColor(0xFF141619);
        c.drawCircle(cx, cy, TIRE, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(0xFF2A2D31);
        c.drawCircle(cx, cy, TIRE - 7, p);
        p.setStyle(Paint.Style.FILL);

        // Enjoliveur blanc-argent à 6 branches en étoile, comme celui d'origine.
        float r = TIRE * 0.72f;
        Shader silver = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFFF7F8F9, 0xFFC9CED3, 0xFFE8EBEE}, null, Shader.TileMode.CLAMP);
        p.setShader(silver);
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);

        // Creux entre les branches : triangles courbés.
        p.setColor(0xFF7C838A);
        p.setPathEffect(new CornerPathEffect(5));
        Path slot = new Path();
        slot.moveTo(cx - 4, cy - r * 0.32f);
        slot.lineTo(cx + 16, cy - r * 0.62f);
        slot.lineTo(cx + 10, cy - r * 0.9f);
        slot.lineTo(cx - 12, cy - r * 0.86f);
        slot.close();
        for (int i = 0; i < 6; i++) {
            c.save();
            c.rotate(i * 60 + 10, cx, cy);
            c.drawPath(slot, p);
            c.restore();
        }
        p.setPathEffect(null);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0xFFA4AAB0);
        c.drawCircle(cx, cy, r * 0.26f, p);
        p.setColor(0xFFFAFBFC);
        c.drawCircle(cx, cy, r - 1, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFDCE0E3);
        c.drawCircle(cx, cy, r * 0.2f, p);
    }

    /** Polygone aux coins arrondis (rayon r). */
    private static Path rounded(float[] xy, float r) {
        int n = xy.length / 2;
        Path p = new Path();
        for (int i = 0; i < n; i++) {
            float x = xy[2 * i], y = xy[2 * i + 1];
            float px = xy[2 * ((i - 1 + n) % n)], py = xy[2 * ((i - 1 + n) % n) + 1];
            float nx = xy[2 * ((i + 1) % n)], ny = xy[2 * ((i + 1) % n) + 1];
            float d1 = (float) Math.hypot(px - x, py - y), d2 = (float) Math.hypot(nx - x, ny - y);
            float a = Math.min(r, d1 / 2) / Math.max(d1, 0.01f), b = Math.min(r, d2 / 2) / Math.max(d2, 0.01f);
            float sx = x + (px - x) * a, sy = y + (py - y) * a;
            if (i == 0) p.moveTo(sx, sy);
            else p.lineTo(sx, sy);
            p.quadTo(x, y, x + (nx - x) * b, y + (ny - y) * b);
        }
        p.close();
        return p;
    }

    /** Polygone à partir de coordonnées x, y. */
    private static Path poly(float[] xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.close();
        return p;
    }

    /** Courbe lisse passant par tous les points (Catmull-Rom). */
    private static Path smooth(float[] xy, boolean closed) {
        int n = xy.length / 2;
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        int segments = closed ? n : n - 1;
        for (int i = 0; i < segments; i++) {
            int i0 = closed ? (i - 1 + n) % n : Math.max(i - 1, 0);
            int i2 = closed ? (i + 1) % n : i + 1;
            int i3 = closed ? (i + 2) % n : Math.min(i + 2, n - 1);
            float c1x = xy[2 * i] + (xy[2 * i2] - xy[2 * i0]) / 6f;
            float c1y = xy[2 * i + 1] + (xy[2 * i2 + 1] - xy[2 * i0 + 1]) / 6f;
            float c2x = xy[2 * i2] - (xy[2 * i3] - xy[2 * i]) / 6f;
            float c2y = xy[2 * i2 + 1] - (xy[2 * i3 + 1] - xy[2 * i + 1]) / 6f;
            p.cubicTo(c1x, c1y, c2x, c2y, xy[2 * i2], xy[2 * i2 + 1]);
        }
        if (closed) p.close();
        return p;
    }
}
