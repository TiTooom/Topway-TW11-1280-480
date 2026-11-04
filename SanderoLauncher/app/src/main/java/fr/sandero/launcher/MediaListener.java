package fr.sandero.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Vide exprès : Android exige ce service (et l'autorisation « Accès aux notifications »)
 * pour qu'une appli puisse lire le titre et la pochette du morceau en cours.
 */
public class MediaListener extends NotificationListenerService {
}
