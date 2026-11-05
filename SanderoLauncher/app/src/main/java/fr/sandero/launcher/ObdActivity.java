package fr.sandero.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Outils → OBD2 : lecture des valeurs du véhicule par un module ELM327 (comme Torque).
 * Écran à part, pour ne pas encombrer l'accueil. Les valeurs sont gardées par {@link Obd}
 * (prêtes à être reprises plus tard sur l'accueil).
 */
public class ObdActivity extends Activity {

    private static final String[] PROTOS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C"};
    private static final String[] PROTO_NAMES = {"automatique", "SAE J1850 PWM", "SAE J1850 VPW", "ISO 9141-2",
            "ISO 14230-4 KWP (init 5 bauds)", "ISO 14230-4 KWP (init rapide)", "ISO 15765-4 CAN 11 bits 500 k",
            "ISO 15765-4 CAN 29 bits 500 k", "ISO 15765-4 CAN 11 bits 250 k", "ISO 15765-4 CAN 29 bits 250 k",
            "SAE J1939 CAN", "CAN utilisateur 1", "CAN utilisateur 2"};

    private float unit = 1f;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Obd obd = Obd.get();

    private TextView statusText, deviceBtn, linkBtn, connectBtn, protoBtn, fuelBtn, csvBtn, autoBtn, footer;
    private TextView dtcText, infoText, termText;
    private ScrollView termScroll;
    private LinearLayout grid;
    private final View[] pages = new View[4];
    private final TextView[] tabs = new TextView[4];
    private int page;
    private int gridVersion = -1;
    private final Map<Integer, TextView> tileValues = new HashMap<>();
    private BroadcastReceiver usbReceiver, btReceiver;
    private String lastRaw = "";

    // ================================================================ Écran

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("obd", MODE_PRIVATE);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        unit = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1280f,
                Math.min(dm.widthPixels, dm.heightPixels) / 480f);
        try {
            setContentView(build());
        } catch (Throwable e) {
            Journal.error("OBD", "écran", e);
            finish();
            return;
        }
        obd.csv = prefs.getBoolean("csv", false);
        obd.fuel = prefs.getInt("fuel", 0);
        Journal.log("UI", "ouverture OBD2");
        registerUsb();
        ui.post(refresh);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            if (usbReceiver != null) unregisterReceiver(usbReceiver);
        } catch (Throwable ignored) {
        }
        stopDiscovery();
        // En quittant l'écran (retour), on coupe la liaison, sauf en connexion automatique
        // (elle reste ouverte en arrière-plan). Un simple passage à l'accueil la garde toujours.
        if (isFinishing() && !prefs.getBoolean("auto", false)) obd.stop();
        super.onDestroy();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF080B12);
        root.setPadding(px(20), px(16), px(20), px(16));

        // --- Colonne gauche : liaison et réglages
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(26, MainActivity.TEXT);
        title.setText("OBD2");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        left.addView(title);
        statusText = text(14, 0xFF7DD3FC);
        statusText.setPadding(0, px(4), 0, px(10));
        left.addView(statusText);

        connectBtn = button("", 0xFF2563EB, v -> toggleConnect());
        left.addView(connectBtn);
        linkBtn = button("", 0xFF334155, v -> {
            int t = (prefs.getInt("transport", Obd.T_BT) + 1) % 3;
            prefs.edit().putInt("transport", t).apply();
            Journal.log("OBD", "liaison choisie : " + Obd.TRANSPORTS[t]);
            paintButtons();
        });
        left.addView(linkBtn);
        deviceBtn = button("", 0xFF334155, v -> chooseDevice());
        left.addView(deviceBtn);
        protoBtn = button("", 0xFF334155, v -> chooseProtocol());
        left.addView(protoBtn);
        fuelBtn = button("", 0xFF334155, v -> {
            int f = (prefs.getInt("fuel", 0) + 1) % Obd.FUELS.length;
            prefs.edit().putInt("fuel", f).apply();
            obd.fuel = f;
            paintButtons();
        });
        left.addView(fuelBtn);
        csvBtn = button("", 0xFF3F3F46, v -> {
            boolean on = !prefs.getBoolean("csv", false);
            prefs.edit().putBoolean("csv", on).apply();
            obd.csv = on;
            Journal.log("OBD", "enregistrement CSV : " + (on ? "oui" : "non"));
            paintButtons();
        });
        left.addView(csvBtn);
        autoBtn = button("", 0xFF3F3F46, v -> {
            boolean on = !prefs.getBoolean("auto", false);
            prefs.edit().putBoolean("auto", on).apply();
            Journal.log("OBD", "connexion automatique au démarrage : " + (on ? "oui" : "non"));
            if (on) Toast.makeText(this, "Au démarrage, Sandero allume le Bluetooth Android et se connecte au module "
                    + "(la musique du téléphone peut hacher quelques secondes).", Toast.LENGTH_LONG).show();
            paintButtons();
        });
        left.addView(autoBtn);
        left.addView(button("Chercher la conso (moteur tournant)", 0xFF7C3AED, v -> {
            if (obd.state != Obd.RUNNING) {
                Toast.makeText(this, "Connectez-vous d'abord au véhicule, moteur tournant", Toast.LENGTH_SHORT).show();
                return;
            }
            obd.wantProbe = true;
            Journal.log("UI", "OBD : recherche de la conso demandée");
            Toast.makeText(this, "Recherche (1 à 2 min). Faites-la au ralenti, puis une 2e fois vers 2500 tr/min, "
                    + "et exportez le journal.", Toast.LENGTH_LONG).show();
            showPage(2);
        }));
        left.addView(button("Copier un relevé dans le journal", 0xFF0F766E, v -> {
            Journal.log("OBD", "relevé : " + obd.snapshot());
            Toast.makeText(this, "Relevé ajouté au journal", Toast.LENGTH_SHORT).show();
        }));
        left.addView(button("État du Bluetooth Android", 0xFF475569, v -> btDiagnostic()));
        if (Safe.disabled(this, "obd_bt") || Safe.disabled(this, "obd_usb")) {
            left.addView(button("Réactiver la liaison (coupée après plantage)", 0xFFCA8A04, v -> {
                Safe.enable(this, "obd_bt");
                Safe.enable(this, "obd_usb");
                v.setVisibility(View.GONE);
            }));
        }
        TextView help = text(12, MainActivity.TEXT_DIM);
        help.setText("Module OBD2 « ELM327 » branché sous le volant, contact mis. "
                + "Bluetooth : appairer d'abord le module (code 1234 ou 0000). "
                + "Wi-Fi : connecter l'autoradio au réseau du module. Lecture seule : rien n'est effacé ni modifié.");
        help.setPadding(0, px(6), 0, 0);
        left.addView(help);

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(px(340), ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Colonne droite : onglets
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(18), 0, 0, 0);
        LinearLayout tabRow = new LinearLayout(this);
        String[] names = {"Valeurs", "Défauts", "Infos", "Terminal"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView t = text(16, MainActivity.TEXT);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> showPage(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(44), 1f);
            lp.rightMargin = i < 3 ? px(8) : 0;
            tabRow.addView(t, lp);
            tabs[i] = t;
        }
        right.addView(tabRow);

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = px(10);
        right.addView(content, clp);

        // Valeurs
        ScrollView gs = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        gs.addView(grid);
        pages[0] = gs;
        // Défauts
        dtcText = text(15, MainActivity.TEXT);
        pages[1] = panel(dtcText, button("Relire les codes défaut", 0xFFB45309, v -> {
            if (obd.state != Obd.RUNNING) {
                Toast.makeText(this, "Pas connecté au véhicule", Toast.LENGTH_SHORT).show();
                return;
            }
            obd.wantDtc = true;
            Journal.log("OBD", "relecture des codes défaut demandée");
        }));
        // Infos
        infoText = text(15, MainActivity.TEXT);
        infoText.setTextIsSelectable(true);
        pages[2] = panel(infoText, null);
        // Terminal
        pages[3] = buildTerminal();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        footer = text(13, MainActivity.TEXT_DIM);
        footer.setPadding(0, px(6), 0, 0);
        footer.setSingleLine(true);
        right.addView(footer);
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        showPage(0);
        paintButtons();
        return root;
    }

    private View panel(TextView body, View action) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(round(0xFF111723, 14));
        body.setPadding(px(16), px(12), px(16), px(12));
        sv.addView(body);
        l.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (action != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) action.getLayoutParams();
            lp.topMargin = px(8);
            lp.bottomMargin = 0;
            l.addView(action);
        }
        return l;
    }

    private View buildTerminal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        termScroll = new ScrollView(this);
        termScroll.setBackground(round(0xFF111723, 14));
        termText = text(13, MainActivity.TEXT);
        termText.setTypeface(Typeface.MONOSPACE);
        termText.setPadding(px(14), px(10), px(14), px(10));
        termText.setTextIsSelectable(true);
        termScroll.addView(termText);
        l.addView(termScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText ed = new EditText(this);
        ed.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16));
        ed.setTextColor(MainActivity.TEXT);
        ed.setHintTextColor(MainActivity.TEXT_DIM);
        ed.setHint("Commande ELM (ex. ATRV, 010C, 0902)");
        ed.setSingleLine(true);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ed.setImeOptions(EditorInfo.IME_ACTION_SEND);
        final Runnable send = () -> {
            String c = ed.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (c.isEmpty()) return;
            if (!obd.running()) {
                Toast.makeText(this, "Pas connecté au module", Toast.LENGTH_SHORT).show();
                return;
            }
            if (c.startsWith("04") || c.startsWith("14")) {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Effacer les codes défaut ?")
                        .setMessage("« " + c + " » efface les codes et remet à zéro les tests antipollution du calculateur. Continuer ?")
                        .setPositiveButton("Envoyer", (d, w) -> obd.terminal.add(c))
                        .setNegativeButton("Annuler", null).show();
            } else {
                obd.terminal.add(c);
            }
            ed.setText("");
        };
        ed.setOnEditorActionListener((v, id, e) -> {
            send.run();
            return true;
        });
        row.addView(ed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = button("Envoyer", 0xFF2563EB, v -> send.run());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(px(140), px(48));
        glp.leftMargin = px(8);
        go.setLayoutParams(glp);
        go.setGravity(Gravity.CENTER);
        row.addView(go);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(8);
        l.addView(row, rlp);
        return l;
    }

    private void showPage(int p) {
        page = p;
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
            tabs[i].setBackground(round(i == p ? 0xFF2563EB : 0xFF1E293B, 12));
        }
        lastRaw = "";
    }

    private void paintButtons() {
        int t = prefs.getInt("transport", Obd.T_BT);
        linkBtn.setText("Liaison : " + Obd.TRANSPORTS[t]);
        deviceBtn.setText("Module : " + deviceLabel(t));
        String proto = prefs.getString("proto", "0");
        int pi = java.util.Arrays.asList(PROTOS).indexOf(proto);
        protoBtn.setText("Protocole : " + (pi < 0 ? proto : PROTO_NAMES[pi]));
        fuelBtn.setText("Carburant (pour la conso) : " + Obd.FUELS[prefs.getInt("fuel", 0)]);
        boolean csv = prefs.getBoolean("csv", false);
        csvBtn.setText((csv ? "●  " : "○  ") + "Enregistrer en CSV : " + (csv ? "oui" : "non"));
        csvBtn.setBackground(round(csv ? 0xFF166534 : 0xFF3F3F46, 14));
        boolean auto = prefs.getBoolean("auto", false);
        autoBtn.setText((auto ? "●  " : "○  ") + "Connexion auto au démarrage : " + (auto ? "oui" : "non"));
        autoBtn.setBackground(round(auto ? 0xFF166534 : 0xFF3F3F46, 14));
        boolean on = obd.running();
        connectBtn.setText(on ? "Se déconnecter" : "Se connecter");
        connectBtn.setBackground(round(on ? 0xFFB91C1C : 0xFF2563EB, 14));
    }

    private String deviceLabel(int t) {
        if (t == Obd.T_WIFI) return prefs.getString("wifi", "192.168.0.10:35000");
        if (t == Obd.T_USB) {
            String n = prefs.getString("usb_label", "");
            return n.isEmpty() ? "à choisir" : n;
        }
        String n = prefs.getString("bt_name", "");
        return n.isEmpty() ? "à choisir" : n;
    }

    // ================================================================ Rafraîchissement (500 ms)

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            try {
                paint();
            } catch (Throwable e) {
                Journal.error("OBD", "affichage", e);
            }
            ui.postDelayed(this, 500);
        }
    };

    private void paint() {
        setIfChanged(statusText, obd.status);
        boolean on = obd.running();
        String c = on ? "Se déconnecter" : "Se connecter";
        if (!c.contentEquals(connectBtn.getText())) paintButtons();
        setIfChanged(footer, obd.state == Obd.RUNNING
                ? obd.requestsPerSec + " requêtes/s · tour " + obd.cycleMs + " ms"
                + (obd.csv && !obd.csvFile.isEmpty() ? " · CSV " + obd.csvFile : "")
                : "ELM327 · Bluetooth, Wi-Fi ou USB");

        if (page == 0) paintGrid();
        else if (page == 1) setIfChanged(dtcText, dtcReport());
        else if (page == 2) setIfChanged(infoText, infoReport());
        else {
            String r = obd.rawText();
            if (!r.equals(lastRaw)) {
                lastRaw = r;
                // Ne redescend que si l'on était déjà en bas (laisse remonter pour lire).
                boolean atBottom = termScroll.getScrollY() + termScroll.getHeight() >= termText.getHeight() - px(60);
                termText.setText(r.isEmpty() ? "(aucun échange)" : r);
                if (atBottom) termScroll.post(() -> termScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void paintGrid() {
        List<Integer> ids = obd.display;
        if (gridVersion != obd.displayVersion) {
            gridVersion = obd.displayVersion;
            grid.removeAllViews();
            tileValues.clear();
            if (ids.isEmpty()) {
                TextView t = text(16, MainActivity.TEXT_DIM);
                t.setText(obd.running() ? "Connexion en cours… les valeurs apparaîtront ici dès que le véhicule répond."
                        : "Choisissez la liaison et le module à gauche, puis « Se connecter ».\n\n"
                        + "Les valeurs lues dépendent du calculateur de la voiture : seules celles qu'il annonce sont affichées.");
                t.setPadding(px(8), px(8), px(8), px(8));
                grid.addView(t);
            }
            LinearLayout row = null;
            for (int i = 0; i < ids.size(); i++) {
                if (i % 4 == 0) {
                    row = new LinearLayout(this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96));
                    lp.bottomMargin = px(8);
                    grid.addView(row, lp);
                }
                row.addView(tile(ids.get(i)), tileLp(i % 4));
            }
            if (row != null) for (int i = ids.size() % 4; i > 0 && i < 4; i++) {
                View pad = new View(this);
                row.addView(pad, tileLp(i));
            }
        }
        for (Map.Entry<Integer, TextView> e : tileValues.entrySet()) {
            int id = e.getKey();
            String u = Obd.unit(id);
            String s = obd.format(id) + (u.isEmpty() ? "" : " " + u);
            TextView tv = e.getValue();
            // Les textes longs (états) passent sur deux lignes, en plus petit.
            boolean longText = s.length() > 14;
            float size = px(longText ? 15 : 22);
            if (tv.getTextSize() != size) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                tv.setSingleLine(!longText);
                tv.setMaxLines(longText ? 2 : 1);
            }
            setIfChanged(tv, s);
        }
    }

    private LinearLayout.LayoutParams tileLp(int col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = col < 3 ? px(8) : 0;
        return lp;
    }

    private View tile(int id) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(12), px(6), px(10), px(6));
        t.setBackground(round(id >= 0x1000 ? 0xFF13213A : 0xFF111723, 14));
        TextView n = text(13, MainActivity.TEXT_DIM);
        n.setText(Obd.name(id));
        n.setMaxLines(2);
        t.addView(n);
        TextView v = text(22, MainActivity.TEXT);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(v);
        tileValues.put(id, v);
        return t;
    }

    private String dtcReport() {
        if (obd.dtcTime == 0) return obd.running() ? "Lecture des codes après connexion au véhicule…" : "Pas connecté.";
        StringBuilder sb = new StringBuilder();
        sb.append("Voyant moteur : ").append(obd.dtcCount < 0 ? "inconnu" : obd.milOn ? "ALLUMÉ" : "éteint");
        if (obd.dtcCount >= 0) sb.append(" · ").append(obd.dtcCount).append(" code(s) annoncé(s) par le calculateur");
        sb.append("\nLu à ").append(obd.dtcStamp).append("\n\n");
        dtcSection(sb, "Codes mémorisés (mode 03)", obd.dtcStored);
        dtcSection(sb, "Codes en attente (mode 07)", obd.dtcPending);
        dtcSection(sb, "Codes permanents (mode 0A)", obd.dtcPermanent);
        sb.append("Effacement : non proposé ici (lecture seule). Possible depuis le Terminal avec « 04 », après confirmation.");
        return sb.toString();
    }

    private static void dtcSection(StringBuilder sb, String title, List<String> codes) {
        sb.append(title).append(" : ");
        if (codes.isEmpty()) sb.append("aucun\n\n");
        else {
            sb.append('\n');
            for (String c : codes) {
                String t = ObdPids.dtcText(c);
                sb.append("   ").append(c).append(t.isEmpty() ? "" : "  —  " + t).append('\n');
            }
            sb.append('\n');
        }
    }

    private String infoReport() {
        StringBuilder sb = new StringBuilder();
        line(sb, "État", obd.status);
        line(sb, "Module", obd.elmVersion);
        line(sb, "Description du module", obd.elmDevice);
        line(sb, "Tension (ATRV)", obd.voltage);
        line(sb, "Protocole", obd.protocol + (obd.protocolNum.isEmpty() ? "" : " (" + obd.protocolNum + ")"));
        line(sb, "VIN", obd.vin.isEmpty() && obd.state == Obd.RUNNING ? "non fourni par le calculateur" : obd.vin);
        line(sb, "Calibration", obd.calId);
        line(sb, "Calculateur", obd.ecuName);
        line(sb, "Norme OBD", obd.values.containsKey(0x1C) ? obd.format(0x1C) : "");
        line(sb, "Carburant (calculateur)", obd.values.containsKey(0x51) ? obd.format(0x51) : "");
        StringBuilder p = new StringBuilder();
        for (int id : obd.supported01) p.append(String.format("%02X ", id));
        line(sb, "PID mode 01 annoncés (" + obd.supported01.size() + ")", p.toString().trim());
        p.setLength(0);
        for (int id : obd.supported09) p.append(String.format("%02X ", id));
        line(sb, "PID mode 09 annoncés", p.toString().trim());
        sb.append("\nConso « calcul » : depuis le débit carburant (PID 5E) ou le carburant injecté (PID A2) "
                + "si le calculateur les donne, sinon depuis le débit d'air (essence/GPL seulement). "
                + "Si rien n'apparaît : bouton « Chercher la conso », moteur tournant.");
        if (!obd.probeReport.isEmpty()) sb.append("\n\n— Recherche de la conso —\n").append(obd.probeReport);
        return sb.toString();
    }

    private static void line(StringBuilder sb, String k, String v) {
        if (v == null || v.trim().isEmpty()) return;
        sb.append(k).append(" : ").append(v).append('\n');
    }

    // ================================================================ Connexion

    private void toggleConnect() {
        if (obd.running()) {
            Journal.log("UI", "OBD : déconnexion demandée");
            obd.stop();
            paintButtons();
            return;
        }
        int t = prefs.getInt("transport", Obd.T_BT);
        String target;
        if (t == Obd.T_WIFI) target = prefs.getString("wifi", "192.168.0.10:35000");
        else if (t == Obd.T_USB) {
            target = prefs.getString("usb", "");
            UsbDevice d = target.isEmpty() ? null : ObdUsb.find(this, target);
            if (d == null) {
                chooseDevice();
                return;
            }
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            if (um != null && !um.hasPermission(d)) {
                askUsbPermission(d);
                return;
            }
        } else {
            target = prefs.getString("bt", "");
            if (target.isEmpty()) {
                chooseDevice();
                return;
            }
        }
        Journal.log("UI", "OBD : connexion demandée (" + Obd.TRANSPORTS[t] + ")");
        obd.start(this, t, target, prefs.getString("proto", "0"));
        gridVersion = -1;
        paintButtons();
    }

    private void chooseProtocol() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Protocole OBD")
                .setItems(PROTO_NAMES, (d, w) -> {
                    prefs.edit().putString("proto", PROTOS[w]).apply();
                    Journal.log("OBD", "protocole choisi : " + PROTO_NAMES[w] + " (pris en compte à la prochaine connexion)");
                    paintButtons();
                }).show();
    }

    private void chooseDevice() {
        int t = prefs.getInt("transport", Obd.T_BT);
        if (t == Obd.T_WIFI) chooseWifi();
        else if (t == Obd.T_USB) chooseUsb();
        else chooseBt();
    }

    // ---- Wi-Fi
    private void chooseWifi() {
        final EditText ed = new EditText(this);
        ed.setText(prefs.getString("wifi", "192.168.0.10:35000"));
        ed.setSingleLine(true);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Module Wi-Fi (adresse:port)")
                .setMessage("Connectez d'abord l'autoradio au réseau Wi-Fi du module (souvent « WiFi_OBDII », sans mot de passe). "
                        + "Adresses habituelles : 192.168.0.10:35000 · 192.168.0.123:35000 · 192.168.1.10:35000")
                .setView(ed)
                .setPositiveButton("OK", (d, w) -> {
                    prefs.edit().putString("wifi", ed.getText().toString().trim()).apply();
                    paintButtons();
                })
                .setNeutralButton("Réglages Wi-Fi", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                    } catch (Throwable e) {
                        Toast.makeText(this, "Réglage absent", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null).show();
    }

    // ---- USB
    private void chooseUsb() {
        final List<UsbDevice> l = ObdUsb.devices(this);
        if (l.isEmpty()) {
            Toast.makeText(this, "Aucun périphérique USB branché", Toast.LENGTH_LONG).show();
            Journal.log("OBD", "USB : aucun périphérique");
            return;
        }
        String[] names = new String[l.size()];
        for (int i = 0; i < l.size(); i++) names[i] = ObdUsb.describe(l.get(i));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Câble OBD USB")
                .setItems(names, (d, w) -> {
                    UsbDevice dev = l.get(w);
                    prefs.edit().putString("usb", dev.getDeviceName()).putString("usb_label", names[w]).apply();
                    Journal.log("OBD", "USB choisi : " + names[w]);
                    paintButtons();
                    UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
                    if (um != null && !um.hasPermission(dev)) askUsbPermission(dev);
                }).show();
    }

    private void askUsbPermission(UsbDevice d) {
        try {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ObdUsb.ACTION_PERMISSION).setPackage(getPackageName()), 0);
            um.requestPermission(d, pi);
            Journal.log("OBD", "USB : demande de permission");
        } catch (Throwable e) {
            Journal.error("OBD", "permission USB", e);
        }
    }

    private void registerUsb() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Journal.log("OBD", "USB : permission " + (ok ? "accordée" : "refusée"));
                if (ok && !obd.running()) toggleConnect();
            }
        };
        try {
            registerReceiver(usbReceiver, new IntentFilter(ObdUsb.ACTION_PERMISSION));
        } catch (Throwable e) {
            Journal.error("OBD", "récepteur USB", e);
        }
    }

    // ---- Bluetooth (API Android standard, jamais le canal TWUtil 8)
    @SuppressLint("MissingPermission")
    private void chooseBt() {
        BluetoothAdapter a;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable e) {
            a = null;
        }
        if (a == null || !a.isEnabled()) {
            btDiagnostic();
            return;
        }
        final List<BluetoothDevice> devs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) {
                // Les modules OBD d'abord
                for (BluetoothDevice d : bonded) if (looksObd(d)) addDev(devs, labels, d, "★ ");
                for (BluetoothDevice d : bonded) if (!looksObd(d)) addDev(devs, labels, d, "");
            }
        } catch (Throwable e) {
            Journal.error("OBD", "appareils appairés", e);
        }
        Journal.log("OBD", "Bluetooth : " + devs.size() + " appareil(s) appairé(s)");
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        final BluetoothAdapter adapter = a;
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Module OBD Bluetooth")
                .setAdapter(ad, (d, w) -> {
                    stopDiscovery();
                    BluetoothDevice dev = devs.get(w);
                    String n = dev.getName() == null ? dev.getAddress() : dev.getName();
                    prefs.edit().putString("bt", dev.getAddress()).putString("bt_name", n).apply();
                    Journal.log("OBD", "Bluetooth choisi : " + n);
                    paintButtons();
                })
                .setNeutralButton("Rechercher", null)
                .setNegativeButton("Fermer", (d, w) -> stopDiscovery())
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                startDiscovery(adapter, devs, labels, ad, dlg)));
        dlg.setOnDismissListener(x -> stopDiscovery());
        dlg.show();
    }

    @SuppressLint("MissingPermission")
    private static void addDev(List<BluetoothDevice> devs, List<String> labels, BluetoothDevice d, String prefix) {
        for (BluetoothDevice x : devs) if (x.getAddress().equals(d.getAddress())) return;
        devs.add(d);
        String n = d.getName() == null ? "(sans nom)" : d.getName();
        labels.add(prefix + n + "  ·  " + d.getAddress() + (d.getBondState() == BluetoothDevice.BOND_BONDED ? "" : "  · nouveau"));
    }

    @SuppressLint("MissingPermission")
    private static boolean looksObd(BluetoothDevice d) {
        String n = d.getName() == null ? "" : d.getName().toUpperCase(Locale.ROOT);
        return n.contains("OBD") || n.contains("ELM") || n.contains("V-LINK") || n.contains("VLINK")
                || n.contains("VGATE") || n.contains("KONNWEI") || n.contains("CAR") || n.contains("SCAN");
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery(BluetoothAdapter a, List<BluetoothDevice> devs, List<String> labels,
                                ArrayAdapter<String> ad, AlertDialog dlg) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 7);
            Toast.makeText(this, "Autorisez la localisation (exigée par Android pour la recherche Bluetooth)", Toast.LENGTH_LONG).show();
            return;
        }
        stopDiscovery();
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (d == null) return;
                        addDev(devs, labels, d, looksObd(d) ? "★ " : "");
                        ad.notifyDataSetChanged();
                        Journal.log("OBD", "trouvé : " + d.getName() + " " + d.getAddress());
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) {
                        dlg.setTitle("Module OBD Bluetooth (recherche terminée)");
                    }
                } catch (Throwable e) {
                    Journal.error("OBD", "recherche", e);
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        try {
            registerReceiver(btReceiver, f);
            boolean ok = a.startDiscovery();
            dlg.setTitle(ok ? "Module OBD Bluetooth (recherche…)" : "Recherche impossible");
            Journal.log("OBD", "recherche Bluetooth : " + (ok ? "lancée" : "refusée par Android"));
        } catch (Throwable e) {
            Journal.error("OBD", "recherche Bluetooth", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopDiscovery() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null && a.isDiscovering()) a.cancelDiscovery();
        } catch (Throwable ignored) {
        }
        try {
            if (btReceiver != null) unregisterReceiver(btReceiver);
        } catch (Throwable ignored) {
        }
        btReceiver = null;
    }

    /** Ce que voit Android du Bluetooth (sur l'autoradio, le module téléphone est géré par Topway). */
    @SuppressLint("MissingPermission")
    private void btDiagnostic() {
        StringBuilder sb = new StringBuilder();
        BluetoothAdapter a = null;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable e) {
            sb.append("Erreur : ").append(e).append('\n');
        }
        boolean enabled = false;
        if (a == null) {
            sb.append("Android ne voit aucun Bluetooth. Sur cet autoradio, le Bluetooth du téléphone passe par l'appli "
                    + "Topway ; un module OBD Bluetooth n'est alors pas utilisable directement. Utilisez un module Wi-Fi ou USB.");
        } else {
            try {
                enabled = a.isEnabled();
                sb.append("Bluetooth Android : présent\nAllumé : ").append(enabled ? "oui" : "non")
                        .append("\nNom : ").append(a.getName())
                        .append("\nAppareils appairés : ").append(a.getBondedDevices() == null ? 0 : a.getBondedDevices().size());
            } catch (Throwable e) {
                sb.append("Erreur de lecture : ").append(e);
            }
            if (!enabled) sb.append("\n\nL'autoradio l'éteint à chaque démarrage. « Se connecter » (ou la connexion auto) "
                    + "le rallume tout seul ; la musique du téléphone peut hacher quelques secondes.");
        }
        Journal.log("OBD", "diagnostic Bluetooth : " + Obd.oneLine(sb.toString().replace('\n', ' ')));
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Bluetooth Android")
                .setMessage(sb.toString())
                .setPositiveButton("Fermer", null);
        final BluetoothAdapter fa = a;
        if (a != null && !enabled) {
            b.setNeutralButton("Allumer", (d, w) -> {
                try {
                    boolean ok = fa.enable();
                    Journal.log("OBD", "Bluetooth Android : allumage " + (ok ? "demandé" : "refusé"));
                } catch (Throwable e) {
                    Journal.error("OBD", "allumage Bluetooth", e);
                }
            });
        }
        b.show();
    }

    // ================================================================ Petits outils

    private TextView button(String label, int color, View.OnClickListener l) {
        TextView b = text(16, 0xFFFFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(16), 0, px(12), 0);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), round(color, 14), null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50));
        lp.bottomMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        return g;
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        return t;
    }

    private static void setIfChanged(TextView t, CharSequence s) {
        if (!s.toString().contentEquals(t.getText())) t.setText(s);
    }

    private int px(float v) {
        return Math.round(v * unit);
    }
}
