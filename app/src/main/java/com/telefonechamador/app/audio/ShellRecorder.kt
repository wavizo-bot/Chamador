package com.telefonechamador.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.telefonechamador.app.util.Prefs
import com.telefonechamador.app.util.ShizukuSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gravação + transcrição ao vivo pelo serviço shell do Shizuku (uid 2000).
 *
 * O serviço abre o AudioRecord com identidade com.android.shell e escreve
 * WAV PCM 16-bit no arquivo que o app criou (via ParcelFileDescriptor, o
 * mesmo padrão do CallRecorderService nativo). Este lado:
 *
 *  - decide as fontes candidatas conforme o modo de gravação (recebida →
 *    DOWNLINK com fallback para VOICE_CALL; enviada → UPLINK; ambas → CALL);
 *  - lê o WAV em crescimento ("tailer") e publica blocos PCM para o pipeline
 *    existente (VAD → Vosk) como se viessem do captador do app;
 *  - troca de fonte quando o rms fica exatamente 0 por ~12 s (fonte morta
 *    entrega zeros digitais; canal vivo em silêncio entrega ruído de base);
 *  - no fim, encerra a sessão e corrige os cabeçalhos WAV (o serviço grava
 *    os tamanhos como placeholder — o comprimento final só o app conhece).
 *
 * Toda chamada Binder passa por timeout: uma transação que não responde
 * (servidor Shizuku em estado ruim, serviço travado) nunca pode prender o
 * encerramento da ligação — depois do primeiro travamento a bandeira
 * [binderStuck] desliga as chamadas seguintes e o heartbeat do serviço
 * (no UserServiceImpl) encerra o pump sozinho em até 1 minuto.
 */
