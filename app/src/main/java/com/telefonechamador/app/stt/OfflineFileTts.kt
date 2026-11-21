package com.telefonechamador.app.stt

import android.util.Log
import java.io.File

/**
 * Ponte do TTS (texto → fala) para o source set sherpa. Por reflexão: sem o
 * AAR em app/libs/ a classe SherpaTts não existe e o main continua compilando
 * (flag sherpaAvailable do build.gradle.kts) — mesmo padrão do OfflineFileStt.
 */
object OfflineFileTts {

    private const val TAG = "TChamador"

    /** true se o build inclui o pacote sherpa-onnx (a mesma lib do ASR). */
    fun available(): Boolean = OfflineFileStt.available()

    /**
     * Sintetiza [text] e devolve mapa "samples" (FloatArray) + "sampleRate" (Int),
     * ou null quando não há lib, não há modelo, ou em qualquer falha.
     * [dataDir] é o tts-data da voz instalada (espeak-ng-data, exigido pelo
     * Kokoro e pelo Piper no sherpa 1.13.8).
     */
    fun synthesize(modelDir: File, dataDir: File, text: String): HashMap<String, Any>? {
        if (!available()) return null
        return try {
            val cls = Class.forName("com.telefonechamador.app.stt.SherpaTts")
            @Suppress("UNCHECKED_CAST")
            cls.getMethod(
                "synthesize",
                File::class.java,
                File::class.java,
                String::class.java,
            ).invoke(null, modelDir, dataDir, text) as? HashMap<String, Any>
        } catch (err: Throwable) {
            Log.d(TAG, "offlineTts: $err")
            null
        }
    }
}
