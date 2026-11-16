package com.telefonechamador.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.telefonechamador.app.util.Prefs

/** Religa o monitor de ligações após o boot (quando o app está configurado). */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!(Prefs.recordingEnabled || Prefs.autoTranscribe || Prefs.overlayEnabled)) return
        try {
            context.startForegroundService(Intent(context, CallSessionService::class.java))
        } catch (_: Throwable) { }
    }
}
