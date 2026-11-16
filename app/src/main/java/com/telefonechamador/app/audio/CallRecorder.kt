package com.telefonechamador.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Lado do áudio: voz recebida, sua voz, ou mix das duas. */
object Side {
    const val RECEIVED = "received"
    const val SENT = "sent"
    const val MIXED = "mixed"
}

data class StreamSpec(val streamId: Int, val side: String, val source: Int)

data class StreamPlan(val streams: List<StreamSpec>, val useMediaRecorder: Boolean, val note: String?)

data class PcmBlock(val streamId: Int, val side: String, val samples: ShortArray, val frames: Int)

data class MediaResult(val mediaPath: String?)

/**
 * Captura o áudio da ligação e publica blocos PCM de 100 ms.
 * Estratégias (em ordem): VOICE_CALL nativo → downlink/uplink → MediaRecorder nativo → microfone.
 */
class CallRecorder(private val context: Context, private val callDir: File) {

    private val _blocks = MutableSharedFlow<PcmBlock>(extraBufferCapacity = 512)
    val blocks: SharedFlow<PcmBlock> = _blocks.asSharedFlow()

    private val running = AtomicBoolean(false)
    private val records = ArrayList<AudioRecord>()
    private val specs = ArrayList<StreamSpec>()
    private val threads = ArrayList<Thread>()
    private val frameCounts = HashMap<Int, Long>()

    private var mr: MediaRecorder? = null
    private var mediaFile: File? = null

    var lastError: String? = null
        private set

    /** true quando o loop de efeito ao vivo está no caminho do áudio. */
    var fxActive = false
        private set

    private var fxLoop: VoiceFxLoop? = null

    /**
     * Troca o efeito de voz DURANTE a ligação (vale no próximo bloco de ~100 ms).
     * "none" desliga a reprodução (bypass sem realimentação) — a captura segue
     * como está até o fim da ligação (replanejar fontes em chamada não é seguro).
     */
    fun setLiveEffect(id: String) {
        fxLoop?.params = VoiceLibrary.paramsOf(context, id)
    }

    /** Fontes candidatas por stream para a troca adaptativa durante a ligação. */
    private val candidatesByStream = HashMap<Int, List<Int>>()

    /** Chamado quando a captura cai no microfone (para ligar o viva-voz). */
    var onMicFallback: (() -> Unit)? = null

    /** Chamado uma vez quando nenhuma fonte entregou energia durante a ligação. */
    var onCaptureDead: ((side: String) -> Unit)? = null

