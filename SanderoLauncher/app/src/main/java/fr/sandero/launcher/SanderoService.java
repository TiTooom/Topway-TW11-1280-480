package fr.sandero.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Service permanent de Sandero :
 * - garde la liaison avec l'autoradio (TWUtil) même quand l'écran n'est pas affiché ;
 * - marche arrière → ouvre la caméra intégrée à Sandero (le son n'est pas coupé par Sandero) ;
 * - réveil de l'autoradio (contact mis) → ramène Sandero au premier plan ;
 * - si le lanceur d'origine revient au premier plan → ramène Sandero (accès à l'utilisation requis).
 */
public class SanderoService extends Service implements HeadUnit.Listener {

    private static final String CHANNEL = "sandero";
    /** Lanceur d'origine de l'autoradio, et quelques lanceurs courants. */
    private static final String[] STOCK_LAUNCHERS = {"com.android.launcher", "com.android.launcher3", "com.tw.launcher"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HeadUnit hu;
    private SharedPreferences tools;
    private boolean lastReverse;

    static void start(Context c) {
        Intent i = new Intent(c, SanderoService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        tools = getSharedPreferences("tools", MODE_PRIVATE);
        startForeground(1, notification());
        Journal.log("SVC", "service démarré");
        hu = HeadUnit.get();
        if (tools.getBoolean("tw_enabled", true)) hu.start(this);
        hu.addListener(this);
        Clock.startGps(this);
        BtInfo.start(this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wake, f);
        handler.postDelayed(watchdog, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hu.removeListener(this);
        try {
            unregisterReceiver(wake);
        } catch (RuntimeException ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Sandero", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Sandero")
                .setContentText("Écran d'accueil actif")
                .setOngoing(true)
                .build();
    }

    // ================================================================ Marche arrière

    @Override
    public void onHeadUnitChanged() {
        if (hu.reverse == lastReverse) return;
        lastReverse = hu.reverse;
        Journal.log("SVC", "marche arrière " + (hu.reverse ? "ENCLENCHÉE" : "relâchée"));
        if (!hu.reverse) {
            // Point mort : on ferme la caméra Sandero et on revient à l'accueil, quoi qu'il arrive.
            // Si la fenêtre caméra était ouverte, elle ramène elle-même l'accueil (une seule fois).
            boolean wasOpen = ReverseCamActivity.closeIfOpen();
            handler.removeCallbacks(camFront);
            if (!wasOpen && tools.getBoolean("cam_in_sandero", true)) handler.postDelayed(this::bringToFront, 600);
            return;
        }
        if (Safe.safeMode(this)) return;
        if (hu.reverse && tools.getBoolean("cam_in_sandero", true)) {
            // Ouverture immédiate (évite le passage par l'écran noir Topway), puis on vérifie
            // à 0,25 / 0,6 / 1 / 1,6 s que la fenêtre Sandero est bien devant ; sinon on la ramène.
            camAttempt = 0;
            showCam("ouverture immédiate");
            handler.removeCallbacks(camFront);
            handler.postDelayed(camFront, 250);
        }
    }

    private int camAttempt;
    private static final int[] CAM_CHECKS = {250, 350, 400, 600};

    private final Runnable camFront = new Runnable() {
        @Override
        public void run() {
            if (!hu.reverse) return;
            if (!ReverseCamActivity.isFront()) showCam("fenêtre recouverte, retour devant");
            if (camAttempt < CAM_CHECKS.length) handler.postDelayed(this, CAM_CHECKS[camAttempt++]);
        }
    };

    private void showCam(String why) {
        Journal.log("SVC", "caméra Sandero : " + why);
        Intent i = new Intent(this, ReverseCamActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    // ================================================================ Retour à Sandero

    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Journal.log("SVC", "réveil : " + intent.getAction());
            if (tools.getBoolean("wake_front", true) && !hu.reverse) {
                handler.postDelayed(SanderoService.this::bringToFront, 800);
            }
        }
    };

    private void bringToFront() {
        Journal.log("SVC", "retour au premier plan de Sandero");
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    /** Toutes les 2 s : si le lanceur d'origine est au premier plan, on revient à Sandero. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (tools.getBoolean("watch_launcher", true) && !hu.reverse) {
                String top = foregroundPackage(SanderoService.this);
                if (top != null) {
                    for (String s : STOCK_LAUNCHERS) {
                        if (s.equals(top)) {
                            Journal.log("SVC", "lanceur d'origine détecté au premier plan (" + top + ")");
                            bringToFront();
                            break;
                        }
                    }
                }
            }
            handler.postDelayed(this, 2000);
        }
    };

    /** Appli au premier plan, via l'accès à l'utilisation (null si non autorisé). */
    static String foregroundPackage(Context c) {
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents ev;
        try {
            ev = usm.queryEvents(now - 60_000, now);
        } catch (RuntimeException e) {
            return null;
        }
        if (ev == null) return null;
        String last = null;
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
        }
        return last;
    }

    /** L'accès à l'utilisation est-il accordé ? (sinon la surveillance du lanceur est inactive) */
    static boolean usageAccess(Context c) {
        android.app.AppOpsManager ops = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), c.getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }
}
