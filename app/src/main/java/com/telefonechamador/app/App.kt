package com.telefonechamador.app

import android.app.Application
import com.telefonechamador.app.data.AppDatabase
import com.telefonechamador.app.retention.RetentionWorker
import com.telefonechamador.app.util.Notifications
import com.telefonechamador.app.util.Prefs
import com.telefonechamador.app.util.ShizukuSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Notifications.createChannels(this)
        RetentionWorker.schedule(this)
        // Pré-aquece o serviço shell do Shizuku: spawna o processo antes da
        // primeira ligação, para a gravação começar já no atendimento.
        appScope.launch {
            try {
                if (Prefs.shizukuRecord && ShizukuSupport.isInstalled(this@App)) {
                    ShizukuSupport.warmUp(this@App)
                }
            } catch (_: Throwable) { }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
