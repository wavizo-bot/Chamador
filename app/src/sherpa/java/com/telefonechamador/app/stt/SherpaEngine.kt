package com.telefonechamador.app.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Parakeet TDT 0.6b v3 via sherpa-onnx (offline, decodificação em blocos).
 * Compilado somente quando o AAR está em app/libs/ (source set condicional).
 * Emissão: parciais a cada ~2 s; "finals" por delta de palavras (≥ 8 palavras
 * novas) e o restante no stop().
 */
class SherpaEngine(private val modelDir: File, private val sampleRate: Int) : SttEngine {

    private val _finals = MutableSharedFlow<SttFinal>(extraBufferCapacity = 64)
    override val finals: SharedFlow<SttFinal> = _finals.asSharedFlow()

    private val _partials = MutableSharedFlow<SttPartial>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val partials: SharedFlow<SttPartial> = _partials.asSharedFlow()

    private var recognizer: OfflineRecognizer? = null
    private val acc = HashMap<Int, ArrayList<Float>>()
    private val decodedMs = HashMap<Int, Long>()
    private val lastWords = HashMap<Int, Int>()
    private val lastText = HashMap<Int, String>()

    override suspend fun prepare(streamIds: List<Int>) {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        streamIds.forEach { id -> acc[id] = ArrayList() }
    }

    override fun feed(streamId: Int, samples: ShortArray, frames: Int) {
        val rec = recognizer ?: return
        val list = acc.getOrPut(streamId) { ArrayList(frames) }
        for (i in 0 until frames) list.add(samples[i] / 32768.0f)
        val totalMs = list.size * 1000L / sampleRate
        if (totalMs - (decodedMs[streamId] ?: 0L) < DECODE_EVERY_MS) return
        decodedMs[streamId] = totalMs
        decode(rec, streamId)
    }

    private fun decode(rec: OfflineRecognizer, streamId: Int) {
        val list = acc[streamId] ?: return
        if (list.size < sampleRate / 4) return
        try {
            val stream = rec.createStream()
            stream.acceptWaveform(list.toFloatArray(), sampleRate)
            rec.decode(stream)
            emit(streamId, rec.getResult(stream).text.trim())
        } catch (_: Throwable) { }
    }

    private fun emit(streamId: Int, text: String) {
        if (text.isBlank()) return
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val done = lastWords[streamId] ?: 0
        if (words.size - done >= FINAL_DELTA_WORDS) {
            val chunk = words.subList(done, words.size).joinToString(" ")
            lastWords[streamId] = words.size
            _finals.tryEmit(SttFinal(streamId, chunk))
        }
        if (text != lastText[streamId]) {
            lastText[streamId] = text
            _partials.tryEmit(SttPartial(streamId, text))
        }
    }

    override suspend fun stop(): List<SttFinal> {
        val out = ArrayList<SttFinal>()
        val rec = recognizer
        if (rec != null) {
            for (id in acc.keys.toList()) {
                try { decode(rec, id) } catch (_: Throwable) { }
                val words = (lastText[id] ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
                val done = lastWords[id] ?: 0
                if (words.size > done) {
                    val f = SttFinal(id, words.subList(done, words.size).joinToString(" "))
                    out.add(f)
                    _finals.tryEmit(f)
                }
                acc[id]?.clear()
            }
        }
        return out
    }

    override fun release() {
        acc.clear()
        lastWords.clear()
        lastText.clear()
        decodedMs.clear()
        recognizer = null
    }

    companion object {
        private const val DECODE_EVERY_MS = 2000L
        private const val FINAL_DELTA_WORDS = 8
    }
}
