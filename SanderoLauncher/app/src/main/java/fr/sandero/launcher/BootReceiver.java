package fr.sandero.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Au démarrage de l'autoradio : ouvre Sandero, même si le lanceur d'origine
 * est encore l'écran d'accueil par défaut. Désactivable dans Outils.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Journal.log("BOOT", "démarrage de l'autoradio reçu : " + intent.getAction());
        SanderoService.start(context);
        if (!context.getSharedPreferences("tools", Context.MODE_PRIVATE).getBoolean("boot_start", true)) return;
        Intent i = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            context.startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }
}
