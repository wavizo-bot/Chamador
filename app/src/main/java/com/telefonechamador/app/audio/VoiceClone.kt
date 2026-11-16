package com.telefonechamador.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/** Medidas de uma amostra de voz: tom mediano (Hz) e brilho (ZCR). */
data class CloneStats(val pitchHz: Double, val zcr: Double, val voicedRatio: Double) {
    val valid: Boolean get() = pitchHz > 50 && voicedRatio > 0.08
}

/**
 * Clonagem de voz (DSP, 100% no aparelho): analisa o áudio de uma ligação do
 * histórico (a voz do interlocutor) e cria um preset que aproxima o TOM
 * (pitch mediano) e o TIMBRE (tilt espectral via ZCR) dessa voz.
 * É uma aproximação por DSP — a conversão neural completa (RVC) fica para
 * uma fase futura; já dá para "falar parecido" em tempo real.
 */
object VoiceClone {

    const val SAMPLE_RATE = 16000

    /**
     * Análise de PCM 16-bit mono: pitch mediano por autocorrelação nas janelas
     * com voz (60–400 Hz) e taxa média de cruzamento de zero (brilho).
     */
    fun analyze(shorts: ShortArray): CloneStats {
        if (shorts.size < SAMPLE_RATE / 2) return CloneStats(0.0, 0.0, 0.0)
        val frame = 640                      // 40 ms
        val hop = 320                        // 20 ms
        val minLag = SAMPLE_RATE / 400       // 400 Hz
        val maxLag = SAMPLE_RATE / 60        // 60 Hz
        val pitches = ArrayList<Double>()
        var voiced = 0
        var frames = 0
        var zcrAcc = 0.0
        var i = 0
        while (i + frame <= shorts.size) {
            var energy = 0.0
            for (j in i until i + frame) energy += shorts[j].toDouble() * shorts[j]
            energy = sqrt(energy / frame)
            frames++
            if (energy > 300) {
                voiced++
                var v0 = 0.0
                for (j in i until i + frame) v0 += shorts[j].toDouble() * shorts[j]
                var best = 0.0
                var bestLag = 0
                var lag = minLag
                while (lag <= maxLag) {
                    var acc = 0.0
                    var n = 0
                    var k = i
                    while (k + lag < i + frame) {
                        acc += shorts[k].toDouble() * shorts[k + lag].toDouble()
                        k++
                        n++
                    }
                    val r = if (n > 0) acc / v0 else 0.0
                    if (r > best) { best = r; bestLag = lag }
                    lag++
                }
                if (best > 0.35 && bestLag > 0) pitches.add(SAMPLE_RATE / bestLag.toDouble())
                var z = 0
                for (j in i + 1 until i + frame) {
                    if ((shorts[j] >= 0) != (shorts[j - 1] >= 0)) z++
                }
                zcrAcc += z.toDouble() / frame
            }
            i += hop
        }
        val pitch = if (pitches.isEmpty()) 0.0 else median(pitches)
        return CloneStats(pitch, if (voiced > 0) zcrAcc / voiced else 0.0, voiced.toDouble() / max(1, frames))
    }

    /** Analisa um arquivo WAV 16k mono (gravações do app). */
    fun analyzeWav(file: File): CloneStats? {
        return try {
            val pcm = ShellRecorder.readWavPcm(file) ?: return null
            analyze(pcm)
        } catch (_: Throwable) {
            null
        }
    }

    /** Grava `seconds` do microfone (calibração/teste). */
    @SuppressLint("MissingPermission")
    fun recordMic(seconds: Int): ShortArray? = try {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 32000),
        )
        val total = SAMPLE_RATE * seconds
        val out = ShortArray(total)
        rec.startRecording()
        var pos = 0
        while (pos < total) {
            val n = rec.read(out, pos, total - pos)
            if (n <= 0) break
            pos += n
        }
        try { rec.stop() } catch (_: Throwable) { }
        try { rec.release() } catch (_: Throwable) { }
        if (pos == total) out else null
    } catch (_: Throwable) {
        null
    }

    /** Toca uma amostra PCM 16-bit mono (teste de voz). */
    fun play(context: Context, shorts: ShortArray) = try {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack(attrs, fmt, maxOf(min * 2, 8192), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        t.play()
        t.write(shorts, 0, shorts.size)
        // deixa o buffer drenar antes de liberar
        Thread.sleep((shorts.size * 1000L / SAMPLE_RATE) + 200)
        t.stop()
        t.release()
    } catch (_: Throwable) { }

    /** Lê a calibração salva ({"pitchHz":..,"zcr":..}); null quando inválida. */
    fun baselineFromJson(json: String): CloneStats? = try {
        val o = JSONObject(json)
        CloneStats(
            pitchHz = o.optDouble("pitchHz", 0.0),
            zcr = o.optDouble("zcr", 0.0),
            voicedRatio = 1.0,
        )
    } catch (_: Throwable) {
        null
    }

    /** Cria o preset-clone aproximando tom (pitch) e timbre (tilt) da voz-alvo. */
    fun buildClone(label: String, desc: String, target: CloneStats, baseline: CloneStats?): VoiceDef {
        val basePitch = baseline?.pitchHz?.takeIf { it in 60.0..350.0 } ?: 130.0
        val ratio = if (target.valid && basePitch > 0) (target.pitchHz / basePitch).coerceIn(0.55, 1.9) else 1.0
        val tilt = if (target.valid && baseline != null)
            ((baseline.zcr - target.zcr) * 30.0).coerceIn(-8.0, 8.0)
        else 0.0
        val id = "clone_" + System.currentTimeMillis()
        return VoiceDef(id, label, FxParams(pitch = ratio, tiltDb = tilt), builtin = false, desc = desc)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
