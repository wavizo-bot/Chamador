package com.telefonechamador.app.call

import android.content.Context
import android.media.MediaRecorder
import com.telefonechamador.app.App
import com.telefonechamador.app.audio.AudioSourceProbe
import com.telefonechamador.app.audio.CaptureCapability
import com.telefonechamador.app.audio.CallRecorder
import com.telefonechamador.app.audio.MediaDecode
import com.telefonechamador.app.audio.PcmBlock
import com.telefonechamador.app.audio.ShellRecorder
import com.telefonechamador.app.audio.Side
import com.telefonechamador.app.audio.VadGate
import com.telefonechamador.app.audio.WavWriter
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.util.Fmt
import com.telefonechamador.app.service.OverlayService
import com.telefonechamador.app.stt.ModelManager
import com.telefonechamador.app.stt.OfflineFileStt
import com.telefonechamador.app.stt.SttEngine
import com.telefonechamador.app.stt.VoskEngine
import com.telefonechamador.app.util.Notifications
import com.telefonechamador.app.util.Prefs
import com.telefonechamador.app.util.ShizukuSupport
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Orquestra uma ligação: captação (CallRecorder) → VAD (VadGate, com pré-roll)
 * → motor de transcrição (SttEngine) → banco (segmentos) → UI (CallController).
 * Gravação WAV fica fiel e completa (sem o filtro de silêncio).
 * Modo MediaRecorder (gravação nativa): transcrição gerada ao encerrar.
 */
class CallEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = CallRepository(context)

    @Volatile private var callId = 0L
    @Volatile private var number = ""
    @Volatile private var lateNumber: String? = null
    private var startedAt = 0L
    private var recorder: CallRecorder? = null

    /** Troca o efeito de voz ao vivo durante a ligação corrente. */
    fun setLiveEffect(id: String) {
        recorder?.setLiveEffect(id)
    }

    /** true quando o loop de efeito ao vivo está no caminho do áudio agora. */
    fun liveEffectActive(): Boolean = recorder?.fxActive == true
    private var stt: SttEngine? = null
    private val wavFiles = ConcurrentHashMap<String, File>()   // side -> arquivo
    private val streamSide = ConcurrentHashMap<Int, String>()  // streamId -> side
    private val vads = HashMap<Int, VadGate>()
    private val preRoll = HashMap<Int, ArrayDeque<PcmBlock>>()
    private val jobs = ArrayList<Job>()
    private var allowedSides: Set<String> = setOf(Side.RECEIVED, Side.MIXED)
    private var mediaPath: String? = null

    // Captura pelo serviço shell do Shizuku (uid 2000) — caminho preferencial.
    private var shellRecorder: ShellRecorder? = null
    @Volatile private var shellActive = false
    private val shellFrames = ConcurrentHashMap<Int, Long>()

    @Volatile private var stopping = false
    @Volatile var isActive = false
        private set

    private var speakerAutoOn = false

    /** true quando nenhuma fonte do captador entregou energia nesta ligação. */
    @Volatile private var captureDead = false

    var note: String? = null
        private set

    fun start(number: String, contactName: String?, direction: String) {
        scope.launch { runStart(number, contactName, direction) }
    }

    /**
     * Número entregue pelo Telecom depois do início: no Android 31+ o
     * TelephonyCallback não traz número e o bind do InCallService pode perder
     * a corrida do OFFHOOK. Preenche o número que ficou vazio e corrige a
     * linha já criada no banco.
     */
    fun updateNumberFromTelecom(raw: String) {
        val n = Fmt.normalize(raw)
        if (n.isBlank()) return
        lateNumber = n
        if (number.isNotBlank()) return
        number = n
        val cid = callId
        if (cid > 0L) {
            scope.launch {
                try { repo.updateNumber(cid, n) } catch (_: Throwable) { }
            }
        }
    }

    private suspend fun runStart(initialNumber: String, contactName: String?, direction: String) {
        startedAt = System.currentTimeMillis()
        isActive = true
        // O handle do Telecom pode chegar depois do OFFHOOK (bind assíncrono):
        // usa o número tardio quando o da largada ficou vazio.
        number = initialNumber.ifBlank { lateNumber ?: "" }
        val wantTranscribe = Prefs.autoTranscribe
        val wantRecording = Prefs.recordingEnabled
        CallController.setActive(
            number = number,
            contactName = contactName,
            direction = direction,
            startedAt = startedAt,
            transcribing = wantTranscribe,
            recording = wantRecording,
        )
        try {
            callId = repo.beginCall(number, contactName, direction, startedAt)
        } catch (t: Throwable) {
            note = "Falha ao registrar a ligação: ${t.message}"
        }
        try {
            OverlayService.start(context)
        } catch (_: Throwable) { }

        if (stopping) return
        val dir = File(context.getExternalFilesDir(null), "chamadas/$callId")
        try { dir.mkdirs() } catch (_: Throwable) { }

        // Caminho preferencial: captura pelo serviço shell do Shizuku (uid
        // 2000) — grava WAV e alimenta a transcrição ao vivo. Se o Shizuku
        // não estiver pronto, a preferência estiver desligada ou nenhuma
        // fonte abrir, segue o captador do app como sempre.
        if (Prefs.shizukuRecord && ShizukuSupport.ready() &&
            tryShellCapture(dir, wantTranscribe, wantRecording)
        ) {
            return
        }

        val caps = loadCaps()
        if (!caps.usable) {
            note = "Sem acesso ao microfone — conceda a permissão no app."
            finishUp(System.currentTimeMillis())
            return
        }
        startInAppCapture(caps, dir, wantTranscribe, wantRecording)
    }

    /**
     * Caminho clássico: captador do app (AudioRecord no processo do app), com
     * plano por capacidades, rotação adaptativa e fallbacks de sempre.
     */
    private suspend fun startInAppCapture(
        caps: CaptureCapability,
        dir: File,
        wantTranscribe: Boolean,
        wantRecording: Boolean,
    ) {
        val r = CallRecorder(context, dir)
        recorder = r
        val plan = r.plan(caps)
        val opened = withContext(Dispatchers.IO) { r.open(plan) }
        note = plan.note ?: r.lastError?.trim()?.takeIf { it.isNotBlank() }
        if (note != null) CallController.setNote(note)

        // Viva-voz pelo caminho certo (rota do Telecom) quando captamos pelo microfone.
        if (Prefs.micSpeakerAuto && opened.firstOrNull()?.source == MediaRecorder.AudioSource.MIC) {
            speakerAutoOn = true
            CallController.setSpeakerRoute(context, true)
        }
        r.onMicFallback = {
            if (Prefs.micSpeakerAuto) {
                speakerAutoOn = true
                CallController.setSpeakerRoute(context, true)
            }
        }
        r.onCaptureDead = {
            captureDead = true
            note = "O aparelho bloqueou a captação — tentando a gravação nativa do sistema; a transcrição desta ligação sai ao encerrar."
            CallController.setNote(note)
            scope.launch(Dispatchers.IO) {
                r.haltCapture()
                delay(400)
                val ok = try { r.startSystemFallback() } catch (_: Throwable) { false }
                Log.d("TChamador", "systemFallback ok=$ok")
                note = if (ok) {
                    "Gravação nativa do sistema em andamento — a transcrição sai ao encerrar a ligação."
                } else {
                    "O aparelho não entrega o áudio da ligação para o app — sem transcrição nesta ligação."
                }
                CallController.setNote(note)
            }
        }

        if (stopping) { stopRecorderSilently(r); finishUp(System.currentTimeMillis()); return }

        if (opened.isEmpty() && !plan.useMediaRecorder) {
            if (note == null) note = "Nenhuma fonte de áudio pôde ser aberta neste aparelho."
            CallController.setNote(note)
            finishUp(System.currentTimeMillis())
            return
        }

        if (wantRecording) {
            for (spec in opened) {
                val f = when (spec.side) {
                    Side.RECEIVED -> File(dir, "audio_recebido.wav")
                    Side.SENT -> File(dir, "audio_enviado.wav")
                    else -> File(dir, "audio.wav")
                }
                try {
                    wavFiles[spec.side] = f
                    WavWriter(f, AudioSourceProbe.SAMPLE_RATE).also {
                        wavWriters[spec.streamId] = it
                    }
                    streamSide[spec.streamId] = spec.side
                } catch (_: Throwable) { }
            }
        }
        if (r.fxActive) streamSide[CallRecorder.FX_RAW_STREAM] = Side.SENT

        if (wantTranscribe) {
            stt = buildStt()
            if (stt != null) {
                allowedSides = computeAllowedSides()
                val ids = opened.map { it.streamId } +
                    (if (r.fxActive) listOf(CallRecorder.FX_RAW_STREAM) else emptyList())
                try {
                    withContext(Dispatchers.IO) { stt!!.prepare(ids) }
                } catch (t: Throwable) {
                    note = (note ?: "") + " Motor de transcrição falhou: ${t.message}."
                    stt = null
                    CallController.setNote(note)
                }
            } else {
                note = (note ?: "") + " Modelo de transcrição não instalado (Ajustes → Modelos)."
                CallController.setNote(note)
            }
        }
        if (stopping) { stopRecorderSilently(r); finishUp(System.currentTimeMillis()); return }

        if (stt != null) {
            subscribeStt(r)
        }
        jobs += scope.launch {
            r.blocks.collect { handleBlock(it) }
        }

        try { r.start() } catch (t: Throwable) {
            note = (note ?: "") + " Falha ao iniciar a captura: ${t.message}."
            CallController.setNote(note)
        }
    }

    /**
     * Captura pelo Shizuku: abre a primeira sessão de gravação no processo
     * shell, prepara o motor de transcrição para os streams das candidatas
     * e devolve true quando tudo está no ar (o tailer publica os blocos).
     */
    private suspend fun tryShellCapture(
        dir: File,
        wantTranscribe: Boolean,
        wantRecording: Boolean,
    ): Boolean {
        if (!wantRecording && !wantTranscribe) return false
        val sh = ShellRecorder(context, dir, AudioSourceProbe.SAMPLE_RATE)
        sh.onSessionStart = { streamId, side, file ->
            streamSide[streamId] = side
            wavFiles[side] = file
        }
        sh.onBlock = { handleBlock(it) }
        sh.onEnded = { anyAudio ->
            if (!anyAudio) {
                note = "A captura pelo Shizuku não recebeu áudio nesta ligação."
                CallController.setNote(note)
            }
        }
        if (!sh.startBlocking(scope)) {
            Log.d("TChamador", "shell: primeira fonte não abriu — usando captador do app")
            return false
        }
        shellRecorder = sh
        shellActive = true
        note = "Captura pelo Shizuku (uid shell) em andamento."
        CallController.setNote(note)
        Log.d("TChamador", "shell capture ativo: streams=${sh.streamIds()}")
        if (wantTranscribe) {
            stt = buildStt()
            if (stt != null) {
                allowedSides = computeAllowedSides()
                try {
                    withContext(Dispatchers.IO) { stt!!.prepare(sh.streamIds()) }
                } catch (t: Throwable) {
                    note = (note ?: "") + " Motor de transcrição falhou: ${t.message}."
                    stt = null
                    CallController.setNote(note)
                }
            } else {
                note = (note ?: "") + " Modelo de transcrição não instalado (Ajustes → Modelos)."
                CallController.setNote(note)
            }
            subscribeStt(null)
        }
        sh.tailJob?.let { jobs += it }
        return true
    }

    /** Assina os fluxos do motor de transcrição — usado pelos dois caminhos. */
    private fun subscribeStt(r: CallRecorder?) {
        val engine = stt ?: return
        jobs += scope.launch {
            engine.finals.collect { f ->
                val side = streamSide[f.streamId] ?: Side.MIXED
                Log.d("TChamador", "final recebido side=$side '${f.text}'")
                if (f.text.isNotBlank() && f.text.any { it.isLetterOrDigit() }) {
                    appendSegment(side, offsetFor(f.streamId, r), f.text)
                }
            }
        }
        jobs += scope.launch {
            engine.partials.collect { p ->
                val side = streamSide[p.streamId] ?: Side.MIXED
                if (allowedSides.contains(side)) CallController.setPartial(side, p.text)
            }
        }
    }

    private fun computeAllowedSides(): Set<String> =
        if (Prefs.transcriptionSide == Prefs.TRANSCRIPTION_SIDE_BOTH)
            setOf(Side.RECEIVED, Side.SENT, Side.MIXED)
        else setOf(Side.RECEIVED, Side.MIXED)

    /** Offset em ms de um stream: shell conta os frames alimentados pelo tailer. */
    private fun offsetFor(streamId: Int, r: CallRecorder?): Long =
        if (streamId >= ShellRecorder.STREAM_BASE)
            (shellFrames[streamId] ?: 0L) * 1000L / AudioSourceProbe.SAMPLE_RATE
        else r?.elapsedMs(streamId) ?: 0L

    /** WAV shell a transcrever pós-ligação, conforme o lado preferido. */
    private fun shellWavTarget(): String? =
        if (Prefs.transcriptionSide == Prefs.TRANSCRIPTION_SIDE_BOTH)
            pathFor(Side.MIXED) ?: pathFor(Side.RECEIVED) ?: pathFor(Side.SENT)
        else pathFor(Side.RECEIVED) ?: pathFor(Side.MIXED) ?: pathFor(Side.SENT)

    private val wavWriters = HashMap<Int, WavWriter>()

    private fun handleBlock(b: PcmBlock) {
        // Durante o encerramento, o engine está sendo finalizado — alimentar
        // o JNI do Vosk aqui causa SIGSEGV (corrida com o stop()).
        if (stopping) return
        try { wavWriters[b.streamId]?.append(b.samples, b.frames) } catch (_: Throwable) { }
        if (b.streamId >= ShellRecorder.STREAM_BASE) {
            shellFrames[b.streamId] = (shellFrames[b.streamId] ?: 0L) + b.frames
        }
        val engine = stt ?: return
        if (!allowedSides.contains(b.side)) return

        val gate = vads.getOrPut(b.streamId) { VadGate(AudioSourceProbe.SAMPLE_RATE) }
        val active = gate.feed(b.samples, b.frames)
        val dq = preRoll.getOrPut(b.streamId) { ArrayDeque() }

        if (!active) {
            if (dq.size >= PREROLL_BLOCKS) dq.removeFirst()
            dq.addLast(b)
            return
        }
        if (dq.isNotEmpty()) {
            dq.forEach { feedStt(engine, it) }
            dq.clear()
        }
        feedStt(engine, b)
    }

    private fun feedStt(engine: SttEngine, b: PcmBlock) {
        try { engine.feed(b.streamId, b.samples, b.frames) } catch (_: Throwable) { }
    }

    private suspend fun appendSegment(side: String, offsetMs: Long, text: String) {
        try { repo.addSegment(callId, side, offsetMs, text) } catch (t: Throwable) {
            Log.d("TChamador", "FALHA ao salvar segmento: ${t.message}")
            return
        }
        CallController.appendSegment(side, offsetMs, text)
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        stopping = true
        val r = recorder
        val engine = stt
        val id = callId
        scope.launch {
            try {
                jobs.forEach { it.cancel() }
                jobs.clear()
                // Shell (Shizuku) primeiro: encerra as sessões e fecha os
                // cabeçalhos WAV antes de drenar o motor de transcrição.
                val sh = shellRecorder
                shellRecorder = null
                if (sh != null) withContext(Dispatchers.IO) { sh.stop() }
                val leftovers = try { engine?.stop() } catch (_: Throwable) { emptyList() }
                leftovers?.forEach { f ->
                    val side = streamSide[f.streamId] ?: Side.MIXED
                    if (f.text.isNotBlank()) {
                        try {
                            repo.addSegment(id, side, offsetFor(f.streamId, r), f.text)
                        } catch (_: Throwable) { }
                    }
                }
                CallController.setPartial("", "")
                try { engine?.release() } catch (_: Throwable) { }
                stt = null

                if (r != null) {
                    val res = withContext(Dispatchers.IO) { r.stop() }
                    res.mediaPath?.let { mediaPath = it }
                }
                closeWavs()
                finishUp(System.currentTimeMillis())
                // Transcrição pós-ligação da gravação nativa (só se não houve
                // fala ao vivo): decodifica o arquivo e alimenta o Vosk.
                if (mediaPath != null && Prefs.autoTranscribe && countSegs() == 0) {
                    transcribeMediaFile(mediaPath!!)
                    try { repo.refreshSegmentCount(callId) } catch (_: Throwable) { }
                }
                // Mesmo fallback para os WAV gravados pelo serviço shell:
                // o tailer pode não ter alimentado o Vosk (modelo ausente,
                // VAD mudo) — a transcrição sai do arquivo ao encerrar.
                if (Prefs.autoTranscribe && shellActive && countSegs() == 0) {
                    val target = shellWavTarget()
                    if (target != null) {
                        val pcm = ShellRecorder.readWavPcm(File(target))
                        if (pcm != null && pcm.isNotEmpty()) {
                            transcribePcm(pcm)
                            try { repo.refreshSegmentCount(callId) } catch (_: Throwable) { }
                        }
                    }
                }
            } catch (_: Throwable) {
                finishUp(System.currentTimeMillis())
            } finally {
                scope.cancel()
            }
        }
    }

    private fun stopRecorderSilently(r: CallRecorder) {
        try {
            val res = r.stop()
            res.mediaPath?.let { mediaPath = it }
        } catch (_: Throwable) { }
        closeWavs()
    }

    private fun closeWavs() {
        wavWriters.values.forEach { try { it.close() } catch (_: Throwable) { } }
        wavWriters.clear()
    }

    private fun pathFor(side: String): String? {
        val f = wavFiles[side] ?: return null
        return if (f.exists() && f.length() > 1000) f.absolutePath else null
    }

    private suspend fun countSegs(): Int =
        try { repo.countSegments(callId) } catch (_: Throwable) { 0 }

    private suspend fun finishUp(endedAt: Long) {
        CallController.setPartial("", "")
        if (speakerAutoOn) {
            try { CallController.setSpeakerRoute(context, false) } catch (_: Throwable) { }
            speakerAutoOn = false
        }
        if (callId != 0L) {
            val dur = ((endedAt - startedAt) / 1000L).coerceAtLeast(0L).toInt()
            // Quando o captador morreu, o WAV do AudioRecord é silêncio —
            // prefere a gravação nativa do sistema (fallback).
            val mixed = if (captureDead) mediaPath ?: pathFor(Side.MIXED) else pathFor(Side.MIXED) ?: mediaPath
            try {
                repo.finishCall(
                    callId, endedAt, dur,
                    mixed, pathFor(Side.RECEIVED), pathFor(Side.SENT),
                    countSegs(), note,
                )
            } catch (_: Throwable) { }
            val s = CallController.state.value
            val title = s.contactName ?: s.number.ifBlank { "Ligação" }
            val parts = ArrayList<String>()
            if (mixed != null || pathFor(Side.RECEIVED) != null || pathFor(Side.SENT) != null)
                parts.add("gravação salva")
            val n = countSegs()
            if (n > 0) parts.add("$n falas transcritas")
            Notifications.callEnded(
                context, title,
                parts.joinToString(" • ").ifBlank { "Sem captura de áudio nesta ligação" },
            )
        }
        CallController.setEnded()
    }

    private suspend fun loadCaps(): CaptureCapability {
        CaptureCapability.fromJson(Prefs.captureCapability)?.let { return it }
        val c = AudioSourceProbe.probe(context)
        try { Prefs.captureCapability = c.toJson() } catch (_: Throwable) { }
        return c
    }

    private suspend fun buildStt(): SttEngine? = withContext(Dispatchers.IO) {
        val rate = AudioSourceProbe.SAMPLE_RATE
        if (Prefs.sttEngine == Prefs.ENGINE_SHERPA) {
            try {
                val cls = Class.forName("com.telefonechamador.app.stt.SherpaEngine")
                // Preferido do usuário se instalado; senão o primeiro sherpa
                // instalado da lista (quem baixou só o fine-tune pt-BR não cai
                // no Vosk por causa do padrão "parakeet_v3" não baixado).
                val def = ModelManager.installedSherpa(context, Prefs.sherpaModel)
                if (def != null) {
                    val dir = ModelManager.modelDir(context, def)
                    val inst = cls.getConstructor(
                        File::class.java, Integer.TYPE,
                    ).newInstance(dir, rate)
                    Log.d("TChamador", "buildStt: Parakeet em ${dir.absolutePath}")
                    return@withContext inst as SttEngine
                }
            } catch (_: Throwable) { }
        }
        val def = ModelManager.byId(Prefs.voskModel)
            ?: ModelManager.MODELS.first { it.id == "vosk_small_pt" }
        val dir = ModelManager.modelDir(context, def)
        if (!ModelManager.isInstalled(context, def)) {
            Log.d("TChamador", "buildStt: modelo Vosk NÃO instalado (${dir.absolutePath})")
            return@withContext null
        }
        Log.d("TChamador", "buildStt: Vosk em ${dir.absolutePath}")
        VoskEngine(dir, rate)
    }

    /**
     * Pós-chamada: decodifica a gravação nativa (m4a), reamostra para 16 kHz
     * e transcreve com Vosk em blocos de 200 ms. Offsets aproximados: cada
     * segmento começa onde o anterior terminou (o silêncio no meio vira lacuna).
     */
    private suspend fun transcribeMediaFile(path: String) = withContext(Dispatchers.Default) {
        // Com Parakeet selecionado e instalado, o Vosk não é exigido aqui —
        // se o Parakeet falhar, transcribePcm faz o próprio fallback e check.
        val sherpaReady = Prefs.sttEngine == Prefs.ENGINE_SHERPA &&
            OfflineFileStt.available() &&
            ModelManager.installedSherpa(context, Prefs.sherpaModel) != null
        val def = ModelManager.byId(Prefs.voskModel)
            ?: ModelManager.MODELS.first { it.id == "vosk_small_pt" }
        if (!sherpaReady && !ModelManager.isInstalled(context, def)) {
            note = (note ?: "") + " Gravação salva; transcrição adiada (modelo ausente)."
            return@withContext
        }
        val dec = MediaDecode.decodeToMono(File(path))
        if (dec == null) {
            note = (note ?: "") + " A gravação não pôde ser decodificada."
            return@withContext
        }
        val pcm = MediaDecode.resampleTo16k(dec.mono, dec.sampleRate)
        if (pcm.isEmpty()) return@withContext
        var asum = 0.0
        for (v in pcm) asum += (if (v < 0) -v else v).toDouble()
        Log.d(
            "TChamador",
            "posTransc: arquivo=${File(path).length()} pcm=${pcm.size} rmsMedio=${(asum / pcm.size).toInt()}",
        )

        transcribePcm(pcm)
    }

    /**
     * Loop de transcrição pós-ligação: alimenta o Vosk em blocos de 200 ms;
     * cada segmento começa onde o anterior terminou (o silêncio vira lacuna).
     * Usada pela gravação nativa (m4a decodificado) e pelos WAV do serviço
     * shell (PCM lido direto do arquivo).
     */
    private suspend fun transcribePcm(pcm: ShortArray) = withContext(Dispatchers.Default) {
        var added = 0
        // Parakeet/sherpa: UM passe sobre a gravação inteira → parágrafo único
        // (é o que resolve "entende frase solta, mas não parágrafo inteiro").
        if (Prefs.sttEngine == Prefs.ENGINE_SHERPA && OfflineFileStt.available()) {
            val sdef = ModelManager.installedSherpa(context, Prefs.sherpaModel)
            if (sdef != null) {
                val text = OfflineFileStt.transcribe(
                    ModelManager.modelDir(context, sdef), AudioSourceProbe.SAMPLE_RATE, pcm,
                )
                Log.d(
                    "TChamador",
                    "posTransc: engine=sherpa modelo=${sdef.id} textLen=${text?.length ?: -1}",
                )
                if (!text.isNullOrBlank()) {
                    try {
                        repo.addSegment(callId, Side.MIXED, 0L, text)
                        added++
                    } catch (_: Throwable) { }
                    notifyTranscribed(added, sherpa = true)
                    return@withContext
                }
            }
            // Falhou (modelo corrompido, memória…): cai no Vosk abaixo.
            Log.d("TChamador", "posTransc: sherpa ausente/falhou → fallback Vosk")
        }
        val def = ModelManager.byId(Prefs.voskModel)
            ?: ModelManager.MODELS.first { it.id == "vosk_small_pt" }
        val dir = ModelManager.modelDir(context, def)
        if (!ModelManager.isInstalled(context, def)) {
            note = (note ?: "") + " Gravação salva; transcrição adiada (modelo ausente)."
            return@withContext
        }
        try {
            val model = Model(dir.absolutePath)
            val recognizer = Recognizer(model, 16000f)
            val chunk = 3200 // 200 ms
            var fed = 0L
            var prevEndMs = 0L
            var idx = 0
            while (idx < pcm.size) {
                val len = minOf(chunk, pcm.size - idx)
                val slice = pcm.copyOfRange(idx, idx + len)
                if (recognizer.acceptWaveForm(slice, len)) {
                    val text = JSONObject(recognizer.result).optString("text", "")
                    if (text.isNotBlank() && text.any { it.isLetterOrDigit() }) {
                        try { repo.addSegment(callId, Side.MIXED, prevEndMs, text); added++ }
                        catch (_: Throwable) { }
                    }
                    prevEndMs = fed * 1000L / AudioSourceProbe.SAMPLE_RATE
                }
                fed += len
                idx += len
            }
            val tail = JSONObject(recognizer.finalResult).optString("text", "")
            if (tail.isNotBlank() && tail.any { it.isLetterOrDigit() }) {
                try { repo.addSegment(callId, Side.MIXED, prevEndMs, tail); added++ }
                catch (_: Throwable) { }
            }
            try { recognizer.close() } catch (_: Throwable) { }
            try { model.close() } catch (_: Throwable) { }
        } catch (_: Throwable) { }

        notifyTranscribed(added, sherpa = false)
    }

    /** Notificação única do fim da transcrição (Parakeet = parágrafo; Vosk = falas). */
    private fun notifyTranscribed(added: Int, sherpa: Boolean) {
        if (added > 0) {
            val s = CallController.state.value
            Notifications.callEnded(
                context,
                s.contactName ?: s.number.ifBlank { "Ligação" },
                if (sherpa) "Transcrição concluída: parágrafo único via Parakeet"
                else "Transcrição concluída: $added fala(s) a partir da gravação",
            )
        }
    }

    companion object {
        private const val PREROLL_BLOCKS = 4 // 400 ms de pré-fala guardados
    }
}
