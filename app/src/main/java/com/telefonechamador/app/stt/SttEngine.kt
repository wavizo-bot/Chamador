package com.telefonechamador.app.stt

import kotlinx.coroutines.flow.SharedFlow

data class SttFinal(val streamId: Int, val text: String)
data class SttPartial(val streamId: Int, val text: String)

/**
 * Motor de transcrição plugável.
 * Implementações: VoskEngine (streaming, sempre disponível) e SherpaEngine
 * (Parakeet TDT via ONNX, compilado quando o AAR está em app/libs/).
 */
interface SttEngine {
    val finals: SharedFlow<SttFinal>
    val partials: SharedFlow<SttPartial>

    /** Carrega o modelo (pesado — rodar fora da main). */
    suspend fun prepare(streamIds: List<Int>)

    /** Alimenta amostras PCM 16-bit mono. */
    fun feed(streamId: Int, samples: ShortArray, frames: Int)

    /** Finaliza e devolve trechos pendentes que não saíram pelo flow. */
    suspend fun stop(): List<SttFinal>

    fun release()
}
