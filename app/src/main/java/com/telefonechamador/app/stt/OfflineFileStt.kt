package com.telefonechamador.app.stt

import android.util.Log
import java.io.File

/**
 * Transcrição de ÁRQUIVO COMPLETO via sherpa-onnx (Parakeet) — UM passe →
 * parágrafo único. A classe SherpaEngine só existe quando o AAR está em
 * app/libs/ (source set condicional), então é acessada por reflexão; se o
 * pacote não estiver no build, tudo devolve null e o chamador usa o Vosk.
 */
object OfflineFileStt {

    /** true se o build inclui o sherpa-onnx (AAR em app/libs/). */
    fun available(): Boolean = try {
        Class.forName("com.telefonechamador.app.stt.SherpaEngine")
        true
    } catch (_: Throwable) { false }

    /** Transcreve o PCM inteiro; null = indisponível/falha (usar Vosk). */
    fun transcribe(modelDir: File, sampleRate: Int, pcm: ShortArray): String? = try {
        val cls = Class.forName("com.telefonechamador.app.stt.SherpaEngine")
        val m = cls.getMethod(
            "transcribeFile",
            File::class.java,
            Integer.TYPE,
            ShortArray::class.java,
        )
        (m.invoke(null, modelDir, sampleRate, pcm) as? String)?.trim()?.ifBlank { null }
    } catch (t: Throwable) {
        Log.d("TChamador", "OfflineFileStt: $t")
        null
    }
}
