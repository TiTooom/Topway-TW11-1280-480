package fr.sandero.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Barre de progression du morceau. Un appui dessus déplace la lecture. */
final class SeekLine extends View {

    interface OnSeek {
        void seek(float fraction);
    }

    private float fraction;
    private int accent = 0xFF19B5FE;
    private OnSeek onSeek;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    SeekLine(Context c) {
        super(c);
        track.setColor(0x26FFFFFF);
        knob.setColor(0xFFFFFFFF);
    }

    void setFraction(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (Math.abs(f - fraction) > 0.0005f) {
            fraction = f;
            invalidate();
        }
    }

    void setAccent(int c) {
        accent = c;
        invalidate();
    }

    void setOnSeek(OnSeek l) {
        onSeek = l;
    }

    @Override
    protected void onDraw(Canvas c) {
        track.setColor(Theme.track);
        knob.setColor(Theme.night ? 0xFFFFFFFF : 0xFFFFFAF3);
        float h = getHeight() * 0.28f;
        float knobR = h * 1.25f;
        float left = knobR, right = getWidth() - knobR;
        float cy = getHeight() / 2f;
        r.set(left, cy - h / 2, right, cy + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, track);
        float x = left + (right - left) * fraction;
        fill.setShader(new LinearGradient(left, 0, Math.max(x, left + 1), 0,
                MainActivity.lighten(accent, 0.45f), accent, Shader.TileMode.CLAMP));
        r.set(left, cy - h / 2, x, cy + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        c.drawCircle(x, cy, knobR, knob);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (onSeek == null) return false;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float knobR = getHeight() * 0.28f * 1.25f;
            float f = (e.getX() - knobR) / Math.max(1f, getWidth() - 2 * knobR);
            onSeek.seek(Math.max(0f, Math.min(1f, f)));
        }
        return true;
    }
}
