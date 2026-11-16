package com.telefonechamador.app.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Vosk: reconhecimento offline em streaming — resultado parcial quase no
 * instante em que o som chega. Modelos baixados por ModelManager.
 */
class VoskEngine(private val modelDir: File, private val sampleRate: Int) : SttEngine {

    companion object {
        private const val TAG = "TChamador"
    }

    private val _finals = MutableSharedFlow<SttFinal>(extraBufferCapacity = 64)
    override val finals: SharedFlow<SttFinal> = _finals.asSharedFlow()

    private val _partials = MutableSharedFlow<SttPartial>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val partials: SharedFlow<SttPartial> = _partials.asSharedFlow()

    private var model: Model? = null
    private val recognizers = HashMap<Int, Recognizer>()
    private var lastPartial = 0L

    override suspend fun prepare(streamIds: List<Int>) = withContext(Dispatchers.IO) {
        val m = Model(modelDir.absolutePath)
        Log.d(TAG, "Vosk modelo carregado: ${modelDir.absolutePath} streams=$streamIds")
        model = m
        streamIds.forEach { id -> recognizers[id] = Recognizer(m, sampleRate.toFloat()) }
    }

    override fun feed(streamId: Int, samples: ShortArray, frames: Int) {
        val rec = recognizers[streamId] ?: return
        val finalize = try {
            rec.acceptWaveForm(samples, frames)
        } catch (_: Throwable) {
            false
        }
        if (finalize) {
            val text = textOf(rec.result)
            Log.d(TAG, "Vosk final(stream=$streamId): '$text'")
            if (text.isNotBlank()) _finals.tryEmit(SttFinal(streamId, text))
        } else {
            val now = System.currentTimeMillis()
            if (now - lastPartial > 180) {
                val p = textOf(rec.partialResult)
                if (p.isNotBlank()) {
                    Log.d(TAG, "Vosk parcial(stream=$streamId): '$p'")
                    lastPartial = now
                    _partials.tryEmit(SttPartial(streamId, p))
                }
            }
        }
    }

    override suspend fun stop(): List<SttFinal> = withContext(Dispatchers.IO) {
        val out = ArrayList<SttFinal>()
        for ((id, r) in recognizers) {
            try {
                val t = textOf(r.finalResult)
                Log.d(TAG, "Vosk finalResult(stream=$id): '$t'")
                if (t.isNotBlank()) {
                    val f = SttFinal(id, t)
                    out.add(f)
                    _finals.tryEmit(f)
                }
            } catch (_: Exception) { }
        }
        recognizers.clear()
        freeModel()
        out
    }

    override fun release() {
        recognizers.clear()
        freeModel()
    }

    private fun freeModel() {
        try { model?.close() } catch (_: Exception) { }
        model = null
    }

    private fun textOf(json: String): String = try {
        val o = JSONObject(json)
        o.optString("text", "").ifBlank { o.optString("partial", "") }
    } catch (_: Exception) {
        ""
    }
}
