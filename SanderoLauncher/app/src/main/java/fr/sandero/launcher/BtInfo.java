package fr.sandero.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Infos Bluetooth lues dans les annonces (broadcasts) de l'appli Bluetooth Topway (com.tw.bt).
 * Aucune ouverture de canal : on écoute ce que l'appli diffuse déjà pour l'assistant vocal,
 * et on lui pose les mêmes questions que lui (GET_PAIR_INFO…). Sans risque pour le module.
 *
 * Reçus :  com.unisound.intent.action.SEND_PAIR_INFO   (pairName, pairMac)   → téléphone connecté
 *          com.unisound.intent.action.SEND_ID3_INFO    (musicTitle, musicArtist)
 *          com.unisound.intent.action.SEND_DEVICE_INFO (deviceName, devicePin) → nom de l'autoradio
 *          android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED (…extra.CONNECTION_STATE)
 *          com.tw.bt_phone_info                         (singal, power)        → réseau et batterie
 * Envoyés : GET_PAIR_INFO, GET_ID3_INFO, GET_DEVICE_INFO, CHECK_BTSTATUS (toutes les 5 s sans téléphone,
 *          toutes les 30 s une fois connecté, et sur demande).
 */
final class BtInfo {

    static final String PAIR = "com.unisound.intent.action.SEND_PAIR_INFO";
    static final String ID3 = "com.unisound.intent.action.SEND_ID3_INFO";
    static final String DEVICE = "com.unisound.intent.action.SEND_DEVICE_INFO";
    static final String CONN = "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED";
    static final String CONN_EXTRA = "android.bluetooth.adapter.extra.CONNECTION_STATE";
    static final String PHONE = "com.tw.bt_phone_info";
    private static final String[] ASK = {
            "com.unisound.intent.action.GET_PAIR_INFO",
            "com.unisound.intent.action.GET_ID3_INFO",
            "com.unisound.intent.action.GET_DEVICE_INFO",
            "com.unisound.intent.action.CHECK_BTSTATUS"};

    private static BroadcastReceiver receiver;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Context app;

    private BtInfo() {
    }

    /** Démarre l'écoute (une seule fois, dans le service). */
    static synchronized void start(Context c) {
        if (receiver != null) return;
        app = c.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    handle(intent);
                } catch (Throwable t) {
                    Journal.error("BT", "annonce illisible " + intent.getAction(), t);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(PAIR);
        f.addAction(ID3);
        f.addAction(DEVICE);
        f.addAction(CONN);
        f.addAction(PHONE);
        try {
            app.registerReceiver(receiver, f);
            Journal.log("BT", "écoute des annonces de l'appli Bluetooth Topway");
        } catch (Throwable t) {
            Journal.error("BT", "écoute impossible", t);
        }
        handler.postDelayed(poll, 1500);
    }

    /** Demande à l'appli Bluetooth ses infos (elle répond par les annonces ci-dessus). */
    static void ask() {
        if (app == null) return;
        for (String a : ASK) {
            try {
                app.sendBroadcast(new Intent(a));
            } catch (Throwable t) {
                Journal.error("BT", "demande " + a, t);
            }
        }
    }

    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            ask();
            // Pas de téléphone vu : on redemande toutes les 5 s pour voir vite celui qui se connecte
            // tout seul après le démarrage ; une fois connecté, toutes les 30 s suffisent.
            handler.postDelayed(this, HeadUnit.get().btConn == 2 ? 30_000 : 5_000);
        }
    };

    /** Dernier contenu journalisé par annonce (fil principal uniquement). */
    private static final java.util.HashMap<String, String> LAST = new java.util.HashMap<>();

    private static void handle(Intent i) {
        String a = i.getAction();
        // Les annonces reviennent toutes les 30 s à l'identique (344 Ko de journal en 5 jours) :
        // journalisées seulement si leur contenu change (l'état de connexion, toujours).
        String ex = extras(i);
        String prev = LAST.put(String.valueOf(a), ex);
        if (CONN.equals(a) || !ex.equals(prev)) Journal.log("BT", "annonce " + shortName(a) + " " + ex);
        HeadUnit hu = HeadUnit.get();
        if (PAIR.equals(a)) {
            hu.setBtDevice(i.getStringExtra("pairName"), i.getStringExtra("pairMac"));
        } else if (ID3.equals(a)) {
            hu.setBtId3(i.getStringExtra("musicTitle"), i.getStringExtra("musicArtist"));
        } else if (DEVICE.equals(a)) {
            hu.setBtModuleName(i.getStringExtra("deviceName"));
        } else if (CONN.equals(a)) {
            hu.setBtConnection(i.getIntExtra(CONN_EXTRA, -1));
            handler.postDelayed(BtInfo::ask, 800); // nouveau téléphone → redemander son nom
        } else if (PHONE.equals(a)) {
            hu.setBtPhone(i.getIntExtra("singal", -1), i.getIntExtra("power", -1));
        }
    }

    private static String shortName(String a) {
        if (a == null) return "?";
        int d = a.lastIndexOf('.');
        return d >= 0 ? a.substring(d + 1) : a;
    }

    private static String extras(Intent i) {
        Bundle b = i.getExtras();
        if (b == null) return "";
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) sb.append(k).append('=').append(b.get(k)).append(' ');
        return sb.append('}').toString();
    }
}
