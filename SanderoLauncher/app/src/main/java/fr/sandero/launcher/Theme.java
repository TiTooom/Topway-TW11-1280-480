package fr.sandero.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Couleurs de l'écran d'accueil (et de la caméra) : mode nuit (interface sombre d'origine)
 * et mode jour (même interface, tons chauds et clairs).
 *
 * Choix (Outils) : « auto » (nuit de 18 h à 9 h, heure de Paris), « day » ou « night » forcés.
 * Le choix est mémorisé jusqu'au prochain changement dans les Outils.
 */
final class Theme {

    static final String AUTO = "auto", DAY = "day", NIGHT = "night";
    static final int NIGHT_FROM = 18, DAY_FROM = 9;

    static boolean night = true;

    // Palette courante (remplie par apply)
    static int text, dim, artist, glass, stroke, track;
    static int bgTop, bgMid, bgBottom;
    static int cardA, cardB, cardC, cardBase;
    static int artA, artB;
    static int accentDefault, accentRadio, accentBt;
    static int statusBar;

    static {
        apply(true);
    }

    private Theme() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tools", Context.MODE_PRIVATE);
    }

    static String mode(Context c) {
        return prefs(c).getString("theme_mode", AUTO);
    }

    static void setMode(Context c, String m) {
        prefs(c).edit().putString("theme_mode", m).apply();
        Journal.log("THEME", "choix du thème : " + label(m));
    }

    static String label(String m) {
        return DAY.equals(m) ? "toujours jour" : NIGHT.equals(m) ? "toujours nuit"
                : "automatique (nuit de " + NIGHT_FROM + " h à " + DAY_FROM + " h)";
    }

    /** Faut-il le mode nuit maintenant ? */
    static boolean nightNow(Context c) {
        String m = mode(c);
        if (DAY.equals(m)) return false;
        if (NIGHT.equals(m)) return true;
        Calendar cal = Calendar.getInstance(Clock.zone());
        cal.setTimeInMillis(Clock.now());
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return h >= NIGHT_FROM || h < DAY_FROM;
    }

    static void apply(boolean n) {
        night = n;
        if (n) {
            text = 0xFFF3F6FA;
            dim = 0xFF8E9AAC;
            artist = 0xD9FFFFFF;
            glass = 0x14FFFFFF;
            stroke = 0x1FFFFFFF;
            track = 0x1FFFFFFF;
            bgTop = 0xFF101826;
            bgMid = 0xFF080B12;
            bgBottom = 0xFF05070B;
            cardA = 0xFF1A2233;
            cardB = 0xFF0F131B;
            cardC = 0xFF0B0E14;
            cardBase = 0xFF0E121A;
            artA = 0xFF2A3346;
            artB = 0xFF161B26;
            accentDefault = 0xFF19B5FE;
            accentRadio = 0xFFF59E0B;
            accentBt = 0xFF3B82F6;
            statusBar = 0xFF0E1116;
        } else {
            // Jour : sable et crème, texte brun foncé, accents ambre / terracotta.
            text = 0xFF2B2118;
            dim = 0xFF8A7663;
            artist = 0xE63A2C20;
            glass = 0x14402A10;
            stroke = 0x26402A10;
            track = 0x1F402A10;
            bgTop = 0xFFFBF4EA;
            bgMid = 0xFFF3E6D5;
            bgBottom = 0xFFE9D7C1;
            cardA = 0xFFFFFAF3;
            cardB = 0xFFF7ECDD;
            cardC = 0xFFF1E2CF;
            cardBase = 0xFFFFF8EF;
            artA = 0xFFF2DFC8;
            artB = 0xFFE4CBAE;
            accentDefault = 0xFFE07A3F;
            accentRadio = 0xFFD97706;
            accentBt = 0xFFE8603C;
            statusBar = 0xFFFBF4EA;
        }
    }
}
