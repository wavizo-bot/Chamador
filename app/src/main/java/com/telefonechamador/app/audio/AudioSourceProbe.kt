package com.telefonechamador.app.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.telefonechamador.app.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Capacidades de captação do aparelho, descobertas em runtime.
 * A maioria dos aparelhos modernos bloqueia VOICE_CALL/UPLINK/DOWNLINK para apps
 * comuns (exigem CAPTURE_AUDIO_OUTPUT, de sistema) — mas muitas ROMs Xiaomi ainda
 * permitem. O resultado é cacheado para não re-testar a cada ligação.
 */
data class CaptureCapability(
    val mix: Boolean = false,    // VOICE_CALL (as duas vozes misturadas) via AudioRecord
    val down: Boolean = false,   // VOICE_DOWNLINK (voz recebida)
    val up: Boolean = false,     // VOICE_UPLINK (sua voz)
    val media: Boolean = false,  // MediaRecorder com fonte nativa (grava; transcrição pós-chamada)
    val mic: Boolean = false,    // microfone comum
) {
    val usable: Boolean get() = mix || down || up || media || mic

    fun toJson(): String = JSONObject()
        .put("mix", mix).put("down", down).put("up", up)
        .put("media", media).put("mic", mic)
        .toString()

    companion object {
        fun fromJson(raw: String): CaptureCapability? = try {
            if (raw.isBlank()) null else JSONObject(raw).let { o ->
                CaptureCapability(
                    mix = o.optBoolean("mix"),
                    down = o.optBoolean("down"),
                    up = o.optBoolean("up"),
                    media = o.optBoolean("media"),
                    mic = o.optBoolean("mic"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

object AudioSourceProbe {
    const val SAMPLE_RATE = 16000

    fun minBufferSize(): Int =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    private fun testAudioRecord(source: Int, ms: Long): Boolean {
        val rec = try {
            val buf = maxOf(minBufferSize() * 2, 8192)
            AudioRecord(
                source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        // Sem startRecording() todo read() volta INVALID_OPERATION (-38).
        rec.startRecording()
        return try {
            val frames = (SAMPLE_RATE * ms / 1000).toInt().coerceAtLeast(3200)
            val chunk = ShortArray(1600)
            var readTotal = 0
            var energy = 0.0
            var samples = 0
            while (readTotal < frames) {
                val n = rec.read(chunk, 0, minOf(chunk.size, frames - readTotal))
                if (n <= 0) break
                for (i in 0 until n) energy += Math.abs(chunk[i].toDouble())
                samples += n
                readTotal += n
            }
            if (samples == 0) false else (energy / samples) > 1.0
        } catch (_: Exception) {
            false
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
        }
    }

    /** Testa MediaRecorder com fonte nativa (caminho que funciona em mais aparelhos). */
    private fun testMediaRecorder(context: Context): Boolean {
        val f = File(context.cacheDir, "probe_rec.m4a")
        return try {
            val mr = MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioEncodingBitRate(64000)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            Thread.sleep(400)
            mr.stop()
            mr.release()
            val ok = f.length() > 1000
            f.delete()
            ok
        } catch (_: Exception) {
            try { f.delete() } catch (_: Exception) { }
            false
        }
    }

    suspend fun probe(context: Context): CaptureCapability = withContext(Dispatchers.IO) {
        if (!Permissions.granted(context, Manifest.permission.RECORD_AUDIO)) {
            return@withContext CaptureCapability()
        }
        val mix = testAudioRecord(MediaRecorder.AudioSource.VOICE_CALL, 900)
        val down = if (!mix) testAudioRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, 900) else false
        val up = if (!mix) testAudioRecord(MediaRecorder.AudioSource.VOICE_UPLINK, 900) else false
        val media = if (!mix && !down && !up) testMediaRecorder(context) else false
        val mic = testAudioRecord(MediaRecorder.AudioSource.MIC, 350)
        CaptureCapability(mix = mix, down = down, up = up, media = media, mic = mic)
    }

    fun sdkNote(): String =
        if (Build.VERSION.SDK_INT >= 34)
            "Android 14+ restringe fortemente a fonte nativa; o app testa e escolhe o que funcionar."
        else ""
}
