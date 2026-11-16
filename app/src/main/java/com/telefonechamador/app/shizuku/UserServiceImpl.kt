package com.telefonechamador.app.shizuku

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementação do IUserService que roda DENTRO do processo do Shizuku
 * (UID shell = 2000). Além de comandos e da sonda de captura, expõe a
 * gravação: AudioRecord com identidade com.android.shell escrevendo WAV
 * PCM 16-bit no arquivo criado pelo app — o serviço recebe apenas o
 * descriptor (mesmo padrão do CallRecorderService nativo, que também usa
 * ParcelFileDescriptor via Binder), então nada aqui precisa de acesso a
 * armazenamento. Sem argumento de construtor: o servidor Shizuku a
 * instancia por reflexão.
 */
class UserServiceImpl : IUserService.Stub() {

    private class RecSession(
        val id: Int,
        val rec: android.media.AudioRecord,
        val pfd: ParcelFileDescriptor,
        val out: BufferedOutputStream,
        val thread: Thread,
    ) {
        val running = AtomicBoolean(true)
        @Volatile var rmsSum = 0.0
        @Volatile var rmsN = 0
        @Volatile var dataLen = 0L

        /**
         * Heartbeat: o app checa o rms a cada ~4 s durante a ligação. Se
         * ninguém tocar aqui por HEARTBEAT_MS, o pump se encerra sozinho —
         * cobre o caso do app morrer/travar sem conseguir chamar stopRecord
         * (a gravação não pode rodar para sempre).
         */
        @Volatile var lastTouch = android.os.SystemClock.elapsedRealtime()
    }

    private val sessions = ConcurrentHashMap<Int, RecSession>()
    private val nextId = AtomicInteger(1)

    init {
        // Aquece o contexto shell na thread em que o Shizuku instancia o
        // serviço (melhor lugar para a reflexão). Falha aqui não derruba
        // nada: a sonda/gravação repetem na hora do uso.
        try {
            ShellCapture.enableHiddenApi()
            ShellCapture.shellContext()
        } catch (t: Throwable) {
            log("warmup falhou (adiado para o uso): ${t.message}")
        }
    }

    override fun runShell(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        p.inputStream.bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        "ERRO: ${t.message}"
    }

