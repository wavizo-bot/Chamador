package com.telefonechamador.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Loop do efeito ao vivo (experimental): processa a voz capturada e reproduz
 * no alto-falante para que o microfone da ligação transmita a voz com efeito.
 * Traje conhecido: nesse modo o interlocutor não é capturado para gravação/
 * transcrição — a captura VOICE_COMMUNICATION foca na voz do usuário.
 */
class VoiceFxLoop(
    private val context: Context,
    private val preset: VoiceFx.Preset,
    private val sampleRate: Int,
) {

    private val st = VoiceFx.FxState()
    private var track: AudioTrack? = null
    private var failed = false

    fun process(input: ShortArray, frames: Int): ShortArray {
        val out = try {
            VoiceFx.processBlock(input, frames, preset, st, sampleRate)
        } catch (_: Throwable) {
            input
        }
        play(out, frames)
        return out
    }

    private fun play(samples: ShortArray, frames: Int) {
        if (failed || frames <= 0) return
        val t = track ?: initTrack() ?: return
        try {
            t.write(samples, 0, frames)
        } catch (_: Throwable) {
            failed = true
            release()
        }
    }

    private fun initTrack(): AudioTrack? = try {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack(
            attrs, fmt, maxOf(min * 2, 8192), AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        try {
            val am = context.getSystemService(AudioManager::class.java)
            val speaker = am?.availableCommunicationDevices
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) t.setPreferredDevice(speaker)
        } catch (_: Throwable) { }
        t.play()
        track = t
        t
    } catch (_: Throwable) {
        failed = true
        null
    }

    fun release() {
        try { track?.stop() } catch (_: Throwable) { }
        try { track?.release() } catch (_: Throwable) { }
        track = null
    }
}
