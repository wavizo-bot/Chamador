package com.telefonechamador.app.util

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val TRANSCRIPTION_SIDE_RECEIVED = "received"
    const val TRANSCRIPTION_SIDE_BOTH = "both"

    const val RECORD_SIDE_BOTH = "both"
    const val RECORD_SIDE_RECEIVED = "received"
    const val RECORD_SIDE_SENT = "sent"

    const val CAPTURE_AUTO = "auto"
    const val CAPTURE_NATIVE = "native"
    const val CAPTURE_MIC = "mic"

    const val ENGINE_VOSK = "vosk"
    const val ENGINE_SHERPA = "sherpa"

    private const val FILE = "telefone_chamador_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun put(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var transcriptionSide: String
        get() = str("transcription_side", TRANSCRIPTION_SIDE_RECEIVED)
        set(v) = put("transcription_side", v)

    var sttEngine: String
        get() = str("stt_engine", ENGINE_VOSK)
        set(v) = put("stt_engine", v)

    var voskModel: String
        get() = str("vosk_model", "vosk_small_pt")
        set(v) = put("vosk_model", v)

    var sherpaModel: String
        get() = str("sherpa_model", "parakeet_v3")
        set(v) = put("sherpa_model", v)

    var recordingEnabled: Boolean
        get() = bool("recording_enabled", true)
        set(v) = put("recording_enabled", v)

    /** Gravação/transcrição via serviço shell do Shizuku quando disponível. */
    var shizukuRecord: Boolean
        get() = bool("shizuku_record", true)
        set(v) = put("shizuku_record", v)

    var recordingSide: String
        get() = str("recording_side", RECORD_SIDE_BOTH)
        set(v) = put("recording_side", v)

    var autoTranscribe: Boolean
        get() = bool("auto_transcribe", true)
        set(v) = put("auto_transcribe", v)

    var captureMode: String
        get() = str("capture_mode", CAPTURE_AUTO)
        set(v) = put("capture_mode", v)

    var micSpeakerAuto: Boolean
        get() = bool("mic_speaker_auto", true)
        set(v) = put("mic_speaker_auto", v)

    /** Preset de efeito de voz (VoiceFx.Preset.id). "none" desligado. */
    var voiceFx: String
        get() = str("voice_fx", "none")
        set(v) = put("voice_fx", v)

    /** Modo experimental: aplica o efeito ao vivo na sua voz via alto-falante. */
    var voiceFxLive: Boolean
        get() = bool("voice_fx_live", false)
        set(v) = put("voice_fx_live", v)


    var retentionDays: Int
        get() = sp.getInt("retention_days", 10)
        set(v) = sp.edit().putInt("retention_days", v.coerceIn(1, 99)).apply()

    var notifyCallEnd: Boolean
        get() = bool("notify_call_end", true)
        set(v) = put("notify_call_end", v)

    var notifyCleanup: Boolean
        get() = bool("notify_cleanup", false)
        set(v) = put("notify_cleanup", v)

    var notifRingtone: String
        get() = str("notif_ringtone", "")
        set(v) = put("notif_ringtone", v)

    var notifVibrate: Boolean
        get() = bool("notif_vibrate", true)
        set(v) = put("notif_vibrate", v)

    var overlayEnabled: Boolean
        get() = bool("overlay_enabled", false)
        set(v) = put("overlay_enabled", v)

    var silenceBlocked: Boolean
        get() = bool("silence_blocked", true)
        set(v) = put("silence_blocked", v)

    var consentAccepted: Boolean
        get() = bool("consent_accepted", false)
        set(v) = put("consent_accepted", v)

    var dialerRoleAsked: Boolean
        get() = bool("dialer_role_asked", false)
        set(v) = put("dialer_role_asked", v)

    /** Cache do diagnóstico de captação por aparelho (JSON). */
    var captureCapability: String
        get() = str("capture_capability", "")
        set(v) = put("capture_capability", v)

    fun clearCapability() {
        captureCapability = ""
    }
}