    /** Planejamento de streams conforme ajustes + capacidades do aparelho. */
    fun plan(caps: CaptureCapability): StreamPlan {
        val mode = Prefs.captureMode

        // Efeito ao vivo (experimental): captura a sua voz, aplica o efeito e
        // reproduz no alto-falante. Somente a sua voz fica disponível.
        if (Prefs.voiceFxLive && VoiceLibrary.byId(context, Prefs.voiceFx)?.id != VoiceLibrary.NONE_ID) {
            return StreamPlan(
                listOf(StreamSpec(0, Side.SENT, MediaRecorder.AudioSource.VOICE_COMMUNICATION)),
                false,
                "Efeito ao vivo ativo — somente a sua voz é capturada neste modo.",
            )
        }

        if (mode == Prefs.CAPTURE_MIC) {
            val note = if (caps.mic) null else "Microfone bloqueado pelo aparelho."
            return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.MIC)), false, note)
        }
        if (mode == Prefs.CAPTURE_NATIVE) {
            val note = if (caps.mix) null else "Fonte nativa pode estar bloqueada — testando mesmo assim."
            return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.VOICE_CALL)), false, note)
        }

        // Automático
        when (Prefs.recordingSide) {
            Prefs.RECORD_SIDE_BOTH -> {
                if (caps.down && caps.up)
                    return StreamPlan(
                        listOf(
                            StreamSpec(0, Side.RECEIVED, MediaRecorder.AudioSource.VOICE_DOWNLINK),
                            StreamSpec(1, Side.SENT, MediaRecorder.AudioSource.VOICE_UPLINK),
                        ), false, null
                    )
                if (caps.mix)
                    return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.VOICE_CALL)), false, null)
                if (caps.media)
                    return StreamPlan(emptyList(), true, "Gravação nativa via MediaRecorder — a transcrição desta ligação será gerada ao encerrar.")
                return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.MIC)), false,
                    if (caps.mic) null else "Nenhuma fonte de áudio disponível neste aparelho.")
            }
            Prefs.RECORD_SIDE_RECEIVED -> {
                if (caps.down)
                    return StreamPlan(listOf(StreamSpec(0, Side.RECEIVED, MediaRecorder.AudioSource.VOICE_DOWNLINK)), false, null)
                if (caps.mix)
                    return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.VOICE_CALL)), false,
                        "Este aparelho não separa a voz recebida — gravando o áudio completo.")
                if (caps.media)
                    return StreamPlan(emptyList(), true, "Gravação nativa (o aparelho só fornece o áudio completo) — transcrição gerada ao encerrar.")
                return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.MIC)), false,
                    "Sem acesso à voz recebida isolada — usando microfone. Ative o alto-falante para captar a outra voz.")
            }
            else -> { // SENT
                if (caps.up)
                    return StreamPlan(listOf(StreamSpec(0, Side.SENT, MediaRecorder.AudioSource.VOICE_UPLINK)), false, null)
                if (caps.mix)
                    return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.VOICE_CALL)), false,
                        "Este aparelho não separa a sua voz — gravando o áudio completo.")
                if (caps.media)
                    return StreamPlan(emptyList(), true, "Gravação nativa (o aparelho só fornece o áudio completo) — transcrição gerada ao encerrar.")
                return StreamPlan(listOf(StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.MIC)), false,
                    "Sem acesso isolado à sua voz — usando microfone.")
            }
        }
    }

    /** Abre as fontes; devolve os streams realmente abertos (vazio no modo MediaRecorder). */
    fun open(plan: StreamPlan): List<StreamSpec> {
        if (plan.useMediaRecorder) {
            val ok = startMediaRecorder()
            if (!ok) {
                // recupera com microfone
                val s = StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.MIC)
                val rec = tryOpen(s.source)
                if (rec != null) {
                    records.add(rec); specs.add(s)
                }
            }
            openedSpecs = specs.toList()
            return specs.toList()
        }

        for (s in plan.streams) {
            val rec = tryOpen(s.source) ?: continue
            records.add(rec)
            specs.add(s)
        }
        if (specs.isEmpty()) {
            val fallbacks = listOf(
                StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.VOICE_CALL),
                StreamSpec(0, Side.MIXED, MediaRecorder.AudioSource.MIC),
            )
            for (s in fallbacks) {
                val rec = tryOpen(s.source) ?: continue
                records.add(rec)
                specs.add(s)
                break
            }
        }
        // Se pediu split e só um dos lados abriu, degrada com nota
        if (plan.streams.size == 2 && specs.size == 1) {
            lastError = (lastError ?: "") + "Só um dos lados pôde ser captado."
        }
        // Efeito ao vivo: entra no caminho do áudio quando a captura é a nossa voz
        if (plan.streams.size == 1 &&
            specs.firstOrNull()?.source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
        ) {
            val params = VoiceLibrary.paramsOf(context, Prefs.voiceFx)
            if (params != null) {
                fxLoop = VoiceFxLoop(context, AudioSourceProbe.SAMPLE_RATE).also { it.params = params }
                fxActive = true
            }
        }
        for (s in specs) {
            candidatesByStream[s.streamId] = candidatesFor(s.side, s.source)
        }
        openedSpecs = specs.toList()
        return specs.toList()
    }

    /**
     * Fontes para tentar em ordem. O probe em idle não prevê o comportamento
     * DURANTE a ligação (a HAL muda de rota no MODE_IN_CALL), então o captador
     * troca sozinho para a próxima candidata quando a atual entrega zeros.
     */
    private fun candidatesFor(side: String, primary: Int): List<Int> {
        val list = when (side) {
            Side.RECEIVED -> listOf(
                primary, MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
            )
            Side.SENT -> listOf(
                primary, MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
            )
            else -> listOf(
                primary, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_DOWNLINK, MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.MIC,
            )
        }
        return list.distinct()
    }

    private var openedSpecs: List<StreamSpec> = emptyList()

    private fun tryOpen(source: Int): AudioRecord? = try {
        val buf = maxOf(AudioSourceProbe.minBufferSize() * 2, 16384)
        val r = AudioRecord(
            source, AudioSourceProbe.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf
        )
        val ok = r.state == AudioRecord.STATE_INITIALIZED
        Log.d(TAG, "tryOpen src=$source state=${r.state} ok=$ok")
        if (ok) r else { r.release(); null }
    } catch (t: Exception) {
        Log.d(TAG, "tryOpen src=$source EXCECAO: ${t.message}")
        lastError = (lastError ?: "") + "Fonte ${source}: ${t.message}. "
        null
    }

    private fun startMediaRecorder(): Boolean {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        for (src in sources) {
            val f = File(callDir, "chamada.m4a")
            try {
                val r = MediaRecorder()
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioEncodingBitRate(64000)
                r.setAudioSamplingRate(16000)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                mr = r
                mediaFile = f
                Log.d(TAG, "mediaRecorder ok src=$src")
                return true
            } catch (t: Exception) {
                Log.d(TAG, "mediaRecorder src=$src FALHOU: ${t.message}")
                lastError = (lastError ?: "") + "MediaRecorder(src=$src): ${t.message}. "
                try { f.delete() } catch (_: Exception) { }
            }
        }
        return false
    }

    /**
     * Gravação de emergência pelo sistema: quando nenhuma fonte do AudioRecord
     * entregou energia (HAL bloqueia terceiros em chamada), o MediaRecorder
     * ainda consegue a rota nativa em várias ROMs. O arquivo alimenta a
     * transcrição pós-ligação.
     */
    fun startSystemFallback(): Boolean {
        if (mr != null) return false
        return startMediaRecorder()
    }

    /** Pede o fim das threads de captura (cada uma libera o próprio AudioRecord). */
    fun haltCapture() {
        running.set(false)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        for ((idx, spec) in specs.withIndex()) {
            val rec = records.getOrNull(idx) ?: continue
            val candidates = (candidatesByStream[spec.streamId] ?: listOf(spec.source)).toMutableList()
            synchronized(frameCounts) { frameCounts[spec.streamId] = 0L }
            threads += thread(name = "cap-${spec.side}") {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (_: Exception) { }
                val buf = ShortArray(1600) // 100 ms @16 kHz
                var cur = rec
                var curSrc = spec.source
                var silentRun = 0
                var blocksTotal = 0L
                var rmsSum = 0.0
                var rmsN = 0
                var deadReported = false
                try {
                    cur.startRecording()
                } catch (t: Exception) {
                    Log.d(TAG, "startRecording EXCECAO: ${t.message}")
                    lastError = (lastError ?: "") + "startRecording: ${t.message}. "
                    return@thread
                }
                while (running.get()) {
                    val n = cur.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n < 0) { Log.d(TAG, "cap side=${spec.side} read=$n (parando)"); break } else continue
                    }
                    var e = 0.0
                    for (i in 0 until n) { val v = buf[i]; e += (if (v < 0) -v else v).toDouble() }
                    rmsSum += e / n
                    rmsN++
                    if (e > 0.0) silentRun = 0 else silentRun++
                    if (++blocksTotal % 20L == 0L) {
                        Log.d(TAG, "cap side=${spec.side} src=$curSrc blocos=$blocksTotal rmsMedio=${"%.0f".format(rmsSum / rmsN)}")
                        rmsSum = 0.0; rmsN = 0
                    }
                    if (fxLoop != null) {
                        // efeito ao vivo: cru (transcrição) + processado (gravação/toque)
                        val out = try { fxLoop!!.process(buf, n) } catch (_: Throwable) { buf.copyOf(n) }
                        synchronized(frameCounts) {
                            frameCounts[FX_RAW_STREAM] = (frameCounts[FX_RAW_STREAM] ?: 0L) + n
                        }
                        emitBlock(PcmBlock(FX_RAW_STREAM, spec.side, buf.copyOf(n), n))
                        synchronized(frameCounts) {
                            frameCounts[spec.streamId] = (frameCounts[spec.streamId] ?: 0L) + n
                        }
                        emitBlock(PcmBlock(spec.streamId, spec.side, out, n))
                    } else {
                        synchronized(frameCounts) {
                            frameCounts[spec.streamId] = (frameCounts[spec.streamId] ?: 0L) + n
                        }
                        emitBlock(PcmBlock(spec.streamId, spec.side, buf.copyOf(n), n))
                    }
                    // Troca adaptativa: em muitas ROMs a HAL muda fontes de terceiros
                    // durante a ligação (zeros digitais). Tenta a próxima candidata.
                    if (silentRun >= ROTATE_AFTER_BLOCKS) {
                        var nextSrc = 0
                        while (candidates.isNotEmpty()) {
                            val cand = candidates.removeAt(0)
                            if (cand != curSrc) { nextSrc = cand; break }
                        }
                        if (nextSrc != 0) {
                            val nr = tryOpen(nextSrc)
                            if (nr != null) {
                                Log.d(TAG, "cap TROCA side=${spec.side} de=$curSrc para=$nextSrc (sem energia)")
                                try { cur.stop() } catch (_: Exception) { }
                                try { cur.release() } catch (_: Exception) { }
                                synchronized(records) { records.remove(cur) }
                                cur = nr
                                curSrc = nextSrc
                                silentRun = 0
                                try { cur.startRecording() } catch (t: Exception) {
                                    Log.d(TAG, "startRecording EXCECAO: ${t.message}")
                                }
                                if (nextSrc == MediaRecorder.AudioSource.MIC) onMicFallback?.invoke()
                            }
                        } else if (!deadReported) {
                            deadReported = true
                            Log.d(TAG, "cap ESGOTOU side=${spec.side} — nenhuma fonte entregou energia")
                            onCaptureDead?.invoke(spec.side)
                            silentRun = 0
                        } else {
                            silentRun = 0
                        }
                    }
                }
                // Fim do loop: devolve o captador para o sistema (o fallback
                // MediaRecorder precisa da sessão de áudio livre).
                try { cur.stop() } catch (_: Exception) { }
                try { cur.release() } catch (_: Exception) { }
                synchronized(records) { records.remove(cur) }
            }
        }
    }

    private fun emitBlock(b: PcmBlock) {
        var tries = 0
        while (!_blocks.tryEmit(b) && tries < 100) {
            tries++
            try { Thread.sleep(2) } catch (_: InterruptedException) { return }
        }
    }

    /** Milissegundos de áudio já capturados em um stream (base para o offset da transcrição). */
    fun elapsedMs(streamId: Int): Long = synchronized(frameCounts) {
        (frameCounts[streamId] ?: 0L) * 1000L / AudioSourceProbe.SAMPLE_RATE
    }

    companion object {
        private const val TAG = "TChamador"

        /** 30 blocos (3 s) sem energia → troca de fonte. */
        private const val ROTATE_AFTER_BLOCKS = 30

        /** Stream extra com o áudio cru do loop de efeito (só transcrição). */
        const val FX_RAW_STREAM = 1000
    }

    fun stop(): MediaResult {
        running.set(false)
        threads.forEach { it.join(1500) }
        threads.clear()
        fxLoop?.release()
        fxLoop = null
        fxActive = false
        records.forEach {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        records.clear()
        var path: String? = null
        mr?.let {
            try { it.stop() } catch (t: Exception) { lastError = (lastError ?: "") + "stop: ${t.message}. " }
            try { it.release() } catch (_: Exception) { }
            mr = null
            path = mediaFile?.takeIf { f -> f.exists() && f.length() > 1000 }?.absolutePath
            if (path == null) try { mediaFile?.delete() } catch (_: Exception) { }
        }
        return MediaResult(path)
    }
}
