package com.telefonechamador.app.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Síntese de voz (TTS) com o sherpa-onnx: texto → fala GERADA (não captada),
 * sem chiado de microfone nem ruído de ambiente — é a "voz nova" que o dono
 * pediu para o Laboratório. Modelo Piper pt-BR (int8, ~17 MB).
 *
 * Este arquivo vive no source set sherpa (só compila com o AAR em app/libs)
 * e é chamado por reflexão pelo main (OfflineFileTts), mesmo padrão do
 * SherpaEngine/OfflineFileStt para o ASR.
 */
object SherpaTts {

    private const val TAG = "TChamador"

    /**
     * Sintetiza [text] com o Piper em [modelDir] (espeak-ng-data em [espeakDir]).
     * Retorna mapa com "samples" (FloatArray) e "sampleRate" (Int), ou null
     * em qualquer falha — o chamador decide o que avisar ao usuário.
     */
    @JvmStatic
    fun synthesize(modelDir: File, espeakDir: File, text: String): HashMap<String, Any>? {
        val msg = text.trim()
        if (msg.isEmpty()) return null
        return try {
            // O nome do .onnx varia por voz (pt_BR-jeff-medium.onnx, …): acha o primeiro.
            val onnx = modelDir.listFiles()?.firstOrNull { it.isFile && it.extension == "onnx" }
            if (onnx == null) {
                Log.d(TAG, "sherpaTts: nenhum .onnx em $modelDir")
                return null
            }
            val tokens = File(modelDir, "tokens.txt")
            if (!tokens.exists()) {
                Log.d(TAG, "sherpaTts: tokens.txt ausente em $modelDir")
                return null
            }
            if (!espeakDir.isDirectory) {
                Log.d(TAG, "sherpaTts: espeak-ng-data ausente em $espeakDir")
                return null
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = onnx.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = espeakDir.absolutePath,
                    ),
                    numThreads = 2,
                ),
            )
            val tts = OfflineTts(assetManager = null, config = config)
            try {
                val audio = tts.generate(msg, 0, 1.0f)
                val samples = audio.samples
                if (samples.isEmpty()) {
                    Log.d(TAG, "sherpaTts: generate devolveu vazio")
                    return null
                }
                Log.d(TAG, "sherpaTts: ok samples=${samples.size} rate=${audio.sampleRate}")
                hashMapOf(
                    "samples" to samples,
                    "sampleRate" to audio.sampleRate,
                )
            } finally {
                try { tts.release() } catch (_: Throwable) { }
            }
        } catch (err: Throwable) {
            Log.d(TAG, "sherpaTts: erro $err")
            null
        }
    }
}
