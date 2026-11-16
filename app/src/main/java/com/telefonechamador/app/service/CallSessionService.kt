package com.telefonechamador.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.telefonechamador.app.call.CallSession
import com.telefonechamador.app.util.Notifications

/**
 * Serviço em primeiro plano: mantém o monitor de ligações vivo (tipo phoneCall)
 * e é re-promovido com tipo microphone quando uma ligação começa, garantindo
 * o acesso "while-in-use" ao microfone durante a captura.
 */
class CallSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsMonitor()
        CallSession.ensureMonitoring(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("promote_mic", false) == true && CallSession.isEngineActive()) {
            promoteMic()
        }
        return START_STICKY
    }

    private fun startAsMonitor() {
        val notif = Notifications.ongoing(this, "Pronto para transcrever ligações")
        try {
            ServiceCompat.startForeground(
                this, 100, notif,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0,
            )
        } catch (_: Throwable) {
            try { startForeground(100, notif) } catch (_: Throwable) { }
        }
    }

    private fun promoteMic() {
        val notif = Notifications.ongoing(this, "Transcrevendo a ligação em andamento…")
        try {
            ServiceCompat.startForeground(
                this, 100, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (_: Throwable) {
            startAsMonitor()
        }
    }

    override fun onDestroy() {
        CallSession.stopMonitoring()
        super.onDestroy()
    }
}
