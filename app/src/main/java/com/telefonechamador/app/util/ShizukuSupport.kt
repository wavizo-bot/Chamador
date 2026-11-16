package com.telefonechamador.app.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.telefonechamador.app.shizuku.IUserService
import com.telefonechamador.app.shizuku.UserServiceImpl
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku (opcional, sem root): executa comandos de shell com os privilégios
 * do ADB por meio de um UserService rodando no processo do Shizuku.
 * Serve para conceder permissões em silêncio, liberar janela flutuante e
 * tirar o app da otimização de bateria — importante em MIUI/HyperOS.
 * Não habilita a captura nativa VOICE_CALL (essa verificação é feita por UID
 * no AudioFlinger, dentro do processo do app).
 */
object ShizukuSupport {

    const val REQUEST_CODE = 8713
    const val PACKAGE = "moe.shizuku.privileged.api"
    // 5: startRecord/stopRecord/recordRms (gravação shell via descriptor).
    // 6: correção do SIGSEGV no encerramento (tailer alimentava o JNI do
    //    Vosk durante o stop). O Shizuku respawn processos com versão menor.
    // 7: heartbeat — o pump se auto-encerra sem consultas de rms por 1 min
    //    (cobre app travado/morto sem chamar stopRecord).
    private const val SERVICE_VERSION = 7

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun ready(): Boolean = isRunning() && hasPermission()

    fun requestPermission() {
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) { }
    }

    // ---------- UserService (execução de comandos) ----------

    @Volatile private var service: IUserService? = null

    private fun serviceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context, UserServiceImpl::class.java),
        ).processNameSuffix("shizuku")
            .debuggable(false)
            .version(SERVICE_VERSION)

    /**
     * Liga o serviço sem esperar (pré-aquecimento): spawna o processo shell
     * cedo, para o custo de spawn não entrar no meio da ligação.
     */
    fun warmUp(context: Context) {
        try {
            Shizuku.bindUserService(
                serviceArgs(context),
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        service = IUserService.Stub.asInterface(binder)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        service = null
                    }
                },
            )
        } catch (_: Throwable) { }
    }

    private fun obtainService(context: Context): IUserService? {
        service?.let { return it }
        val latch = CountDownLatch(1)
        var bound: IUserService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try { bound = IUserService.Stub.asInterface(binder) } catch (_: Throwable) { }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        try {
            Shizuku.bindUserService(serviceArgs(context), conn)
            latch.await(15, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            return null
        }
        service = bound
        return bound
    }

    /** Roda um comando shell via Shizuku; devolve a saída ou null em falha. */
    fun runShell(context: Context, cmd: String): String? = try {
        obtainService(context)?.runShell(cmd)
    } catch (_: Throwable) {
        null
    }

    /**
     * Teste decisivo: abre AudioRecords DENTRO do processo do Shizuku (uid
     * shell) e mede a energia. Devolve por fonte (MIC, VOICE_COMMUNICATION,
     * VOICE_CALL): {abriu(1)/não inicializou(0)/falhou(-1), rmsMedio, blocos}.
     */
    fun probeCapture(context: Context, seconds: Int): IntArray? = try {
        obtainService(context)?.probeCapture(seconds)
    } catch (_: Throwable) {
        null
    }

    /**
     * Inicia uma gravação no processo shell: o app cria o arquivo (no próprio
     * armazenamento) e passa o descriptor via Binder; o serviço escreve WAV
     * PCM 16-bit nele. Devolve o id da sessão ou null em falha.
     */
    /** Loga transações Binder lentas (diagnóstico de travamentos). */
    private inline fun <T> timedBinder(name: String, slowMs: Long = 200L, block: () -> T): T {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            if (dt > slowMs) {
                android.util.Log.d("TChamador", "shizuku $name: ${dt} ms (lento)")
            }
        }
    }

    fun startShellRecord(context: Context, source: Int, sampleRate: Int, file: java.io.File): Int? {
        val svc = obtainService(context) ?: return null
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                    android.os.ParcelFileDescriptor.MODE_CREATE or
                    android.os.ParcelFileDescriptor.MODE_TRUNCATE,
            )
            val id = timedBinder("startRecord src=$source", 400L) {
                svc.startRecord(source, sampleRate, pfd!!)
            }
            if (id >= 0) id else null
        } catch (t: Throwable) {
            null
        } finally {
            try { pfd?.close() } catch (_: Throwable) { }
        }
    }

    /** Encerra a sessão; devolve o rms médio final (0 = silêncio absoluto) ou null. */
    fun stopShellRecord(context: Context, sessionId: Int): Int? {
        return try {
            val svc = obtainService(context) ?: return null
            timedBinder("stopRecord id=$sessionId", 400L) { svc.stopRecord(sessionId) }
        } catch (_: Throwable) {
            null
        }
    }

    /** rms médio acumulado da sessão, ou -1 se a sessão não existe. */
    fun shellRecordRms(context: Context, sessionId: Int): Int {
        return try {
            val svc = obtainService(context) ?: return -1
            timedBinder("recordRms id=$sessionId", 1000L) { svc.recordRms(sessionId) }
        } catch (_: Throwable) {
            -1
        }
    }

    /** Aplica todas as permissões/camadas que o Shizuku permite sem root. */
    fun applyPrivileges(context: Context): String {
        if (!ready()) return "Shizuku não está em execução ou o app ainda não tem permissão nele."
        val pkg = context.packageName
        val log = StringBuilder()

        var granted = 0
        for (p in Permissions.withCallLog) {
            if (Permissions.granted(context, p)) continue
            runShell(context, "pm grant $pkg $p")
            if (Permissions.granted(context, p)) granted++
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            runShell(context, "pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
            if (Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)) granted++
        }
        log.append("Permissões concedidas agora: $granted")

        if (!Permissions.canDrawOverlays(context)) {
            runShell(context, "appops set $pkg SYSTEM_ALERT_WINDOW allow")
            log.append("\nJanela flutuante: ")
            log.append(if (Permissions.canDrawOverlays(context)) "liberada" else "não liberada")
        } else {
            log.append("\nJanela flutuante: já estava liberada")
        }

        runShell(context, "dumpsys deviceidle whitelist +$pkg")
        val listed = runShell(context, "dumpsys deviceidle whitelist")
            ?.contains(pkg) == true
        log.append("\nBateria: ")
        log.append(
            if (listed) "adicionado à lista de otimização ignorada"
            else "não foi possível confirmar na lista"
        )

        return log.toString()
    }
}
