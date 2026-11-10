package fr.sandero.launcher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lecture OBD2 par un module ELM327 (ou clone), comme le fait Torque.
 *
 * Liaisons : Bluetooth (SPP), Wi-Fi (TCP, souvent 192.168.0.10:35000) ou USB (voir {@link ObdUsb}).
 * Tout se passe dans un fil dédié : connexion → initialisation AT → recherche du protocole
 * du véhicule (0100) → liste des PID pris en charge → infos (VIN…) → codes défaut →
 * lecture en boucle. En cas de coupure : nouvel essai toutes les 5 s tant que l'écran le demande.
 *
 * Lecture seule : aucune commande d'effacement ou d'écriture n'est envoyée au véhicule
 * (sauf ce que l'utilisateur tape lui-même dans le terminal).
 * N'utilise jamais TWUtil ; le Bluetooth passe par l'API Android standard, jamais par le canal 8.
 */
final class Obd {

    /** Une liaison série vers le module. */
    interface Link {
        void write(byte[] b) throws IOException;

        /** Lit ce qui est arrivé (attend au plus timeoutMs) ; 0 si rien. */
        int read(byte[] b, int timeoutMs) throws IOException;

        /** Change la vitesse (USB seulement) ; false si sans objet. */
        boolean setBaud(int baud);

        void close();
    }

    static final class Value {
        double num = Double.NaN;
        String text;
        boolean noData;
        long time;
        int fails;
    }

    static final int T_BT = 0, T_WIFI = 1, T_USB = 2;
    static final String[] TRANSPORTS = {"Bluetooth", "Wi-Fi", "USB"};
    static final int OFF = 0, CONNECTING = 1, INIT = 2, RUNNING = 3, ERROR = 4;
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final String[] FUELS = {"Essence", "Diesel", "GPL"};

    private static Obd instance;

    static synchronized Obd get() {
        if (instance == null) instance = new Obd();
        return instance;
    }

    // ---- état lu par l'écran (fil principal)
    volatile int state = OFF;
    volatile String status = "Déconnecté";
    volatile String elmVersion = "", elmDevice = "", protocol = "", protocolNum = "";
    volatile String vin = "", calId = "", ecuName = "", voltage = "";
    volatile boolean milOn;
    volatile int dtcCount = -1;
    volatile List<String> dtcStored = Collections.emptyList(), dtcPending = Collections.emptyList(),
            dtcPermanent = Collections.emptyList();
    volatile long dtcTime;
    volatile String dtcStamp = "";
    volatile List<Integer> supported01 = Collections.emptyList(), supported09 = Collections.emptyList();
    /** Ce que l'écran affiche, dans l'ordre (change → version++). */
    volatile List<Integer> display = Collections.emptyList();
    volatile int displayVersion;
    final Map<Integer, Value> values = new ConcurrentHashMap<>();
    volatile int requestsPerSec;
    volatile long cycleMs;
    volatile String csvFile = "";

    // ---- réglages (posés par l'écran)
    volatile boolean csv;
    volatile int fuel;
    volatile boolean wantDtc, wantProbe;
    /** Résultat de la dernière recherche de conso (onglet Infos). */
    volatile String probeReport = "";
    /** PID non annoncés mais qui répondent (trouvés par la recherche de conso). */
    private final Set<Integer> extra01 = Collections.synchronizedSet(new java.util.HashSet<>());
    final ConcurrentLinkedQueue<String> terminal = new ConcurrentLinkedQueue<>();

    private final ArrayDeque<String> raw = new ArrayDeque<>();
    private Thread worker;
    private volatile boolean running;
    private volatile Link link;
    private Context app;
    private boolean can;
    private int timeouts, reqCount;
    private long reqStamp;
    private FileWriter csvOut;
    private long csvLast;
    private int csvVersion = -1;

    private Obd() {
    }

    /** Réglages de l'estimation de conso (cylindrée, remplissage, correction après un plein). */
    void loadFuelPrefs(android.content.SharedPreferences p) {
        displacementCc = Math.max(500, Math.min(8000, p.getInt("cc", 1461)));
        volEff = Math.max(30, Math.min(130, p.getInt("ve", 85)));
        fuelCorrection = Math.max(30, Math.min(300, p.getInt("k", 100)));
    }

    boolean running() {
        return running;
    }

    /**
     * Connexion automatique (réglage « auto » de l'écran OBD2) : au démarrage du service et au
     * réveil de l'autoradio, avec le dernier module choisi. Reste connecté en arrière-plan.
     */
    static void autoStart(Context c, String why) {
        try {
            android.content.SharedPreferences p = c.getSharedPreferences("obd", Context.MODE_PRIVATE);
            if (!p.getBoolean("auto", false)) return;
            Obd o = get();
            if (o.running()) return;
            if (Safe.safeMode(c)) {
                Journal.log("OBD", "connexion automatique ignorée : mode sans échec");
                return;
            }
            int t = p.getInt("transport", T_BT);
            String target = t == T_WIFI ? p.getString("wifi", "192.168.0.10:35000")
                    : t == T_USB ? p.getString("usb", "") : p.getString("bt", "");
            if (target.isEmpty()) {
                Journal.log("OBD", "connexion automatique : aucun module choisi");
                return;
            }
            o.csv = p.getBoolean("csv", false);
            o.fuel = p.getInt("fuel", 0);
            o.loadFuelPrefs(p);
            Journal.log("OBD", "connexion automatique (" + why + ")");
            o.start(c, t, target, p.getString("proto", "0"));
        } catch (Throwable e) {
            Journal.error("OBD", "connexion automatique", e);
        }
    }

    // ================================================================ Démarrer / arrêter

    /**
     * @param target adresse Bluetooth, « hôte:port » ou nom du périphérique USB
     * @param proto  protocole ELM (« 0 » = automatique)
     */
    synchronized void start(Context c, final int transport, final String target, final String proto) {
        stop();
        app = c.getApplicationContext();
        running = true;
        Journal.log("OBD", "démarrage : " + TRANSPORTS[transport] + " → " + target + " · protocole " + proto);
        worker = new Thread(() -> loop(transport, target, proto), "obd");
        worker.start();
    }

    synchronized void stop() {
        if (!running && worker == null) return;
        running = false;
        closeLink();
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
        closeCsv();
        setState(OFF, "Déconnecté");
        Journal.log("OBD", "arrêt");
    }

    /** Vrai tant que ce fil est le fil OBD en cours (un ancien fil s'arrête de lui-même). */
    private boolean alive() {
        return running && Thread.currentThread() == worker;
    }

    private void loop(int transport, String target, String proto) {
        while (alive()) {
            Link mine = null;
            try {
                setState(CONNECTING, "Connexion " + TRANSPORTS[transport] + " à " + target + "…");
                mine = open(transport, target);
                if (!alive()) break;
                link = mine;
                Journal.log("OBD", "liaison ouverte (" + TRANSPORTS[transport] + ")");
                session(transport, proto);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                if (!alive()) break;
                Journal.error("OBD", "échec", e);
                setState(ERROR, friendly(e, transport) + " · nouvel essai dans 5 s");
            } finally {
                if (mine != null) {
                    if (link == mine) link = null;
                    try {
                        mine.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!alive()) break;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (Thread.currentThread() == worker || worker == null) closeCsv();
    }

    /** Message court pour l'écran (le détail complet est dans le journal). */
    private static String friendly(Throwable e, int transport) {
        if (e instanceof java.net.ConnectException || e instanceof SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException) {
            return "Module Wi-Fi injoignable (autoradio connecté au Wi-Fi du module ?)";
        }
        if (e instanceof java.net.SocketException) return "Liaison coupée par le module";
        String m = e.getMessage();
        if (transport == T_BT && m != null && (m.contains("read failed") || m.contains("socket might closed")
                || m.contains("timeout"))) {
            return "Module Bluetooth injoignable (contact mis ? module appairé ?)";
        }
        return "Erreur : " + (m == null ? e.getClass().getSimpleName() : m);
    }

    private void setState(int s, String msg) {
        if (s != OFF && !alive()) return;
        state = s;
        if (!msg.equals(status)) {
            status = msg;
            Journal.log("OBD", msg);
        }
    }

    private void closeLink() {
        Link l = link;
        link = null;
        if (l != null) {
            try {
                l.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ================================================================ Liaisons

    private Link open(int transport, String target) throws Exception {
        if (transport == T_WIFI) return openWifi(target);
        String step = transport == T_BT ? "obd_bt" : "obd_usb";
        if (!Safe.begin(app, step)) {
            setState(ERROR, "Liaison " + TRANSPORTS[transport] + " désactivée après un plantage (bouton Réactiver)");
            running = false;
            throw new InterruptedException();
        }
        try {
            return transport == T_BT ? openBt(target) : ObdUsb.open(app, target);
        } finally {
            Safe.end(app, step);
        }
    }

    private static final class StreamLink implements Link {
        final InputStream in;
        final OutputStream out;
        final java.io.Closeable c;
        final Socket tcp;

        StreamLink(InputStream in, OutputStream out, java.io.Closeable c, Socket tcp) {
            this.in = in; this.out = out; this.c = c; this.tcp = tcp;
        }

        @Override public void write(byte[] b) throws IOException {
            out.write(b);
            out.flush();
        }

        @Override public int read(byte[] b, int timeoutMs) throws IOException {
            if (tcp != null) {
                tcp.setSoTimeout(Math.max(1, timeoutMs));
                try {
                    int n = in.read(b);
                    if (n < 0) throw new IOException("liaison fermée par le module");
                    return n;
                } catch (SocketTimeoutException e) {
                    return 0;
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                int a = in.available();
                if (a > 0) {
                    int n = in.read(b, 0, Math.min(a, b.length));
                    if (n < 0) throw new IOException("liaison fermée par le module");
                    return n;
                }
                if (System.currentTimeMillis() >= end) return 0;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new IOException("interrompu");
                }
            }
        }

        @Override public boolean setBaud(int baud) {
            return false;
        }

        @Override public void close() {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private Link openWifi(String target) throws IOException {
        String host = target;
        int port = 35000;
        int i = target.lastIndexOf(':');
        if (i > 0) {
            host = target.substring(0, i).trim();
            try {
                port = Integer.parseInt(target.substring(i + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 5000);
        return new StreamLink(s.getInputStream(), s.getOutputStream(), s, s);
    }

    @SuppressLint("MissingPermission")
    private Link openBt(String address) throws Exception {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) throw new IOException("pas de Bluetooth Android sur cet appareil");
        if (!a.isEnabled()) {
            // L'autoradio éteint le Bluetooth Android à chaque démarrage (journal 05/11) : on le rallume,
            // puisque la connexion a été demandée (bouton ou connexion automatique). Effet constaté :
            // la musique du téléphone hache 4-5 s, puis repart.
            boolean asked = false;
            try {
                asked = a.enable();
            } catch (Throwable e) {
                Journal.error("OBD", "allumage Bluetooth", e);
            }
            Journal.log("OBD", "Bluetooth Android éteint → allumage " + (asked ? "demandé" : "refusé"));
            setState(CONNECTING, "Allumage du Bluetooth Android…");
            for (int i = 0; i < 60 && alive() && !a.isEnabled(); i++) {
                Thread.sleep(250);
                if (i == 24 && !a.isEnabled()) {
                    try {
                        a.enable();   // le premier essai est parfois ignoré (vu le 05/11)
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!a.isEnabled()) throw new IOException("Bluetooth Android éteint (allumage sans effet)");
            Journal.log("OBD", "Bluetooth Android allumé");
            Thread.sleep(1500);   // laisse la pile Bluetooth finir de démarrer
        }
        try {
            a.cancelDiscovery();
        } catch (Throwable ignored) {
        }
        BluetoothDevice d = a.getRemoteDevice(address);
        Journal.log("OBD", "Bluetooth : " + d.getName() + " · appairé " + (d.getBondState() == BluetoothDevice.BOND_BONDED ? "oui" : "non"));
        // Même ordre d'essais que la plupart des applis OBD : non sécurisé, sécurisé, canal 1 forcé.
        String[] ways = {"insecure", "secure", "canal 1"};
        Exception last = null;
        for (String w : ways) {
            if (!alive()) throw new InterruptedException();
            BluetoothSocket s = null;
            try {
                if (w.equals("insecure")) s = d.createInsecureRfcommSocketToServiceRecord(SPP);
                else if (w.equals("secure")) s = d.createRfcommSocketToServiceRecord(SPP);
                else s = (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
                s.connect();
                Journal.log("OBD", "Bluetooth connecté (" + w + ")");
                return new StreamLink(s.getInputStream(), s.getOutputStream(), s, null);
            } catch (Exception e) {
                last = e;
                Journal.error("OBD", "Bluetooth " + w, e);
                if (s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        throw last != null ? last : new IOException("connexion Bluetooth impossible");
    }

    // ================================================================ Échanges ELM327

    /** Envoie une commande et attend le « > » de l'ELM. */
    String cmd(String c, int timeoutMs) throws IOException, InterruptedException {
        Link l = link;
        if (l == null || !alive()) throw new InterruptedException();
        l.write((c + "\r").getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[512];
        long end = System.currentTimeMillis() + timeoutMs;
        boolean done = false;
        while (!done && alive()) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            int n = l.read(buf, (int) Math.min(200, left));
            for (int i = 0; i < n; i++) {
                char ch = (char) (buf[i] & 0xFF);
                if (ch == '>') {
                    done = true;
                    break;
                }
                if (ch != 0) sb.append(ch);
            }
        }
        if (!alive()) throw new InterruptedException();
        String r = sb.toString();
        raw("→ " + c);
        raw("← " + oneLine(r) + (done ? "" : "  (délai dépassé)"));
        countRequest();
        if (done) timeouts = 0;
        else if (++timeouts >= 5) throw new IOException("le module ne répond plus");
        return r;
    }

    private void countRequest() {
        reqCount++;
        long now = System.currentTimeMillis();
        if (now - reqStamp >= 1000) {
            requestsPerSec = (int) (reqCount * 1000 / Math.max(1, now - reqStamp));
            reqCount = 0;
            reqStamp = now;
        }
    }

    private void drain(int ms) throws IOException {
        Link l = link;
        if (l == null) return;
        byte[] b = new byte[512];
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (l.read(b, 50) <= 0 && System.currentTimeMillis() > end - ms / 2) break;
        }
    }

    static String oneLine(String r) {
        return r.replace('\r', ' ').replace('\n', ' ').replaceAll(" {2,}", " ").trim();
    }

    /** Réponse nettoyée : lignes en majuscules, sans espaces, sans écho ni « SEARCHING… ». */
    static List<String> lines(String r, String echo) {
        List<String> out = new ArrayList<>();
        for (String l : r.split("[\r\n]+")) {
            String s = l.replace(" ", "").toUpperCase(Locale.ROOT).trim();
            if (s.isEmpty() || s.equals(echo) || s.startsWith("SEARCHING") || s.startsWith("BUSINIT")) continue;
            out.add(s);
        }
        return out;
    }

    private static boolean isHex(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    private static int[] hex(String s) {
        int[] b = new int[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /**
     * Messages reçus (un par calculateur). Les réponses CAN en plusieurs trames
     * (« 014 », « 0:… », « 1:… ») sont recollées.
     */
    static List<int[]> messages(String r, String echo) {
        List<String> ls = lines(r, echo);
        List<int[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String l : ls) {
            if (l.length() > 2 && l.charAt(1) == ':' && isHex(l.substring(0, 1)) && isHex(l.substring(2))) {
                cur.append(l.substring(2));
            } else if (l.length() == 3 && isHex(l)) {
                if (cur.length() > 0) out.add(hex(cur.toString()));
                cur.setLength(0);
            } else if (isHex(l) && l.length() % 2 == 0) {
                if (cur.length() > 0) out.add(hex(cur.toString()));
                cur.setLength(0);
                out.add(hex(l));
            }
        }
        if (cur.length() > 0) out.add(hex(cur.toString()));
        return out;
    }

    private static boolean noData(String r) {
        String u = r.toUpperCase(Locale.ROOT);
        return u.contains("NO DATA") || u.contains("NODATA") || u.contains("ERROR") || u.contains("UNABLE")
                || u.contains("STOPPED") || u.contains("?");
    }

    // ================================================================ Session

    private void session(int transport, String proto) throws Exception {
        values.clear();
        display = Collections.emptyList();
        displayVersion++;
        supported01 = Collections.emptyList();
        supported09 = Collections.emptyList();
        timeouts = 0;
        setState(INIT, "Initialisation du module…");
        drain(300);

        // --- Réveil du module (USB : on essaie plusieurs vitesses)
        String r = "";
        int[] bauds = transport == T_USB ? new int[]{38400, 115200, 9600, 57600, 230400} : new int[]{0};
        for (int baud : bauds) {
            if (baud > 0 && link.setBaud(baud)) Journal.log("OBD", "USB : essai à " + baud + " bauds");
            timeouts = 0;
            r = cmd("ATZ", 3000);
            if (!r.toUpperCase(Locale.ROOT).contains("ELM")) r = cmd("ATI", 2000);
            if (r.toUpperCase(Locale.ROOT).contains("ELM") || r.toUpperCase(Locale.ROOT).contains("OK")) break;
        }
        for (String l : r.split("[\r\n]+")) if (l.toUpperCase(Locale.ROOT).contains("ELM")) elmVersion = l.trim();
        if (elmVersion.isEmpty() && oneLine(r).isEmpty()) throw new IOException("le module ne répond pas (est-ce un ELM327 ?)");
        Journal.log("OBD", "module : " + (elmVersion.isEmpty() ? oneLine(r) : elmVersion));

        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP" + proto}) {
            String a = cmd(c, 2000);
            if (!a.toUpperCase(Locale.ROOT).contains("OK")) Journal.log("OBD", c + " → " + oneLine(a));
        }
        elmDevice = clean(cmd("AT@1", 1500));
        readVoltage();

        // Le module reste ouvert ; si le véhicule se tait (moteur coupé, calculateur en veille),
        // on referme la session K-line (ATPC) et on recherche à nouveau le véhicule.
        boolean first = true;
        while (alive()) {
            vehicle(first);
            first = false;
            if (!alive()) break;
            for (Map.Entry<Integer, Value> e : values.entrySet()) {
                if (e.getKey() != ObdPids.ID_VOLT) e.getValue().noData = true;
            }
            try {
                cmd("ATPC", 2000);
            } catch (IOException e) {
                Journal.log("OBD", "ATPC : " + e.getMessage());
            }
            timeouts = 0;
            Thread.sleep(2000);
        }
    }

    /** Délai sans aucune réponse du véhicule avant de le considérer comme perdu. */
    private static final long LOST_MS = 8000;
    /** Un PID qui ne répond plus est redemandé toutes les… */
    private static final long RETRY_MS = 15000;

    /** Une connexion au calculateur ; revient quand le véhicule ne répond plus. */
    private void vehicle(boolean first) throws Exception {
        String r;
        while (alive()) {
            setState(INIT, "Recherche du protocole du véhicule… (contact mis)");
            r = cmd("0100", 15000);
            if (find(messages(r, "0100"), 0x41, 0x00) != null) break;
            setState(INIT, "Le véhicule ne répond pas (" + oneLine(r) + ") · contact mis ? nouvel essai…");
            readVoltage();
            terminalStep();
            Thread.sleep(3000);
        }
        if (!alive()) return;
        if (!first) Journal.log("OBD", "véhicule retrouvé");
        protocol = clean(cmd("ATDP", 2000));
        protocolNum = clean(cmd("ATDPN", 2000));
        String pn = protocolNum.startsWith("A") && protocolNum.length() > 1 ? protocolNum.substring(1) : protocolNum;
        can = pn.length() == 1 && "6789ABC".contains(pn);
        Journal.log("OBD", "protocole : " + protocol + " (" + protocolNum + ")" + (can ? " · CAN" : ""));

        // --- PID pris en charge
        supported01 = scanSupported("01", 0x41);
        Journal.log("OBD", "mode 01 : " + supported01.size() + " PID pris en charge : " + pidList(supported01));
        supported09 = scanSupported("09", 0x49);
        if (!supported09.isEmpty()) Journal.log("OBD", "mode 09 : " + pidList(supported09));
        readInfo();
        readDtc();

        // --- Listes de lecture
        List<Integer> fast = new ArrayList<>(), slow = new ArrayList<>();
        Set<Integer> shown = new LinkedHashSet<>();
        for (ObdPids.P p : ObdPids.all()) {
            if (p.pid >= 0x1000) continue;
            if (!supported01.contains(p.pid) && !extra01.contains(p.pid)) continue;
            if (p.once) {
                readPid(p.pid);
            } else if (p.fast) fast.add(p.pid);
            else slow.add(p.pid);
        }
        for (int pid : supported01) {   // PID inconnus de la table : affichés en brut
            if ((pid & 0x1F) == 0) continue;  // masques 0x20, 0x40…
            if (ObdPids.get(pid) == null) slow.add(pid);
        }
        shown.add(ObdPids.ID_VOLT);
        if (first) resetTrip();
        int src = fuelSource(fast);
        if (src != 0) {
            shown.add(ObdPids.ID_LH);
            if (supported01.contains(0x0D)) {
                shown.add(ObdPids.ID_L100);
                shown.add(ObdPids.ID_AVG);
            }
            shown.add(ObdPids.ID_LTRIP);
            if (supported01.contains(0x0D)) shown.add(ObdPids.ID_KTRIP);
        }
        Journal.log("OBD", "conso : " + FUEL_SOURCES[src] + (src == 4 ? " · moteur " + (diesel() ? "diesel" : "essence/GPL")
                + " · cylindrée " + displacementCc + " cm³ · remplissage " + volEff + " % · correction " + fuelCorrection + " %" : ""));
        List<Integer> ordered = new ArrayList<>(fast);
        ordered.addAll(slow);
        for (ObdPids.P p : ObdPids.all()) if (p.once && supported01.contains(p.pid)) ordered.add(p.pid);
        Collections.sort(ordered, (x, y) -> order(x) - order(y));
        shown.addAll(ordered);
        display = new ArrayList<>(shown);
        displayVersion++;
        Journal.log("OBD", "lecture : " + fast.size() + " rapides, " + slow.size() + " lentes");

        // --- Boucle
        setState(RUNNING, "Connecté · " + (protocol.isEmpty() ? "véhicule" : protocol));
        int cycle = 0, slowIdx = 0;
        long lastOk = System.currentTimeMillis();
        // PID qui ne répondent plus : redemandés de temps en temps (avant : abandonnés pour toujours,
        // d'où des valeurs figées à l'écran après un arrêt du moteur, journal du 05/11).
        Map<Integer, Long> parked = new java.util.LinkedHashMap<>();
        Set<Integer> fastSet = new java.util.HashSet<>(fast);
        while (alive()) {
            long t0 = System.currentTimeMillis();
            terminalStep();
            if (wantDtc) {
                wantDtc = false;
                readDtc();
            }
            if (wantProbe) {
                wantProbe = false;
                List<Integer> found = probeFuel();
                lastOk = System.currentTimeMillis();
                boolean add = false;
                for (int p : found) {
                    if (fast.contains(p) || slow.contains(p)) continue;
                    fast.add(p);
                    fastSet.add(p);
                    add = true;
                }
                if (add) {
                    Set<Integer> d = new LinkedHashSet<>(display);
                    if (fuelSource(fast) != 0) {
                        d.add(ObdPids.ID_LH);
                        d.add(ObdPids.ID_L100);
                        d.add(ObdPids.ID_AVG);
                        d.add(ObdPids.ID_LTRIP);
                        d.add(ObdPids.ID_KTRIP);
                    }
                    d.addAll(found);
                    List<Integer> l = new ArrayList<>(d);
                    Collections.sort(l, (x, y) -> order(x) - order(y));
                    display = l;
                    displayVersion++;
                }
                setState(RUNNING, "Connecté · " + (protocol.isEmpty() ? "véhicule" : protocol));
            }
            for (int i = 0; i < fast.size() && alive(); i++) {
                int res = readPid(fast.get(i));
                if (res > 0) lastOk = System.currentTimeMillis();
                else if (res < 0) parked.put(fast.remove(i--), t0 + RETRY_MS);
            }
            if (!slow.isEmpty()) {
                // Une valeur lente par tour, plus toutes au premier tour.
                int n = cycle == 0 ? slow.size() : 1;
                for (int k = 0; k < n && !slow.isEmpty() && alive(); k++) {
                    slowIdx %= slow.size();
                    int res = readPid(slow.get(slowIdx));
                    if (res > 0) lastOk = System.currentTimeMillis();
                    if (res < 0) parked.put(slow.remove(slowIdx), t0 + RETRY_MS);
                    else slowIdx++;
                }
            }
            // Un PID en attente par tour, quand son heure est venue.
            for (Map.Entry<Integer, Long> e : parked.entrySet()) {
                if (e.getValue() > t0) continue;
                int pid = e.getKey();
                int res = readPid(pid);
                if (res > 0) {
                    parked.remove(pid);
                    lastOk = System.currentTimeMillis();
                    Journal.log("OBD", "PID " + String.format("%02X", pid) + " : répond à nouveau");
                    if (fastSet.contains(pid)) fast.add(pid);
                    else slow.add(pid);
                } else {
                    e.setValue(t0 + RETRY_MS);
                }
                break;
            }
            if (System.currentTimeMillis() - lastOk > LOST_MS) {
                setState(INIT, "Le véhicule ne répond plus (moteur coupé ?) · nouvelle recherche…");
                return;
            }
            if (cycle % 5 == 0) readVoltage();
            derived();
            writeCsv();
            cycle++;
            cycleMs = System.currentTimeMillis() - t0;
            if (fast.isEmpty()) Thread.sleep(300);
        }
    }

    private static int order(int pid) {
        ObdPids.P p = ObdPids.get(pid);
        return p != null ? p.order : 10000 + pid;
    }

    private static String pidList(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int p : l) sb.append(String.format("%02X ", p));
        return sb.toString().trim();
    }

    private static String clean(String r) {
        StringBuilder sb = new StringBuilder();
        for (String l : r.split("[\r\n]+")) {
            String s = l.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static int[] find(List<int[]> msgs, int mode, int pid) {
        for (int[] m : msgs) if (m.length >= 2 && m[0] == mode && m[1] == pid) return m;
        return null;
    }

    private List<Integer> scanSupported(String mode, int resp) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (int base = 0; base <= 0xE0 && alive(); base += 0x20) {
            String c = mode + String.format("%02X", base);
            List<int[]> msgs = messages(cmd(c, 5000), c);
            long mask = 0;
            boolean got = false;
            for (int[] m : msgs) {
                if (m.length >= 6 && m[0] == resp && m[1] == base) {
                    got = true;
                    mask |= ((long) m[2] << 24) | (m[3] << 16) | (m[4] << 8) | m[5];
                }
            }
            if (!got) break;
            for (int i = 0; i < 32; i++) if ((mask & (1L << (31 - i))) != 0) out.add(base + i + 1);
            if ((mask & 1) == 0) break;
        }
        return out;
    }

    /** Lit un PID du mode 01 : 1 lu, 0 pas de réponse cette fois, -1 ne répond plus (3 échecs). */
    private int readPid(int pid) throws Exception {
        String c = String.format("01%02X", pid);
        String r = cmd(c, 2000);
        int[] m = find(messages(r, c), 0x41, pid);
        Value v = values.get(pid);
        if (v == null) {
            v = new Value();
            values.put(pid, v);
        }
        if (m == null) {
            v.fails++;
            if (v.fails >= 3) {
                if (!v.noData) Journal.log("OBD", "PID " + String.format("%02X", pid) + " : plus de réponse ("
                        + oneLine(r) + "), redemandé toutes les " + (RETRY_MS / 1000) + " s");
                v.noData = true;
                return -1;
            }
            return 0;
        }
        int[] d = new int[m.length - 2];
        System.arraycopy(m, 2, d, 0, d.length);
        ObdPids.P p = ObdPids.get(pid);
        if (p == null || !p.decode(d, v)) {
            v.text = ObdPids.hex(d);
            v.num = Double.NaN;
        }
        v.fails = 0;
        v.noData = false;
        v.time = System.currentTimeMillis();
        if (pid == 0x01 && d.length >= 1) {
            milOn = (d[0] & 0x80) != 0;
            dtcCount = d[0] & 0x7F;
        }
        return 1;
    }

    private void readVoltage() throws Exception {
        String r = clean(cmd("ATRV", 1500));
        voltage = r;
        try {
            double v = Double.parseDouble(r.replaceAll("[^0-9.]", ""));
            put(ObdPids.ID_VOLT, v);
        } catch (NumberFormatException ignored) {
        }
    }

    private void put(int id, double num) {
        Value v = values.get(id);
        if (v == null) {
            v = new Value();
            values.put(id, v);
        }
        v.num = num;
        v.noData = false;
        v.time = System.currentTimeMillis();
    }

    static final String[] FUEL_SOURCES = {"aucune source", "PID 5E (débit carburant)",
            "PID A2 (carburant injecté)", "PID 10 (débit d'air)", "estimation pression d'admission + régime + charge"};

    /**
     * D'où vient la conso : 1 PID 5E, 2 PID A2 (mg/coup), 3 débit d'air (essence/GPL),
     * 4 estimation par la pression d'admission (MAP 0B + régime 0C, + charge 04 en diesel), 0 rien.
     * La source 4 est celle de la Sandero dCi (ni 5E, ni A2, ni MAF), comme dans Car Scanner.
     */
    private int fuelSource(List<Integer> readList) {
        if (readList.contains(0x5E)) return 1;
        if (readList.contains(0xA2) && readList.contains(0x0C)) return 2;
        if (readList.contains(0x10) && !diesel()) return 3;
        if (readList.contains(0x0B) && readList.contains(0x0C) && (readList.contains(0x04) || !diesel())) return 4;
        return 0;
    }

    /**
     * Diesel : choisi dans l'écran, ou deviné quand le réglage est resté sur « Essence » (défaut) :
     * pression de rampe 23 sans papillon 11 ni débit d'air 10, cas de la Sandero dCi.
     */
    boolean diesel() {
        if (fuel == 1) return true;
        if (fuel == 2) return false;
        List<Integer> s = supported01;
        return s.contains(0x23) && !s.contains(0x11) && !s.contains(0x10);
    }

    /** Cylindrée (cm³) et remplissage (%) pour l'estimation par la MAP (prefs obd « cc », « ve »). */
    volatile int displacementCc = 1461, volEff = 85;
    /** Correction (%) réglée après un plein (pref obd « k ») : conso réelle ÷ conso affichée × 100. */
    volatile int fuelCorrection = 100;

    // Trajet : débit et vitesse intégrés depuis la connexion.
    private double tripLiters, tripKm;
    private long tripLast;

    private void resetTrip() {
        tripLiters = 0;
        tripKm = 0;
        tripLast = 0;
    }

    // ================================================================ Recherche de la conso

    /**
     * Cherche où le calculateur donne le carburant injecté (la Sandero diesel n'annonce ni 5E
     * ni débit d'air : PID 01 04 05 0B 0C 0D 0F 1C 20 21 23 30 31, relevé du 05/11/2026).
     * 1) PID standard non annoncés (certains calculateurs répondent quand même) ;
     * 2) blocs de données Renault (service KWP 21, lecture seule) du calculateur moteur
     *    (adresse 7A, probable) : chaque bloc qui répond est copié tel quel dans le journal,
     *    pour repérer ensuite l'octet du débit carburant en comparant ralenti et 2500 tr/min.
     * Aucune écriture : seulement des lectures (21xx) et au besoin une ouverture de session (10C0).
     */
    private List<Integer> probeFuel() throws Exception {
        List<Integer> found = new ArrayList<>();
        StringBuilder rep = new StringBuilder();
        rep.append("Recherche du ").append(Clock.format("HH:mm:ss")).append(" · régime ").append(format(0x0C))
                .append(" tr/min · charge ").append(format(0x04)).append(" %\n");
        Journal.log("OBD", "recherche conso : début · régime " + format(0x0C) + " tr/min · charge " + format(0x04) + " %");
        setState(RUNNING, "Recherche de la conso : PID standard…");
        for (int pid : new int[]{0x5E, 0xA2, 0x10, 0x9D, 0x9E, 0x83}) {
            if (!alive()) return found;
            String c = String.format("01%02X", pid);
            String r = cmd(c, 2000);
            int[] m = find(messages(r, c), 0x41, pid);
            String line = c + " → " + (m == null ? oneLine(r) : ObdPids.hex(m));
            rep.append(line).append('\n');
            Journal.log("OBD", "recherche conso : " + line);
            if (m != null && ObdPids.get(pid) != null) {
                found.add(pid);
                extra01.add(pid);
            }
        }

        // --- Calculateur Renault (service 21)
        setState(RUNNING, "Recherche de la conso : calculateur Renault…");
        String header = null;
        boolean session = false, customHeader = false;
        for (String h : new String[]{"", "817AF1"}) {
            if (!alive()) return found;
            if (!h.isEmpty()) {
                cmd("ATSH" + h, 2000);
                customHeader = true;
            }
            String r = cmd("2180", 3000);
            String one = oneLine(r);
            rep.append("en-tête ").append(h.isEmpty() ? "standard" : h).append(" · 2180 → ").append(one).append('\n');
            Journal.log("OBD", "recherche conso : en-tête " + (h.isEmpty() ? "standard" : h) + " · 2180 → " + one);
            // Réponse 7F 21 xx : refus. Code 11 = service inconnu du calculateur (inutile d'insister) ;
            // les autres codes (12, 22, 31, 33…) montrent que le service 21 existe.
            int st = service21(messages(r, "2180"));
            if (st == 0) {
                String sr = cmd("10C0", 3000);
                String s = oneLine(sr);
                Journal.log("OBD", "recherche conso : ouverture de session 10C0 → " + s);
                rep.append("10C0 → ").append(s).append('\n');
                boolean sessionOk = false;
                for (int[] m : messages(sr, "10C0")) if (m.length >= 1 && m[0] == 0x50) sessionOk = true;
                if (sessionOk) {
                    session = true;
                    st = service21(messages(cmd("2180", 3000), "2180"));
                }
            }
            if (st > 0) {
                header = h;
                break;
            }
            Journal.log("OBD", "recherche conso : service 21 non pris en charge (en-tête "
                    + (h.isEmpty() ? "standard" : h) + ")");
        }
        int blocks = 0;
        if (header == null) {
            rep.append("Le calculateur ne répond pas au service 21 (données Renault).\n");
            Journal.log("OBD", "recherche conso : pas de réponse au service 21");
        } else {
            for (int lid = 0x01; lid <= 0xFF && alive(); lid++) {
                if (lid % 16 == 0) setState(RUNNING, "Recherche de la conso : bloc " + String.format("%02X", lid) + "/FF…");
                String c = String.format("21%02X", lid);
                String r = cmd(c, 1500);
                for (int[] m : messages(r, c)) {
                    if (m.length >= 3 && m[0] == 0x61 && m[1] == lid) {
                        blocks++;
                        String line = c + " → " + ObdPids.hex(m);
                        rep.append(line).append('\n');
                        Journal.log("OBD", "Renault " + line);
                    }
                }
                if (lid % 8 == 0) terminalStep();
            }
            rep.append(blocks).append(" bloc(s) de données Renault lus.\n");
            Journal.log("OBD", "recherche conso : " + blocks + " bloc(s) Renault (service 21)");
        }
        // Retour à l'état normal
        try {
            if (session) cmd("1081", 3000);
            if (customHeader) cmd("ATSHC133F1", 2000);
        } catch (IOException e) {
            Journal.log("OBD", "recherche conso : retour à l'état normal : " + e.getMessage());
        }
        timeouts = 0;
        rep.append(found.isEmpty() ? "Aucun PID standard de conso.\n" : "PID trouvés : " + pidList(found) + "\n");
        probeReport = rep.toString();
        Journal.log("OBD", "recherche conso : fin · PID standard trouvés : " + (found.isEmpty() ? "aucun" : pidList(found)));
        return found;
    }

    /**
     * Réponse au service 21 : 1 pris en charge (61 …, ou refus autre que « service inconnu »),
     * 0 refusé (7F 21 11 : service non pris en charge), -1 pas de réponse.
     */
    private static int service21(List<int[]> ms) {
        int st = -1;
        for (int[] m : ms) {
            if (m.length >= 1 && m[0] == 0x61) return 1;
            if (m.length >= 2 && m[0] == 0x7F && m[1] == 0x21) {
                if (m.length >= 3 && m[2] != 0x11) return 1;
                st = 0;
            }
        }
        return st;
    }

    /** Consommation : 5E (L/h), sinon A2 (mg par coup), sinon débit d'air (essence/GPL). */
    private void derived() {
        double lh = Double.NaN;
        Value f = values.get(0x5E);
        Value a2 = values.get(0xA2);
        Value rpm = values.get(0x0C);
        Value maf = values.get(0x10);
        if (f != null && !f.noData && !Double.isNaN(f.num)) lh = f.num;
        else if (a2 != null && !a2.noData && !Double.isNaN(a2.num) && rpm != null && !Double.isNaN(rpm.num)) {
            // mg par cylindre et par coup × 4 cylindres × (tr/min ÷ 2) × 60 → g/h, ÷ densité (g/L)
            double density = fuel == 1 ? 832 : fuel == 2 ? 540 : 745;
            lh = a2.num * 4 * (rpm.num / 2.0) * 60.0 / 1000.0 / density;
        } else if (maf != null && !maf.noData && !Double.isNaN(maf.num) && !diesel()) {
            double afr = fuel == 2 ? 15.5 : 14.7, density = fuel == 2 ? 540 : 745; // g/L
            lh = maf.num * 3600.0 / (afr * density);
        } else {
            lh = mapEstimate(rpm);
        }
        if (Double.isNaN(lh)) return;
        lh = lh * fuelCorrection / 100.0;
        put(ObdPids.ID_LH, lh);
        Value sp = values.get(0x0D);
        // Totaux du trajet (trous de plus de 5 s ignorés : liaison perdue, recherche de conso…)
        long now = System.currentTimeMillis();
        if (tripLast != 0 && now - tripLast < 5000) {
            double h = (now - tripLast) / 3600000.0;
            tripLiters += lh * h;
            if (sp != null && !sp.noData && !Double.isNaN(sp.num)) tripKm += sp.num * h;
        }
        tripLast = now;
        put(ObdPids.ID_LTRIP, tripLiters);
        put(ObdPids.ID_KTRIP, tripKm);
        if (tripKm >= 0.5) put(ObdPids.ID_AVG, tripLiters * 100.0 / tripKm);
        if (sp != null && !Double.isNaN(sp.num)) {
            if (sp.num >= 3) put(ObdPids.ID_L100, lh * 100.0 / sp.num);
            else {
                Value v = values.get(ObdPids.ID_L100);
                if (v != null) v.num = Double.NaN;
            }
        }
    }

    /**
     * Estimation « vitesse-densité » (probable, à étalonner par un plein) : masse d'air aspirée
     * calculée depuis la pression d'admission, le régime, la température d'air, la cylindrée et
     * le remplissage. Essence/GPL : carburant = air ÷ rapport stœchiométrique.
     * Diesel : mélange pauvre et variable ; la charge (PID 04) d'un diesel est la part du débit
     * maximal de gazole, approchée ici par air × charge ÷ 14,5.
     */
    private double mapEstimate(Value rpm) {
        Value map = values.get(0x0B), load = values.get(0x04), iat = values.get(0x0F);
        if (map == null || map.noData || Double.isNaN(map.num) || rpm == null || rpm.noData || Double.isNaN(rpm.num))
            return Double.NaN;
        double tK = (iat != null && !iat.noData && !Double.isNaN(iat.num) ? iat.num : 25) + 273.15;
        // g/s = P (Pa) × cylindrée (m³) × tr/s ÷ 2 (4 temps) × remplissage ÷ (R × T), × 1000
        double airGs = map.num * 1000.0 * (displacementCc / 1e6) * (rpm.num / 120.0) * (volEff / 100.0)
                / (287.05 * tK) * 1000.0;
        if (diesel()) {
            if (load == null || load.noData || Double.isNaN(load.num)) return Double.NaN;
            return airGs * (load.num / 100.0) / 14.5 * 3600.0 / 832.0;
        }
        double afr = fuel == 2 ? 15.5 : 14.7, density = fuel == 2 ? 540 : 745;
        return airGs * 3600.0 / (afr * density);
    }

    // ================================================================ Infos (mode 09, etc.)

    private void readInfo() throws Exception {
        if (supported01.contains(0x01)) readPid(0x01);
        vin = supported09.contains(0x02) ? ascii("0902", 0x02) : "";
        calId = supported09.contains(0x04) ? ascii("0904", 0x04) : "";
        ecuName = supported09.contains(0x0A) ? ascii("090A", 0x0A) : "";
        if (supported09.isEmpty()) {
            // Certains calculateurs répondent sans annoncer le mode 09.
            vin = ascii("0902", 0x02);
        }
        Journal.log("OBD", "VIN : " + (vin.isEmpty() ? "non fourni" : vin)
                + (calId.isEmpty() ? "" : " · calibration " + calId) + (ecuName.isEmpty() ? "" : " · calculateur " + ecuName));
    }

    /** Texte du mode 09 (VIN, calibration, nom), quel que soit le protocole. */
    private String ascii(String c, int pid) throws Exception {
        String r = cmd(c, 5000);
        if (noData(r)) return "";
        StringBuilder sb = new StringBuilder();
        for (int[] m : messages(r, c)) {
            if (m.length < 3 || m[0] != 0x49 || m[1] != pid) continue;
            // CAN : 49 PID nb données… ; autres : 49 PID n°trame 4 octets
            for (int i = 3; i < m.length; i++) if (m[i] >= 0x20 && m[i] < 0x7F) sb.append((char) m[i]);
        }
        return sb.toString().trim();
    }

    // ================================================================ Codes défaut

    private void readDtc() throws Exception {
        dtcStored = readDtcMode("03", 0x43);
        dtcPending = readDtcMode("07", 0x47);
        dtcPermanent = readDtcMode("0A", 0x4A);
        dtcTime = System.currentTimeMillis();
        dtcStamp = Clock.format("HH:mm:ss");
        Journal.log("OBD", "codes défaut : mémorisés " + dtcStored + " · en attente " + dtcPending + " · permanents " + dtcPermanent);
    }

    private List<String> readDtcMode(String c, int resp) throws Exception {
        String r = cmd(c, 5000);
        Set<String> out = new LinkedHashSet<>();
        for (int[] m : messages(r, c)) {
            if (m.length < 1 || m[0] != resp) continue;
            int start = can ? 2 : 1;   // CAN : octet de nombre de codes après 43
            for (int i = start; i + 1 < m.length; i += 2) {
                if (m[i] == 0 && m[i + 1] == 0) continue;
                out.add(ObdPids.dtc(m[i], m[i + 1]));
            }
        }
        return new ArrayList<>(out);
    }

    // ================================================================ Terminal, journal brut

    private void terminalStep() throws Exception {
        String c;
        while ((c = terminal.poll()) != null) {
            Journal.log("OBD", "terminal : " + c);
            cmd(c, 8000);
        }
    }

    private void raw(String s) {
        synchronized (raw) {
            raw.addLast(Clock.format("HH:mm:ss") + "  " + s);
            while (raw.size() > 400) raw.removeFirst();
        }
    }

    String rawText() {
        StringBuilder sb = new StringBuilder();
        synchronized (raw) {
            for (String s : raw) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    String format(int id) {
        ObdPids.P p = ObdPids.get(id);
        Value v = values.get(id);
        if (p == null) return v == null ? "…" : v.noData ? "pas de réponse" : v.text == null ? "…" : v.text;
        return p.format(v);
    }

    static String name(int id) {
        ObdPids.P p = ObdPids.get(id);
        return p != null ? p.name : String.format("PID %02X (brut)", id);
    }

    static String unit(int id) {
        ObdPids.P p = ObdPids.get(id);
        return p != null ? p.unit : "";
    }

    /** Relevé de toutes les valeurs, sur une ligne (pour le journal). */
    String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (int id : display) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(name(id)).append(" ").append(format(id)).append(unit(id).isEmpty() ? "" : " " + unit(id));
        }
        return sb.toString();
    }

    // ================================================================ Enregistrement CSV

    private void writeCsv() {
        if (!csv) {
            closeCsv();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - csvLast < 1000) return;
        csvLast = now;
        try {
            List<Integer> cols = display;
            if (csvOut == null || csvVersion != displayVersion) {
                closeCsv();
                File dir = new File(app.getExternalFilesDir(null), "obd");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dossier " + dir);
                File f = new File(dir, "obd_" + Clock.format("yyyyMMdd_HHmmss") + ".csv");
                csvOut = new FileWriter(f);
                csvVersion = displayVersion;
                csvFile = f.getAbsolutePath();
                StringBuilder h = new StringBuilder("heure");
                for (int id : cols) h.append(';').append(name(id)).append(unit(id).isEmpty() ? "" : " (" + unit(id) + ")");
                csvOut.write(h.append('\n').toString());
                Journal.log("OBD", "enregistrement CSV : " + csvFile);
            }
            StringBuilder l = new StringBuilder(Clock.format("HH:mm:ss"));
            for (int id : cols) l.append(';').append(format(id).replace(';', ','));
            csvOut.write(l.append('\n').toString());
            csvOut.flush();
        } catch (Throwable e) {
            Journal.error("OBD", "CSV", e);
            csv = false;
            closeCsv();
        }
    }

    private void closeCsv() {
        try {
            if (csvOut != null) csvOut.close();
        } catch (Throwable ignored) {
        }
        csvOut = null;
    }
}