    /**
     * Sonda: abre AudioRecord DENTRO do processo shell e mede a energia.
     * Contrato: para cada fonte i → out[3i]=abriu(1)/não(0)/exceção(-1),
     * out[3i+1]=rmsMedio, out[3i+2]=blocos lidos.
     */
    override fun probeCapture(seconds: Int): IntArray {
        val sources = intArrayOf(
            android.media.MediaRecorder.AudioSource.MIC,
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            android.media.MediaRecorder.AudioSource.VOICE_CALL,
            android.media.MediaRecorder.AudioSource.VOICE_UPLINK,
            android.media.MediaRecorder.AudioSource.VOICE_DOWNLINK,
        )
        val labels = arrayOf("MIC", "VOICE_COMMUNICATION", "VOICE_CALL", "VOICE_UPLINK", "VOICE_DOWNLINK")
        val out = IntArray(sources.size * 3) { -2 }
        log("probeCapture begin: ${ShellCapture.diagnostics()}")

        val rate = 16000
        val buf = ShortArray(rate / 5) // 200 ms

        for (i in sources.indices) {
            var rec: android.media.AudioRecord? = null
            try {
                rec = ShellCapture.openRecord(
                    sources[i],
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (rec == null) {
                    log("probe ${labels[i]}: não abriu")
                    out[i * 3] = 0
                    continue
                }
                if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    log("probe ${labels[i]}: state=${rec.state}")
                    out[i * 3] = 0
                    continue
                }
                rec.startRecording()
                var asum = 0.0
                var blocks = 0
                val deadline = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var s = 0L
                        for (j in 0 until n) {
                            val v = buf[j].toInt()
                            s += (if (v < 0) -v else v)
                        }
                        asum += s.toDouble() / n
                        blocks++
                    }
                }
                val rms = if (blocks > 0) (asum / blocks).toInt() else 0
                out[i * 3] = 1
                out[i * 3 + 1] = rms
                out[i * 3 + 2] = blocks
                log("probe ${labels[i]}: abriu rms=$rms blocos=$blocks")
            } catch (t: Throwable) {
                log("probe ${labels[i]}: exceção ${t.javaClass.simpleName}: ${t.message}")
                out[i * 3] = -1
            } finally {
                try { rec?.stop() } catch (_: Throwable) { }
                try { rec?.release() } catch (_: Throwable) { }
            }
        }
        log("probeCapture end: ${out.joinToString()}")
        return out
    }

    /**
     * Abre um AudioRecord shell e inicia a gravação WAV no descriptor dado.
     * Cabeçalho com tamanhos em placeholder (o app corrige ao encerrar, pois
     * só ele conhece o comprimento final do arquivo).
     */
    override fun startRecord(source: Int, sampleRate: Int, output: ParcelFileDescriptor): Int {
        var rec: android.media.AudioRecord? = null
        var out: BufferedOutputStream? = null
        return try {
            ShellCapture.enableHiddenApi()
            rec = ShellCapture.openRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (rec == null) {
                log("startRecord src=$source: openRecord devolveu null")
                return -1
            }
            out = BufferedOutputStream(FileOutputStream(output.fileDescriptor))
            out.write(wavHeader(sampleRate))
            val id = nextId.getAndIncrement()
            // A thread de bombeamento precisa da sessão pronta: holder em
            // array para capturar a referência antes de start().
            val holder = arrayOfNulls<RecSession>(1)
            val t = Thread({ holder[0]?.let { pump(it) } }, "tcham-shell-rec-$id")
            val s = RecSession(id, rec, output, out, t)
            holder[0] = s
            sessions[id] = s
            t.start()
            log("startRecord src=$source id=$id ok")
            id
        } catch (t: Throwable) {
            log("startRecord src=$source EXCECAO: ${t.javaClass.simpleName}: ${t.message}")
            try { out?.close() } catch (_: Throwable) { }
            try { rec?.stop() } catch (_: Throwable) { }
            try { rec?.release() } catch (_: Throwable) { }
            -1
        }
    }

    /** Pump: lê PCM e escreve LE no stream; acumula rms para o lado do app. */
    private fun pump(s: RecSession) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) { }
        val buf = ShortArray(1600) // 100 ms @ 16 kHz
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        try { s.rec.startRecording() } catch (t: Throwable) {
            log("pump id=${s.id} startRecording: ${t.message}")
        }
        while (s.running.get() &&
            android.os.SystemClock.elapsedRealtime() - s.lastTouch < HEARTBEAT_MS
        ) {
            val n = try { s.rec.read(buf, 0, buf.size) } catch (t: Throwable) {
                log("pump id=${s.id} read: ${t.message}")
                break
            }
            if (n <= 0) {
                if (n < 0) break else continue
            }
            var e = 0.0
            for (i in 0 until n) {
                val v = buf[i].toInt()
                e += (if (v < 0) -v else v).toDouble()
            }
            synchronized(s) { s.rmsSum += e / n; s.rmsN++; s.dataLen += n * 2L }
            bytes.clear()
            bytes.asShortBuffer().put(buf, 0, n)
            try { s.out.write(bytes.array(), 0, n * 2) } catch (t: Throwable) {
                log("pump id=${s.id} write: ${t.message}")
                break
            }
        }
        if (s.running.get()) {
            log("pump id=${s.id} heartbeat expirou (sem consultas do app) — auto-stop")
        }
        try { s.rec.stop() } catch (_: Throwable) { }
        try { s.rec.release() } catch (_: Throwable) { }
        try { s.out.flush() } catch (_: Throwable) { }
    }

    override fun stopRecord(sessionId: Int): Int {
        val s = sessions.remove(sessionId) ?: return -1
        s.running.set(false)
        try { s.thread.join(4000) } catch (_: Throwable) { }
        try { s.out.flush() } catch (_: Throwable) { }
        try { s.out.close() } catch (_: Throwable) { }
        try { s.pfd.close() } catch (_: Throwable) { }
        val rms = synchronized(s) { if (s.rmsN > 0) (s.rmsSum / s.rmsN).toInt() else 0 }
        log("stopRecord id=$sessionId rms=$rms bytes=${s.dataLen}")
        return rms
    }

    override fun recordRms(sessionId: Int): Int {
        val s = sessions[sessionId] ?: return -1
        s.lastTouch = android.os.SystemClock.elapsedRealtime()
        return synchronized(s) { if (s.rmsN > 0) (s.rmsSum / s.rmsN).toInt() else 0 }
    }

    /** Cabeçalho WAV 44 bytes (PCM 16-bit mono) com tamanhos em placeholder. */
    private fun wavHeader(sampleRate: Int): ByteArray {
        val b = ByteArrayOutputStream(44)
        b.write("RIFF".toByteArray(Charsets.US_ASCII))
        b.write(le32(0)) // riffSize — corrigido pelo app no fim
        b.write("WAVE".toByteArray(Charsets.US_ASCII))
        b.write("fmt ".toByteArray(Charsets.US_ASCII))
        b.write(le32(16))
        b.write(le16(1)) // PCM
        b.write(le16(1)) // mono
        b.write(le32(sampleRate))
        b.write(le32(sampleRate * 2))
        b.write(le16(2)) // blockAlign
        b.write(le16(16)) // bits
        b.write("data".toByteArray(Charsets.US_ASCII))
        b.write(le32(0)) // dataSize — corrigido pelo app no fim
        return b.toByteArray()
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte(),
    )

    private fun le16(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
    )

    override fun exit() {
        for (id in sessions.keys.toList()) {
            try { stopRecord(id) } catch (_: Throwable) { }
        }
        System.exit(0)
    }

    private fun log(s: String) {
        try {
            android.util.Log.d("TChamador.Shizuku", s)
        } catch (_: Throwable) { }
    }

    private companion object {
        /** Sem consultas de rms por 1 minuto → o pump se encerra sozinho. */
        const val HEARTBEAT_MS = 60_000L
    }
}
