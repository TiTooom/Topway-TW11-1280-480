package fr.sandero.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Icônes vectorielles dessinées en code (grille 24 x 24), nettes à toutes les tailles. */
final class Icon extends Drawable {

    static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3, STOP = 4, VOL_DOWN = 5, VOL_UP = 6,
            NAV = 7, RADIO = 8, BT = 9, SETTINGS = 10, APPS = 11, MUSIC = 12, OPEN = 13,
            REW = 14, FWD = 15, SCAN = 16, POWER = 17;

    private int type;
    private int size = -1;   // taille propre (px) ; -1 = remplit la zone donnée
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Icon(int type, int color) {
        this.type = type;
        fill.setColor(color);
        fill.setPathEffect(new CornerPathEffect(1.2f));
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void setType(int t) {
        if (t != type) {
            type = t;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.centerX() - 12 * s, b.centerY() - 12 * s);
        c.scale(s, s);
        path.reset();
        switch (type) {
            case PLAY:
                poly(7.5f, 4.5f, 19.5f, 12f, 7.5f, 19.5f);
                c.drawPath(path, fill);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(6, 5, 10, 19), 1.3f, 1.3f, fill);
                c.drawRoundRect(new RectF(14, 5, 18, 19), 1.3f, 1.3f, fill);
                break;
            case PREV:
                c.drawRoundRect(new RectF(5, 5.5f, 7.5f, 18.5f), 1f, 1f, fill);
                poly(19, 5.5f, 8.5f, 12, 19, 18.5f);
                c.drawPath(path, fill);
                break;
            case NEXT:
                c.drawRoundRect(new RectF(16.5f, 5.5f, 19, 18.5f), 1f, 1f, fill);
                poly(5, 5.5f, 15.5f, 12, 5, 18.5f);
                c.drawPath(path, fill);
                break;
            case STOP:
                c.drawRoundRect(new RectF(6, 6, 18, 18), 2.2f, 2.2f, fill);
                break;
            case VOL_DOWN:
            case VOL_UP:
                poly(3, 9.5f, 7, 9.5f, 12, 5, 12, 19, 7, 14.5f, 3, 14.5f);
                c.drawPath(path, fill);
                c.drawLine(15.5f, 12, 21, 12, stroke);
                if (type == VOL_UP) c.drawLine(18.25f, 9.25f, 18.25f, 14.75f, stroke);
                break;
            case NAV:
                poly(12, 3, 19.5f, 20.5f, 12, 16.5f, 4.5f, 20.5f);
                c.drawPath(path, fill);
                break;
            case RADIO:
                c.drawRoundRect(new RectF(3, 8, 21, 20), 2.5f, 2.5f, stroke);
                c.drawLine(7, 8, 16, 3.5f, stroke);
                c.drawCircle(15.5f, 14, 2.6f, fill);
                c.drawLine(6.5f, 12, 10, 12, stroke);
                c.drawLine(6.5f, 16, 10, 16, stroke);
                break;
            case BT:
                path.moveTo(7, 7.5f);
                path.lineTo(17, 16.5f);
                path.lineTo(12, 21);
                path.lineTo(12, 3);
                path.lineTo(17, 7.5f);
                path.lineTo(7, 16.5f);
                c.drawPath(path, stroke);
                break;
            case SETTINGS:
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, 12, 12);
                    c.drawRoundRect(new RectF(10.4f, 2.2f, 13.6f, 6.5f), 0.8f, 0.8f, fill);
                    c.restore();
                }
                float w = stroke.getStrokeWidth();
                stroke.setStrokeWidth(3.6f);
                c.drawCircle(12, 12, 5.4f, stroke);
                stroke.setStrokeWidth(w);
                break;
            case APPS:
                for (int x = 0; x < 3; x++)
                    for (int y = 0; y < 3; y++)
                        c.drawCircle(6 + x * 6, 6 + y * 6, 1.9f, fill);
                break;
            case MUSIC:
                c.drawCircle(8.5f, 17.5f, 2.8f, fill);
                c.drawCircle(17.5f, 15.5f, 2.8f, fill);
                c.drawLine(11, 17.5f, 11, 6, stroke);
                c.drawLine(20, 15.5f, 20, 4, stroke);
                poly(11, 5.5f, 20, 3.5f, 20, 7, 11, 9);
                c.drawPath(path, fill);
                break;
            case REW:
                poly(11.5f, 6, 3, 12, 11.5f, 18);
                c.drawPath(path, fill);
                path.reset();
                poly(20.5f, 6, 12, 12, 20.5f, 18);
                c.drawPath(path, fill);
                break;
            case FWD:
                poly(3.5f, 6, 12, 12, 3.5f, 18);
                c.drawPath(path, fill);
                path.reset();
                poly(12.5f, 6, 21, 12, 12.5f, 18);
                c.drawPath(path, fill);
                break;
            case SCAN:
                c.drawCircle(10.5f, 10.5f, 6, stroke);
                c.drawLine(15, 15, 20, 20, stroke);
                c.drawLine(8, 10.5f, 13, 10.5f, stroke);
                break;
            case POWER:
                c.drawArc(new RectF(5, 5.5f, 19, 19.5f), -60, 300, false, stroke);
                c.drawLine(12, 3.5f, 12, 11.5f, stroke);
                break;
            case OPEN:
                path.moveTo(11, 5);
                path.lineTo(6.5f, 5);
                path.quadTo(5, 5, 5, 6.5f);
                path.lineTo(5, 17.5f);
                path.quadTo(5, 19, 6.5f, 19);
                path.lineTo(17.5f, 19);
                path.quadTo(19, 19, 19, 17.5f);
                path.lineTo(19, 13);
                c.drawPath(path, stroke);
                c.drawLine(11, 13, 19, 5, stroke);
                c.drawLine(13.5f, 5, 19, 5, stroke);
                c.drawLine(19, 5, 19, 10.5f, stroke);
                break;
        }
        c.restore();
    }

    private void poly(float... xy) {
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
    }

    @Override public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
        invalidateSelf();
    }

    /** Donne à l'icône une taille fixe : centrée, jamais agrandie au-delà (ImageView en CENTER_INSIDE). */
    Icon sized(int px) {
        size = px;
        return this;
    }

    @Override public int getIntrinsicWidth() {
        return size;
    }

    @Override public int getIntrinsicHeight() {
        return size;
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
