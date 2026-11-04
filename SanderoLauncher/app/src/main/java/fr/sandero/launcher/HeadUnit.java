package fr.sandero.launcher;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * État de l'autoradio Topway (phares, source audio, radio, Bluetooth) et commandes,
 * via {@link Tw}. Un seul exemplaire pour toute l'appli.
 * Sans TWUtil (émulateur), tout reste inactif et {@link #available()} renvoie false.
 */
final class HeadUnit implements Tw.Listener {

    interface Listener {
        void onHeadUnitChanged();
    }

    /** Messages bruts, pour le Moniteur (même canal, aucune ouverture supplémentaire). */
    interface Raw {
        void onRaw(String channel, int what, int arg1, int arg2, Object obj);
    }

    static final class Preset {
        int freq;
        String name;
    }

    private static HeadUnit instance;

    static HeadUnit get() {
        if (instance == null) instance = new HeadUnit();
        return instance;
    }

    private Tw main, bt;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Raw> raws = new ArrayList<>();
    private String blocked = "";

    // --- État
    boolean lightsOn;
    boolean reverse;              // marche arrière enclenchée
    int source = -1;              // source audio signalée par le MCU (Tw.SRC_*)

    int radioBand = -1, radioFreq, radioFlags, radioPreset = -1;
    String radioName = "", radioText = "";
    final Preset[] presets = new Preset[6];

    boolean btConnected, btPlaying;
    boolean btPlayingGuess;       // sans canal Bluetooth : état déduit de nos propres commandes
    // Infos lues dans les annonces de l'appli Bluetooth Topway (voir BtInfo)
    String btDevice = "", btMac = "", btModule = "";
    int btConn = -1;              // -1 inconnu, 0 déconnecté, 1 connexion, 2 connecté, 3 déconnexion
    int btBattery = -1, btSignal = -1;
    String btTitle = "", btArtist = "", btAlbum = "";
    int btPos, btDur;
    long btPosAt;

    private HeadUnit() {
        for (int i = 0; i < presets.length; i++) presets[i] = new Preset();
    }

    /**
     * Ouvre le canal principal TWUtil, en écoute seule (aucune écriture au démarrage).
     * Le canal Bluetooth (TWUtil(8)) n'est JAMAIS ouvert par défaut : il est partagé avec
     * l'appli Bluetooth Topway et l'ouvrir en double peut bloquer le module Bluetooth.
     * Chaque ouverture est protégée par {@link Safe} : si elle fait planter l'appli,
     * elle est désactivée d'office au démarrage suivant.
     */
    synchronized void start(Context c) {
        if (!Tw.present()) return;
        if (main == null) {
            if (Safe.begin(c, "tw_main")) {
                main = new Tw("main", -1, Tw.MAIN_IDS, 0, this);
                Safe.end(c, "tw_main");
                Journal.log("TW", "canal principal : " + (main.ok ? "ouvert" : "échec " + main.error));
            } else {
                blocked = Safe.safeMode(c) ? "mode sans échec" : "désactivé après un plantage";
                Journal.log("TW", "canal principal non ouvert : " + blocked);
            }
        }
        boolean btWanted = c.getSharedPreferences("tools", Context.MODE_PRIVATE).getBoolean("bt_channel", false);
        if (bt == null && btWanted && Safe.begin(c, "tw_bt")) {
            bt = new Tw("bt", Tw.BT_CHANNEL, Tw.BT_IDS, Tw.BT_BAUD, this);
            Safe.end(c, "tw_bt");
            Journal.log("TW", "canal Bluetooth direct (expert) : " + (bt.ok ? "ouvert" : "échec " + bt.error));
        }
    }

    void addRaw(Raw r) {
        if (!raws.contains(r)) raws.add(r);
    }

    void removeRaw(Raw r) {
        raws.remove(r);
    }

    boolean available() {
        return main != null && main.ok;
    }

    boolean btAvailable() {
        return bt != null && bt.ok;
    }

    String status() {
        if (!Tw.present()) return "TWUtil absent (pas un autoradio Topway)";
        String m = main != null && main.ok ? "ok" : !blocked.isEmpty() ? blocked
                : "échec " + (main == null ? "" : main.error);
        String b = bt != null && bt.ok ? "canal ouvert (expert)" : "via l'appli Topway (sûr)";
        return "principal : " + m + " · Bluetooth : " + b;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onHeadUnitChanged();
    }

    // ================================================================ Messages reçus

    @Override
    public void onTw(Tw from, int what, int arg1, int arg2, Object obj) {
        String d;
        try {
            d = Tw.describe(obj);
        } catch (Throwable t) {
            d = "?";
        }
        Journal.log(from == null ? "SIMU" : from == bt ? "BT" : "MCU", String.format(java.util.Locale.ROOT,
                "0x%04x %s arg1=%d arg2=%d %s", what & 0xffff, name(what), arg1, arg2, d));
        for (Raw r : new ArrayList<>(raws)) {
            try {
                r.onRaw(from != null && from == bt ? "BT" : "MCU", what, arg1, arg2, obj);
            } catch (Throwable ignored) {
            }
        }
        if (from != null && from == bt) {
            onBt(what, arg1, arg2, obj);
            return;
        }
        switch (what) {
            case Tw.MSG_ILL:
                lightsOn = arg1 == 1;
                break;
            case Tw.MSG_REVERSE:
                reverse = arg1 != 0;
                break;
            case 0x020b:
            case 0x020c:
                reverse = false; // fin d'écran de recul (variantes Topway)
                break;
            case 0x9f00:
                // Titre envoyé par l'appli Bluetooth Topway à l'afficheur (arg1 = 8) : lu sans ouvrir son canal.
                if (arg1 == Tw.SRC_BT && obj instanceof String) {
                    String t = ((String) obj).trim();
                    btTitle = "Not Provided".equalsIgnoreCase(t) ? "" : t;
                    if (!btTitle.isEmpty()) btPlayingGuess = true;
                } else return;
                break;
            case Tw.MSG_SOURCE:
                source = arg1;
                break;
            case Tw.MSG_RADIO_INFO:
                onRadioInfo(arg1, arg2, obj);
                break;
            case Tw.MSG_RADIO_PRESET: {
                int index = arg1 & 0xff;
                if (index < presets.length) {
                    presets[index].freq = arg2;
                    presets[index].name = obj instanceof String ? ((String) obj).trim() : "";
                }
                break;
            }
            case Tw.MSG_RADIO_RDS:
                radioText = obj instanceof String ? ((String) obj).trim() : "";
                break;
            default:
                return;
        }
        changed();
    }

    private void onRadioInfo(int kind, int value, Object obj) {
        switch (kind) {
            case 0: radioFlags = value; break;
            case 1: radioBand = value; break;
            case 2:
                radioFreq = value;
                radioName = obj instanceof String ? ((String) obj).trim() : "";
                break;
            case 4: radioPreset = value; break;
        }
    }

    private void onBt(int what, int arg1, int arg2, Object obj) {
        switch (what) {
            case Tw.BT_HFP:
                btConnected = arg1 == 2;
                if (!btConnected) {
                    btPlaying = false;
                    btTitle = btArtist = btAlbum = "";
                }
                break;
            case Tw.BT_PLAY_STATE:
                btPlaying = arg1 != 0;
                btPosAt = SystemClock.elapsedRealtime();
                break;
            case Tw.BT_ID3: {
                String t = Tw.text(obj, "obj3");
                String rest = Tw.text(obj, "obj4");
                btTitle = t == null ? "" : t.trim();
                btArtist = rest == null ? "" : rest.trim();
                btAlbum = "";
                int i = btArtist.lastIndexOf("-#");
                if (i >= 0) {
                    btAlbum = btArtist.substring(i + 2).trim();
                    btArtist = btArtist.substring(0, i).trim();
                }
                break;
            }
            case Tw.BT_PROGRESS:
                btPos = arg1 & 0x7fffffff;
                btDur = arg2;
                btPosAt = SystemClock.elapsedRealtime();
                break;
            case Tw.MSG_SOURCE:
                source = arg1;
                break;
            default:
                return;
        }
        changed();
    }

    /** Nom lisible d'un message, pour le journal. */
    static String name(int what) {
        switch (what) {
            case Tw.MSG_ILL: return "(éclairage)";
            case Tw.MSG_REVERSE: return "(marche arrière)";
            case Tw.MSG_SOURCE: return "(source)";
            case Tw.MSG_KEY: return "(touche)";
            case Tw.MSG_MUTE: return "(sourdine)";
            case Tw.MSG_BRAKE: return "(frein à main)";
            case Tw.MSG_RADIO_INFO: return "(radio)";
            case Tw.MSG_RADIO_PRESET: return "(présélection)";
            case Tw.MSG_RADIO_RDS: return "(RDS)";
            case 0x9f00: return "(afficheur)";
            case 0x9f10: return "(extinction)";
            case 0x020b: case 0x020c: return "(fin de recul)";
            default: return "";
        }
    }

    // ================================================================ Infos Bluetooth (annonces Topway)

    void setBtDevice(String name, String mac) {
        String n = name == null ? "" : name.trim();
        if (!n.equals(btDevice)) Journal.log("BT", "téléphone : " + (n.isEmpty() ? "aucun" : n) + (mac == null ? "" : " (" + mac + ")"));
        btDevice = n;
        btMac = mac == null ? "" : mac;
        if (!n.isEmpty() && btConn < 0) btConn = 2;
        if (n.isEmpty() && btConn == 2) btConn = 0;
        changed();
    }

    void setBtId3(String title, String artist) {
        String t = title == null ? "" : title.trim();
        if (!"Not Provided".equalsIgnoreCase(t) && !t.isEmpty()) btTitle = t;
        String a = artist == null ? "" : artist.trim();
        btArtist = "Not Provided".equalsIgnoreCase(a) ? "" : a;
        changed();
    }

    void setBtModuleName(String name) {
        btModule = name == null ? "" : name.trim();
        changed();
    }

    void setBtConnection(int state) {
        if (state != btConn) Journal.log("BT", "état de connexion : " + btConnText(state) + " (" + state + ")");
        btConn = state;
        btConnected = state == 2;
        if (state == 0) {
            btDevice = "";
            btTitle = "";
            btArtist = "";
            btPlayingGuess = false;
        }
        changed();
    }

    void setBtPhone(int signal, int battery) {
        btSignal = signal;
        btBattery = battery;
        changed();
    }

    static String btConnText(int s) {
        switch (s) {
            case 0: return "déconnecté";
            case 1: return "connexion…";
            case 2: return "connecté";
            case 3: return "déconnexion…";
            default: return "inconnu";
        }
    }

    /** Position Bluetooth estimée maintenant (ms), d'après le dernier relevé. */
    int btPositionNow() {
        long p = btPos;
        if (btPlaying && btPosAt > 0) p += SystemClock.elapsedRealtime() - btPosAt;
        return (int) Math.min(p, btDur > 0 ? btDur : p);
    }

    // ================================================================ Commandes

    /** Signale au MCU que l'image de recul est affichée (comme l'écran Topway). */
    void cameraVideo(boolean on) {
        Journal.log("CAM", "write(0x0304, " + (on ? 1 : 0) + ")" + (available() ? "" : " ignoré (TWUtil absent)"));
        if (available()) main.write(Tw.CAMERA_VIDEO, on ? 1 : 0);
    }

    /** Pour les tests sans voiture (Moniteur) : simule un message du MCU. */
    void simulate(int what, int arg1) {
        onTw(null, what, arg1, 0, null);
    }

    /** Demande au MCU de basculer le son sur une source (radio, média Android, Bluetooth). */
    void requestSource(int src) {
        Journal.log("CMD", "demande de source " + src);
        Tw t = src == Tw.SRC_BT && btAvailable() ? bt : main;
        if (t != null && t.ok) t.write(Tw.SOURCE, 0xc0, src);
    }

    void releaseSource(int src) {
        Tw t = src == Tw.SRC_BT && btAvailable() ? bt : main;
        if (t != null && t.ok) t.write(Tw.SOURCE, 0xc0, src | 0x80);
    }

    // --- Bluetooth
    private void btCommand(int code) {
        Journal.log("CMD", "Bluetooth direct write(20, " + code + ")");
        if (!btAvailable()) return;
        if (source != Tw.SRC_BT) requestSource(Tw.SRC_BT);
        bt.write(Tw.BT_MUSIC, code);
    }

    /**
     * Commande musique Bluetooth SANS ouvrir le canal du module : message 0x9e08 sur le canal
     * principal, traité par l'appli Bluetooth Topway (qui bascule elle-même la source si besoin).
     */
    void btControl(int code) {
        if (!available()) return;
        if (source != Tw.SRC_BT && code != 6) requestSource(Tw.SRC_BT);
        Journal.log("CMD", "Bluetooth write(0x9e08, " + code + ")");
        main.write(Tw.BT_CONTROL, code);
        if (code == 1) btPlayingGuess = !btPlayingGuess;
        else if (code == 5 || code == 3 || code == 4) btPlayingGuess = true;
        else if (code == 6) btPlayingGuess = false;
        changed();
    }

    void btPlayPause() { btCommand(0); }
    void btNext() { btCommand(2); }
    void btPrev() { btCommand(3); }

    // --- Radio
    void radioOn() {
        if (!available()) return;
        requestSource(Tw.SRC_RADIO);
        main.write(0x0301, 0xff);
        main.write(0x0406, 0);
        main.write(0x0401, 0xff); // demande fréquence, bande, présélections
        main.write(0x0404, 0xff);
        main.write(0x0203, 0xff);
    }

    void radioOff() {
        releaseSource(Tw.SRC_RADIO);
    }

    void radioSeek(boolean up) { radioCmd(0x0401, 1, up ? 0 : 1); }
    void radioStep(boolean up) { radioCmd(0x0401, 2, up ? 0 : 1); }
    void radioAutoStore() { radioCmd(0x0401, 0, 0); }
    void radioPreset(int index) { radioCmd(0x0402, index, Math.max(radioBand, 0)); }

    /** Bascule FM ↔ AM (bande 0 = FM, 2 = AM, comme l'appli Radio Topway). */
    void radioToggleBand() {
        radioCmd(0x0401, 5, isAm() ? 0 : 2);
    }

    private void radioCmd(int id, int a, int b) {
        Journal.log("CMD", String.format(java.util.Locale.ROOT, "radio write(0x%04x, %d, %d)", id, a, b));
        if (!available()) return;
        if (source != Tw.SRC_RADIO) requestSource(Tw.SRC_RADIO);
        main.write(id, a, b);
    }

    boolean isAm() {
        return radioFreq > 0 && radioFreq < 3000;
    }

    /** Fréquence lisible : 10120 → « 101.2 », 1035 → « 1035 ». */
    static String formatFreq(int f) {
        if (f <= 0) return "--.-";
        if (f < 3000) return String.valueOf(f);
        return String.format(java.util.Locale.ROOT, "%.1f", f / 100f);
    }
}
