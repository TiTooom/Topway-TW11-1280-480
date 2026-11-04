package fr.sandero.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Accès root via « su » s'il existe et accepte l'appli (fréquent sur les builds « eng »
 * comme celle de l'autoradio, mais pas garanti). À appeler hors du thread principal.
 */
final class Root {

    private static Boolean cached;

    private Root() {
    }

    /** Teste une fois si « su -c id » répond uid=0. */
    static synchronized boolean available() {
        if (cached == null) {
            String out = run("id", 5);
            cached = out != null && out.contains("uid=0");
        }
        return cached;
    }

    static synchronized void reset() {
        cached = null;
    }

    /** Où se trouve su, pour l'affichage. */
    static String suPath() {
        for (String p : new String[]{"/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su"}) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    /** Lance une commande en root ; renvoie la sortie, ou null si refusé / absent. */
    static String run(String cmd, int timeoutSeconds) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process proc = p;
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                } catch (Exception ignored) {
                }
            });
            reader.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroy();
                return null;
            }
            reader.join(1000);
            if (p.exitValue() != 0 && bo.size() == 0) return null;
            return bo.toString("UTF-8");
        } catch (Exception e) {
            if (p != null) p.destroy();
            return null;
        }
    }
}
