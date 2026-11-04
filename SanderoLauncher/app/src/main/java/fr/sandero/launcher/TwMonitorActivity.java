package fr.sandero.launcher;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Moniteur TWUtil : affiche les messages de l'autoradio reçus par Sandero.
 *
 * Sécurité : le moniteur n'ouvre AUCUN canal TWUtil. Il écoute le canal principal déjà
 * ouvert par Sandero (relais {@link HeadUnit.Raw}). L'affichage est groupé toutes les
 * 300 ms et limité, pour ne jamais saturer l'écran même si le MCU envoie en rafale.
 */
public class TwMonitorActivity extends Activity implements HeadUnit.Raw {

    private static final int MAX_LINES = 300;

    private float unit = 1f;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, log;
    private ScrollView scroll;
    private final StringBuilder all = new StringBuilder();
    private final ArrayDeque<String> shown = new ArrayDeque<>();
    private final StringBuilder pending = new StringBuilder();
    private boolean scheduled;
    private int count;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        unit = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1280f,
                Math.min(dm.widthPixels, dm.heightPixels) / 480f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF080B12);
        root.setPadding(px(20), px(14), px(20), px(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(24, MainActivity.TEXT);
        title.setText("Moniteur TWUtil");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(button("Simuler phares", 0xFF7C3AED, v -> {
            HeadUnit hu = HeadUnit.get();
            hu.simulate(Tw.MSG_ILL, hu.lightsOn ? 0 : 1);
            add("simulation : éclairage " + (hu.lightsOn ? "allumé" : "éteint"));
        }));
        top.addView(button("Simuler marche arrière", 0xFFEA580C, v -> {
            HeadUnit hu = HeadUnit.get();
            hu.simulate(Tw.MSG_REVERSE, hu.reverse ? 0 : 1);
            add("simulation : marche arrière " + (hu.reverse ? "enclenchée" : "relâchée"));
        }));
        top.addView(button("Enregistrer", 0xFF0D9488, v -> save()));
        top.addView(button("Effacer", 0xFF475569, v -> {
            all.setLength(0);
            shown.clear();
            log.setText("");
        }));
        root.addView(top);

        status = text(14, 0xFF7DD3FC);
        status.setPadding(0, px(6), 0, px(6));
        status.setText("Écoute du canal de Sandero · " + HeadUnit.get().status()
                + "\nAllumez les feux, passez la marche arrière, appuyez sur les touches du volant, lancez la radio ou la musique.");
        root.addView(status);

        scroll = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111723);
        bg.setCornerRadius(px(14));
        scroll.setBackground(bg);
        log = text(14, MainActivity.TEXT);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(px(14), px(10), px(14), px(10));
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        add("ouverture du moniteur");
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeadUnit.get().addRaw(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        HeadUnit.get().removeRaw(this);
    }

    @Override
    public void onRaw(String channel, int what, int arg1, int arg2, Object obj) {
        String d;
        try {
            d = Tw.describe(obj);
        } catch (Throwable t) {
            d = "?";
        }
        add(String.format(Locale.ROOT, "%-4s 0x%04x  arg1=%d (0x%x)  arg2=%d (0x%x)  %s",
                channel, what & 0xffff, arg1, arg1, arg2, arg2, d));
    }

    /** Ajoute une ligne ; l'écran est mis à jour par paquets. */
    private void add(String line) {
        String l = clock.format(new Date()) + "  " + line + "\n";
        if (all.length() > 2_000_000) all.delete(0, 500_000);
        all.append(l);
        pending.append(l);
        count++;
        if (!scheduled) {
            scheduled = true;
            ui.postDelayed(flush, 300);
        }
    }

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            scheduled = false;
            for (String l : pending.toString().split("\n")) {
                if (l.isEmpty()) continue;
                shown.addLast(l);
                if (shown.size() > MAX_LINES) shown.removeFirst();
            }
            pending.setLength(0);
            StringBuilder sb = new StringBuilder();
            for (String l : shown) sb.append(l).append('\n');
            log.setText(sb);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    };

    private void save() {
        try {
            File dir = getExternalFilesDir(null);
            File f = new File(dir, "twutil_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".txt");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(all.toString().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, count + " lignes enregistrées : " + f.getPath()
                    + "\n(copiable sur la clé avec l'explorateur des Outils)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Échec : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private View button(String label, int color, View.OnClickListener l) {
        TextView b = text(15, 0xFFFFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(14), 0, px(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(12));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44));
        lp.leftMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        return t;
    }

    private int px(float v) {
        return Math.round(v * unit);
    }
}
