package fr.sandero.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Camera;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.ref.WeakReference;

/**
 * Caméra de recul intégrée à Sandero : l'image à droite, la musique continue à gauche.
 *
 * Séquence reprise de l'écran de recul Topway (ReverseActivity) :
 *   write(0x0304, 1) → Camera.open(id) → setAnalogInputColor(32, 102, 100) → aperçu.
 * L'écran Topway s'ouvre d'abord ; en passant par-dessus, il libère la caméra.
 *
 * Garde-fous : caméra ouverte sur un fil à part (jamais de blocage de l'écran) ; en mode
 * automatique, une caméra qui ne donne que du noir est écartée et la suivante essayée ;
 * le numéro qui marche est retenu ; bouton Retour toujours visible ; si rien ne marche,
 * on se retire pour laisser l'écran Topway.
 */
public class ReverseCamActivity extends Activity implements SurfaceHolder.Callback, HeadUnit.Listener {

    static final String EXTRA_TEST = "test";
    private static final String TAG = "SanderoCam";
    private static final int DARK = 10;         // luminosité moyenne en dessous de laquelle l'image est « noire »

    private static WeakReference<ReverseCamActivity> current = new WeakReference<>(null);

    /** Fermeture demandée par le service (point mort). */
    static boolean closeIfOpen() {
        ReverseCamActivity a = current.get();
        if (a != null && !a.isFinishing()) {
            a.leave(true);
            return true;
        }
        return false;
    }

    private static volatile boolean front;

    /**
     * L'utilisateur a quitté la caméra lui-même (Retour, Écran Topway) pendant ce recul :
     * le service ne la ramène plus devant jusqu'à la fin du recul (0x9f1c = 0).
     */
    private static volatile boolean dismissed;

    static boolean dismissedByUser() {
        return dismissed;
    }

    /** Fin du recul : la prochaine marche arrière rouvre la caméra normalement. */
    static void resetDismissed() {
        dismissed = false;
    }

    /** Fermeture choisie par l'utilisateur (bouton). */
    private void leaveByUser(boolean home) {
        if (!test && hu != null && hu.reverse) {
            dismissed = true;
            Journal.log("CAM", "caméra quittée par l'utilisateur : plus ramenée devant jusqu'à la fin du recul");
        }
        leave(home);
    }

    /** La fenêtre caméra est-elle affichée devant (et pas recouverte par l'écran Topway) ? */
    static boolean isFront() {
        return front;
    }



    private static final Handler HOME = new Handler(Looper.getMainLooper());

    private float unit = 1f;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread camThread;
    private Handler cam;
    private HeadUnit hu;
    private SharedPreferences tools;
    private boolean test;

