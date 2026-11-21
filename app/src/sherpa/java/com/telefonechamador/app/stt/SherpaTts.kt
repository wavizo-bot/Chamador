package com.telefonechamador.app.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Síntese de voz (TTS) com o sherpa-onnx: texto → fala GERADA (não captada),
 * sem chiado de microfone nem ruído de ambiente — é a "voz nova" que o dono
 * pediu para o Laboratório.
 *
 * Dois motores, detectados pelos arquivos do modelo:
 *  - Kokoro int8 pt-BR (Etapa 2 da clonagem de voz): model.int8.onnx +
 *    voices.bin, fonemização pelo espeak-ng com lang "pt-br" — o sherpa
 *    1.13.8 (versão do AAR) EXIGE o dir espeak-ng-data em kokoro-data-dir
 *    e sempre usa o espeak quando lang é informado. Voz sid 42 = pf_dora.
 *  - Piper pt-BR (Jeff): *.onnx + fonemas espeak-ng-data.
 *
 * Este arquivo vive no source set sherpa (só compila com o AAR em app/libs)
 * e é chamado por reflexão pelo main (OfflineFileTts), mesmo padrão do
 * SherpaEngine/OfflineFileStt para o ASR.
 */
object SherpaTts {

    private const val TAG = "TChamador"

    /** sid da voz pt-BR pf_dora no kokoro-int8-multi-lang-v1_0 (42/54). */
    private const val KOKORO_SID = 42

    /**
     * Sintetiza [text] com a voz instalada em [modelDir]; [dataDir] é o
     * tts-data daquela voz (espeak-ng-data, exigido pelo Kokoro e pelo
     * Piper). Retorna mapa com "samples" (FloatArray) e "sampleRate" (Int),
     * ou null em qualquer falha — o chamador decide o que avisar ao usuário.
     */
    @JvmStatic
    fun synthesize(modelDir: File, dataDir: File, text: String): HashMap<String, Any>? {
        val msg = text.trim()
        if (msg.isEmpty()) return null
        return try {
            if (File(modelDir, "voices.bin").exists() && File(modelDir, "model.int8.onnx").exists()) {
                synthKokoro(modelDir, dataDir, msg)
            } else {
                synthVits(modelDir, dataDir, msg)
            }
        } catch (err: Throwable) {
            Log.d(TAG, "sherpaTts: erro $err")
            null
        }
    }

    /**
     * Kokoro int8 pt-BR: fonemas pelo espeak-ng (lang "pt-br").
     *
     * Armadilha do sherpa 1.13.8 (versão embutida no AAR): a validação do
     * config EXIGE kokoro-data-dir com os arquivos do espeak-ng (phontab,
     * phonindex, phondata, intonations) — sem isso o construtor nativo joga
     * "Invalid OfflineTtsConfig". E, com lang preenchido, o caminho do
     * lexicon fica inalcançável (lang nunca vem vazio: se o config vier sem,
     * o impl cai no meta_data.voice), então o lexicon pt-BR não é usado.
     */
    private fun synthKokoro(modelDir: File, dataDir: File, msg: String): HashMap<String, Any>? {
        val onnx = File(modelDir, "model.int8.onnx")
        val voices = File(modelDir, "voices.bin")
        val tokens = File(modelDir, "tokens.txt")
        if (!onnx.exists() || !voices.exists() || !tokens.exists()) {
            Log.d(TAG, "kokoroTts: arquivos do modelo ausentes em $modelDir")
            return null
        }
        // O nativo valida kokoro-data-dir: precisa ser o espeak-ng-data com
        // phontab. Aceita o dir direto (installedTts devolve ele) ou o irmão
        // em stt-models — cobre layouts antigos que passavam o lexicon.
        val espeak = sequenceOf(
            dataDir,
            dataDir.parentFile?.let { File(it, "espeak-ng-data") },
        ).filterNotNull().firstOrNull { File(it, "phontab").exists() }
        if (espeak == null) {
            Log.d(TAG, "kokoroTts: espeak-ng-data ausente (baixe 'Fonemas pt-BR' em Configurar → Modelos)")
            return null
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = onnx.absolutePath,
                    voices = voices.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeak.absolutePath,
                    // Voz do espeak: português do Brasil. "pt" é português de
                    // Portugal (sotaque de estrangeiro) e o default do modelo
                    // é "en-us" (fonemiza pt como inglês = bagunça).
                    lang = "pt-br",
                ),
                numThreads = 2,
            ),
        )
        val tts = OfflineTts(assetManager = null, config = config)
        try {
            val audio = tts.generate(msg, KOKORO_SID, 1.0f)
            return pack(audio, "kokoroTts")
        } finally {
            try { tts.release() } catch (_: Throwable) { }
        }
    }

    /** Piper pt-BR: *.onnx + fonemas do espeak-ng-data. */
    private fun synthVits(modelDir: File, espeakDir: File, msg: String): HashMap<String, Any>? {
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
            return pack(tts.generate(msg, 0, 1.0f), "sherpaTts")
        } finally {
            try { tts.release() } catch (_: Throwable) { }
        }
    }

    /** GeneratedAudio → mapa "samples"/"sampleRate", ou null se vazio. */
    private fun pack(audio: GeneratedAudio, tag: String): HashMap<String, Any>? {
        val samples = audio.samples
        if (samples.isEmpty()) {
            Log.d(TAG, "$tag: generate devolveu vazio")
            return null
        }
        Log.d(TAG, "$tag: ok samples=${samples.size} rate=${audio.sampleRate}")
        return hashMapOf(
            "samples" to samples,
            "sampleRate" to audio.sampleRate,
        )
    }
}
