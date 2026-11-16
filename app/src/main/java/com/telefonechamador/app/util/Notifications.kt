package com.telefonechamador.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.telefonechamador.app.R
import com.telefonechamador.app.ui.MainActivity

object Notifications {
    const val CH_CALLS = "ligacoes"
    const val CH_ACTIVE = "transcricao_ativa"
    const val CH_ALERTS = "alertas"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val calls = NotificationChannel(CH_CALLS, "Ligações", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisos sobre ligações gravadas e transcritas"
            setSound(ringtoneUri(), attrs)
            enableVibration(Prefs.notifVibrate)
            vibrationPattern = longArrayOf(0, 220, 120, 220)
        }
        val active = NotificationChannel(CH_ACTIVE, "Transcrição em andamento", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Notificação persistente durante a ligação"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val alerts = NotificationChannel(CH_ALERTS, "Alertas do app", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Resumos de gravação, transcrição e limpeza"
            setSound(null, null)
            enableVibration(Prefs.notifVibrate)
        }
        nm.createNotificationChannels(listOf(calls, active, alerts))
    }

    /** Recria canais quando toque/vibração mudam nas Ajustes. */
    fun refreshChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel(CH_CALLS)
        nm.deleteNotificationChannel(CH_ALERTS)
        createChannels(context)
    }

    private fun ringtoneUri(): Uri =
        if (Prefs.notifRingtone.isNotBlank()) Uri.parse(Prefs.notifRingtone)
        else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun ongoing(context: Context, text: String): Notification {
        // Tocar na notificação abre a tela da ligação (não o app por trás dela).
        val pi = PendingIntent.getActivity(
            context, 10, Intent(context, com.telefonechamador.app.ui.OngoingCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Telefone Chamador")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setSilent(true)
            .build()
    }

    fun callEnded(context: Context, title: String, text: String) {
        if (!Prefs.notifyCallEnd) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val i = Intent(context, MainActivity::class.java).putExtra("open_last_call", true)
        val pi = PendingIntent.getActivity(
            context, 11, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { nm.notify(2001, n) } catch (_: Exception) { }
    }

    fun cleanup(context: Context, deleted: Int) {
        if (!Prefs.notifyCleanup || deleted <= 0) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Limpeza automática")
            .setContentText("$deleted conversa(s) antiga(s) removida(s) — favoritos preservados.")
            .setAutoCancel(true)
            .build()
        try { nm.notify(2002, n) } catch (_: Exception) { }
    }

    /** Aviso com atalho para a tela da ligação (funciona em segundo plano). */
    fun callActive(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val i = Intent(context, com.telefonechamador.app.ui.OngoingCallActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 12, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(2003, n) } catch (_: Exception) { }
    }
}
