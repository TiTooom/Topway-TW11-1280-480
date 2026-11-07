package fr.sandero.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;

/**
 * Diagnostic de l'ADB de l'autoradio, sans root : propriétés système en lecture, ports en écoute,
 * état et mode de la prise USB, essai de connexion. Rien n'est écrit au MCU ni sur le matériel.
 * À appeler hors du fil principal. Tout est journalisé en [ADB].
 */
final class AdbDiag {

    private static final String[] PROPS = {
            "init.svc.adbd", "service.adb.tcp.port", "persist.adb.tcp.port", "persist.service.adb.enable",
            "sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "sys.usb.controller",
            "ro.adb.secure", "ro.debuggable", "ro.secure", "ro.build.type", "ro.tw.version"
    };

    private AdbDiag() {
    }

    /** Lecture seule : tout ce qu'une appli peut apprendre sur l'ADB. */
    static String diagnose(Context ctx) {
        StringBuilder sb = new StringBuilder("DIAGNOSTIC ADB\n\n");
        try {
            sb.append("Réglages Android\n");
            android.content.ContentResolver cr = ctx.getContentResolver();
            sb.append("  adb_enabled = ").append(android.provider.Settings.Global.getInt(cr, "adb_enabled", -1)).append('\n');
            sb.append("  development_settings_enabled = ")
                    .append(android.provider.Settings.Global.getInt(cr, "development_settings_enabled", -1)).append('\n');

            sb.append("\nPropriétés système\n");
            for (String k : PROPS) {
                String v = prop(k);
                sb.append("  ").append(k).append(" = ").append(v.isEmpty() ? "(vide)" : v).append('\n');
            }

            sb.append("\nRoot (su) : ").append(Root.suPath() == null ? "absent" : Root.suPath()).append('\n');

            sb.append("\nAdresses IPv4 de l'autoradio\n");
            List<String> ips = ips();
            if (ips.isEmpty()) sb.append("  aucune (Wi-Fi non connecté ?)\n");
            for (String ip : ips) sb.append("  ").append(ip).append('\n');

            TreeSet<Integer> ports = listeningPorts();
            sb.append("\nPorts TCP en écoute : ").append(ports.isEmpty() ? "(illisible ou aucun)" : ports.toString()).append('\n');
            sb.append("  → port 5555 ").append(ports.contains(5555) ? "OUVERT" : "fermé").append('\n');

            sb.append("\nConnexion à soi-même sur le port 5555\n");
            List<String> targets = new ArrayList<>(ips);
            targets.add("127.0.0.1");
            for (String ip : targets) sb.append("  ").append(ip).append(" : ").append(probe(ip, 5555)).append('\n');

            sb.append("\nPrise USB\n");
            Intent st = ctx.registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
            if (st != null && st.getExtras() != null) {
                Bundle b = st.getExtras();
                List<String> keys = new ArrayList<>(b.keySet());
                Collections.sort(keys);
                for (String k : keys) sb.append("  état ").append(k).append(" = ").append(b.get(k)).append('\n');
            } else {
                sb.append("  état USB : aucune annonce\n");
            }
            try {
                UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
                java.util.HashMap<String, UsbDevice> devs = um.getDeviceList();
                sb.append("  périphériques branchés sur la prise (mode hôte) : ").append(devs.size()).append('\n');
                for (UsbDevice d : devs.values()) {
                    sb.append("    VID ").append(Integer.toHexString(d.getVendorId())).append(" PID ")
                            .append(Integer.toHexString(d.getProductId())).append('\n');
                }
            } catch (Throwable t) {
                sb.append("  UsbManager illisible : ").append(t).append('\n');
            }

            sb.append("\nContrôleur USB (sysfs)\n");
            sb.append(sysfs("/sys/class/android_usb/android0", new String[]{"enable", "functions", "state"}));
            sb.append(sysfsDir("/sys/class/udc"));
            sb.append(sysfsDir("/sys/devices/platform/soc/usbc0"));
            sb.append(sysfs("/sys/devices/platform/soc/usbc0", new String[]{"otg_role", "usb_host", "usb_device", "usb_null"}));
        } catch (Throwable t) {
            Journal.error("ADB", "diagnostic", t);
            sb.append("\nErreur : ").append(t).append('\n');
        }
        log(sb);
        return sb.toString();
    }

