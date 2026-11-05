package fr.sandero.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Curseur de volume vertical, façon iOS / HyperOS : une capsule fine remplie depuis le bas,
 * icône haut-parleur en bas, valeur affichée pendant le glissement.
 * Glissement relatif (le doigt n'a pas besoin d'être sur le niveau) ; un appui simple
 * en haut / en bas du curseur monte / baisse d'un cran.
 */
final class VolumeSlider extends View {

    interface OnChange {
        /** {@code done} : le doigt est relevé (pour ne journaliser que la valeur finale). */
        void onVolume(int value, boolean done);
    }

    private int max = 15, value;
    private int accent = MainActivity.DEFAULT_ACCENT;
    private OnChange listener;
    private boolean dragging;
    private float downY, downFraction;
    private boolean moved;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path clip = new Path();
    private final Icon speaker = new Icon(Icon.VOL_UP, 0xFFFFFFFF);

    VolumeSlider(Context c) {
        super(c);
        track.setColor(0x1FFFFFFF);
        label.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    void setAccent(int c) {
        accent = c;
        invalidate();
    }

    /** Mise à jour depuis le système (molette, autre appli) ; ignorée pendant un glissement. */
    void setLevel(int v, int m) {
        if (dragging) return;
        m = Math.max(1, m);
        v = Math.max(0, Math.min(v, m));
        if (v != value || m != max) {
            value = v;
            max = m;
            invalidate();
        }
    }

    int level() {
        return value;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float radius = w / 2f;
        track.setColor(Theme.track);
        r.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawRect(r, track);
        float f = value / (float) max;
        float top = h - h * f;
        fill.setShader(new LinearGradient(0, top, 0, h,
                MainActivity.lighten(accent, 0.35f), accent, Shader.TileMode.CLAMP));
        c.drawRect(0, top, w, h, fill);
        c.restore();

        // Icône haut-parleur en bas, valeur en haut pendant le glissement.
        // Proportions prévues pour un curseur large (75 unités) : icône et chiffre un peu plus petits.
        int s = Math.round(w * 0.4f);
        int cx = Math.round(w / 2f);
        int iy = Math.round(h - w * 0.62f);
        speaker.setType(value == 0 ? Icon.VOL_DOWN : Icon.VOL_UP);
        speaker.setAlpha(value == 0 ? 120 : 230);
        speaker.setBounds(cx - s / 2, iy - s / 2, cx + s / 2, iy + s / 2);
        speaker.draw(c);
        if (dragging) {
            label.setTextSize(w * 0.36f);
            c.drawText(String.valueOf(value), cx, w * 0.62f, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float h = Math.max(1, getHeight());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                moved = false;
                downY = e.getY();
                downFraction = value / (float) max;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = downY - e.getY();
                if (Math.abs(dy) > h * 0.02f) moved = true;
                if (moved) set(Math.round((downFraction + dy / h) * max), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!moved) set(value + (e.getY() < h / 2 ? 1 : -1), false);
                dragging = false;
                if (listener != null) listener.onVolume(value, true);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onVolume(value, true);
                invalidate();
                return true;
        }
        return false;
    }

    private void set(int v, boolean done) {
        v = Math.max(0, Math.min(v, max));
        if (v == value) return;
        value = v;
        invalidate();
        if (listener != null) listener.onVolume(v, done);
    }
}
