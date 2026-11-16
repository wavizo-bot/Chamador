package com.telefonechamador.app.incall

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.telefonechamador.app.call.CallController
import com.telefonechamador.app.call.CallSession

/**
 * Discador completo: quando o app é o telefone padrão, o Telecom nos entrega
 * as chamadas. O motor de transcrição continua começando pelo CallMonitor
 * (OFFHOOK); aqui fazemos o attach do Call para os comandos e o bloqueio,
 * além de refletir o estado real da rota de áudio (viva-voz) na UI.
 */
class InCallServiceImpl : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallSession.onTelecomCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallSession.onTelecomCallRemoved()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let { CallController.setSpeaker(it.route == CallAudioState.ROUTE_SPEAKER) }
    }

    /** Rota da ligação pelo caminho do Telecom (discador AOSP usa este). */
    fun setRouteSpeaker(on: Boolean) {
        try {
            setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        } catch (_: Throwable) { }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: InCallServiceImpl? = null
            private set
    }
}
