package fr.sandero.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Écran d'accueil de l'autoradio (1280 x 480) :
 * - à gauche : heure, date, la Sandero (phares animés selon l'éclairage) et le dock ;
 * - à droite : la carte audio avec trois sources pilotées sans quitter l'appli :
 *   Radio et Bluetooth (via TWUtil, l'API Topway) et Applis (Spotify, YouTube… via MediaSession).
 */
public class MainActivity extends Activity implements HeadUnit.Listener {

    static final int TEXT = 0xFFF3F6FA;
    static final int TEXT_DIM = 0xFF8E9AAC;
    static final int GLASS = 0x14FFFFFF;
    static final int GLASS_STROKE = 0x1FFFFFFF;
    static final int DEFAULT_ACCENT = 0xFF19B5FE;
    private static final int RADIO_ACCENT = 0xFFF59E0B;
    private static final int BT_ACCENT = 0xFF3B82F6;

    private static final int MODE_RADIO = 0, MODE_BT = 1, MODE_APPS = 2;
    private static final String[] MODE_NAMES = {"Radio", "Bluetooth", "Applis"};
    private static final int[] MODE_ICONS = {Icon.RADIO, Icon.BT, Icon.MUSIC};

    // Dock : Navigation et Réglages lancent une appli (appui long pour la choisir) ;
    // Radio et Bluetooth basculent la carte audio (appui long : appli Topway d'origine).
    private static final int SLOT_NAV = 0, SLOT_RADIO = 1, SLOT_BT = 2, SLOT_SETTINGS = 3;
    private static final String[] SLOT_NAMES = {"Navigation", "Radio", "Bluetooth", "Réglages"};
    private static final int[] SLOT_ICONS = {Icon.NAV, Icon.RADIO, Icon.BT, Icon.SETTINGS};
    private static final int[][] SLOT_COLORS = {
            {0xFF2DD4BF, 0xFF0891B2},
            {0xFFFBBF24, 0xFFEA580C},
            {0xFF60A5FA, 0xFF4F46E5},
            {0xFF94A3B8, 0xFF475569},
    };
    private static final int[] APPS_COLORS = {0xFFE879F9, 0xFF9333EA};
    private static final String[][] SLOT_KEYWORDS = {
            {"waze", "maps", "here wego", "navigation", "navi"},
            {"com.tw.radio", "radio", "收音机"},
            {"com.tw.bt", "bt music", "btmusic", "bluetooth music", "蓝牙音乐", "bluetooth", "蓝牙"},
            {"com.android.settings", "settings", "paramètres", "réglages", "设置"},
    };

    private SharedPreferences prefs;
    private float unit = 1f;
    private float ts = 1f;   // taille des caractères choisie dans Outils (x1 à x3), voir UiScale
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog openDialog;
    private AudioManager audio;
    private HeadUnit hu;
    private volatile List<App> appCache = new ArrayList<>();

    // Vues
    private LinearLayout dock;
    private CarView car;
    private GradientDrawable cardBg, playBg, artBg;
    private ImageView art;
    private TextView title, artist, album, elapsed, duration, volumeLabel;
    private VolumeSlider volume;
    private TextView btPillText;
    private GradientDrawable btDot;
    private int lastVolume = -1;
    private boolean volumeByUs;
    private SeekLine seek;
    private View timeRow;
    private LinearLayout presetRow;
    private final TextView[] presetViews = new TextView[6];
    private final LinearLayout[] modeChips = new LinearLayout[3];
    private final Icon[] ctlIcons = new Icon[5];
    private Icon playIcon;

    // État
    private int mode = MODE_APPS;
    private int lastSource = -2;
    private boolean lastLights;
    private MediaSessionManager sessions;
    private MediaController controller;
    private MediaController btController;   // session média de l'appli Bluetooth Topway, si active
    private boolean listenerAllowed;
    private Bitmap lastArt;
    private String artFor = "";
    private int accent = Theme.accentDefault, appsAccent = Theme.accentDefault;
    private ValueAnimator accentAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dock", MODE_PRIVATE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        hu = HeadUnit.get();
        if (getSharedPreferences("tools", MODE_PRIVATE).getBoolean("tw_enabled", true)) hu.start(this);
        SanderoService.start(this);
        // Heure GPS : autorisation de localisation demandée une fois.
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 2);
        }
        // Caméra de recul intégrée : autorisation demandée une fois.
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1);
        }

        // Tout est dimensionné pour un écran 1280 x 480, puis mis à l'échelle.
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        unit = Math.min(w / 1280f, h / 480f);
        ts = UiScale.get(this);
        if (ts != 1f) Journal.log("UI", "taille des caractères " + UiScale.label(UiScale.index(this)));

        mode = prefs.getInt("mode", MODE_BT);
        if (mode == MODE_APPS) mode = MODE_BT; // Applis et Bluetooth réunis : le téléphone envoie tout son son
        Theme.apply(Theme.nightNow(this));
        accent = appsAccent = Theme.accentDefault;
        Journal.log("THEME", "thème " + (Theme.night ? "NUIT" : "JOUR") + " appliqué · réglage : "
                + Theme.label(Theme.mode(this)) + " · " + Clock.format("HH:mm"));
        getWindow().setStatusBarColor(Theme.statusBar);
        getWindow().setNavigationBarColor(Theme.night ? 0xFF0E1116 : Theme.bgBottom);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(Theme.night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setContentView(buildLayout());
        applyAccent(accent);
        setMode(mode, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Journal.log("UI", "accueil affiché (source " + MODE_NAMES[mode] + ")");
        refreshDock();
        // Liste des applis rechargée en arrière-plan : pas de blocage au retour à l'accueil.
        new Thread(() -> {
            List<App> fresh = loadApps();
            for (App a : fresh) icon(a);
            handler.post(() -> {
                appCache = fresh;
                refreshDock();
            });
        }, "apps").start();
        hu.addListener(this);
        try {
            registerReceiver(volumeReceiver, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (Throwable t) {
            Journal.error("VOL", "écoute du volume impossible", t);
        }
        BtInfo.ask();
        connectMedia();
        onHeadUnitChanged();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Journal.log("UI", "accueil masqué");
        handler.removeCallbacks(ticker);
        hu.removeListener(this);
        try {
            unregisterReceiver(volumeReceiver);
        } catch (Throwable ignored) {
        }
        disconnectMedia();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissDialog();
    }

    @Override
    public void onBackPressed() {
        // Écran d'accueil : le bouton retour ne fait rien.
        dismissDialog();
    }

    // ================================================================ Mise en page

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Theme.bgTop, Theme.bgMid, Theme.bgBottom}));
        root.setPadding(px(26), px(20), px(20), px(20));

        root.addView(buildLeftColumn(), new LinearLayout.LayoutParams(px(410), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = px(18);
        root.addView(buildMusicCard(), lp);
        return root;
    }

    private View buildLeftColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView time = new Clock.View(this, "HH:mm");
        time.setTextColor(Theme.text);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(96 * sc(1.5f)));
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setLetterSpacing(-0.02f);
        col.addView(time);

        TextView date = new Clock.View(this, "EEEE d MMMM");
        date.setAllCaps(true);
        date.setLetterSpacing(0.14f);
        date.setTextColor(Theme.dim);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17 * sc(1.5f)));
        date.setPadding(px(4), px(4), 0, 0);
        col.addView(date);

        car = new CarView(this);
        LinearLayout.LayoutParams carLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        carLp.topMargin = px(6);
        car.setOnLongClickListener(v -> {
            openTools();
            return true;
        });
        col.addView(car, carLp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(dock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private View buildMusicCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        cardBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Theme.cardA, Theme.cardB});
        cardBg.setCornerRadius(px(30));
        cardBg.setStroke(Math.max(1, px(1)), Theme.stroke);

        // --- Haut : choix de la source + volume
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 2; i++) {
            final int m = i;
            modeChips[i] = chip(MODE_ICONS[i], MODE_NAMES[i]);
            modeChips[i].setOnClickListener(v -> setMode(m, true));
            top.addView(modeChips[i]);
        }
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(buildBtPill());
        card.addView(top);

        // --- Milieu : pochette + infos
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Theme.artA, Theme.artB});
        artBg.setCornerRadius(px(22));
        art.setBackground(artBg);
        art.setClipToOutline(true);
        art.setElevation(px(12));
        art.setOnClickListener(v -> onInfoTapped());
        middle.addView(art, new LinearLayout.LayoutParams(px(196), px(196)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(30), 0, 0, 0);
        info.setOnClickListener(v -> onInfoTapped());
        title = text(36, Theme.text, 2.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        marquee(title);
        info.addView(title);
        artist = text(23, Theme.artist, 2.2f);
        artist.setSingleLine(true);
        marquee(artist);
        artist.setPadding(0, px(6), 0, 0);
        info.addView(artist);
        album = text(18, Theme.dim, 1.6f);
        album.setSingleLine(true);
        marquee(album);
        album.setPadding(0, px(4), 0, 0);
        info.addView(album);

        seek = new SeekLine(this);
        seek.setOnSeek(this::seekTo);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(24));
        seekLp.topMargin = px(18);
        info.addView(seek, seekLp);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        elapsed = text(15, Theme.dim, 1.5f);
        duration = text(15, Theme.dim, 1.5f);
        duration.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(duration, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        timeRow = times;
        info.addView(times);

        // Présélections radio (à la place de la barre de progression).
        presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < presetViews.length; i++) {
            final int index = i;
            TextView p = text(15, Theme.text, 1.6f);
            p.setGravity(Gravity.CENTER);
            p.setSingleLine(true);
            p.setEllipsize(TextUtils.TruncateAt.END);
            p.setOnClickListener(v -> hu.radioPreset(index));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ph(46), 1f);
            p.setMinHeight(px(46));
            plp.setMargins(i == 0 ? 0 : px(4), 0, i == presetViews.length - 1 ? 0 : px(4), 0);
            presetRow.addView(p, plp);
            presetViews[i] = p;
        }
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = px(16);
        info.addView(presetRow, prLp);
        middle.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Bas : 5 commandes, dont le rôle change selon la source
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        int[] sizes = {72, 86, 112, 86, 72};
        int[] iconSizes = {32, 40, 52, 40, 32};
        for (int i = 0; i < 5; i++) {
            final int slot = i;
            if (i == 2) {
                playIcon = new Icon(Icon.PLAY, 0xFFFFFFFF);
                ctlIcons[i] = playIcon;
                playBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6FD3FF, Theme.accentDefault});
                playBg.setShape(GradientDrawable.OVAL);
                View play = roundButton(playIcon, sizes[i], iconSizes[i], 0, v -> control(slot, false));
                play.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), playBg, null));
                play.setElevation(px(14));
                controls.addView(play);
            } else {
                ctlIcons[i] = new Icon(Icon.STOP, Theme.text);
                View b = roundButton(ctlIcons[i], sizes[i], iconSizes[i], Theme.glass, v -> control(slot, false));
                b.setOnLongClickListener(v -> {
                    control(slot, true);
                    return true;
                });
                controls.addView(b);
            }
        }
        card.addView(controls);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setPadding(px(30), px(20), px(18), px(20));
        outer.setBackground(cardBg);
        outer.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        volume = new VolumeSlider(this);
        volume.setOnChange(this::onVolumeSlider);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(px(75), ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.leftMargin = px(16);
        outer.addView(volume, vlp);
        return outer;
    }

    /** Pastille « téléphone connecté » en haut à droite (appui : appli Bluetooth Topway). */
    private View buildBtPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(px(14), 0, px(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.glass);
        bg.setCornerRadius(px(22));
        bg.setStroke(Math.max(1, px(1)), Theme.stroke);
        pill.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null));
        View dot = new View(this);
        btDot = new GradientDrawable();
        btDot.setShape(GradientDrawable.OVAL);
        btDot.setColor(0xFF6B7280);
        dot.setBackground(btDot);
        pill.addView(dot, new LinearLayout.LayoutParams(px(10), px(10)));
        btPillText = text(15, Theme.text);
        btPillText.setSingleLine(true);
        btPillText.setEllipsize(TextUtils.TruncateAt.END);
        btPillText.setMaxWidth(px(300 * Math.min(ts, 2f)));
        btPillText.setPadding(px(10), 0, 0, 0);
        pill.addView(btPillText);
        pill.setOnClickListener(v -> {
            Journal.log("UI", "pastille Bluetooth → appli Bluetooth Topway");
            BtInfo.ask();
            launchSlot(SLOT_BT);
        });
        pill.setMinimumHeight(px(44));
        pill.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ph(44)));
        updateBtPill();
        return pill;
    }

    private void updateBtPill() {
        if (btPillText == null) return;
        String text;
        int color;
        if (!TextUtils.isEmpty(hu.btDevice) && hu.btConn != 0) {
            text = hu.btDevice + " · " + (hu.btConn == 1 ? "connexion…" : "connecté");
            color = hu.btConn == 1 ? 0xFFFBBF24 : 0xFF4ADE80;
        } else if (hu.btConn == 0) {
            text = "Aucun téléphone · déconnecté";
            color = 0xFF6B7280;
        } else if (hu.btConn == 2) {
            text = "Téléphone connecté";
            color = 0xFF4ADE80;
        } else {
            text = "Bluetooth";
            color = 0xFF6B7280;
        }
        setIfChanged(btPillText, text);
        btDot.setColor(color);
    }

    // ================================================================ Volume

    /**
     * Le curseur règle-t-il le volume de l'autoradio (comme le bouton de façade et la barre de son
     * Topway) ? Oui dès que TWUtil est là, en radio comme en Bluetooth ; non sur l'émulateur ou si
     * le MCU n'a pas suivi la commande (on revient alors au volume Android).
     */
    private boolean sliderOnMcu() {
        return hu.available() && hu.volumeCmdWorks != 0;
    }

    private boolean androidVolumeMaxDone;

    /**
     * Une fois le volume autoradio confirmé : volume média Android au maximum, une seule fois,
     * pour que le Bluetooth ne soit pas baissé deux fois et suive le même volume que la radio
     * (comme l'interface d'origine, où seul le volume autoradio compte).
     */
    private void androidVolumeToMax() {
        if (androidVolumeMaxDone || audio == null || hu.volumeCmdWorks != 1) return;
        androidVolumeMaxDone = true;
        try {
            int m = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v < m) {
                volumeByUs = true;
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, m, 0);
                lastVolume = m;
                Journal.log("VOL", "volume Android " + v + " → " + m + "/" + m + " : seul le volume autoradio règle le son");
            }
        } catch (Throwable t) {
            Journal.error("VOL", "volume Android au maximum", t);
        } finally {
            volumeByUs = false;
        }
    }

    /**
     * Curseur déplacé. Avec l'autoradio : volume général (MCU 0x0203, le même que le bouton et la
     * barre de son Topway), qui agit sur la radio et le Bluetooth à la fois. Sans : volume Android.
     */
    private void onVolumeSlider(int value, boolean done) {
        if (sliderOnMcu()) {
            try {
                hu.setMcuVolume(value, done);
                if (done) Journal.log("VOL", "volume autoradio réglé à " + value + "/" + hu.mcuVolumeMax
                        + " (curseur, " + MODE_NAMES[mode] + ")");
            } catch (Throwable t) {
                Journal.error("VOL", "réglage du volume autoradio impossible", t);
            }
            return;
        }
        if (audio == null) return;
        try {
            volumeByUs = true;
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
            lastVolume = value;
            if (done) Journal.log("VOL", "volume réglé à " + value + "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + " (curseur)");
        } catch (Throwable t) {
            Journal.error("VOL", "réglage du volume impossible", t);
        } finally {
            volumeByUs = false;
        }
    }

    private final android.content.BroadcastReceiver volumeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            updateVolumeLabel();
        }
    };

    private LinearLayout chip(int iconType, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(14), 0, px(18), 0);
        ImageView i = new ImageView(this);
        i.setImageDrawable(new Icon(iconType, Theme.text));
        c.addView(i, new LinearLayout.LayoutParams(px(20), px(20)));
        TextView t = text(16, Theme.text);
        t.setText(label);
        t.setPadding(px(8), 0, 0, 0);
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ph(44));
        c.setMinimumHeight(px(44));
        lp.rightMargin = px(10);
        c.setLayoutParams(lp);
        return c;
    }

    private View buildVolumePill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.glass);
        bg.setCornerRadius(px(26));
        bg.setStroke(Math.max(1, px(1)), Theme.stroke);
        pill.setBackground(bg);
        pill.setPadding(px(4), px(4), px(4), px(4));

        pill.addView(roundButton(new Icon(Icon.VOL_DOWN, Theme.text), 44, 24, 0, v -> changeVolume(AudioManager.ADJUST_LOWER), 0));
        volumeLabel = text(17, Theme.text);
        volumeLabel.setGravity(Gravity.CENTER);
        volumeLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.addView(volumeLabel, new LinearLayout.LayoutParams(ph(44) == px(44) ? px(44) : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pill.addView(roundButton(new Icon(Icon.VOL_UP, Theme.text), 44, 24, 0, v -> changeVolume(AudioManager.ADJUST_RAISE), 0));
        return pill;
    }

    private View roundButton(Drawable icon, int size, int iconSize, int bgColor, View.OnClickListener click) {
        return roundButton(icon, size, iconSize, bgColor, click, 12);
    }

    private View roundButton(Drawable icon, int size, int iconSize, int bgColor, View.OnClickListener click, int margin) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(icon);
        int pad = px((size - iconSize) / 2f);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(bgColor);
        if (bgColor != 0) shape.setStroke(Math.max(1, px(1)), Theme.stroke);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape,
                bgColor == 0 ? ovalMask() : null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(size), px(size));
        lp.setMargins(px(margin), 0, px(margin), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private static Drawable ovalMask() {
        GradientDrawable m = new GradientDrawable();
        m.setShape(GradientDrawable.OVAL);
        m.setColor(Color.WHITE);
        return m;
    }

    /** Facteur de taille des caractères, plafonné à « cap » pour que la mise en page tienne dans 480 px. */
    private float sc(float cap) {
        return Math.min(ts, cap);
    }

    /** Hauteur d'un élément portant du texte : fixe à x1 (rien ne bouge), libre dès que le texte grossit. */
    private int ph(float h) {
        return ts > 1f ? ViewGroup.LayoutParams.WRAP_CONTENT : px(h);
    }

    private TextView text(float size, int color) {
        return text(size, color, 2f);
    }

    private TextView text(float size, int color, float cap) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size * sc(cap)));
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable pill(int color, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(22));
        if (strokeColor != 0) g.setStroke(Math.max(1, px(1)), strokeColor);
        return g;
    }

    // ================================================================ Sources

    /** Change la source affichée ; si {@code user}, bascule aussi le son de l'autoradio. */
    private void setMode(int m, boolean user) {
        Journal.log("UI", "source " + MODE_NAMES[m] + (user ? " (choix utilisateur)" : " (automatique)"));
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        if (user) {
            if (m == MODE_RADIO) hu.radioOn();
            else if (m == MODE_BT && hu.available()) hu.selectBt(); // son du téléphone + reprise de la lecture
        }
        updateVolumeLabel();
        for (int i = 0; i < 2; i++) {
            boolean sel = i == m;
            modeChips[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),
                    pill(sel ? (accentFor(i) & 0x00FFFFFF) | 0x55000000 : Theme.glass,
                            sel ? accentFor(i) : Theme.stroke), null));
        }
        boolean radio = m == MODE_RADIO;
        presetRow.setVisibility(radio ? View.VISIBLE : View.GONE);
        int[][] icons = {
                {Icon.SCAN, Icon.REW, Icon.POWER, Icon.FWD, Icon.RADIO},  // radio
                {Icon.STOP, Icon.PREV, Icon.PLAY, Icon.NEXT, Icon.OPEN},  // bluetooth
                {Icon.STOP, Icon.PREV, Icon.PLAY, Icon.NEXT, Icon.OPEN},  // applis
        };
        for (int i = 0; i < 5; i++) ctlIcons[i].setType(icons[m][i]);
        artFor = "";
        refresh();
    }

    private int accentFor(int m) {
        return m == MODE_RADIO ? Theme.accentRadio : m == MODE_BT ? Theme.accentBt : appsAccent;
    }

    /** Boutons du bas : slot 0..4 de gauche à droite, appui long éventuel. */
    private void control(int slot, boolean longPress) {
        Journal.log("UI", "bouton " + (slot + 1) + "/5" + (longPress ? " (appui long)" : "") + " · " + MODE_NAMES[mode]);
        if (mode == MODE_RADIO) {
            if (!hu.available()) {
                Toast.makeText(this, "Radio indisponible : " + hu.status(), Toast.LENGTH_LONG).show();
                return;
            }
            switch (slot) {
                case 0:
                    hu.radioAutoStore();
                    Toast.makeText(this, "Recherche et mémorisation des stations…", Toast.LENGTH_SHORT).show();
                    break;
                case 1: if (longPress) hu.radioStep(false); else hu.radioSeek(false); break;
                case 2: if (hu.source == Tw.SRC_RADIO) hu.radioOff(); else hu.radioOn(); break;
                case 3: if (longPress) hu.radioStep(true); else hu.radioSeek(true); break;
                case 4: hu.radioToggleBand(); break;
            }
            return;
        }
        if (mode == MODE_BT && hu.btAvailable()) {
            switch (slot) {
                case 0: if (hu.btPlaying) hu.btPlayPause(); break;
                case 1: hu.btPrev(); break;
                case 2: hu.btPlayPause(); break;
                case 3: hu.btNext(); break;
                case 4: launchSlot(SLOT_BT); break;
            }
            return;
        }
        if (mode == MODE_BT) {
            // Bluetooth via l'appli Topway elle-même (sans ouvrir son canal) : sûr.
            if (hu.available()) {
                // Message 0x9e08 vers l'appli Bluetooth Topway (canal principal, sûr).
                switch (slot) {
                    case 0: hu.btControl(6); break;
                    case 1: hu.btControl(4); break;
                    case 2: hu.btControl(1); break;
                    case 3: hu.btControl(3); break;
                    case 4: launchSlot(SLOT_BT); break;
                }
                return;
            }
            switch (slot) {
                case 0: btKey(KeyEvent.KEYCODE_MEDIA_PAUSE); break;
                case 1: btKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
                case 2: btKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
                case 3: btKey(KeyEvent.KEYCODE_MEDIA_NEXT); break;
                case 4: launchSlot(SLOT_BT); break;
            }
            return;
        }
        // Applis : MediaSession, sinon touches média.
        switch (slot) {
            case 0: mediaAction(KeyEvent.KEYCODE_MEDIA_STOP); break;
            case 1: mediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case 2:
                if (hu.available() && hu.source != Tw.SRC_MEDIA && !isPlaying()) hu.requestSource(Tw.SRC_MEDIA);
                mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case 3: mediaAction(KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case 4: openPlayer(); break;
        }
    }

    private void onInfoTapped() {
        if (mode == MODE_APPS) {
            if (!listenerAllowed) askNotificationAccess();
            else openPlayer();
        }
    }

    @Override
    public void onHeadUnitChanged() {
        // Le MCU a changé de source (volant, appli Topway…) : on suit.
        if (hu.source != lastSource) {
            lastSource = hu.source;
            int m = hu.source == Tw.SRC_RADIO ? MODE_RADIO : hu.source == Tw.SRC_BT ? MODE_BT
                    : -1;
            if (m >= 0 && m != mode) setMode(m, false);
        }
        if (hu.lightsOn != lastLights) {
            lastLights = hu.lightsOn;
            car.setLights(hu.lightsOn);
        }
        // Bouton de façade ou barre de son Topway : le curseur suit (radio et Bluetooth).
        if (sliderOnMcu()) {
            androidVolumeToMax();
            updateVolumeLabel();
        }
        refresh();
    }

    /** Met à jour la carte selon la source affichée. */
    private void refresh() {
        if (mode == MODE_RADIO) renderRadio();
        else if (mode == MODE_BT && hu.btAvailable()) renderBt();
        else if (mode == MODE_BT) renderBtSafe();
        else updateMetadata();
        updatePlayback();
    }

    private void renderRadio() {
        setArt("radio", Icon.RADIO, Theme.accentRadio);
        animateAccent(Theme.accentRadio);
        if (!hu.available()) {
            setIfChanged(title, "Radio");
            setIfChanged(artist, "Fonction réservée à l'autoradio Topway");
            setIfChanged(album, hu.status());
        } else {
            boolean am = hu.isAm();
            setIfChanged(title, HeadUnit.formatFreq(hu.radioFreq) + (am ? " kHz" : " MHz"));
            artist.setText(!TextUtils.isEmpty(hu.radioName) ? hu.radioName
                    : hu.source == Tw.SRC_RADIO ? (am ? "Radio AM" : "Radio FM") : "Radio éteinte · touchez ⏻");
            setIfChanged(album, !TextUtils.isEmpty(hu.radioText) ? hu.radioText : am ? "AM" : "FM");
        }
        for (int i = 0; i < presetViews.length; i++) {
            HeadUnit.Preset p = hu.presets[i];
            String label = !TextUtils.isEmpty(p.name) ? p.name
                    : p.freq > 0 ? HeadUnit.formatFreq(p.freq) : String.valueOf(i + 1);
            presetViews[i].setText(label);
            boolean sel = i == hu.radioPreset;
            presetViews[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),
                    pill(sel ? (Theme.accentRadio & 0x00FFFFFF) | 0x66000000 : Theme.glass, sel ? Theme.accentRadio : Theme.stroke), null));
        }
    }

    private void renderBt() {
        setArt("bt", Icon.BT, Theme.accentBt);
        animateAccent(Theme.accentBt);
        if (!hu.btConnected && TextUtils.isEmpty(hu.btTitle)) {
            setIfChanged(title, "Bluetooth");
            setIfChanged(artist, "Aucun téléphone connecté");
            setIfChanged(album, "Appui long sur Bluetooth (dock) pour appairer un téléphone");
        } else {
            setIfChanged(title, !TextUtils.isEmpty(hu.btTitle) ? hu.btTitle : "Musique Bluetooth");
            setIfChanged(artist, hu.btArtist);
            setIfChanged(album, hu.btAlbum);
        }
    }

    /** Bluetooth sans canal direct : titre via la session Topway ou l'afficheur MCU (0x9f00). */
    private void renderBtSafe() {
        setArt("bt", Icon.BT, Theme.accentBt);
        animateAccent(Theme.accentBt);
        MediaMetadata md = btController != null ? btController.getMetadata() : null;
        String t = md != null ? firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)) : null;
        String a = md != null ? firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)) : null;
        if (t == null && !TextUtils.isEmpty(hu.btTitle)) t = hu.btTitle;
        if (a == null && !TextUtils.isEmpty(hu.btArtist)) a = hu.btArtist;
        updateBtPill();
        if (hu.btConn == 0) {
            setIfChanged(title, "Aucun téléphone connecté");
            setIfChanged(artist, !TextUtils.isEmpty(hu.btModule)
                    ? "Connectez « " + hu.btModule + " » depuis le téléphone" : "Connectez le téléphone en Bluetooth");
            setIfChanged(album, "");
            return;
        }
        setIfChanged(title, t != null ? t : "Musique Bluetooth");
        setIfChanged(artist, a != null ? a : t != null ? "" : "Lecture depuis le téléphone");
        setIfChanged(album, md != null ? firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ALBUM), "") : "");
    }

    /** Touche média adressée à l'appli Bluetooth Topway (session, sinon son récepteur de boutons). */
    private void btKey(int keyCode) {
        if (btController != null) {
            MediaController.TransportControls tc = btController.getTransportControls();
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    PlaybackState s = btController.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                    else tc.play();
                    return;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: tc.pause(); return;
                case KeyEvent.KEYCODE_MEDIA_NEXT: tc.skipToNext(); return;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: tc.skipToPrevious(); return;
            }
        }
        try {
            Intent q = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage("com.tw.bt");
            List<ResolveInfo> r = getPackageManager().queryBroadcastReceivers(q, 0);
            if (!r.isEmpty()) {
                ComponentName cn = new ComponentName(r.get(0).activityInfo.packageName, r.get(0).activityInfo.name);
                long now = SystemClock.uptimeMillis();
                for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn)
                            .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, action, keyCode, 0));
                    sendBroadcast(i);
                }
                return;
            }
        } catch (RuntimeException ignored) {
        }
        sendMediaKey(keyCode);
    }

    private boolean btPlayingSafe() {
        // On ne se fie à la session Topway qu'après l'avoir vue changer d'état : sinon son état peut être figé.
        if (btController != null && btSessionChanges > 0) {
            PlaybackState s = btController.getPlaybackState();
            if (s != null) return s.getState() == PlaybackState.STATE_PLAYING;
        }
        return hu.available() ? hu.source == Tw.SRC_BT && hu.btPlayingGuess
                : audio != null && audio.isMusicActive();
    }

    /** Vignette de la source (radio, Bluetooth) à la place de la pochette. */
    private void setArt(String key, int iconType, int color) {
        if (key.equals(artFor)) return;
        artFor = key;
        lastArt = null;
        // Icône de taille fixe, centrée : elle ne peut plus déborder, quelle que soit la taille de l'écran.
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        art.setImageDrawable(new Icon(iconType, 0xFFFFFFFF).sized(px(72)));
        art.setPadding(px(16), px(16), px(16), px(16));
        artBg.setColors(new int[]{lighten(color, 0.15f), blend(color, Theme.night ? 0xFF0B0E14 : 0xFF7C3A12, Theme.night ? 0.55f : 0.3f)});
    }

    // ================================================================ Dock

    /** Dock : deux boutons-cartes, Réglages et Applis (la radio et le Bluetooth sont dans la carte audio). */
    private void refreshDock() {
        List<App> apps = appCache;
        dock.removeAllViews();
        final App settings = resolveSlot(SLOT_SETTINGS, apps);
        View set = dockTile(Icon.SETTINGS, Theme.night ? SLOT_COLORS[SLOT_SETTINGS] : new int[]{0xFFC08B5C, 0xFF8A5A36}, "Réglages",
                settings != null ? settings.label : "Choisir l'appli");
        set.setOnClickListener(v -> {
            Journal.log("UI", "dock : Réglages");
            if (settings != null) launch(settings.component);
            else pickAppForSlot(SLOT_SETTINGS);
        });
        set.setOnLongClickListener(v -> {
            pickAppForSlot(SLOT_SETTINGS);
            return true;
        });
        dock.addView(set);
        View all = dockTile(Icon.APPS, Theme.night ? APPS_COLORS : new int[]{0xFFF6B04A, 0xFFE8603C}, "Applis", apps.isEmpty() ? "Toutes les applis" : apps.size() + " applis");
        all.setOnClickListener(v -> {
            Journal.log("UI", "dock : Applis");
            showAppGrid("Toutes les applis", a -> launch(a.component), null);
        });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) all.getLayoutParams();
        lp.leftMargin = px(12);
        dock.addView(all);
    }

    private void launchSlot(int slot) {
        App app = resolveSlot(slot, appCache);
        if (app != null) launch(app.component);
        else pickAppForSlot(slot);
    }

    /** Bouton-carte du dock, dans le style de la carte audio. */
    private View dockTile(int iconType, int[] colors, String name, String sub) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(px(12), px(10), px(14), px(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Theme.cardA, Theme.cardB});
        bg.setCornerRadius(px(22));
        bg.setStroke(Math.max(1, px(1)), Theme.stroke);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.night ? 0x40FFFFFF : 0x30402A10), bg, null));
        tile.setElevation(px(4));

        ImageView circle = new ImageView(this);
        circle.setImageDrawable(new Icon(iconType, 0xFFFFFFFF));
        circle.setPadding(px(10), px(10), px(10), px(10));
        GradientDrawable cb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        cb.setShape(GradientDrawable.OVAL);
        circle.setBackground(cb);
        tile.addView(circle, new LinearLayout.LayoutParams(px(42), px(42)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(12), 0, 0, 0);
        TextView label = text(17, Theme.text, 1.5f);
        label.setText(name);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        texts.addView(label);
        TextView s2 = text(12, Theme.dim, 1.5f);
        s2.setText(sub);
        if (ts > 1.25f) s2.setVisibility(View.GONE);   // place pour le titre de la carte
        s2.setSingleLine(true);
        s2.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(s2);
        tile.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tile.setMinimumHeight(px(66));
        tile.setLayoutParams(new LinearLayout.LayoutParams(0, ph(66), 1f));
        return tile;
    }

    /** Appli choisie par l'utilisateur, sinon devinée par mots-clés. */
    private App resolveSlot(int slot, List<App> apps) {
        String saved = prefs.getString("slot_" + slot, null);
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            for (App a : apps) {
                if (a.component.equals(cn)) return a;
            }
        }
        for (String kw : SLOT_KEYWORDS[slot]) {
            String compact = kw.replace(" ", "");
            for (App a : apps) {
                String label = a.label.toLowerCase(Locale.ROOT);
                String pkg = a.component.getPackageName().toLowerCase(Locale.ROOT);
                if (label.contains(kw) || pkg.contains(compact)) return a;
            }
        }
        return null;
    }

    private void pickAppForSlot(final int slot) {
        showAppGrid("Raccourci « " + SLOT_NAMES[slot] + " »", app -> {
            prefs.edit().putString("slot_" + slot, app.component.flattenToString()).apply();
            refreshDock();
        }, () -> {
            prefs.edit().remove("slot_" + slot).apply();
            refreshDock();
        });
    }

    // ================================================================ Liste des applis

    private static final class App {
        final String label;
        final ComponentName component;
        final ResolveInfo info;
        Drawable icon;

        App(String label, ComponentName component, ResolveInfo info) {
            this.label = label;
            this.component = component;
            this.info = info;
        }
    }

    private interface OnAppPicked {
        void picked(App app);
    }

    private void showAppGrid(String heading, final OnAppPicked onPicked, final Runnable onReset) {
        final List<App> apps = appCache.isEmpty() ? loadApps() : appCache;
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing(px(8));
        grid.setPadding(px(8), px(8), px(8), px(8));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout cell = convert instanceof LinearLayout ? (LinearLayout) convert : appCell();
                App app = apps.get(i);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(icon(app));
                ((TextView) cell.getChildAt(1)).setText(app.label);
                return cell;
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(heading)
                .setView(grid)
                .setNegativeButton("Fermer", null);
        if (onReset != null) b.setNeutralButton("Par défaut", (d, w) -> onReset.run());
        else b.setPositiveButton("Outils", (d, w) -> openTools());
        dismissDialog();
        openDialog = b.show();
        grid.setOnItemClickListener((p, v, pos, id) -> {
            dismissDialog();
            onPicked.picked(apps.get(pos));
        });
    }

    private LinearLayout appCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(px(4), px(8), px(4), px(8));
        cell.addView(new ImageView(this), new LinearLayout.LayoutParams(px(48), px(48)));
        TextView t = text(13, Theme.text);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(t);
        return cell;
    }

    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            CharSequence label = ri.loadLabel(pm);
            apps.add(new App(label != null ? label.toString() : cn.getPackageName(), cn, ri));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    private Drawable icon(App app) {
        if (app.icon == null) app.icon = app.info.loadIcon(getPackageManager());
        return app.icon;
    }

    // ================================================================ Applis (MediaSession)

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onSessionDestroyed() { connectMedia(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pickController;

    private void connectMedia() {
        ComponentName listener = new ComponentName(this, MediaListener.class);
        List<MediaController> list = null;
        try {
            if (sessions != null) {
                list = sessions.getActiveSessions(listener);
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener);
                listenerAllowed = true;
            }
        } catch (SecurityException e) {
            listenerAllowed = false;
        }
        pickController(list);
    }

    private void disconnectMedia() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (controller != null) controller.unregisterCallback(controllerCallback);
        controller = null;
    }

    // --- Session média de l'appli Bluetooth Topway (v0.14) : on l'observe et on journalise ce qu'elle expose.
    private int btSessionChanges;   // nombre de changements d'état vus : 0 = état jamais confirmé, on ne s'y fie pas

    private final MediaController.Callback btCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) {
            btSessionChanges++;
            Journal.log("BT", "session Topway : état " + (s == null ? "null" : s.getState())
                    + (s == null ? "" : " · position " + s.getPosition() + " · vitesse " + s.getPlaybackSpeed()));
            refresh();
        }
        @Override public void onMetadataChanged(MediaMetadata md) {
            Journal.log("BT", "session Topway : métadonnées "
                    + (md == null ? "null" : md.getString(MediaMetadata.METADATA_KEY_TITLE)
                    + " / " + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    + " / album " + md.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    + " / pochette " + (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null)));
            refresh();
        }
    };

    private void watchBtSession(MediaController bt) {
        try {
            boolean same = bt != null && btController != null
                    && bt.getSessionToken().equals(btController.getSessionToken());
            if (same) return;
            if (btController != null) btController.unregisterCallback(btCallback);
            btController = bt;
            btSessionChanges = 0;
            if (bt != null) {
                bt.registerCallback(btCallback, handler);
                PlaybackState s = bt.getPlaybackState();
                Journal.log("BT", "session Topway trouvée (" + bt.getPackageName() + ") : état initial "
                        + (s == null ? "null" : s.getState()));
            } else {
                Journal.log("BT", "session Topway absente");
            }
        } catch (Throwable t) {
            Journal.error("BT", "observation de la session Topway", t);
        }
    }

    /** Garde la session qui joue, sinon la plus récente. */
    private void pickController(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController mc : list) {
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = mc;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        MediaController bt = null;
        if (list != null) {
            for (MediaController mc : list) {
                if (mc.getPackageName() != null && mc.getPackageName().startsWith("com.tw.bt")) {
                    bt = mc;
                    break;
                }
            }
        }
        watchBtSession(bt);
        boolean same =best != null && controller != null
                && best.getSessionToken().equals(controller.getSessionToken());
        if (!same) {
            if (controller != null) controller.unregisterCallback(controllerCallback);
            controller = best;
            if (controller != null) controller.registerCallback(controllerCallback, handler);
        }
        refresh();
    }

    private void updateMetadata() {
        MediaMetadata md = controller != null ? controller.getMetadata() : null;
        String t = null, a = null, al = null;
        Bitmap bmp = null;
        if (md != null) {
            t = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            a = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
            al = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
            bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        String source = null;
        if (controller != null) {
            PackageManager pm = getPackageManager();
            try {
                source = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(controller.getPackageName(), 0)));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        if (controller == null && !listenerAllowed) {
            setIfChanged(title, "Prêt à jouer");
            setIfChanged(artist, "Touchez ici pour afficher titre et pochette");
            setIfChanged(album, "Autoriser « Sandero » dans l'accès aux notifications");
        } else {
            setIfChanged(title, t != null ? t : (controller != null ? "Lecture en cours" : "Aucune lecture"));
            setIfChanged(artist, a != null ? a : (controller != null ? "" : "Lancez Spotify, YouTube ou une autre appli"));
            setIfChanged(album, al != null ? al : (source != null ? source : ""));
        }

        String key = "apps" + mode;
        if (!key.equals(artFor) || bmp != lastArt) {
            artFor = key;
            lastArt = bmp;
            artBg.setColors(new int[]{Theme.artA, Theme.artB});
            if (bmp != null) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageBitmap(bmp);
                art.setPadding(0, 0, 0, 0);
                appsAccent = dominantColor(bmp, Theme.accentDefault);
            } else {
                art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                art.setImageDrawable(new Icon(mode == MODE_BT ? Icon.BT : Icon.MUSIC,
                        Theme.night ? 0x66FFFFFF : 0x66402A10).sized(px(76)));
                art.setPadding(px(16), px(16), px(16), px(16));
                appsAccent = Theme.accentDefault;
            }
            setModeChipsOnly();
        }
        animateAccent(appsAccent);
    }

    private void setModeChipsOnly() {
        LinearLayout c = modeChips[MODE_APPS];
        if (c != null && mode == MODE_APPS) {
            c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),
                    pill((appsAccent & 0x00FFFFFF) | 0x55000000, appsAccent), null));
        }
    }

    private void updatePlayback() {
        boolean playing;
        if (mode == MODE_RADIO) {
            ctlIcons[2].setType(Icon.POWER);
            playing = hu.source == Tw.SRC_RADIO;
        } else if (mode == MODE_BT && hu.btAvailable()) {
            playing = hu.btPlaying;
            playIcon.setType(playing ? Icon.PAUSE : Icon.PLAY);
        } else if (mode == MODE_BT) {
            renderBtSafe();
            playing = btPlayingSafe();
            playIcon.setType(playing ? Icon.PAUSE : Icon.PLAY);
        } else {
            playing = isPlaying();
            playIcon.setType(playing ? Icon.PAUSE : Icon.PLAY);
        }
        updateProgress();
    }

    private boolean isPlaying() {
        if (controller != null) {
            PlaybackState s = controller.getPlaybackState();
            return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                    || s.getState() == PlaybackState.STATE_BUFFERING);
        }
        return audio != null && audio.isMusicActive();
    }

    private long durationMs() {
        if (mode == MODE_BT && hu.btAvailable()) return hu.btDur;
        if (mode == MODE_RADIO) return 0;
        MediaController c = mode == MODE_BT ? btController : controller;
        MediaMetadata md = c != null ? c.getMetadata() : null;
        return md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
    }

    private long positionMs() {
        if (mode == MODE_BT && hu.btAvailable()) return hu.btPositionNow();
        MediaController c = mode == MODE_BT ? btController : controller;
        PlaybackState s = c != null ? c.getPlaybackState() : null;
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return pos;
    }

    private void updateProgress() {
        long dur = mode == MODE_RADIO ? 0 : durationMs();
        if (dur <= 0) {
            seek.setVisibility(mode == MODE_RADIO ? View.GONE : View.INVISIBLE);
            timeRow.setVisibility(mode == MODE_RADIO ? View.GONE : View.INVISIBLE);
            return;
        }
        long pos = Math.min(Math.max(0, positionMs()), dur);
        seek.setVisibility(View.VISIBLE);
        timeRow.setVisibility(View.VISIBLE);
        seek.setFraction(pos / (float) dur);
        setIfChanged(elapsed, formatTime(pos));
        setIfChanged(duration, "-" + formatTime(dur - pos));
    }

    private void seekTo(float fraction) {
        long dur = durationMs();
        if (mode != MODE_APPS || controller == null || dur <= 0) return;
        controller.getTransportControls().seekTo((long) (fraction * dur));
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (UiScale.get(MainActivity.this) != ts) {
                Journal.log("UI", "nouvelle taille des caractères : accueil reconstruit");
                recreate();
                return;
            }
            if (Theme.nightNow(MainActivity.this) != Theme.night) {
                Journal.log("THEME", "bascule vers le mode " + (Theme.night ? "JOUR" : "NUIT")
                        + " (" + Clock.format("HH:mm") + ", réglage " + Theme.label(Theme.mode(MainActivity.this)) + ")");
                recreate();
                return;
            }
            updatePlayback();
            updateVolumeLabel();
            handler.postDelayed(this, 500);
        }
    };

    // ================================================================ Actions

    private void mediaAction(int keyCode) {
        if (controller == null) {
            sendMediaKey(keyCode);
            return;
        }
        MediaController.TransportControls tc = controller.getTransportControls();
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (isPlaying()) tc.pause();
                else tc.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: tc.skipToNext(); break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: tc.skipToPrevious(); break;
            case KeyEvent.KEYCODE_MEDIA_STOP: tc.stop(); break;
        }
    }

    /** Envoie une touche média à l'appli audio active. */
    private void sendMediaKey(int keyCode) {
        if (audio == null) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void changeVolume(int direction) {
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        updateVolumeLabel();
    }

    private void updateVolumeLabel() {
        if (volume == null) return;
        if (sliderOnMcu()) {
            // Niveau inconnu tant que le bouton n'a pas bougé : milieu, le MCU corrigera.
            volume.setLevel(hu.mcuVolume >= 0 ? hu.mcuVolume : hu.mcuVolumeMax / 2, hu.mcuVolumeMax);
            updateBtPill();
            return;
        }
        if (audio == null) return;
        try {
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int m = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (v != lastVolume && !volumeByUs) {
                if (lastVolume >= 0) Journal.log("VOL", "volume changé ailleurs : " + v + "/" + m);
                lastVolume = v;
            }
            volume.setLevel(v, m);
        } catch (Throwable t) {
            Journal.error("VOL", "lecture du volume impossible", t);
        }
        updateBtPill();
    }

    /** Ouvre l'appli qui joue (mode Applis). */
    private void openPlayer() {
        if (controller != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
            }
        }
        showAppGrid("Choisir une appli audio", a -> launch(a.component), null);
    }

    private void openTools() {
        dismissDialog();
        startActivity(new Intent(this, ToolsActivity.class));
    }

    private void askNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Réglage introuvable sur cet autoradio", Toast.LENGTH_LONG).show();
        }
    }

    private void launch(ComponentName cn) {
        Journal.log("UI", "ouverture de " + cn.getPackageName());
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Impossible d'ouvrir cette appli", Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissDialog() {
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        openDialog = null;
    }

    // ================================================================ Couleur d'ambiance

    private void animateAccent(int target) {
        if (target == accent || (accentAnim != null && accentAnim.isRunning() && target == animTarget)) return;
        if (accentAnim != null) accentAnim.cancel();
        animTarget = target;
        accentAnim = ValueAnimator.ofObject(new ArgbEvaluator(), accent, target);
        accentAnim.setDuration(700);
        accentAnim.addUpdateListener(an -> applyAccent((Integer) an.getAnimatedValue()));
        accentAnim.start();
    }

    private int animTarget;

    private void applyAccent(int c) {
        accent = c;
        cardBg.setColors(new int[]{blend(c, Theme.cardBase, Theme.night ? 0.62f : 0.82f), Theme.cardB, Theme.cardC});
        playBg.setColors(new int[]{lighten(c, 0.35f), c});
        seek.setAccent(c);
        if (volume != null) volume.setAccent(c);
        car.setAccent(c);
    }

    /** Teinte dominante (la plus colorée) d'une pochette. */
    private static int dominantColor(Bitmap b, int fallback) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
            float[] hsv = new float[3];
            double x = 0, y = 0, sum = 0;
            for (int i = 0; i < 24; i++) {
                for (int j = 0; j < 24; j++) {
                    Color.colorToHSV(s.getPixel(i, j), hsv);
                    double w = hsv[1] * hsv[2];
                    w *= w;
                    double rad = Math.toRadians(hsv[0]);
                    x += Math.cos(rad) * w;
                    y += Math.sin(rad) * w;
                    sum += w;
                }
            }
            if (s != b) s.recycle();
            if (sum < 2) return fallback; // pochette en noir et blanc
            float hue = (float) Math.toDegrees(Math.atan2(y, x));
            if (hue < 0) hue += 360;
            return Color.HSVToColor(new float[]{hue, 0.78f, 0.95f});
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    static int lighten(int c, float t) {
        return blend(c, 0xFFFFFFFF, t);
    }

    // ================================================================ Utilitaires

    /** Évite une mise en page complète quand le texte ne change pas. */
    /** Texte trop long : il défile de droite à gauche au lieu d'être coupé par « … » (v0.16). */
    private static void marquee(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontallyScrolling(true);
        t.setSelected(true);
    }

    private static void setIfChanged(TextView t, CharSequence text) {
        if (!TextUtils.equals(t.getText(), text)) t.setText(text);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private int px(float v) {
        return Math.round(v * unit);
    }
}
