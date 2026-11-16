package com.telefonechamador.app.audio

/**
 * Efeitos de voz por DSP (VoiceLibrary define os parâmetros).
 * Processamento 100% local:
 *  - pitch shift por delay-line com 2 cabeças de leitura + crossfade Hann;
 *  - ring modulation (robô);
 *  - band-pass (rádio/telefone) com leve saturação;
 *  - eco com feedback;
 *  - tilt de timbre (grave/agudo) com filtros de 1 polo.
 *
 * Uso ao vivo: processBlock() por bloco de ~100 ms com um FxState por stream.
 * Uso offline: apply() percorre o áudio inteiro com um FxState novo.
 */
object VoiceFx {

    class FxState {
        internal val buf = DoubleArray(BUF)
        internal var writeIdx = 0
        internal var r0 = 0.0
        internal var r1 = BUF / 2.0
        internal var lpY = 0.0
        internal var lpX = 0.0
        internal var hpY = 0.0
        internal var hpX = 0.0
        internal var ringPhase = 0.0
        internal var delay = DoubleArray(0)
        internal var delayIdx = 0
        // tilt de timbre (filtros de 1 polo)
        internal var darkY = 0.0
        internal var lowY = 0.0
    }

    private const val BUF = 1200          // ~75 ms @16 kHz (janela de crossfade = BUF/2)

    /** Processa um bloco PCM 16-bit mono; params null = sem efeito (bypass). */
    fun processBlock(
        input: ShortArray,
        frames: Int,
        params: FxParams?,
        st: FxState,
        sampleRate: Int,
    ): ShortArray {
        if (params == null || frames <= 0) return input
        var work = DoubleArray(frames)
        for (i in 0 until frames) work[i] = input[i].toDouble()

        // 1) tilt de timbre (antes do pitch — muda o corpo da voz)
        if (Math.abs(params.tiltDb) > 0.05) {
            val dt = 1.0 / sampleRate
            val t = (Math.abs(params.tiltDb) / 10.0).coerceIn(0.0, 1.0)
            if (params.tiltDb > 0) {
                // escurece: mistura com grave
                val a = dt / ((1.0 / (2 * Math.PI * 1100.0)) + dt)
                for (i in work.indices) {
                    st.darkY += a * (work[i] - st.darkY)
                    work[i] = work[i] + t * (st.darkY - work[i])
                }
            } else {
                // clareia: realça o que passa de ~450 Hz
                val a = dt / ((1.0 / (2 * Math.PI * 450.0)) + dt)
                for (i in work.indices) {
                    st.lowY += a * (work[i] - st.lowY)
                    work[i] = work[i] + t * ((work[i] - st.lowY) - work[i])
                }
            }
        }

        // 2) pitch shift (delay-line doppler)
        val pr = params.pitch
        if (Math.abs(pr - 1.0) > 0.01) work = dopplerPitch(work, frames, pr, st)

        // 3) ring mod (robô)
        if (params.ringHz > 0.0) {
            val inc = 2.0 * Math.PI * params.ringHz / sampleRate
            for (i in work.indices) {
                work[i] *= Math.sin(st.ringPhase)
                st.ringPhase += inc
                if (st.ringPhase > 2 * Math.PI) st.ringPhase -= 2 * Math.PI
            }
        }

        // 4) band-pass (rádio antigo): HP 300 Hz + LP 3400 Hz + saturação
        if (params.radio) {
            val dt = 1.0 / sampleRate
            val aHp = (1.0 / (2 * Math.PI * 300.0)) / ((1.0 / (2 * Math.PI * 300.0)) + dt)
            val aLp = dt / ((1.0 / (2 * Math.PI * 3400.0)) + dt)
            for (i in work.indices) {
                val x = work[i]
                val hp = aHp * (st.hpY + x - st.hpX)
                st.hpX = x; st.hpY = hp
                val lp = st.lpY + aLp * (hp - st.lpY)
                st.lpY = lp
                work[i] = Math.max(-24000.0, Math.min(24000.0, lp * 2.2))
            }
        }

        // 5) eco com feedback
        if (params.echo) {
            val delayLen = (sampleRate * 0.12).toInt()
            if (st.delay.size != delayLen) {
                st.delay = DoubleArray(delayLen)
                st.delayIdx = 0
            }
            for (i in work.indices) {
                val d = st.delay[st.delayIdx]
                val v = work[i] * 0.72 + d * 0.5
                st.delay[st.delayIdx] = v * 0.45
                st.delayIdx = (st.delayIdx + 1) % delayLen
                work[i] = v
            }
        }

        val out = ShortArray(frames)
        val g = params.gain
        for (i in 0 until frames) {
            var v = work[i] * g
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toDouble()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toDouble()
            out[i] = v.toInt().toShort()
        }
        return out
    }

    /**
     * Pitch shifter por delay line: duas cabeças de leitura avançando a taxa `pitch`,
     * desfasadas de BUF/2, com pesos Hann complementares (soma ≈ 1).
     */
    private fun dopplerPitch(x: DoubleArray, n: Int, pitch: Double, st: FxState): DoubleArray {
        val out = DoubleArray(n)
        val half = BUF / 2.0
        for (i in 0 until n) {
            st.buf[st.writeIdx] = x[i]
            st.writeIdx = (st.writeIdx + 1) % BUF

            var acc = 0.0
            // cabeça 0
            var p0 = st.r0
            val idx0 = p0.toInt() % BUF
            val frac0 = p0 - p0.toInt()
            val a0 = st.buf[idx0]
            val b0 = st.buf[(idx0 + 1) % BUF]
            val s0 = a0 + (b0 - a0) * frac0
            val w0 = 0.5 - 0.5 * Math.cos(2 * Math.PI * (p0 % half) / half)
            acc += s0 * w0
            p0 += pitch
            if (p0 >= BUF) p0 -= BUF
            st.r0 = p0

            // cabeça 1 (desfasada half)
            var p1 = st.r1
            val idx1 = p1.toInt() % BUF
            val frac1 = p1 - p1.toInt()
            val a1 = st.buf[idx1]
            val b1 = st.buf[(idx1 + 1) % BUF]
            val s1 = a1 + (b1 - a1) * frac1
            val w1 = 0.5 - 0.5 * Math.cos(2 * Math.PI * (p1 % half) / half)
            acc += s1 * w1
            p1 += pitch
            if (p1 >= BUF) p1 -= BUF
            st.r1 = p1

            out[i] = acc * 1.35
        }
        return out
    }

    /** Offline: aplica os parâmetros em um áudio PCM inteiro (ShortArray). */
    fun apply(input: ShortArray, params: FxParams?, sampleRate: Int): ShortArray {
        if (params == null) return input
        val st = FxState()
        val out = ShortArray(input.size)
        var pos = 0
        while (pos < input.size) {
            val len = minOf(1600, input.size - pos)
            val chunk = input.copyOfRange(pos, pos + len)
            val fx = processBlock(chunk, len, params, st, sampleRate)
            System.arraycopy(fx, 0, out, pos, len)
            pos += len
        }
        return out
    }
}
