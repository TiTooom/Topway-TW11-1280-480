package fr.sandero.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Système et allègement : mémoire, nettoyage, applis à désactiver, animations.
 * Sans root, les désactivations passent par la page Android de chaque appli
 * (bouton « Désactiver » / « Désinstaller »). Avec root, elles se font ici en un appui.
 */
public class SystemActivity extends Activity {

    /**
     * Applis lourdes ou inutiles en voiture, avec un conseil. Aligné sur tools/allegement_liste.txt
     * (choix du propriétaire, 09/11/2026 : Bluetooth seulement, ni navigation ni services Google).
     * Jamais les com.tw.* indispensables (service, bt, keypad, eq, carchoose, radio).
     */
    private static final String[][] CANDIDATES = {
            // paquet, conseil, niveau (1 conseillé, 2 si inutilisé, 3 prudence)
            {"com.google.android.gms", "Services Google : le plus lourd en mémoire et en processeur. Sandero n'en a pas besoin (heure par le GPS seul).", "1"},
            {"com.google.android.gsf", "Cadre des services Google (va avec les services Google).", "1"},
            {"com.google.android.partnersetup", "Configuration partenaire Google.", "1"},
            {"com.google.android.apps.maps", "Google Maps (a besoin des services Google).", "2"},
            {"com.waze", "Waze.", "2"},
            {"com.tw.assistivetouch", "Menu flottant Topway : une couche de plus par-dessus l'écran.", "1"},
            {"com.tw.music", "Lecteur musique USB Topway (la musique Bluetooth n'en dépend pas).", "2"},
            {"com.tw.video", "Lecteur vidéo USB Topway.", "2"},
            {"com.tw.auxin", "Entrée AUX/AV Topway.", "2"},
            {"com.txznet.txz", "Assistant vocal TXZ : tourne en permanence et consomme beaucoup. Inutile si tu ne parles pas à l'autoradio.", "1"},
            {"com.txznet.smartadapter", "Module vocal PODOFO (va avec TXZ).", "1"},
            {"com.google.android.googlequicksearchbox", "Appli Google / Assistant : lourde et toujours active.", "1"},
            {"net.easyconn", "CarbitLink (recopie du téléphone) : à retirer si tu ne t'en sers pas.", "2"},
            {"com.android.chrome", "Navigateur Chrome. Les applis gardent Android System WebView.", "2"},
            {"com.google.android.youtube", "YouTube.", "2"},
            {"com.here.app.maps", "HERE WeGo (si tu utilises Waze ou Maps).", "2"},
            {"com.androits.gps.test.pro", "GPS Test.", "2"},
            {"org.prowl.torque", "Torque (OBD).", "2"},
            {"com.ex.dabplayer.pad", "DAB+ (inutile sans antenne DAB).", "2"},
            {"com.spotify.music", "Spotify.", "2"},
            {"com.artifex.mupdf", "Lecteur du manuel PDF.", "2"},
            {"com.android.printspooler", "Impression : inutile en voiture.", "1"},
            {"com.android.printservice.recommendation", "Impression : inutile en voiture.", "1"},
            {"com.android.bookmarkprovider", "Favoris du navigateur.", "1"},
            {"com.android.dreams.basic", "Écrans de veille.", "1"},
            {"com.android.wallpaper.livepicker", "Fonds d'écran animés.", "1"},
            {"com.android.emergency", "Infos d'urgence (téléphone).", "1"},
            {"com.android.companiondevicemanager", "Montres connectées.", "2"},
            {"com.android.bluetoothmidiservice", "Bluetooth MIDI (instruments).", "1"},
            {"com.svox.pico", "Synthèse vocale Pico (utile seulement pour la voix).", "2"},
            {"com.abupdate.fota_demo_iot", "Mises à jour en ligne : vérifie le réseau en fond. À laisser si tu attends une mise à jour Topway.", "3"},
            {"com.android.vending", "Play Store : ses mises à jour automatiques ralentissent. À désactiver après avoir installé tes applis.", "3"},
            {"com.android.launcher", "Lanceur d'origine Topway : ÉTAPE FINALE, seulement quand Sandero est l'écran d'accueil par défaut.", "3"},
    };