class ShellRecorder(
    private val context: Context,
    private val dir: File,
    private val sampleRate: Int,
) {

    private class Live(
        val sessionId: Int,
        val streamId: Int,
        val source: Int,
        val side: String,
        val file: File,
    ) {
        var offset = 44L      // cabeçalho WAV de 44 bytes
        var zeroChecks = 0
    }

    /** streamId fixo por candidato: 2000 + índice (o motor prepara um por stream). */
    fun streamIds(): List<Int> = candidates.indices.map { STREAM_BASE + it }

    var onSessionStart: ((streamId: Int, side: String, file: File) -> Unit)? = null
    var onBlock: ((PcmBlock) -> Unit)? = null
    var onEnded: ((anyAudio: Boolean) -> Unit)? = null

    @Volatile private var live: Live? = null
    private var job: Job? = null
    private val usedFiles = ArrayList<File>()

    /** Verdadeiro após o primeiro Binder que não responde: não insiste mais. */
    @Volatile private var binderStuck = false

    /** Rms médio da última sessão encerrada (diagnóstico; -2 = stop sem resposta). */
    var lastRms = -1
        private set

    val tailJob: Job?
        get() = job

    private val candidates: List<Pair<Int, String>> = buildCandidates()

    /** Chamada Binder com timeout; null = travou (marca binderStuck). */
    private suspend fun <T> binder(tag: String, timeoutMs: Long, block: () -> T?): T? {
        if (binderStuck) {
            Log.d(TAG, "shell binder $tag ignorado (binder travado antes)")
            return null
        }
        val r = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { block() }
        }
        if (r == null) {
            binderStuck = true
            Log.d(TAG, "shell binder $tag TIMEOUT (${timeoutMs} ms) — demais chamadas serão ignoradas")
        }
        return r
    }

    /**
     * Abre a primeira candidata e inicia o tailer. Devolve false quando
     * nenhuma fonte abre, para o chamador usar o captador do app.
     */
    suspend fun startBlocking(scope: CoroutineScope): Boolean {
        if (candidates.isEmpty()) return false
        if (!openCandidate(0)) return false
        job = scope.launch(Dispatchers.IO) { runLoop() }
        return true
    }

    /** Abre a candidata i; registra stream/side/arquivo. */
    private suspend fun openCandidate(idx: Int): Boolean {
        val (src, side) = candidates[idx]
        val file = fileFor(side)
        val id = binder("start src=$src", BINDER_START_MS) {
            ShizukuSupport.startShellRecord(context, src, sampleRate, file)
        }
        if (id == null) {
            if (!binderStuck) Log.d(TAG, "shell: fonte src=$src não abriu")
            return false
        }
        val l = Live(id, STREAM_BASE + idx, src, side, file)
        live = l
        synchronized(usedFiles) { usedFiles.add(file) }
        Log.d(TAG, "shell sessão id=$id stream=${l.streamId} src=$src side=$side")
        onSessionStart?.invoke(l.streamId, side, file)
        return true
    }

    /**
     * Loop principal: acompanha o arquivo da sessão atual, publica os blocos
     * novos e troca de candidata quando a fonte entrega zeros.
     */
    private suspend fun runLoop() {
        var candIdx = 0
        while (job?.isActive == true) {
            if (live == null) {
                var opened = false
                while (candIdx < candidates.size) {
                    if (openCandidate(candIdx)) { opened = true; break }
                    candIdx++
                }
                if (!opened) {
                    Log.d(TAG, "shell: candidatas esgotadas sem áudio")
                    onEnded?.invoke(false)
                    return
                }
            }
            val l = live ?: continue
            var checks = 0
            while (job?.isActive == true) {
                delay(POLL_MS)
                feedNewData(l)
                checks++
                if (checks % CHECK_EVERY == 0) {
                    val rms = binder("rms id=${l.sessionId}", BINDER_RMS_MS) {
                        ShizukuSupport.shellRecordRms(context, l.sessionId).takeIf { it >= 0 }
                    }
                    when {
                        rms == null -> { /* travou ou sessão sumiu: sem rotação */ }
                        rms == 0 -> {
                            l.zeroChecks++
                            Log.d(TAG, "shell rms=0 (${l.zeroChecks}/$ZERO_LIMIT) stream=${l.streamId}")
                            if (l.zeroChecks >= ZERO_LIMIT) {
                                binder("stop id=${l.sessionId}", BINDER_STOP_MS) {
                                    ShizukuSupport.stopShellRecord(context, l.sessionId)
                                }
                                live = null
                                candIdx++
                                break
                            }
                        }
                        else -> {
                            l.zeroChecks = 0
                            if (checks % (CHECK_EVERY * 4) == 0) {
                                Log.d(TAG, "shell rms=$rms stream=${l.streamId} fed=${l.offset - 44}")
                            }
                        }
                    }
                }
            }
        }
    }

    /** Lê os bytes novos do WAV e publica em blocos de 200 ms. */
    private fun feedNewData(l: Live) {
        val len = l.file.length()
        var avail = len - l.offset
        if (avail < 2) return
        if (avail % 2L != 0L) avail -= 1 // amostras de 2 bytes: só consome pares
        if (avail <= 0) return
        val data = ByteArray(avail.toInt())
        try {
            RandomAccessFile(l.file, "r").use { raf ->
                raf.seek(l.offset)
                raf.readFully(data)
            }
        } catch (t: Throwable) {
            return
        }
        l.offset += avail
        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        var idx = 0
        while (idx < shorts.size) {
            val n = minOf(CHUNK, shorts.size - idx)
            onBlock?.invoke(PcmBlock(l.streamId, l.side, shorts.copyOfRange(idx, idx + n), n))
            idx += n
        }
    }

    /**
     * Encerra tudo: cancela e espera o tailer (com teto), pede o stop da
     * sessão (com teto) e SEMPRE fecha os cabeçalhos WAV — o histórico e o
     * arquivo final não podem depender de uma resposta do Shizuku.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        // join é obrigatório: o tailer alimenta o JNI do Vosk — finalizar o
        // engine com o tailer ainda rodando causa SIGSEGV (use-after-free).
        val j = job
        job = null
        if (j != null) {
            val joined = withTimeoutOrNull(JOIN_TIMEOUT_MS) { j.cancelAndJoin() }
            if (joined == null) {
                Log.d(TAG, "shell join do tailer TIMEOUT — seguindo sem ele")
            }
        }
        val l = live
        live = null
        if (l != null) {
            val rms = binder("stop id=${l.sessionId}", BINDER_STOP_MS) {
                ShizukuSupport.stopShellRecord(context, l.sessionId)
            }
            lastRms = rms ?: -2
            Log.d(TAG, "shell stop sessão=${l.sessionId} rms=$lastRms${if (rms == null) " (sem resposta; heartbeat do serviço encerra o pump)" else ""}")
        }
        val files = synchronized(usedFiles) { usedFiles.toList() }
        for (f in files) patchWavHeader(f)
    }

    private fun fileFor(side: String): File = when (side) {
        Side.RECEIVED -> File(dir, "audio_recebido.wav")
        Side.SENT -> File(dir, "audio_enviado.wav")
        else -> File(dir, "audio.wav")
    }

    private fun buildCandidates(): List<Pair<Int, String>> = when (Prefs.recordingSide) {
        Prefs.RECORD_SIDE_RECEIVED -> listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK to Side.RECEIVED,
            MediaRecorder.AudioSource.VOICE_CALL to Side.MIXED,
        )
        Prefs.RECORD_SIDE_SENT -> listOf(
            MediaRecorder.AudioSource.VOICE_UPLINK to Side.SENT,
            MediaRecorder.AudioSource.VOICE_CALL to Side.MIXED,
        )
        else -> listOf(MediaRecorder.AudioSource.VOICE_CALL to Side.MIXED)
    }

    private fun patchWavHeader(f: File) {
        try {
            if (!f.exists() || f.length() < 44) return
            RandomAccessFile(f, "rw").use { raf ->
                val len = raf.length()
                raf.seek(4)
                raf.write(le32((len - 8).toInt()))
                raf.seek(40)
                raf.write(le32((len - 44).toInt()))
            }
        } catch (t: Throwable) {
            Log.d(TAG, "patchWav ${f.name}: ${t.message}")
        }
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte(),
    )

    companion object {
        private const val TAG = "TChamador"
        const val STREAM_BASE = 2000

        private const val POLL_MS = 250L
        private const val CHECK_EVERY = 16          // ~4 s entre checagens de rms
        private const val ZERO_LIMIT = 3            // 3 zeros seguidos (~12 s) → troca
        private const val CHUNK = 3200              // 200 ms @ 16 kHz

        // Tetos das transações Binder: abrir a fonte custa ~1 s no primeiro
        // uso; rms/stop são chamadas locais no serviço (resposta em ms).
        private const val BINDER_START_MS = 6000L
        private const val BINDER_RMS_MS = 3000L
        private const val BINDER_STOP_MS = 4000L
        private const val JOIN_TIMEOUT_MS = 4000L

        /**
         * PCM 16-bit de um WAV gravado aqui (cabeçalho de 44 bytes). Serve
         * para a transcrição pós-ligação direto do arquivo — sem depender de
         * decodificadores de container.
         */
        fun readWavPcm(file: File): ShortArray? = try {
            val bytes = file.readBytes()
            if (bytes.size <= 44) null
            else ShortArray((bytes.size - 44) / 2).also {
                ByteBuffer.wrap(bytes, 44, it.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(it)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
