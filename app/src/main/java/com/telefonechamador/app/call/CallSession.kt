package com.telefonechamador.app.call

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.VideoProfile
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.service.CallSessionService
import com.telefonechamador.app.service.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Coordenador único entre os caminhos de detecção de ligação:
 *  - CallMonitor (TelephonyManager, sempre que o app roda em segundo plano);
 *  - InCallServiceImpl (quando o app é o discador padrão).
 * Garante no máximo um CallEngine ativo por vez.
 */
object CallSession {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitor: CallMonitor? = null
    private var engine: CallEngine? = null
    private var appContext: Context? = null

    fun ensureMonitoring(context: Context) {
        synchronized(lock) {
            val ctx = context.applicationContext
            appContext = ctx
            if (monitor == null) {
                val m = CallMonitor(ctx)
                m.listener = { e -> scope.launch { onPhoneEvent(e) } }
                m.start()
                monitor = m
            }
        }
    }

    fun stopMonitoring() {
        synchronized(lock) {
            monitor?.stop()
            monitor = null
        }
    }

    fun isEngineActive(): Boolean = engine != null

    private suspend fun onPhoneEvent(e: PhoneEvent) {
        when (e) {
            is PhoneEvent.Ringing -> {
                if (e.number.isNotBlank()) CallController.setRinging(e.number)
            }
            is PhoneEvent.Offhook -> startEngine(e.number, e.direction)
            PhoneEvent.Idle -> endEngine()
        }
    }

    private fun startEngine(monitorNumber: String, direction: String) {
        synchronized(lock) {
            if (engine != null) return
            val ctx = appContext ?: return
            // Número real: o Telecom entrega o handle exato (o memo do monitor
            // pode carregar um número velho de triagem).
            val number = CallController.state.value.number.ifBlank { monitorNumber }
            CallController.reset()
            val name = CallRepository(ctx).lookupName(number)
            val e = CallEngine(ctx)
            engine = e
            e.start(number, name, direction)
            promoteMic(ctx)
            try {
                ctx.startActivity(
                    Intent(ctx, com.telefonechamador.app.ui.OngoingCallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) { }
        }
    }

    fun endEngine() {
        synchronized(lock) {
            appContext?.let { OverlayService.stop(it) }
            val e = engine ?: run {
                CallController.setEnded()
                return
            }
            engine = null
            e.stop()
        }
    }

    /** Re-promove o serviço em primeiro plano com tipo microphone na ligação. */
    private fun promoteMic(ctx: Context) {
        try {
            val i = Intent(ctx, CallSessionService::class.java).putExtra("promote_mic", true)
            ctx.startForegroundService(i)
        } catch (_: Throwable) { }
    }

    // ---------- integração com InCallService ----------

    fun onTelecomCallAdded(call: Call) {
        CallController.attachCall(call)
        val number = try { call.details.handle?.schemeSpecificPart ?: "" } catch (_: Throwable) { "" }
        CallController.updateNumber(number)
        if (number.isNotBlank()) {
            // Propaga o número tardio ao motor já em pé (o OFFHOOK do monitor
            // pode ter criado a linha antes do bind do Telecom chegar).
            synchronized(lock) { engine?.updateNumberFromTelecom(number) }
        }
        val st = try { call.details.state } catch (_: Throwable) { -1 }
        if (st == Call.STATE_CONNECTING || st == Call.STATE_DIALING) {
            // Ligação NOVA saindo: o estado pode estar sujo de uma ligação
            // anterior ("ended"/"ringing" com número velho) — recomeça limpo.
            if (number.isNotBlank() && CallController.state.value.phase != "dialing") {
                CallController.reset()
                CallController.setDialing(number)
                appContext?.let { ctx ->
                    try {
                        ctx.startActivity(
                            Intent(ctx, com.telefonechamador.app.ui.OngoingCallActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) { }
                }
            }
        }
        if (call.details.state == Call.STATE_RINGING && number.isNotBlank()) {
            CallController.setRinging(number)
            val ctx = appContext ?: return
            scope.launch {
                val blocked = try { CallRepository(ctx).isBlocked(number) } catch (_: Throwable) { false }
                if (blocked) {
                    try { call.reject(VideoProfile.STATE_AUDIO_ONLY) } catch (_: Throwable) { }
                    CallController.detachCall()
                }
            }
        }
    }

    fun onTelecomCallRemoved() {
        CallController.detachCall()
        endEngine()
    }
}
