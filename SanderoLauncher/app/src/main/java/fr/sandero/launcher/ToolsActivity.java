package fr.sandero.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Outils : copie sur clé USB des applis installées, des fichiers système lisibles
 * et de n'importe quel fichier ou dossier choisi dans l'explorateur.
 * Sans root, les partitions brutes (boot, recovery…) et /data restent inaccessibles.
 */
public class ToolsActivity extends Activity {

    private static final int REQ_TREE = 1;
    private static final int REQ_PERMS = 2;
    private static final String[] NO_RECURSE = {"/proc", "/sys", "/dev", "/acct", "/d"};

    private float unit = 1f;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView destLabel, status, pathLabel;
    private ListView list;
    private final List<File> entries = new ArrayList<>();
    private File current = new File("/");

    private Dest dest;
    private volatile boolean running, cancel;
    private long files, bytes, skipped;
    private String lastName = "";

    // ================================================================ Écran

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tools", MODE_PRIVATE);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        unit = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1280f,
                Math.min(dm.widthPixels, dm.heightPixels) / 480f);
        setContentView(buildLayout());
        getExternalFilesDirs(null); // crée les dossiers propres à l'appli sur chaque stockage
        restoreDest();
        browse(current);
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMS);
        }
    }

    @Override
    public void onBackPressed() {
        File parent = current.getParentFile();
        if (parent != null) browse(parent);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cancel = true;
        super.onDestroy();
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF080B12);
        root.setPadding(px(20), px(16), px(20), px(16));

        // --- Colonne gauche : destination + exports
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(26, MainActivity.TEXT);
        title.setText("Outils");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        left.addView(title);
        destLabel = text(14, MainActivity.TEXT_DIM);
        destLabel.setPadding(0, px(4), 0, px(10));
        left.addView(destLabel);

        left.addView(button("Choisir la clé USB", 0xFF2563EB, v -> chooseDest()));
        left.addView(button("Exporter toutes les applis (APK)", 0xFF0D9488, v -> run("applis", this::exportApps)));
        left.addView(button("Exporter infos + framework", 0xFF7C3AED, v -> run("infos", this::exportInfo)));
        left.addView(button("Exporter tout /system", 0xFFEA580C, v -> run("system", this::exportSystem)));
        left.addView(button("Sauvegarde complète (sans root)", 0xFFB45309, v -> run("complete", this::exportFull)));
        left.addView(button("Image des partitions (root)", 0xFFB91C1C, v -> askPartitionImage()));
        left.addView(button("Système et allègement", 0xFF059669,
                v -> startActivity(new Intent(this, SystemActivity.class))));
        left.addView(button("Annuler la copie", 0xFF475569, v -> cancel = true));

        // --- Véhicule : valeurs OBD2 (module ELM327)
        TextView obdT = text(14, MainActivity.TEXT_DIM);
        obdT.setText("VÉHICULE");
        obdT.setPadding(0, px(10), 0, px(8));
        left.addView(obdT);
        left.addView(button("OBD2 (valeurs du moteur, codes défaut)", 0xFF0369A1, v -> {
            Journal.log("UI", "Outils → OBD2");
            startActivity(new Intent(this, ObdActivity.class));
        }));

        // --- Affichage : thème jour / nuit
        TextView th = text(14, MainActivity.TEXT_DIM);
        th.setText("AFFICHAGE");
        th.setPadding(0, px(10), 0, px(8));
        left.addView(th);
        final TextView themeBtn = (TextView) button("", 0xFF9A3412, null);
        final Runnable paintTheme = () -> {
            String m = Theme.mode(this);
            themeBtn.setText("Thème : " + Theme.label(m) + " · maintenant " + (Theme.nightNow(this) ? "nuit" : "jour"));
        };
        paintTheme.run();
        themeBtn.setOnClickListener(v -> {
            String m = Theme.mode(this);
            String next = Theme.AUTO.equals(m) ? Theme.DAY : Theme.DAY.equals(m) ? Theme.NIGHT : Theme.AUTO;
            Theme.setMode(this, next);
            paintTheme.run();
            Toast.makeText(this, "Appliqué au retour à l'accueil", Toast.LENGTH_SHORT).show();
        });
        left.addView(themeBtn);

        // Taille des caractères de l'accueil : x1 (défaut) à x3, mémorisée.
        final TextView scaleBtn = (TextView) button("", 0xFF9A3412, null);
        final Runnable paintScale = () -> scaleBtn.setText("Taille des caractères de l'accueil : "
                + UiScale.label(UiScale.index(this)) + " (toucher pour changer)");
        paintScale.run();
        scaleBtn.setOnClickListener(v -> {
            UiScale.next(this);
            paintScale.run();
            Toast.makeText(this, "Appliqué au retour à l'accueil", Toast.LENGTH_SHORT).show();
        });
        left.addView(scaleBtn);

        // --- Journal et heure
        TextView jt = text(14, MainActivity.TEXT_DIM);
        jt.setText("JOURNAL ET HEURE");
        jt.setPadding(0, px(10), 0, px(8));
        left.addView(jt);
        left.addView(button("Voir le journal (1000 dernières actions)", 0xFF0F766E, v -> showJournal()));
        left.addView(button("Exporter le journal sur la clé", 0xFF115E59, v -> run("journal", this::exportJournal)));
        final TextView tz = (TextView) button("", 0xFF334155, null);
        final Runnable paintTz = () -> tz.setText("Fuseau d'affichage : " + ("system".equals(Clock.zoneName())
                ? "celui du système (" + java.util.TimeZone.getDefault().getID() + ")" : Clock.zoneName())
                + " · " + Clock.format("HH:mm"));
        paintTz.run();
        tz.setOnClickListener(v -> {
            Clock.setZone("system".equals(Clock.zoneName()) ? Clock.DEFAULT_TZ : "system");
            paintTz.run();
        });
        left.addView(tz);
        final TextView gps = (TextView) button("", 0xFF334155, null);
        final Runnable paintGps = () -> gps.setText("Recalage sur l'heure GPS : " + (Clock.gpsEnabled() ? "oui" : "non")
                + (Clock.gpsSynced() ? " · écart " + (Clock.offset() / 1000) + " s" : Clock.gpsEnabled() ? " · en attente du GPS" : ""));
        paintGps.run();
        gps.setOnClickListener(v -> {
            Clock.setGps(this, !Clock.gpsEnabled());
            paintGps.run();
        });
        left.addView(gps);
        left.addView(button("Réglages date et heure Android", 0xFF475569, v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_DATE_SETTINGS));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Réglage absent", Toast.LENGTH_SHORT).show();
            }
        }));

        TextView car = text(14, MainActivity.TEXT_DIM);
        car.setText("AUTORADIO · " + HeadUnit.get().status());
        car.setPadding(0, px(10), 0, px(8));
        left.addView(car);
        left.addView(button("Moniteur TWUtil (messages en direct)", 0xFFDB2777,
                v -> startActivity(new Intent(this, TwMonitorActivity.class))));
        left.addView(toggle("Intégration Topway (TWUtil)", "tw_enabled"));

        // --- Garde-fous
        boolean safeMode = Safe.safeMode(this);
        boolean blocked = Safe.disabled(this, "tw_main") || Safe.disabled(this, "tw_bt");
        if (safeMode || blocked) {
            TextView warn = text(14, 0xFFFBBF24);
            warn.setText(safeMode ? "⚠ Mode sans échec : activé après des plantages répétés (Topway et caméra coupés)."
                    : "⚠ Intégration coupée après un plantage : " + Safe.why(this, "tw_main") + Safe.why(this, "tw_bt"));
            warn.setPadding(0, px(4), 0, px(6));
            left.addView(warn);
            left.addView(button("Réactiver (redémarre Sandero)", 0xFFCA8A04, v -> {
                Safe.setSafeMode(this, false);
                Safe.enable(this, "tw_main");
                Safe.enable(this, "tw_bt");
                restartApp();
            }));
        }
        if (!Safe.crashLog(this).isEmpty()) {
            left.addView(button("Voir le journal des plantages", 0xFF52525B, v ->
                    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Plantages enregistrés")
                            .setMessage(Safe.crashLog(this))
                            .setPositiveButton("Fermer", null)
                            .show()));
        }
        left.addView(expertBtToggle());
        left.addView(toggle("Ouvrir Sandero au démarrage", "boot_start"));
        left.addView(toggle("Revenir à Sandero au réveil", "wake_front"));
        left.addView(toggle("Chasser le lanceur d'origine", "watch_launcher"));
        left.addView(button(SanderoService.usageAccess(this) ? "Accès à l'utilisation : accordé ✔"
                : "Autoriser l'accès à l'utilisation", 0xFF4338CA, v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
                Toast.makeText(this, "Activez « Sandero » dans la liste", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Réglage absent sur cet autoradio", Toast.LENGTH_LONG).show();
            }
        }));

        TextView camTitle = text(14, MainActivity.TEXT_DIM);
        camTitle.setText("CAMÉRA DE RECUL");
        camTitle.setPadding(0, px(10), 0, px(8));
        left.addView(camTitle);
        left.addView(toggle("Caméra de recul dans Sandero", "cam_in_sandero"));
        final TextView camId = (TextView) button("", 0xFF334155, null);
        final Runnable paintId = () -> {
            int id = prefs.getInt("cam_id", -1);
            camId.setText("Numéro de caméra : " + (id < 0 ? "automatique" : String.valueOf(id))
                    + "  (" + android.hardware.Camera.getNumberOfCameras() + " détectées)");
        };
        paintId.run();
        camId.setOnClickListener(v -> {
            int n = Math.max(1, android.hardware.Camera.getNumberOfCameras());
            int id = prefs.getInt("cam_id", -1) + 1;
            prefs.edit().putInt("cam_id", id >= n ? -1 : id).apply();
            paintId.run();
        });
        left.addView(camId);
        left.addView(button("Tester la caméra de recul", 0xFFEA580C, v ->
                startActivity(new Intent(this, ReverseCamActivity.class).putExtra(ReverseCamActivity.EXTRA_TEST, true))));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(px(360), ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Colonne droite : explorateur + état
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(20), 0, 0, 0);
        pathLabel = text(17, MainActivity.TEXT);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        right.addView(pathLabel);
        TextView hint = text(13, MainActivity.TEXT_DIM);
        hint.setText("Touchez un dossier pour l'ouvrir · appui long sur un fichier ou dossier pour le copier sur la clé");
        hint.setPadding(0, px(2), 0, px(6));
        right.addView(hint);

        list = new ListView(this);
        list.setDividerHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111723);
        bg.setCornerRadius(px(16));
        list.setBackground(bg);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            File f = entries.get(pos);
            if (f.isDirectory()) browse(f);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            File f = entries.get(pos);
            if (pos == 0 && current.getParentFile() != null) return true; // ligne « .. »
            confirmCopy(f);
            return true;
        });
        right.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = text(14, 0xFF7DD3FC);
        status.setPadding(0, px(8), 0, 0);
        status.setMaxLines(2);
        status.setText("Prêt.");
        right.addView(status);
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private View button(String label, int color, View.OnClickListener l) {
        TextView b = text(16, 0xFFFFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(16), 0, px(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(14));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), bg, null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
        lp.bottomMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void showJournal() {
        java.util.List<String> l = Journal.snapshot();
        StringBuilder sb = new StringBuilder();
        for (int i = l.size() - 1; i >= Math.max(0, l.size() - 300); i--) sb.append(l.get(i)).append('\n');
        TextView t = text(12, MainActivity.TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setText(sb.length() == 0 ? "(journal vide)" : sb);
        t.setPadding(px(16), px(10), px(16), px(10));
        t.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Journal · " + l.size() + " lignes (les plus récentes en haut)")
                .setView(sv)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Effacer", (d, w) -> {
                    Journal.clear();
                    Journal.log("APP", "journal effacé");
                })
                .show();
    }

    /** Journal + plantages + infos de base, dans le dossier d'export. */
    private void exportJournal(Object out) throws IOException {
        Journal.log("OUTILS", "export du journal");
        Journal.flushNow();
        String head = "Sandero · export du " + Clock.stamp() + "\n"
                + "Fuseau système : " + java.util.TimeZone.getDefault().getID()
                + " · affichage : " + Clock.zoneName() + " · écart GPS : " + (Clock.offset() / 1000) + " s\n"
                + "Autoradio : " + HeadUnit.get().status() + "\n"
                + "Mode sans échec : " + (Safe.safeMode(this) ? "oui" : "non") + "\n\n";
        writeText(out, "journal.txt", head + Journal.text());
        String crashes = Safe.crashLog(this);
        if (!crashes.isEmpty()) writeText(out, "plantages.txt", crashes);
        File ext = getExternalFilesDir(null);
        File[] mon = ext == null ? null : ext.listFiles((d, n) -> n.startsWith("twutil_"));
        if (mon != null) for (File f : mon) copyFile(f, out, f.getName());
        lastName = "journal exporté";
    }

    /** Canal Bluetooth direct : désactivé par défaut, avec avertissement. */
    private View expertBtToggle() {
        final TextView b = text(14, 0xFFFFFFFF);
        final Runnable paint = () -> {
            boolean on = prefs.getBoolean("bt_channel", false);
            b.setText((on ? "●  " : "○  ") + "Bluetooth direct (expert) : " + (on ? "oui" : "non"));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFF9A3412 : 0xFF3F3F46);
            bg.setCornerRadius(px(14));
            b.setBackground(bg);
        };
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(16), 0, px(12), 0);
        paint.run();
        b.setOnClickListener(v -> {
            if (prefs.getBoolean("bt_channel", false)) {
                prefs.edit().putBoolean("bt_channel", false).apply();
                paint.run();
                restartApp();
                return;
            }
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Bluetooth direct")
                    .setMessage("Ouvre le canal du module Bluetooth en même temps que l'appli Bluetooth Topway. "
                            + "Ça donne titre et progression directement, mais ça peut bloquer le Bluetooth de l'autoradio "
                            + "(appairage impossible) tant que Sandero tourne.\n\nPar défaut (non), Sandero pilote la musique "
                            + "Bluetooth à travers l'appli Topway, sans risque.")
                    .setPositiveButton("Activer quand même", (d, w) -> {
                        prefs.edit().putBoolean("bt_channel", true).apply();
                        paint.run();
                        restartApp();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        lp.bottomMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** Relance complète du processus (les canaux TWUtil ne se rouvrent qu'au démarrage). */
    private void restartApp() {
        Toast.makeText(this, "Redémarrage de Sandero…", Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_CANCEL_CURRENT);
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 800, pi);
        stopService(new Intent(this, SanderoService.class));
        ui.postDelayed(() -> {
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 300);
    }

    /** Interrupteur enregistré dans les préférences « tools » (actif par défaut). */
    private View toggle(final String label, final String key) {
        final TextView b = text(15, 0xFFFFFFFF);
        final Runnable paint = () -> {
            boolean on = prefs.getBoolean(key, true);
            b.setText((on ? "●  " : "○  ") + label + (on ? " : oui" : " : non"));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFF166534 : 0xFF3F3F46);
            bg.setCornerRadius(px(14));
            b.setBackground(bg);
        };
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(16), 0, px(12), 0);
        paint.run();
        b.setOnClickListener(v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, true)).apply();
            paint.run();
            if (key.equals("tw_enabled")) {
                Toast.makeText(this, "Pris en compte au prochain redémarrage de l'appli", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48));
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

    // ================================================================ Explorateur

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : newRow();
            File f = entries.get(i);
            boolean up = i == 0 && current.getParentFile() != null && f.equals(current.getParentFile());
            boolean dir = f.isDirectory();
            TextView name = (TextView) row.getChildAt(0);
            TextView info = (TextView) row.getChildAt(1);
            name.setText(up ? "⬑  .." : (dir ? "▸  " : "    ") + f.getName());
            name.setTextColor(f.canRead() ? MainActivity.TEXT : 0xFF5B6576);
            info.setText(up ? "" : dir ? (f.canRead() ? "dossier" : "verrouillé") : formatSize(f.length()));
            return row;
        }
    };

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14), px(10), px(14), px(10));
        TextView name = text(16, MainActivity.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView info = text(13, MainActivity.TEXT_DIM);
        info.setPadding(px(10), 0, 0, 0);
        row.addView(info);
        return row;
    }

    private void browse(File dir) {
        File[] children = dir.listFiles();
        if (dir.getPath().equals("/") && (children == null || children.length == 0)) {
            // Android interdit souvent de lister la racine : on propose les dossiers connus.
            List<File> known = new ArrayList<>();
            for (String p : new String[]{"/system", "/vendor", "/oem", "/odm", "/product", "/sdcard",
                    "/storage", "/mnt", "/data/app", "/data/local/tmp", "/proc", "/dev/block"}) {
                File f = new File(p);
                if (f.exists()) known.add(f);
            }
            children = known.toArray(new File[0]);
        }
        if (children == null && !dir.getPath().equals("/")) {
            Toast.makeText(this, "Accès refusé : ce dossier demande le root", Toast.LENGTH_SHORT).show();
            return;
        }
        current = dir;
        entries.clear();
        if (dir.getParentFile() != null) entries.add(dir.getParentFile());
        if (children != null) {
            Arrays.sort(children, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            entries.addAll(Arrays.asList(children));
        }
        pathLabel.setText(dir.getPath());
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private void confirmCopy(final File f) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Copier sur la clé ?")
                .setMessage(f.getPath())
                .setPositiveButton("Copier", (d, w) -> run("fichiers", out -> {
                    Object target = dest.dir(out, "fichiers");
                    String parentPath = f.getParent() == null ? "" : f.getParent();
                    for (String part : parentPath.split("/")) {
                        if (!part.isEmpty()) target = dest.dir(target, part);
                    }
                    copy(f, target);
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ================================================================ Destination (clé USB)

    private void chooseDest() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
        } catch (ActivityNotFoundException e) {
            chooseFolderDest();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_TREE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            // Sélecteur absent ou annulé : on propose les dossiers inscriptibles trouvés.
            if (res != RESULT_CANCELED) chooseFolderDest();
            return;
        }
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        prefs.edit().putString("tree", tree.toString()).remove("folder").apply();
        restoreDest();
    }

    /** Si le sélecteur Android n'existe pas sur l'autoradio : liste des dossiers où l'on peut écrire. */
    private void chooseFolderDest() {
        final List<File> found = new ArrayList<>();
        File[] appDirs = getExternalFilesDirs(null);
        if (appDirs != null) for (File f : appDirs) if (f != null) found.add(f);
        for (String base : new String[]{"/storage", "/mnt", "/mnt/media_rw", "/mnt/usb_storage", "/udisk"}) {
            File[] kids = new File(base).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("emulated") || n.equals("self") || n.equals("runtime") || n.equals("secure")
                        || n.equals("asec") || n.equals("obb") || n.equals("user") || n.equals("appfuse")) continue;
                if (k.isDirectory() && k.canWrite() && !found.contains(k)) found.add(k);
            }
        }
        if (found.isEmpty()) {
            Toast.makeText(this, "Aucune clé USB inscriptible trouvée", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(i).getPath();
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Où copier ?")
                .setItems(names, (d, w) -> {
                    prefs.edit().putString("folder", names[w]).remove("tree").apply();
                    restoreDest();
                })
                .show();
    }

    private void restoreDest() {
        String tree = prefs.getString("tree", null);
        String folder = prefs.getString("folder", null);
        if (tree != null) dest = new SafDest(getContentResolver(), Uri.parse(tree));
        else if (folder != null) dest = new FileDest(new File(folder));
        else dest = null;
        destLabel.setText(dest == null ? "Destination : aucune clé choisie" : "Destination : " + dest.describe());
    }

    // ================================================================ Exports

    private interface Task {
        void run(Object outDir) throws IOException;
    }

    private void run(final String what, final Task task) {
        Journal.log("OUTILS", "action « " + what + " »");
        final boolean needDest = !what.equals("image");
        if (needDest && dest == null) {
            Toast.makeText(this, "Choisissez d'abord la clé USB", Toast.LENGTH_SHORT).show();
            chooseDest();
            return;
        }
        if (running) {
            Toast.makeText(this, "Une copie est déjà en cours", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        cancel = false;
        files = bytes = skipped = 0;
        // Heure de Paris (Clock), pas celle du système qui est en Asia/Shanghai.
        final String folder = "SanderoExport_" + Clock.format("yyyyMMdd_HHmmss");
        ui.post(progress);
        new Thread(() -> {
            String result;
            try {
                Object out = needDest ? dest.dir(dest.root(), folder) : null;
                task.run(out);
                result = (cancel ? "Annulé. " : "Terminé : ") + files + " fichiers, " + formatSize(bytes)
                        + (skipped > 0 ? ", " + skipped + " illisibles (root nécessaire)" : "")
                        + " → " + (needDest ? folder : lastName);
            } catch (Exception e) {
                result = "Erreur : " + e.getMessage();
            }
            final String r = result;
            Journal.log("OUTILS", "« " + what + " » : " + r);
            ui.post(() -> {
                running = false;
                ui.removeCallbacks(progress);
                status.setText(r);
            });
        }, "export-" + what).start();
    }

    private final Runnable progress = new Runnable() {
        @Override
        public void run() {
            status.setText("Copie… " + files + " fichiers, " + formatSize(bytes) + " · " + lastName);
            if (running) ui.postDelayed(this, 300);
        }
    };

    /** Tous les APK installés, rangés en système / utilisateur, plus la liste des paquets. */
    private void exportApps(Object out) throws IOException {
        PackageManager pm = getPackageManager();
        Object sysDir = dest.dir(out, "applis_systeme");
        Object userDir = dest.dir(out, "applis_utilisateur");
        StringBuilder sb = new StringBuilder("paquet;nom;version;systeme;chemin\n");
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (cancel) return;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String label = String.valueOf(ai.loadLabel(pm));
            String version = "";
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                version = pi.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            sb.append(ai.packageName).append(';').append(label).append(';').append(version).append(';')
                    .append(system ? "oui" : "non").append(';').append(ai.sourceDir).append('\n');
            String base = safeName(label) + "_" + ai.packageName;
            Object target = system ? sysDir : userDir;
            copyFile(new File(ai.sourceDir), target, base + ".apk");
            if (ai.splitSourceDirs != null) {
                for (String split : ai.splitSourceDirs) {
                    copyFile(new File(split), target, base + "_" + new File(split).getName());
                }
            }
        }
        writeText(out, "liste_applis.csv", sb.toString());
    }

    /** Infos matériel + fichiers de config + /system/framework (où se trouvent les classes Topway). */
    private void exportInfo(Object out) throws IOException {
        exportInfoFiles(out);
        Object sys = dest.dir(out, "system");
        for (String d : new String[]{"framework", "etc"}) {
            if (cancel) return;
            copy(new File("/system", d), sys);
        }
    }

    /** Tout ce qui est récupérable sans root, dans un seul dossier. */
    private void exportFull(Object out) throws IOException {
        exportApps(out);
        if (cancel) return;
        exportInfoFiles(out);
        exportSettings(out);
        if (cancel) return;
        exportSystem(out);
        writeText(out, "LISEZMOI.txt", "Sauvegarde Sandero sans root du " + new Date() + "\n\n"
                + "applis_systeme / applis_utilisateur : toutes les applis (APK), réinstallables une par une.\n"
                + "infos : propriétés, partitions, services, build.prop.\n"
                + "reglages : réglages Android (system, global, secure) au moment de la sauvegarde.\n"
                + "system : copie lisible de /system (et /vendor).\n\n"
                + "Ce n'est pas une image flashable : en cas de plantage complet, il faut le firmware Topway\n"
                + "(TW11-1280x480-X, plateforme T3L) et l'outil PhoenixSuit, ou l'image des partitions faite en root.\n");
    }

    /** Réglages Android lisibles par une appli (system, global, secure). */
    private void exportSettings(Object out) throws IOException {
        Object dir = dest.dir(out, "reglages");
        String[][] tables = {{"system", "content://settings/system"}, {"global", "content://settings/global"},
                {"secure", "content://settings/secure"}};
        for (String[] t : tables) {
            StringBuilder sb = new StringBuilder();
            try (android.database.Cursor c = getContentResolver().query(Uri.parse(t[1]),
                    new String[]{"name", "value"}, null, null, null)) {
                if (c != null) while (c.moveToNext()) sb.append(c.getString(0)).append('=').append(c.getString(1)).append('\n');
            } catch (RuntimeException e) {
                sb.append("lecture impossible : ").append(e.getMessage());
            }
            writeText(dir, t[0] + ".txt", sb.toString());
        }
    }

    private void exportInfoFiles(Object out) throws IOException {
        Object info = dest.dir(out, "infos");
        writeText(info, "getprop.txt", exec("getprop"));
        writeText(info, "partitions_par_nom.txt", exec("ls -l /dev/block/by-name /dev/block/platform/*/by-name"));
        writeText(info, "services.txt", exec("service list"));
        writeText(info, "paquets.txt", exec("pm list packages -f"));
        for (String p : new String[]{"/proc/cpuinfo", "/proc/version", "/proc/mounts", "/proc/partitions",
                "/proc/meminfo", "/system/build.prop", "/vendor/build.prop", "/default.prop"}) {
            copyFile(new File(p), info, p.substring(1).replace('/', '_'));
        }
    }

    // ================================================================ Image des partitions (root)

    /** Partitions par nom (boot, system, vendor…) lues dans /dev/block/by-name. */
    private static List<String[]> partitions() {
        List<String[]> out = new ArrayList<>();
        String ls = exec("ls -l /dev/block/by-name");
        for (String line : ls.split("\n")) {
            int arrow = line.indexOf(" -> ");
            if (arrow < 0) continue;
            String[] left = line.substring(0, arrow).trim().split("\\s+");
            String name = left[left.length - 1];
            String dev = line.substring(arrow + 4).trim();
            out.add(new String[]{name, dev, String.valueOf(blockSize(dev))});
        }
        return out;
    }

    /** Taille en octets d'après /proc/partitions (blocs de 1 Ko). */
    private static long blockSize(String dev) {
        String n = new File(dev).getName();
        for (String line : exec("cat /proc/partitions").split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length == 4 && f[3].equals(n)) {
                try {
                    return Long.parseLong(f[2]) * 1024L;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private void askPartitionImage() {
        status.setText("Test du root…");
        new Thread(() -> {
            boolean root = Root.available();
            final List<String[]> parts = root ? partitions() : null;
            ui.post(() -> {
                if (!root) {
                    status.setText("Root indisponible.");
                    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Image des partitions : root nécessaire")
                            .setMessage("L'appli n'a pas accès à « su ». Deux solutions :\n\n"
                                    + "• Faire la « Sauvegarde complète (sans root) » : applis, /system et réglages.\n"
                                    + "• Faire l'image depuis le PC avec ADB : script tools/Sauvegarde_ADB.bat du dépôt Sandero "
                                    + "(l'autoradio est une build « eng » : adb root y fonctionne si ADB est activé).")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                long sys = 0, all = 0;
                for (String[] p : parts) {
                    long s = Long.parseLong(p[2]);
                    all += s;
                    if (!p[0].equalsIgnoreCase("UDISK")) sys += s;
                }
                final long sysF = sys, allF = all;
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Image des partitions (" + parts.size() + ")")
                        .setMessage("Copie brute de chaque partition (boot, recovery, system, vendor…) sur la clé USB, "
                                + "avec un fichier de contrôle MD5.\n\nSans les données : " + formatSize(sysF)
                                + "\nAvec /data (UDISK) : " + formatSize(allF)
                                + "\n\nPrévoir une clé assez grande. Ne pas couper le contact pendant la copie.")
                        .setPositiveButton("Sans les données", (d, w) -> run("image", out -> imagePartitions(parts, false)))
                        .setNeutralButton("Avec les données", (d, w) -> run("image", out -> imagePartitions(parts, true)))
                        .setNegativeButton("Annuler", null)
                        .show();
            });
        }).start();
    }

    /** Dossier de la clé USB vu par root (le chemin /storage passe par FUSE et peut être refusé). */
    private static String rootUsbDir() {
        String ls = Root.run("ls /mnt/media_rw", 10);
        if (ls == null) return null;
        for (String n : ls.split("\\s+")) {
            n = n.trim();
            if (!n.isEmpty()) return "/mnt/media_rw/" + n;
        }
        return null;
    }

    private void imagePartitions(List<String[]> parts, boolean withData) throws IOException {
        String usb = rootUsbDir();
        if (usb == null) throw new IOException("clé USB introuvable dans /mnt/media_rw");
        String dir = usb + "/SanderoImage_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        Root.run("mkdir -p " + dir, 10);
        StringBuilder readme = new StringBuilder("Image des partitions de l'autoradio, " + new Date() + "\n\n");
        long chunk = 3L * 1024 * 1024 * 1024; // FAT32 : fichiers de moins de 4 Go
        int bs = 4 * 1024 * 1024;
        for (String[] p : parts) {
            if (cancel) break;
            String name = p[0];
            long size = Long.parseLong(p[2]);
            if (!withData && name.equalsIgnoreCase("UDISK")) continue;
            lastName = name + " (" + formatSize(size) + ")";
            long blocks = (size + bs - 1) / bs;
            long perChunk = chunk / bs;
            int pieces = (int) Math.max(1, (blocks + perChunk - 1) / perChunk);
            for (int i = 0; i < pieces && !cancel; i++) {
                String file = dir + "/" + name + (pieces > 1 ? ".img.part" + i : ".img");
                String r = Root.run("dd if=/dev/block/by-name/" + name + " of=" + file + " bs=" + bs
                        + " skip=" + (i * perChunk) + " count=" + perChunk + " && sync", 3600);
                if (r == null) throw new IOException("échec de la copie de " + name);
                files++;
                bytes += Math.min(size - i * perChunk * (long) bs, chunk);
            }
            readme.append(name).append(" → ").append(p[1]).append(", ").append(formatSize(size))
                    .append(pieces > 1 ? ", en " + pieces + " morceaux (à recoller avec : copy /b)" : "").append('\n');
        }
        Root.run("cd " + dir + " && md5sum *.img* > MD5SUMS.txt", 3600);
        readme.append("\nRestauration (dernier recours, root nécessaire) :\n")
                .append("  dd if=boot.img of=/dev/block/by-name/boot\n")
                .append("Ne jamais restaurer bootloader/env/private d'un autre appareil.\n")
                .append("Si l'autoradio ne démarre plus du tout : firmware Topway + PhoenixSuit (Allwinner).\n");
        String esc = readme.toString().replace("'", "'\\''");
        Root.run("printf '%s' '" + esc + "' > " + dir + "/LISEZMOI.txt", 30);
        lastName = dir;
    }

    /** Tout ce qui est lisible dans /system (et /vendor, /oem s'ils existent). */
    private void exportSystem(Object out) throws IOException {
        copy(new File("/system"), out);
        for (String d : new String[]{"/vendor", "/oem"}) {
            File f = new File(d);
            if (!cancel && f.isDirectory() && !isSymlink(f)) copy(f, out);
        }
    }

    // ================================================================ Copie

    /** Copie un fichier ou un dossier (récursif) dans le dossier de destination. */
    private void copy(File src, Object destDir) throws IOException {
        if (cancel) return;
        if (src.isDirectory()) {
            if (isSymlink(src) || noRecurse(src)) return;
            File[] kids = src.listFiles();
            if (kids == null) {
                skipped++;
                return;
            }
            Object d = dest.dir(destDir, src.getName().isEmpty() ? "racine" : src.getName());
            for (File k : kids) {
                if (cancel) return;
                if (k.isDirectory()) copy(k, d);
                else if (!isSymlink(k)) copyFile(k, d, k.getName());
            }
        } else {
            copyFile(src, destDir, src.getName());
        }
    }

    private void copyFile(File src, Object destDir, String name) {
        lastName = src.getPath();
        try (InputStream in = new FileInputStream(src)) {
            try (OutputStream o = dest.file(destDir, name)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel) return;
                    o.write(buf, 0, n);
                    bytes += n;
                }
            }
            files++;
        } catch (IOException | SecurityException e) {
            skipped++;
        }
    }

    private void writeText(Object dir, String name, String content) throws IOException {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = dest.file(dir, name)) {
            o.write(b);
        }
        bytes += b.length;   // oublié avant : l'export du journal affichait « 0 o »
        files++;
    }

    private static String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + " 2>&1"});
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "échec : " + e;
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean noRecurse(File f) {
        String p = f.getPath();
        for (String n : NO_RECURSE) if (p.equals(n) || p.startsWith(n + "/")) return true;
        return false;
    }

    private static String safeName(String s) {
        String r = s.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return r.isEmpty() ? "appli" : r;
    }

    static String formatSize(long b) {
        if (b < 1024) return b + " o";
        if (b < 1024 * 1024) return String.format(Locale.FRANCE, "%.0f Ko", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.FRANCE, "%.1f Mo", b / 1048576.0);
        return String.format(Locale.FRANCE, "%.2f Go", b / 1073741824.0);
    }

    // ================================================================ Écriture sur la clé

    /** Destination d'écriture : dossier classique ou dossier choisi via le sélecteur Android. */
    private abstract static class Dest {
        abstract Object root();
        abstract Object dir(Object parent, String name) throws IOException;
        abstract OutputStream file(Object dir, String name) throws IOException;
        abstract String describe();
    }

    private static final class FileDest extends Dest {
        private final File base;

        FileDest(File base) { this.base = base; }

        @Override Object root() { return base; }

        @Override Object dir(Object parent, String name) throws IOException {
            File d = new File((File) parent, name);
            if (!d.isDirectory() && !d.mkdirs()) {
                File p = (File) parent;
                if (!p.isDirectory()) throw new IOException("clé USB absente ou pas encore prête (" + p + ")");
                if (!p.canWrite()) throw new IOException("clé USB en lecture seule pour Sandero (" + p + ")");
                throw new IOException("impossible de créer " + d);
            }
            return d;
        }

        @Override OutputStream file(Object dir, String name) throws IOException {
            return new FileOutputStream(new File((File) dir, name));
        }

        @Override String describe() { return base.getPath(); }
    }

    private static final class SafDest extends Dest {
        private final ContentResolver cr;
        private final Uri tree;

        SafDest(ContentResolver cr, Uri tree) {
            this.cr = cr;
            this.tree = tree;
        }

        @Override Object root() {
            return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        }

        @Override Object dir(Object parent, String name) throws IOException {
            return create((Uri) parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        }

        @Override OutputStream file(Object dir, String name) throws IOException {
            Uri u = create((Uri) dir, "application/octet-stream", name);
            OutputStream o = cr.openOutputStream(u);
            if (o == null) throw new IOException("écriture impossible : " + name);
            return o;
        }

        // Les dossiers déjà créés sont mémorisés pour ne pas créer « nom (1) ».
        private final java.util.HashMap<String, Uri> dirs = new java.util.HashMap<>();

        private Uri create(Uri parent, String mime, String name) throws IOException {
            boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            String key = parent + "/" + name;
            if (isDir && dirs.containsKey(key)) return dirs.get(key);
            try {
                Uri u = DocumentsContract.createDocument(cr, parent, mime, name);
                if (u == null) throw new IOException("création impossible : " + name);
                if (isDir) dirs.put(key, u);
                return u;
            } catch (Exception e) {
                throw new IOException("création impossible : " + name + " (" + e.getMessage() + ")");
            }
        }

        @Override String describe() {
            String id = DocumentsContract.getTreeDocumentId(tree);
            return "clé " + id;
        }
    }
}