    private float unit = 1f;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView state, rootState, animState;
    private final List<String[]> rows = new ArrayList<>();
    private BaseAdapter adapter;
    private boolean root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        unit = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1280f,
                Math.min(dm.widthPixels, dm.heightPixels) / 480f);
        setContentView(buildLayout());
        new Thread(() -> {
            boolean r = Root.available();
            ui.post(() -> {
                root = r;
                String su = Root.suPath();
                rootState.setText(r ? "Root : disponible ✔ (tout se fait ici)"
                        : "Root : non" + (su != null ? " (su présent : " + su + ", mais refusé à l'appli)" : " (su absent)")
                        + "\nLes désactivations passent par la page Android de chaque appli.");
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        reloadRows();
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF080B12);
        root.setPadding(px(20), px(16), px(20), px(16));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(26, MainActivity.TEXT);
        title.setText("Système");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        left.addView(title);
        state = text(14, MainActivity.TEXT_DIM);
        state.setPadding(0, px(4), 0, px(6));
        left.addView(state);
        rootState = text(13, 0xFF7DD3FC);
        rootState.setText("Root : test en cours…");
        rootState.setPadding(0, 0, 0, px(10));
        left.addView(rootState);

        left.addView(button("Libérer la mémoire", 0xFF0D9488, v -> freeMemory()));
        left.addView(button("Animations plus rapides (0,5x)", 0xFF2563EB, v -> setAnimations(0.5f)));
        left.addView(button("Animations désactivées (0x)", 0xFF1D4ED8, v -> setAnimations(0f)));
        left.addView(button("Animations normales (1x)", 0xFF475569, v -> setAnimations(1f)));
        animState = text(13, MainActivity.TEXT_DIM);
        animState.setPadding(0, 0, 0, px(8));
        left.addView(animState);
        left.addView(button("Options pour les développeurs", 0xFF7C3AED, v -> openDevSettings()));

        ScrollView ls = new ScrollView(this);
        ls.addView(left);
        root.addView(ls, new LinearLayout.LayoutParams(px(380), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(20), 0, 0, 0);
        TextView h = text(17, MainActivity.TEXT);
        h.setText("Applis à désactiver pour alléger");
        right.addView(h);
        TextView hint = text(13, MainActivity.TEXT_DIM);
        hint.setText("Vert = conseillé · Orange = si tu ne t'en sers pas · Rouge = prudence. Touchez une ligne pour la désactiver ou la réactiver.");
        hint.setPadding(0, px(2), 0, px(6));
        right.addView(hint);

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111723);
        bg.setCornerRadius(px(16));
        list.setBackground(bg);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : newRow();
                String[] r = rows.get(i);
                TextView name = (TextView) row.getChildAt(0);
                TextView note = (TextView) row.getChildAt(1);
                int color = "1".equals(r[2]) ? 0xFF4ADE80 : "2".equals(r[2]) ? 0xFFFBBF24 : 0xFFF87171;
                boolean enabled = "on".equals(r[4]);
                name.setText((enabled ? "●  " : "○  ") + r[3] + (enabled ? "" : "   (désactivée)"));
                name.setTextColor(enabled ? color : 0xFF6B7280);
                note.setText(r[1]);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> toggleApp(rows.get(pos)));
        right.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(px(14), px(8), px(14), px(8));
        TextView name = text(16, MainActivity.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name);
        TextView note = text(13, MainActivity.TEXT_DIM);
        note.setMaxLines(2);
        note.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(note);
        return row;
    }

    // ================================================================ État

    private void refreshState() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        state.setText(String.format(Locale.FRANCE, "Mémoire libre : %s / %s%s\nStockage libre : %s / %s",
                ToolsActivity.formatSize(mi.availMem), ToolsActivity.formatSize(mi.totalMem),
                mi.lowMemory ? "  ⚠ saturée" : "",
                ToolsActivity.formatSize(fs.getAvailableBytes()), ToolsActivity.formatSize(fs.getTotalBytes())));
        float a = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        float w = Settings.Global.getFloat(getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
        animState.setText(String.format(Locale.FRANCE, "Animations actuelles : fenêtres %.1fx, transitions %.1fx", w, a));
    }

    private void reloadRows() {
        rows.clear();
        PackageManager pm = getPackageManager();
        for (String[] c : CANDIDATES) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(c[0], PackageManager.GET_DISABLED_COMPONENTS);
                String label = String.valueOf(ai.loadLabel(pm));
                rows.add(new String[]{c[0], c[1], c[2], label, ai.enabled ? "on" : "off",
                        (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "sys" : "user"});
            } catch (PackageManager.NameNotFoundException ignored) {
                // pas installée : rien à faire
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ================================================================ Actions

    /** Arrête les applis en arrière-plan qui ne servent pas à la conduite. */
    private void freeMemory() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        PackageManager pm = getPackageManager();
        int n = 0;
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String p = ai.packageName;
            // On garde : Sandero, Topway, Android, claviers, Z-Link. Plus d'exception pour Google,
            // Waze ni Maps (non utilisés, 09/11/2026).
            if (p.equals(getPackageName()) || p.startsWith("com.tw.") || p.equals("android")
                    || p.startsWith("com.android.systemui") || p.startsWith("com.android.phone")
                    || p.startsWith("com.android.bluetooth") || p.contains("inputmethod")
                    || p.contains("zlink")) {
                continue;
            }
            am.killBackgroundProcesses(p);
            n++;
        }
        final long freedBase = before.availMem;
        final int count = n;
        ui.postDelayed(() -> {
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            long gain = after.availMem - freedBase;
            Journal.log("SYS", "libérer la mémoire : " + count + " applis arrêtées, gain "
                    + ToolsActivity.formatSize(Math.max(0, gain)));
            Toast.makeText(this, count + " applis arrêtées en arrière-plan · "
                    + (gain > 0 ? ToolsActivity.formatSize(gain) + " libérés" : "mémoire déjà propre"), Toast.LENGTH_LONG).show();
            refreshState();
        }, 1500);
    }

    private void toggleApp(final String[] r) {
        final boolean enabled = "on".equals(r[4]);
        if (root) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle((enabled ? "Désactiver " : "Réactiver ") + r[3] + " ?")
                    .setMessage(r[1] + "\n\nRéversible à tout moment depuis cet écran.")
                    .setPositiveButton(enabled ? "Désactiver" : "Réactiver", (d, w) -> new Thread(() -> {
                        String out = Root.run("pm " + (enabled ? "disable-user --user 0 " : "enable ") + r[0], 20);
                        Journal.log("SYS", (enabled ? "désactiver " : "réactiver ") + r[0] + " : "
                                + (out == null ? "échec" : out.trim()));
                        ui.post(() -> {
                            Toast.makeText(this, out == null ? "Échec" : out.trim(), Toast.LENGTH_SHORT).show();
                            reloadRows();
                        });
                    }).start())
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }
        String how = "user".equals(r[5]) ? "« Désinstaller »" : "« Désactiver »";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(r[3])
                .setMessage(r[1] + "\n\nLa page Android de l'appli va s'ouvrir : appuyez sur " + how
                        + " (ou « Activer » pour revenir en arrière), puis sur Retour.")
                .setPositiveButton("Ouvrir", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + r[0])));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "Page introuvable", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void setAnimations(final float scale) {
        if (!root) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Réglage manuel")
                    .setMessage("Sans root, ce réglage se fait dans les Options pour les développeurs :\n\n"
                            + "1. Paramètres → À propos → touchez 7 fois « Numéro de build » (si les options ne sont pas encore visibles).\n"
                            + "2. Options pour les développeurs → « Échelle animation fenêtres », « Échelle animation transitions » et « Échelle durée animation » → "
                            + (scale == 0 ? "Animation désactivée" : scale == 1 ? "1x" : "0,5x") + ".")
                    .setPositiveButton("Ouvrir les options", (d, w) -> openDevSettings())
                    .setNegativeButton("Fermer", null)
                    .show();
            return;
        }
        new Thread(() -> {
            String s = String.format(Locale.ROOT, "%.1f", scale);
            Root.run("settings put global window_animation_scale " + s
                    + "; settings put global transition_animation_scale " + s
                    + "; settings put global animator_duration_scale " + s, 10);
            ui.post(this::refreshState);
        }).start();
    }

    private void openDevSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                Toast.makeText(this, "Touchez 7 fois « Numéro de build » pour activer les options", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Paramètres introuvables", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ================================================================ Utilitaires

    private View button(String label, int color, View.OnClickListener l) {
        TextView b = text(16, 0xFFFFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(16), 0, px(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(14));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50));
        lp.bottomMargin = px(8);
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
