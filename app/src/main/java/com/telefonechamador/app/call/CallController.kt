package com.telefonechamador.app.call

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import com.telefonechamador.app.util.CallUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiSegment(val side: String, val offsetMs: Long, val text: String)

data class CallUiState(
    val phase: String = "idle",   // idle | ringing | active | ended
    val number: String = "",
    val contactName: String? = null,
    val direction: String = "",   // in | out
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val transcribing: Boolean = false,
    val recording: Boolean = false,
    val note: String? = null,
    val segments: List<UiSegment> = emptyList(),
    val partial: String = "",
    val partialSide: String = "",
)

/**
 * Estado vivo da ligação para as telas (OngoingCallActivity, OverlayService).
 * Comandos de mudo/speaker tentam primeiro o Call do Telecom (quando somos o
 * discador) e usam o AudioManager como caminho local.
 */
object CallController {

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    @Volatile
    var telecomCall: Call? = null

    fun attachCall(c: Call) {
        telecomCall = c
    }

    fun detachCall() {
        telecomCall = null
    }

    fun setRinging(number: String) {
        if (_state.value.phase == "active") return
        _state.value = CallUiState(phase = "ringing", number = number)
    }

    /** Ligação nossa em progresso de discagem (o número vem do Telecom, exato). */
    fun setDialing(number: String) {
        if (_state.value.phase != "idle") return
        _state.value = CallUiState(phase = "dialing", number = number)
    }

    fun setActive(
        number: String,
        contactName: String?,
        direction: String,
        startedAt: Long,
        transcribing: Boolean,
        recording: Boolean,
    ) {
        _state.value = CallUiState(
            phase = "active",
            number = number,
            contactName = contactName,
            direction = direction,
            startedAt = startedAt,
            transcribing = transcribing,
            recording = recording,
        )
    }

    fun updateNumber(number: String) {
        if (number.isBlank()) return
        // O handle do Telecom é a verdade do número da ligação ATUAL — sempre
        // sobrescreve (o estado pode carregar número de uma ligação anterior).
        _state.value = _state.value.copy(number = number)
    }

    fun setNote(note: String?) {
        _state.value = _state.value.copy(note = note?.trim()?.takeIf { it.isNotBlank() })
    }

    fun appendSegment(side: String, offsetMs: Long, text: String) {
        val s = _state.value
        _state.value = s.copy(segments = s.segments + UiSegment(side, offsetMs, text))
    }

    fun setPartial(side: String, text: String) {
        _state.value = _state.value.copy(partial = text, partialSide = side)
    }

    fun setMuted(v: Boolean) {
        _state.value = _state.value.copy(muted = v)
    }

    fun setSpeaker(v: Boolean) {
        _state.value = _state.value.copy(speaker = v)
    }

    fun setEnded() {
        val s = _state.value
        if (s.phase == "idle") return
        _state.value = s.copy(phase = "ended", partial = "", partialSide = "")
    }

    fun reset() {
        _state.value = CallUiState()
    }

    // ---------- comandos ----------

    fun toggleMute(context: Context) {
        val to = !_state.value.muted
        CallUtils.setMuted(context, to)
        setMuted(to)
    }

    fun toggleSpeaker(context: Context) {
        setSpeakerRoute(context, !_state.value.speaker)
    }

    /**
     * Rota de áudio da ligação: via InCallService.setAudioRoute (o caminho do
     * discador AOSP — funciona em chamada de celular; o AudioManager sozinho
     * não altera a rota do Telecom), com fallback local para o resto.
     */
    fun setSpeakerRoute(context: Context, on: Boolean) {
        val svc = try { com.telefonechamador.app.incall.InCallServiceImpl.instance } catch (_: Throwable) { null }
        if (svc != null) {
            svc.setRouteSpeaker(on)
            setSpeaker(on)
            return
        }
        CallUtils.setSpeakerOn(context, on)
        setSpeaker(on)
    }

    fun answer(context: Context) {
        val c = telecomCall
        if (c != null && c.state == Call.STATE_RINGING) {
            try {
                c.answer(VideoProfile.STATE_AUDIO_ONLY)
                return
            } catch (_: Throwable) { }
        }
        CallUtils.answerRingingCall(context)
    }

    fun reject(context: Context) {
        val c = telecomCall
        if (c != null && c.state == Call.STATE_RINGING) {
            try {
                c.reject(VideoProfile.STATE_AUDIO_ONLY)
                detachCall()
                return
            } catch (_: Throwable) { }
        }
        CallUtils.endCall(context)
    }

    fun end(context: Context) {
        val c = telecomCall
        if (c != null) {
            try {
                c.disconnect()
                return
            } catch (_: Throwable) { }
        }
        CallUtils.endCall(context)
    }
}
