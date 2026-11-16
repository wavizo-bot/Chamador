package com.telefonechamador.app.audio

import android.util.Log

/**
 * VAD de energia adaptativo (sem dependências).
 * - Piso de ruído dinâmico: desce rápido em silêncio, sobe devagar.
 * - Limiar = piso × fator, com mínimo absoluto.
 * - Hangover: mantém "fala" ativa ~350 ms após a energia cair (evita cortar palavras).
 * - Pré-roll feito pelo chamador (CallEngine) para não perder o começo das frases.
 */
class VadGate(private val sampleRate: Int) {

    private var noiseFloor = 150.0
    private var speechRunMs = 0L
    private var hangoverMs = 0L
    private var active = false

    /** Recebe um bloco; devolve true enquanto há fala (incluindo hangover). */
    @Synchronized
    fun feed(samples: ShortArray, frames: Int): Boolean {
        if (frames <= 0) return active
        var e = 0L
        for (i in 0 until frames) {
            val v = samples[i]
            e += (if (v < 0) -v else v).toLong()
        }
        val rms = e / frames.toDouble()
        val ms = frames * 1000L / sampleRate

        if (!active) {
            noiseFloor = 0.9 * noiseFloor + 0.1 * rms
        }
        val threshold = maxOf(noiseFloor * FACTOR, MIN_THRESHOLD)
        val speech = rms > threshold

        if (speech) {
            speechRunMs += ms
            hangoverMs = HANGOVER_MS
            if (speechRunMs >= MIN_SPEECH_MS) {
                if (!active) Log.d(TAG, "VAD ATIVA (rms=%.0f limiar=%.0f)".format(rms, threshold))
                active = true
            }
        } else {
            speechRunMs = 0
            hangoverMs -= ms
            if (hangoverMs <= 0) {
                if (active) Log.d(TAG, "VAD inativa (rms=%.0f piso=%.0f)".format(rms, noiseFloor))
                active = false
            }
        }
        return active
    }

    fun isActive(): Boolean = active

    fun reset() {
        synchronized(this) {
            noiseFloor = 150.0
            speechRunMs = 0
            hangoverMs = 0
            active = false
        }
    }

    companion object {
        private const val TAG = "TChamador"
        private const val FACTOR = 2.2
        private const val MIN_THRESHOLD = 90.0
        private const val MIN_SPEECH_MS = 120L
        private const val HANGOVER_MS = 350L
    }
}
