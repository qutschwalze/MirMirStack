package com.heddrich.companion.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.heddrich.companion.MainActivity
import com.heddrich.companion.R

/**
 * Fertig-Benachrichtigung (Phase 5).
 *
 * Geraete-Modus: Link direkt auf die Wiki-Seite.
 * Server-Modus: Die finale URL entsteht erst serverseitig — die Notification
 * oeffnet die Inbox (bzw. fuehrt den Nutzer ins Wiki); kein Credential
 * wird fuer Polling aufs Geraet zurueckgeholt.
 */
object AppNotifier {

    const val CHANNEL_ID = "publish_status"
    private const val NOTIF_ID = 1001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MirMirStack Status",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    /**
     * Gesammelte Datei (Datensammler): Mit Wiki-URL oeffnet der Tap direkt
     * die Sammel-Seite; ohne URL die Inbox.
     */
    fun publishCollected(context: Context, title: String?, url: String?) {
        ensureChannel(context)
        val contentText: String
        val contentIntent: PendingIntent
        if (!url.isNullOrBlank()) {
            contentText = "Im Wiki gespeichert – antippen zum Öffnen"
            contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            contentText = "Datei gespeichert – antippen öffnet die Inbox"
            contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title?.take(60) ?: "Datei gespeichert")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID + 1, notification)
    }

    fun publishDone(context: Context, title: String?, url: String?) {
        ensureChannel(context)

        val contentText: String
        val contentIntent: PendingIntent
        if (!url.isNullOrBlank()) {
            contentText = "Wiki-Seite gespeichert – antippen zum Öffnen"
            contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            contentText = "An Server übergeben – Seite entsteht in wenigen Sekunden"
            contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title?.take(60) ?: "MirMirStack")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }
}