    private SurfaceHolder holder;
    private volatile Camera camera;
    private volatile int cameraId = -1;
    private int[] order = new int[0];
    private int orderPos;
    private int darkFrames, brightFrames;
    private TextView status, title, artist;
    private Icon playIcon;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new WeakReference<>(this);
        SharedPreferences tp = getSharedPreferences("tools", MODE_PRIVATE);
        Journal.log("CAM", "fenêtre caméra ouverte" + (getIntent().getBooleanExtra(EXTRA_TEST, false) ? " (test)" : "")
                + " · " + Camera.getNumberOfCameras() + " caméras · choix " + tp.getInt("cam_id", -1)
                + " · appris " + tp.getInt("cam_id_auto", -1));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        hu = HeadUnit.get();
        tools = getSharedPreferences("tools", MODE_PRIVATE);
        test = getIntent().getBooleanExtra(EXTRA_TEST, false);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        unit = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1280f,
                Math.min(dm.widthPixels, dm.heightPixels) / 480f);
        camThread = new HandlerThread("camera");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        setContentView(buildLayout());
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        test = intent.getBooleanExtra(EXTRA_TEST, test);
    }

    @Override
    protected void onResume() {
        super.onResume();
        front = true; // affichée devant (onPause la remet à false si l'écran Topway passe par-dessus)
        hu.addListener(this);
        hu.cameraVideo(true); // comme Topway : basculer la vidéo sur la caméra AVANT de l'ouvrir
        // Retour devant après avoir été recouvert : la caméra a été fermée dans onPause, on la rouvre.
        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid() && camera == null) {
            startCamera();
        }
        onHeadUnitChanged();
        ui.post(ticker);
        // Filet de sécurité : jamais plus de 10 minutes ouvert sans confirmation.
        if (!test) ui.postDelayed(() -> leave(true), 10 * 60_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        front = false;
        hu.removeListener(this);
        ui.removeCallbacksAndMessages(null);
        cam.removeCallbacksAndMessages(null);
        cam.post(this::closeCameraNow);
        if (isFinishing()) {
            hu.cameraVideo(false);
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        cam.post(() -> {
            closeCameraNow();
            camThread.quitSafely();
        });
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        leaveByUser(!test);
    }

    /** Ferme la fenêtre ; {@code home} : revenir explicitement à l'accueil Sandero. */
    void leave(boolean home) {
        if (isFinishing()) return;
        Journal.log("CAM", "fermeture de la fenêtre caméra" + (home ? " → accueil" : " → écran Topway"));
        finish();
        if (home) {
            // Handler indépendant de l'écran : il ne doit pas être annulé par onPause().
            final android.content.Context app = getApplicationContext();
            HOME.postDelayed(() -> {
                try {
                    // Marche arrière ré-enclenchée entre-temps : ne pas recouvrir la nouvelle caméra.
                    if (HeadUnit.get().reverse && !dismissed) return;
                    app.startActivity(new Intent(app, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } catch (RuntimeException ignored) {
                }
            }, 250);
        }
    }

    // ================================================================ Mise en page

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF101826, 0xFF080B12, 0xFF05070B}));
        root.setPadding(px(22), px(18), px(18), px(18));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = new Clock.View(this, "HH:mm");
        time.setTextColor(MainActivity.TEXT);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(52));
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        head.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = pillButton("Retour", 0x33FFFFFF);
        back.setOnClickListener(v -> leaveByUser(!test));
        head.addView(back);
        left.addView(head);

        TextView badge = text(16, 0xFFFFFFFF);
        badge.setText("  MARCHE ARRIÈRE  ");
        badge.setLetterSpacing(0.12f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable bb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFF97316, 0xFFDC2626});
        bb.setCornerRadius(px(14));
        badge.setBackground(bb);
        badge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34));
        blp.topMargin = px(12);
        left.addView(badge, blp);

        status = text(13, MainActivity.TEXT_DIM);
        status.setPadding(0, px(8), 0, px(6));
        status.setMaxLines(3);
        left.addView(status);

        LinearLayout tools2 = new LinearLayout(this);
        tools2.setOrientation(LinearLayout.HORIZONTAL);
        TextView next = pillButton("Caméra suivante", 0x33FFFFFF);
        next.setOnClickListener(v -> nextCamera(true));
        tools2.addView(next);
        TextView stock = pillButton("Écran Topway", 0x33FFFFFF);
        stock.setOnClickListener(v -> leaveByUser(false));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
        slp.leftMargin = px(8);
        tools2.addView(stock, slp);
        left.addView(tools2);

        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout music = new LinearLayout(this);
        music.setOrientation(LinearLayout.VERTICAL);
        music.setPadding(px(18), px(12), px(18), px(12));
        GradientDrawable mb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1A2233, 0xFF0F131B});
        mb.setCornerRadius(px(24));
        mb.setStroke(Math.max(1, px(1)), MainActivity.GLASS_STROKE);
        music.setBackground(mb);
        title = text(19, MainActivity.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        music.addView(title);
        artist = text(14, MainActivity.TEXT_DIM);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        music.addView(artist);
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, px(8), 0, 0);
        ctl.addView(roundButton(new Icon(Icon.PREV, MainActivity.TEXT), 54, 24, MainActivity.GLASS, v -> media(1)));
        playIcon = new Icon(Icon.PLAY, 0xFFFFFFFF);
        View play = roundButton(playIcon, 64, 30, 0, v -> media(2));
        GradientDrawable pb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6FD3FF, MainActivity.DEFAULT_ACCENT});
        pb.setShape(GradientDrawable.OVAL);
        play.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), pb, null));
        ctl.addView(play);
        ctl.addView(roundButton(new Icon(Icon.NEXT, MainActivity.TEXT), 54, 24, MainActivity.GLASS, v -> media(3)));
        music.addView(ctl);
        left.addView(music);

        root.addView(left, new LinearLayout.LayoutParams(px(310), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout frame = new FrameLayout(this);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0xFF000000);
        fb.setCornerRadius(px(26));
        frame.setBackground(fb);
        frame.setClipToOutline(true);
        SurfaceView surface = new SurfaceView(this);
        holder = surface.getHolder();
        holder.addCallback(this);
        frame.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View border = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0x00000000);
        bd.setCornerRadius(px(26));
        bd.setStroke(px(3), 0x66F97316);
        border.setBackground(bd);
        frame.addView(border, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        flp.leftMargin = px(18);
        root.addView(frame, flp);
        return root;
    }

    private TextView pillButton(String label, int color) {
        TextView b = text(14, 0xFFFFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(14), 0, px(14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(20));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null));
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)));
        return b;
    }

    private View roundButton(Icon icon, int size, int iconSize, int bg, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(icon);
        int pad = px((size - iconSize) / 2f);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(bg);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(size), px(size));
        lp.setMargins(px(6), 0, px(6), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ================================================================ Caméra (fil « camera »)

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        startCamera();
    }

    /** Prépare l'ordre des caméras et lance l'ouverture (après création de l'image ou retour devant). */
    private void startCamera() {
        cam.removeCallbacksAndMessages(null);
        int n = Camera.getNumberOfCameras();
        int pref = tools.getInt("cam_id", -1);          // choix manuel
        int learned = tools.getInt("cam_id_auto", -1);  // dernier numéro qui a donné une image
        order = new int[Math.max(n, 1)];
        int k = 0;
        if (pref >= 0 && pref < n) {
            order[k++] = pref;
        } else {
            if (learned >= 0 && learned < n) order[k++] = learned;
            for (int i = 0; i < n; i++) if (i != learned) order[k++] = i;
        }
        if (k == 0) {
            setStatus("Aucune caméra détectée");
            if (!test) ui.postDelayed(() -> leave(false), 1500);
            return;
        }
        int[] o = new int[k];
        System.arraycopy(order, 0, o, 0, k);
        order = o;
        orderPos = 0;
        // Laisser à l'écran Topway le temps de libérer la caméra.
        cam.postDelayed(() -> openAt(0, 1), 350);
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        cam.removeCallbacksAndMessages(null);
        cam.post(this::closeCameraNow);
    }

    /** Ouvre la caméra order[pos] ; réessaie si elle est encore occupée par l'écran Topway. */
    private void openAt(final int pos, final int attempt) {
        if (isFinishing() || pos >= order.length) {
            if (!isFinishing()) noCamera();
            return;
        }
        closeCameraNow();
        videoOn();
        int id = order[pos];
        try {
            Camera c = Camera.open(id);
            if (isFinishing()) {
                // Camera.open peut bloquer plus d'une seconde : fenêtre fermée entre-temps (vu le 09/11).
                c.release();
                return;
            }
            analogColor(c);
            c.setPreviewDisplay(holder);
            c.startPreview();
            camera = c;
            cameraId = id;
            orderPos = pos;
            darkFrames = brightFrames = 0;
            setStatus("Caméra " + id + " · son conservé" + signalText());
            cam.postDelayed(checkImage, 700);
        } catch (Throwable e) {
            Log.w(TAG, "caméra " + id + " (essai " + attempt + ")", e);
            Journal.error("CAM", "caméra " + id + " essai " + attempt, e);
            closeCameraNow();
            // Juste après le démarrage de l'autoradio, le service caméra n'est pas prêt
            // (« Fail to connect » en boucle, 09/11 16:14) : ~10 s d'essais sur la première caméra.
            boolean booting = android.os.SystemClock.elapsedRealtime() < 120_000 && pos == 0;
            if (booting && attempt == 6) Journal.log("CAM", "autoradio qui démarre : essais prolongés sur la caméra " + id);
            if (attempt < (booting ? 40 : 6)) {
                setStatus("Ouverture de la caméra " + id + "… (" + attempt + ")");
                cam.postDelayed(() -> openAt(pos, attempt + 1), 250);
            } else {
                cam.post(() -> openAt(pos + 1, 1));
            }
        }
    }

    /** write(0x0304, 1) sur le fil principal (TWUtil y est utilisé partout), puis on attend un peu. */
    private void videoOn() {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            try {
                hu.cameraVideo(true);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
    }

    /** Couleurs de l'entrée analogique comme Topway (méthode Allwinner, absente ailleurs). */
    private static void analogColor(Camera c) {
        try {
            Camera.class.getMethod("setAnalogInputColor", int.class, int.class, int.class).invoke(c, 32, 102, 100);
        } catch (Throwable ignored) {
        }
    }

    /** Mesure la luminosité de l'image ; en automatique, écarte une caméra qui reste noire. */
    private final Runnable checkImage = new Runnable() {
        @Override
        public void run() {
            final Camera c = camera;
            if (c == null) return;
            try {
                c.setOneShotPreviewCallback((data, cm) -> {
                    int mean = meanLuma(data);
                    Journal.log("CAM", "caméra " + cameraId + " luminosité " + mean);
                    if (mean >= 0 && mean < DARK) darkFrames++;
                    else brightFrames++;
                    onLuma(mean);
                });
            } catch (Throwable t) {
                Log.w(TAG, "mesure impossible", t);
            }
        }
    };

    private void onLuma(int mean) {
        boolean auto = tools.getInt("cam_id", -1) < 0;
        if (brightFrames >= 2) {
            tools.edit().putInt("cam_id_auto", cameraId).apply();
            setStatus("Caméra " + cameraId + " · image OK · son conservé" + signalText());
            return; // plus de vérification
        }
        if (auto && darkFrames >= 3) {
            setStatus("Caméra " + cameraId + " : image noire, essai de la suivante…");
            cam.post(() -> openAt(orderPos + 1, 1));
            return;
        }
        if (!auto && darkFrames >= 3) {
            setStatus("Caméra " + cameraId + " : image noire (lum. " + mean + "). Essayez « Caméra suivante »." + signalText());
        }
        cam.postDelayed(checkImage, 500);
    }

    /** Luminosité moyenne (plan Y d'une image NV21), sur un échantillon. */
    private static int meanLuma(byte[] d) {
        if (d == null || d.length < 1000) return -1;
        int n = Math.min(d.length * 2 / 3, d.length);
        long sum = 0;
        int count = 0;
        for (int i = 0; i < n; i += 97) {
            sum += d[i] & 0xff;
            count++;
        }
        return count == 0 ? -1 : (int) (sum / count);
    }

    /** Bouton « Caméra suivante » : passe au numéro suivant et le retient comme choix manuel. */
    private void nextCamera(boolean remember) {
        cam.removeCallbacksAndMessages(null);
        cam.post(() -> {
            int n = Camera.getNumberOfCameras();
            if (n == 0) return;
            int next = (cameraId + 1 + n) % n;
            if (remember) tools.edit().putInt("cam_id", next).apply();
            order = new int[]{next};
            openAt(0, 1);
        });
    }

    private void noCamera() {
        setStatus("Pas d'image sur les caméras. Retour à l'écran Topway.");
        if (!test) ui.postDelayed(() -> leave(false), 1500);
    }

    private void closeCameraNow() {
        Camera c = camera;
        camera = null;
        if (c == null) return;
        try {
            c.setPreviewCallback(null);
            c.stopPreview();
        } catch (Throwable ignored) {
        }
        try {
            c.release();
        } catch (Throwable ignored) {
        }
    }

    private static String signalText() {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/tw/av/signal"))) {
            String s = r.readLine();
            return s == null ? "" : " · signal " + s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void setStatus(final String s) {
        Journal.log("CAM", s);
        ui.post(() -> status.setText(s));
    }

    // ================================================================ Musique et marche arrière

    @Override
    public void onHeadUnitChanged() {
        if (!test && hu.available() && !hu.reverse) {
            leave(true);
            return;
        }
        int mode = getSharedPreferences("dock", MODE_PRIVATE).getInt("mode", 1);
        if (mode == 2) mode = 1;
        if (mode == 0 && hu.available()) {
            title.setText(HeadUnit.formatFreq(hu.radioFreq) + (hu.isAm() ? " kHz" : " MHz"));
            artist.setText(!TextUtils.isEmpty(hu.radioName) ? hu.radioName : "Radio");
            playIcon.setType(Icon.POWER);
        } else if (mode == 1) {
            title.setText(!TextUtils.isEmpty(hu.btTitle) ? hu.btTitle : "Bluetooth");
            artist.setText(hu.btArtist);
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean playing = hu.btAvailable() ? hu.btPlaying : hu.available() ? hu.btPlayingGuess : am != null && am.isMusicActive();
            playIcon.setType(playing ? Icon.PAUSE : Icon.PLAY);
        } else {
            title.setText("Musique");
            artist.setText("La lecture continue");
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            playIcon.setType(am != null && am.isMusicActive() ? Icon.PAUSE : Icon.PLAY);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            onHeadUnitChanged();
            ui.postDelayed(this, 1000);
        }
    };

    /** 1 précédent, 2 lecture/pause, 3 suivant, selon la source choisie dans Sandero. */
    private void media(int action) {
        int mode = getSharedPreferences("dock", MODE_PRIVATE).getInt("mode", 1);
        if (mode == 2) mode = 1;
        if (mode == 0 && hu.available()) {
            if (action == 1) hu.radioSeek(false);
            else if (action == 3) hu.radioSeek(true);
            return;
        }
        if (mode == 1 && hu.btAvailable()) {
            if (action == 1) hu.btPrev();
            else if (action == 2) hu.btPlayPause();
            else hu.btNext();
            return;
        }
        if (mode == 1 && hu.available()) {
            hu.btControl(action == 1 ? 4 : action == 2 ? 1 : 3);
            return;
        }
        int key = action == 1 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : action == 2 ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : KeyEvent.KEYCODE_MEDIA_NEXT;
        long now = SystemClock.uptimeMillis();
        if (mode == 1) {
            // Bluetooth : adressé à l'appli Topway.
            try {
                Intent q = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage("com.tw.bt");
                java.util.List<android.content.pm.ResolveInfo> r = getPackageManager().queryBroadcastReceivers(q, 0);
                if (!r.isEmpty()) {
                    android.content.ComponentName cn = new android.content.ComponentName(
                            r.get(0).activityInfo.packageName, r.get(0).activityInfo.name);
                    for (int a : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn)
                                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, a, key, 0)));
                    }
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
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