    /** Essais d'activation d'ADB par Wi-Fi sans root (refus attendus), puis avec root si disponible. */
    static String tryEnable(Context ctx) {
        StringBuilder sb = new StringBuilder("ESSAI D'ACTIVATION ADB WI-FI\n\n");
        try {
            sb.append(setProp("service.adb.tcp.port", "5555"));
            sb.append(setProp("ctl.restart", "adbd"));
            try {
                android.provider.Settings.Global.putInt(ctx.getContentResolver(), "adb_wifi_enabled", 1);
                sb.append("Réglage adb_wifi_enabled : accepté\n");
            } catch (Throwable t) {
                sb.append("Réglage adb_wifi_enabled : refusé (").append(t.getClass().getSimpleName()).append(")\n");
            }
            if (Root.available()) {
                String r = Root.run("setprop service.adb.tcp.port 5555; stop adbd; start adbd; getprop service.adb.tcp.port", 10);
                sb.append("Avec root : ").append(r == null ? "échec" : r.trim()).append('\n');
            } else {
                sb.append("Avec root : indisponible\n");
            }
            sleep(1500);
            sb.append("\nRelecture : service.adb.tcp.port = ").append(prop("service.adb.tcp.port"))
                    .append(", init.svc.adbd = ").append(prop("init.svc.adbd")).append('\n');
            sb.append("Port 5555 en écoute : ").append(listeningPorts().contains(5555) ? "OUI" : "non").append('\n');
            for (String ip : ips()) sb.append(ip).append(":5555 → ").append(probe(ip, 5555)).append('\n');
        } catch (Throwable t) {
            Journal.error("ADB", "essai d'activation", t);
            sb.append("\nErreur : ").append(t).append('\n');
        }
        log(sb);
        return sb.toString();
    }

    /** Ouvre l'écran Android de choix du mode USB, s'il existe. */
    static String openUsbChooser(Context ctx) {
        Intent[] tries = {
                new Intent().setClassName("com.android.settings", "com.android.settings.deviceinfo.UsbModeChooserActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.UsbSettings"),
                new Intent("android.settings.USB_SETTINGS"),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$StorageDashboardActivity")
        };
        String[] names = {"UsbModeChooserActivity", "UsbSettings", "USB_SETTINGS", "Stockage"};
        for (int i = 0; i < tries.length; i++) {
            try {
                tries[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(tries[i]);
                Journal.log("ADB", "écran ouvert : " + names[i]);
                return "Écran ouvert : " + names[i];
            } catch (Throwable t) {
                Journal.log("ADB", "écran impossible (" + names[i] + ") : " + t.getClass().getSimpleName());
            }
        }
        return "Aucun écran de choix du mode USB trouvé : voir le journal [ADB].";
    }

    // ---- outils ----

    private static String setProp(String key, String val) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            c.getMethod("set", String.class, String.class).invoke(null, key, val);
            return "setprop " + key + " " + val + " : accepté\n";
        } catch (Throwable t) {
            Throwable e = t.getCause() != null ? t.getCause() : t;
            return "setprop " + key + " : refusé (" + e.getClass().getSimpleName() + ")\n";
        }
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static List<String> ips() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> ad = ni.getInetAddresses();
                while (ad.hasMoreElements()) {
                    InetAddress a = ad.nextElement();
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Throwable t) {
            Journal.log("ADB", "adresses illisibles : " + t);
        }
        return out;
    }

    private static TreeSet<Integer> listeningPorts() {
        TreeSet<Integer> ports = new TreeSet<>();
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                while ((line = r.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length > 3 && "0A".equals(p[3])) {
                        String loc = p[1];
                        ports.add(Integer.parseInt(loc.substring(loc.lastIndexOf(':') + 1), 16));
                    }
                }
            } catch (Throwable t) {
                Journal.log("ADB", f + " illisible : " + t.getClass().getSimpleName());
            }
        }
        return ports;
    }

    private static String probe(String ip, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 1500);
            return "répond";
        } catch (java.net.ConnectException e) {
            return "refusé (personne n'écoute)";
        } catch (Throwable t) {
            return "pas de réponse (" + t.getClass().getSimpleName() + ")";
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String sysfs(String dir, String[] names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            File f = new File(dir, n);
            if (!f.exists()) continue;
            sb.append("  ").append(f.getPath()).append(" = ");
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l = r.readLine();
                sb.append(l == null ? "(vide)" : l.trim());
            } catch (Throwable t) {
                sb.append("(illisible)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String sysfsDir(String dir) {
        File d = new File(dir);
        String[] names = d.list();
        if (names == null) return "  " + dir + " : absent ou illisible\n";
        java.util.Arrays.sort(names);
        StringBuilder sb = new StringBuilder("  " + dir + " :");
        for (String n : names) sb.append(' ').append(n);
        return sb.append('\n').toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void log(CharSequence report) {
        for (String line : report.toString().split("\n")) {
            if (!line.trim().isEmpty()) Journal.log("ADB", line.trim());
        }
    }
}
