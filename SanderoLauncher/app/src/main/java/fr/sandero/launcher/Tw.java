package fr.sandero.launcher;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Pont vers android.tw.john.TWUtil, l'API Topway intégrée au framework de l'autoradio.
 * La classe n'existe pas dans le SDK : tout passe par réflexion, et rien ne plante
 * si elle est absente (émulateur, autre autoradio) : {@link #ok} reste alors à false.
 *
 * Identifiants et commandes relevés dans les applis Topway (voir .claude/skills/topway-twutil/SKILL.md).
 */
final class Tw {

    private static final String TAG = "SanderoTw";

    // --- Sources audio (write(SOURCE, 0xc0, n) ; n | 0x80 = relâcher)
    static final int SOURCE = 0x9e11;
    static final int SRC_RADIO = 1, SRC_MEDIA = 3, SRC_BT = 8;

    // --- Messages du MCU (canal principal)
    static final int MSG_KEY = 0x0201;        // touches volant/façade : arg1 = code
    static final int MSG_MUTE = 0x0203;
    static final int MSG_ILL = 0x0204;        // éclairage : arg1 == 1 → feux allumés (nuit)
    static final int MSG_BRAKE = 0x0205;
    static final int MSG_REVERSE_END = 0x020b;
    static final int MSG_REVERSE = 0x9f1c;    // marche arrière : arg1 == 1 enclenchée, 0 relâchée
    static final int CAMERA_VIDEO = 0x0304;   // écrit par l'écran de recul Topway : 1 à l'affichage, 0 à la fermeture
    static final int MSG_SOURCE = 0x0301;     // source active : arg1
    static final int MSG_RADIO_INFO = 0x0401; // arg1 : 0 drapeaux, 1 bande, 2 fréquence (+obj nom), 3 PTY, 4 présélection
    static final int MSG_RADIO_PRESET = 0x0402; // arg1 & 0xff = index, arg2 = fréquence, obj = nom
    static final int MSG_RADIO_RDS = 0x0405;  // obj = texte RDS

    // --- Messages du module Bluetooth (canal 8)
    static final int BT_HFP = 0x0007;         // arg1 == 2 → téléphone connecté
    static final int BT_PLAY_STATE = 0x0015;  // arg1 != 0 → lecture
    static final int BT_ID3 = 0x0037;         // obj = TWObject : obj3 titre, obj4 "artiste-#album"
    static final int BT_PROGRESS = 0x0047;    // arg1 & 0x7fffffff = position, arg2 = durée
    static final int BT_MUSIC = 20;
    // Commande musique Bluetooth relayée à l'appli Bluetooth Topway (message inter-applis, canal principal) :
    // 1 lecture/pause, 3 suivant, 4 précédent, 5 lecture, 6 pause, 11 sourdine, 12 fin de sourdine.
    static final int BT_CONTROL = 0x9e08;           // write(20, 0) lecture/pause, 2 suivant, 3 précédent

    static final short[] MAIN_IDS = {
            0x0201, 0x0203, 0x0204, 0x0205, 0x020b, 0x020c, 0x0301, 0x0302,
            0x0401, 0x0402, 0x0404, 0x0405, 0x0406, (short) 0x9e00, (short) 0x9e01,
            (short) 0x9f00, (short) 0x9f10, (short) 0x9f1c};
    static final short[] BT_IDS = {
            0x010b, 0x0112, 0x0201, 0x0203, 0x0301, 0x0510, (short) 0x9e08, (short) 0x9e1d};
    static final int BT_CHANNEL = 8, BT_BAUD = 0x1c200;

    interface Listener {
        void onTw(Tw from, int what, int arg1, int arg2, Object obj);
    }

    final String name;
    boolean ok;
    String error = "";
    private Object util;
    private Class<?> cls;

    /**
     * @param channel -1 pour le canal principal, 8 pour le module Bluetooth
     * @param baud    0 pour open(ids), sinon open(ids, baud)
     */
    Tw(String name, int channel, short[] ids, int baud, final Listener listener) {
        this.name = name;
        try {
            cls = Class.forName("android.tw.john.TWUtil");
            if (channel < 0) {
                util = cls.getConstructor().newInstance();
            } else {
                Constructor<?> c = cls.getConstructor(int.class);
                util = c.newInstance(channel);
            }
            int r;
            if (baud > 0) {
                try {
                    r = (Integer) cls.getMethod("open", short[].class, int.class).invoke(util, ids, baud);
                } catch (NoSuchMethodException e) {
                    r = (Integer) cls.getMethod("open", short[].class).invoke(util, (Object) ids);
                }
            } else {
                r = (Integer) cls.getMethod("open", short[].class).invoke(util, (Object) ids);
            }
            if (r != 0) {
                error = "open() a renvoyé " + r;
                return;
            }
            cls.getMethod("start").invoke(util);
            final Tw self = this;
            Handler h = new Handler(Looper.getMainLooper(), msg -> {
                try {
                    listener.onTw(self, msg.what, msg.arg1, msg.arg2, msg.obj);
                } catch (Throwable e) {
                    Log.w(TAG, "message 0x" + Integer.toHexString(msg.what), e);
                }
                return true;
            });
            cls.getMethod("addHandler", String.class, Handler.class).invoke(util, "sandero_" + name, h);
            ok = true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "TWUtil indisponible (" + name + ")", t);
        }
    }

    static boolean present() {
        try {
            Class.forName("android.tw.john.TWUtil");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    int write(int id) {
        return call(new Class<?>[]{int.class}, id);
    }

    int write(int id, int a) {
        return call(new Class<?>[]{int.class, int.class}, id, a);
    }

    int write(int id, int a, int b) {
        return call(new Class<?>[]{int.class, int.class, int.class}, id, a, b);
    }

    private int call(Class<?>[] sig, Object... args) {
        if (!ok) return -1;
        try {
            Method m = cls.getMethod("write", sig);
            Object r = m.invoke(util, args);
            return r instanceof Integer ? (Integer) r : 0;
        } catch (Throwable t) {
            Log.w(TAG, "write échoué", t);
            return -1;
        }
    }

    void close() {
        if (util == null) return;
        try {
            cls.getMethod("removeHandler", String.class).invoke(util, "sandero_" + name);
        } catch (Throwable ignored) {
        }
        try {
            cls.getMethod("stop").invoke(util);
            cls.getMethod("close").invoke(util);
        } catch (Throwable ignored) {
        }
        ok = false;
    }

    /** Lit un champ (obj1…obj4) d'un TWUtil$TWObject, ou renvoie l'objet s'il est déjà du texte. */
    static String text(Object obj, String field) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        try {
            Field f = obj.getClass().getField(field);
            Object v = f.get(obj);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            try {
                Field f = obj.getClass().getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(obj);
                return v == null ? null : String.valueOf(v);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    /** Texte lisible d'un objet de message, pour l'écran de test. */
    static String describe(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[]) {
            byte[] b = (byte[]) obj;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(b.length, 24); i++) sb.append(String.format("%02x ", b[i]));
            return sb.append(b.length > 24 ? "…]" : "]").toString();
        }
        if (obj instanceof String) return "\"" + obj + "\"";
        StringBuilder sb = new StringBuilder(obj.getClass().getSimpleName()).append('{');
        for (String f : new String[]{"obj1", "obj2", "obj3", "obj4"}) {
            String v = text(obj, f);
            if (v != null) sb.append(f).append('=').append(v).append(' ');
        }
        return sb.append('}').toString();
    }
}
