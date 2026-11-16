package com.telefonechamador.app.util

import androidx.preference.PreferenceDataStore

/**
 * Ponte entre o PreferenceScreen (Ajustes) e o Prefs — o MESMO arquivo
 * ("telefone_chamador_prefs") que o motor do app lê. Sem isso, o
 * PreferenceFragmentCompat gravaria num arquivo próprio do
 * PreferenceManager e os Ajustes não afetariam o comportamento real.
 */
class PrefsDataStore : PreferenceDataStore() {

    override fun getBoolean(key: String, defValue: Boolean): Boolean = when (key) {
        "recording_enabled" -> Prefs.recordingEnabled
        "auto_transcribe" -> Prefs.autoTranscribe
        "voice_fx_live" -> Prefs.voiceFxLive
        "mic_speaker_auto" -> Prefs.micSpeakerAuto
        "notify_call_end" -> Prefs.notifyCallEnd
        "notif_vibrate" -> Prefs.notifVibrate
        "notify_cleanup" -> Prefs.notifyCleanup
        "silence_blocked" -> Prefs.silenceBlocked
        "overlay_enabled" -> Prefs.overlayEnabled
        else -> defValue
    }

    override fun putBoolean(key: String, value: Boolean) {
        when (key) {
            "recording_enabled" -> Prefs.recordingEnabled = value
            "auto_transcribe" -> Prefs.autoTranscribe = value
            "voice_fx_live" -> Prefs.voiceFxLive = value
            "mic_speaker_auto" -> Prefs.micSpeakerAuto = value
            "notify_call_end" -> Prefs.notifyCallEnd = value
            "notif_vibrate" -> Prefs.notifVibrate = value
            "notify_cleanup" -> Prefs.notifyCleanup = value
            "silence_blocked" -> Prefs.silenceBlocked = value
            "overlay_enabled" -> Prefs.overlayEnabled = value
        }
    }

    override fun getString(key: String, defValue: String?): String = when (key) {
        "recording_side" -> Prefs.recordingSide
        "transcription_side" -> Prefs.transcriptionSide
        "stt_engine" -> Prefs.sttEngine
        "voice_fx" -> Prefs.voiceFx
        "capture_mode" -> Prefs.captureMode
        else -> defValue ?: ""
    }

    override fun putString(key: String, value: String?) {
        when (key) {
            "recording_side" -> Prefs.recordingSide = value ?: Prefs.RECORD_SIDE_BOTH
            "transcription_side" -> Prefs.transcriptionSide = value ?: Prefs.TRANSCRIPTION_SIDE_RECEIVED
            "stt_engine" -> Prefs.sttEngine = value ?: Prefs.ENGINE_VOSK
            "voice_fx" -> Prefs.voiceFx = value ?: "none"
            "capture_mode" -> Prefs.captureMode = value ?: Prefs.CAPTURE_AUTO
        }
    }
}
