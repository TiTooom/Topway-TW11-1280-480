package fr.sandero.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Taille des caractères de l'accueil (v0.15) : x1 (défaut, rien ne change) jusqu'à x3 pour lire de loin.
 * Choisie dans Outils → Affichage, mémorisée dans les préférences « tools » (clé « ui_scale »,
 * index dans {@link #STEPS}) donc conservée après chaque redémarrage.
 */
final class UiScale {

    static final float[] STEPS = {1f, 1.25f, 1.5f, 2f, 2.5f, 3f};

    private UiScale() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tools", Context.MODE_PRIVATE);
    }

    static int index(Context c) {
        try {
            int i = prefs(c).getInt("ui_scale", 0);
            return i < 0 || i >= STEPS.length ? 0 : i;
        } catch (Throwable t) {
            return 0;
        }
    }

    static float get(Context c) {
        return STEPS[index(c)];
    }

    /** Passe au cran suivant (retour à x1 après x3) et renvoie le nouvel index. */
    static int next(Context c) {
        int i = (index(c) + 1) % STEPS.length;
        prefs(c).edit().putInt("ui_scale", i).apply();
        Journal.log("UI", "taille des caractères : " + label(i));
        return i;
    }

    static String label(int i) {
        float s = STEPS[i];
        return "x" + (s == (int) s ? String.valueOf((int) s) : String.valueOf(s).replace('.', ','));
    }
}
