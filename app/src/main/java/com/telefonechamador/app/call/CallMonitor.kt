package com.telefonechamador.app.call

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.util.Permissions
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed class PhoneEvent {
    data class Ringing(val number: String) : PhoneEvent()
    data class Offhook(val number: String, val direction: String) : PhoneEvent()
    object Idle : PhoneEvent()
}

/**
 * Observa o estado de telefonia (TelephonyCallback 31+ com fallback
 * PhoneStateListener) e aplica o bloqueio de números: silencia o toque
 * e rejeita a ligação quando o número está na lista.
 */
class CallMonitor(private val context: Context) {

    var listener: ((PhoneEvent) -> Unit)? = null

    private val repo = CallRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callback: Any? = null
    private var registered = false

    private var sawRingingAt = 0L
    private var lastNumber = ""
    private var prevRingerMode = AudioManager.RINGER_MODE_NORMAL
    private var silencedRinger = false

    fun start() {
        if (registered) return
        if (!Permissions.granted(context, Manifest.permission.READ_PHONE_STATE)) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state, null)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val pl = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        onState(state, phoneNumber)
                }
                @Suppress("DEPRECATION")
                tm.listen(pl, PhoneStateListener.LISTEN_CALL_STATE)
                callback = pl
            }
            registered = true
        } catch (_: Throwable) {
            registered = false
        }
    }

    fun stop() {
        if (!registered) return
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (Build.VERSION.SDK_INT >= 31) {
                (callback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                (callback as? PhoneStateListener)?.let {
                    @Suppress("DEPRECATION")
                    tm?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Throwable) { }
        registered = false
    }

    private fun onState(state: Int, rawNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                sawRingingAt = System.currentTimeMillis()
                // Em 31+ o framework não entrega o número aqui; CallScreeningImpl
                // guarda o último recebido (papel de triagem de chamadas).
                val number = rawNumber ?: IncomingMemo.peek() ?: ""
                if (number.isNotBlank()) lastNumber = number
                handleRinging(number)
                listener?.invoke(PhoneEvent.Ringing(number))
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val number = rawNumber ?: lastNumber
                val direction =
                    if (System.currentTimeMillis() - sawRingingAt < 60_000L) "in" else "out"
                listener?.invoke(PhoneEvent.Offhook(number, direction))
            }
            else -> {
                restoreRinger()
                listener?.invoke(PhoneEvent.Idle)
            }
        }
    }

    private fun handleRinging(number: String) {
        if (number.isBlank() || !Prefs.silenceBlocked) return
        scope.launch {
            val blocked = try { repo.isBlocked(number) } catch (_: Throwable) { false }
            if (!blocked) return@launch
            try {
                context.getSystemService(AudioManager::class.java)?.let { am ->
                    if (!silencedRinger) {
                        prevRingerMode = am.ringerMode
                        silencedRinger = true
                    }
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                }
            } catch (_: Throwable) { }
            try {
                val tm = context.getSystemService(TelecomManager::class.java)
                if (tm != null && Permissions.granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
                    tm.endCall()
                }
            } catch (_: Throwable) { }
        }
    }

    private fun restoreRinger() {
        if (!silencedRinger) return
        silencedRinger = false
        try {
            context.getSystemService(AudioManager::class.java)?.ringerMode = prevRingerMode
        } catch (_: Throwable) { }
    }
}
