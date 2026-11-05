package fr.sandero.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Dacia Sandero 1 grise vue de 3/4 avant, posée sur un sol réfléchissant avec un halo de couleur.
 *
 * Image (reflet et ombre compris, fond transparent), dans res/drawable-nodpi :
 *  - car_photo.png : photo détourée par tools/car3d/PhotoCut (prioritaire si présente) ;
 *  - car_34.png    : rendu du modèle 3D par tools/car3d/Car3D (image de secours).
 * Chaque image a ses repères (phares, sol…) en fraction de l'image, relevés par l'outil
 * (tools/car3d/*_points.txt). Les phares s'allument (fondu) quand l'autoradio signale
 * l'éclairage (0x0204).
 */
final class CarView extends View {

    /** Repères d'une image (fractions largeur / hauteur). */
    private static final class Spec {
        float headNearX, headNearY, headNearW, headFarX, headFarY, headFarW, tailX, tailY;
        float beamNearX, beamNearY, beamFarX, beamFarY, poolX, poolY;
        /** Milieux de l'empreinte au sol : arrière, avant, côté proche, côté éloigné. */
        float rearX, rearY, frontX, frontY, nearX, nearY, farX, farY;
        /** Ombre à dessiner (0 si l'image a déjà la sienne), place du vide pour les faisceaux. */
        float shadow, placeX;
    }

    /** Photo détourée : avant à gauche (tools/car3d/car_photo_points.txt). */
    private static Spec photoSpec() {
        Spec s = new Spec();
        s.headNearX = 0.329f; s.headNearY = 0.432f; s.headNearW = 0.183f;
        s.headFarX = 0.038f; s.headFarY = 0.410f; s.headFarW = 0.037f;
        s.tailX = 0.970f; s.tailY = 0.222f;
        s.beamNearX = -0.023f; s.beamNearY = 0.990f; s.beamFarX = -0.370f; s.beamFarY = 0.848f;
        s.poolX = -0.187f; s.poolY = 0.891f;
        // sol : avant proche 0.498/0.821, arrière proche 0.936/0.656, avant loin 0.142/0.762, arrière loin 0.790/0.620
        s.frontX = 0.320f; s.frontY = 0.792f; s.rearX = 0.863f; s.rearY = 0.638f;
        s.nearX = 0.717f; s.nearY = 0.738f; s.farX = 0.466f; s.farY = 0.691f;
        s.shadow = 0.45f;
        s.placeX = 0.6f;    // un peu vers la droite : les faisceaux partent à gauche
        return s;
    }

    /** Rendu 3D : avant à droite (tools/car3d/car_34_points.txt). */
    private static Spec renderSpec() {
        Spec s = new Spec();
        s.headNearX = 0.752f; s.headNearY = 0.456f; s.headNearW = 0.138f;
        s.headFarX = 0.952f; s.headFarY = 0.383f; s.headFarW = 0.043f;
        s.tailX = 0.013f; s.tailY = 0.272f;
        s.beamNearX = 1.237f; s.beamNearY = 1.015f; s.beamFarX = 1.445f; s.beamFarY = 0.867f;
        s.poolX = 1.119f; s.poolY = 0.864f;
        s.rearX = 0.096f; s.rearY = 0.535f; s.frontX = 0.929f; s.frontY = 0.803f;
        s.nearX = 0.285f; s.nearY = 0.733f; s.farX = 0.643f; s.farY = 0.591f;
        s.shadow = 1f;
        s.placeX = 0.2f;
        return s;
    }

    private int accent = 0xFF19B5FE;
    private Spec spec;
    private Bitmap source, rendered;
    private float bw, bh;           // taille affichée de l'image
    private float left, top;        // position de l'image dans la vue

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Phares : 0 = éteints, 1 = allumés (animé en fondu).
    private float lights;
    private android.animation.ValueAnimator lightAnim;
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);

    CarView(Context c) {
        super(c);
    }

    void setAccent(int color) {
        accent = color;
        preparedAccent = 0;
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

    private void load() {
        if (source != null) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        int photo = getResources().getIdentifier("car_photo", "drawable", getContext().getPackageName());
        try {
            if (photo != 0) {
                source = BitmapFactory.decodeResource(getResources(), photo, o);
                spec = photoSpec();
            }
            if (source == null) {
                source = BitmapFactory.decodeResource(getResources(), R.drawable.car_34, o);
                spec = renderSpec();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            Journal.error("UI", "image de la voiture", e);
            source = null;
        }
    }

    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0) return;
        preparedFor = 0;
        load();
        if (source == null) return;
        // Un peu de place pour le faisceau des phares.
        float s = Math.min(w * 0.94f / source.getWidth(), h / (float) source.getHeight());
        bw = source.getWidth() * s;
        bh = source.getHeight() * s;
        // Image réduite une fois à la bonne taille (dessin rapide, pas de filtrage à chaque image).
        if (rendered != null && rendered != source) rendered.recycle();
        rendered = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(bw)), Math.max(1, Math.round(bh)), true);
        left = (w - bw) * spec.placeX;
        top = (h - bh) / 2f;
    }

    // Dégradés préparés une fois (pas d'allocation à chaque image : animation fluide).
    private int preparedFor = 0;
    private int preparedAccent;
    private float cx, cy, angle, rx, ry;
    private final Path coneNear = new Path(), coneFar = new Path();
    private Shader nearBeam, farBeam, poolShader, headNear, headFar, tailShader;

    private float X(float f) { return left + f * bw; }
    private float Y(float f) { return top + f * bh; }

    private void prepare() {
        int key = getWidth() * 31 + getHeight();
        if (key == preparedFor && accent == preparedAccent) return;
        boolean geometry = key != preparedFor;
        preparedFor = key;
        preparedAccent = accent;
        Spec s = spec;

        // Ellipse au sol : centre de l'empreinte, axe le long de la voiture.
        cx = (X(s.rearX) + X(s.frontX) + X(s.nearX) + X(s.farX)) / 4f;
        cy = (Y(s.rearY) + Y(s.frontY) + Y(s.nearY) + Y(s.farY)) / 4f;
        angle = (float) Math.toDegrees(Math.atan2(Y(s.frontY) - Y(s.rearY), X(s.frontX) - X(s.rearX)));
        rx = (float) Math.hypot(X(s.frontX) - X(s.rearX), Y(s.frontY) - Y(s.rearY)) / 2f;
        ry = (float) Math.hypot(X(s.nearX) - X(s.farX), Y(s.nearY) - Y(s.farY)) / 2f;

        glow.setShader(new RadialGradient(0, 0, 1f,
                new int[]{(accent & 0x00FFFFFF) | 0x99000000, (accent & 0x00FFFFFF) | 0x33000000, 0x00000000},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        if (!geometry) return;
        int sa = Math.round(0xCC * s.shadow), sb = Math.round(0x66 * s.shadow);
        shadow.setShader(new RadialGradient(0, 0, 1f,
                new int[]{sa << 24, sb << 24, 0x00000000}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));

        // Faisceaux : du phare vers le sol devant la voiture.
        cone(coneNear, X(s.headNearX), Y(s.headNearY), s.headNearW * bw * 0.35f, X(s.beamNearX), Y(s.beamNearY), bw * 0.20f);
        cone(coneFar, X(s.headFarX), Y(s.headFarY), s.headFarW * bw * 0.5f, X(s.beamFarX), Y(s.beamFarY), bw * 0.16f);
        nearBeam = new LinearGradient(X(s.headNearX), Y(s.headNearY), X(s.beamNearX), Y(s.beamNearY),
                new int[]{0x8CFFF6D8, 0x1EFFF6D8, 0x00FFF6D8}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        farBeam = new LinearGradient(X(s.headFarX), Y(s.headFarY), X(s.beamFarX), Y(s.beamFarY),
                new int[]{0x70FFF6D8, 0x16FFF6D8, 0x00FFF6D8}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        poolShader = new RadialGradient(X(s.poolX), Y(s.poolY), bw * 0.24f, 0x6EFFF3CF, 0x00FFF3CF, Shader.TileMode.CLAMP);
        headNear = new RadialGradient(X(s.headNearX), Y(s.headNearY), s.headNearW * bw * 0.75f,
                new int[]{0xFFFFFFFF, 0x78FFF1C9, 0x00FFF1C9}, new float[]{0f, .3f, 1f}, Shader.TileMode.CLAMP);
        headFar = new RadialGradient(X(s.headFarX), Y(s.headFarY), s.headFarW * bw * 1.3f,
                new int[]{0xFFFFFFFF, 0x78FFF1C9, 0x00FFF1C9}, new float[]{0f, .3f, 1f}, Shader.TileMode.CLAMP);
        tailShader = new RadialGradient(X(s.tailX), Y(s.tailY), bw * 0.035f,
                new int[]{0xE6FF3B3B, 0x5AFF1E1E, 0x00FF0000}, new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Cône lumineux : étroit au phare (demi-largeur a), large au sol (demi-largeur b). */
    private static void cone(Path p, float x0, float y0, float a, float x1, float y1, float b) {
        float dx = x1 - x0, dy = y1 - y0, l = (float) Math.hypot(dx, dy);
        if (l < 1) l = 1;
        float nx = -dy / l, ny = dx / l;
        p.reset();
        p.moveTo(x0 + nx * a, y0 + ny * a);
        p.lineTo(x1 + nx * b, y1 + ny * b);
        p.lineTo(x1 - nx * b, y1 - ny * b);
        p.lineTo(x0 - nx * a, y0 - ny * a);
        p.close();
    }

    /** Ellipse au sol (rayon 1 → rx, ry), tournée dans l'axe de la voiture. */
    private void groundEllipse(Canvas c, Paint p, float kx, float ky) {
        c.save();
        c.translate(cx, cy);
        c.rotate(angle);
        c.scale(rx * kx, ry * ky);
        c.drawCircle(0, 0, 1f, p);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rendered == null) return;
        prepare();
        Spec s = spec;

        // Halo coloré puis ombre de contact sous la voiture.
        groundEllipse(canvas, glow, 1.25f, 2.2f);
        if (s.shadow > 0) groundEllipse(canvas, shadow, 1.08f, 1.25f);

        int alpha = Math.round(255 * lights);
        if (alpha > 2) {
            // Faisceaux des phares et tache de lumière au sol.
            beam.setAlpha(alpha);
            beam.setShader(farBeam);
            canvas.drawPath(coneFar, beam);
            beam.setShader(nearBeam);
            canvas.drawPath(coneNear, beam);
            beam.setShader(poolShader);
            canvas.save();
            canvas.scale(1f, 0.3f, X(s.poolX), Y(s.poolY));
            canvas.drawCircle(X(s.poolX), Y(s.poolY), bw * 0.24f, beam);
            canvas.restore();
        }

        canvas.drawBitmap(rendered, left, top, bmp);

        if (alpha > 2) {
            // Phares et feu arrière allumés.
            lamp.setAlpha(alpha);
            lamp.setShader(headNear);
            canvas.drawCircle(X(s.headNearX), Y(s.headNearY), s.headNearW * bw * 0.75f, lamp);
            lamp.setShader(headFar);
            canvas.drawCircle(X(s.headFarX), Y(s.headFarY), s.headFarW * bw * 1.3f, lamp);
            lamp.setShader(tailShader);
            canvas.drawCircle(X(s.tailX), Y(s.tailY), bw * 0.035f, lamp);
        }
    }
}
