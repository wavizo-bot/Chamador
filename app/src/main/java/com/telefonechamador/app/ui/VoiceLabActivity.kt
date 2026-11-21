package com.telefonechamador.app.ui

import android.Manifest
import android.content.Intent
import android.provider.OpenableColumns
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.telefonechamador.app.R
import com.telefonechamador.app.audio.MediaDecode
import com.telefonechamador.app.audio.VoiceClone
import com.telefonechamador.app.audio.VoiceDef
import com.telefonechamador.app.audio.VoiceFx
import com.telefonechamador.app.audio.VoiceLibrary
import com.telefonechamador.app.audio.WavWriter
import com.telefonechamador.app.call.CallSession
import com.telefonechamador.app.share.ShareUtils
import com.telefonechamador.app.stt.ModelManager
import com.telefonechamador.app.stt.OfflineFileStt
import com.telefonechamador.app.stt.OfflineFileTts
import com.telefonechamador.app.util.Fmt
import com.telefonechamador.app.util.Permissions
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Laboratório de voz: grava áudio SEM ligação, toca o original e com qualquer
 * efeito da biblioteca, salva a gravação como uma voz com nome próprio (clonagem
 * offline) e transcreve arquivos de áudio sem ligação (Vosk local).
 * Também recebe o áudio de uma ligação (extra "audioPath") para ouvir filtros
 * e clonar antes de exportar.
 */
class VoiceLabActivity : AppCompatActivity() {

    private var sourceFile: File? = null
    private var sourceLabel = "nenhuma"
    private var voices: List<VoiceDef> = emptyList()
    private var transcript: String? = null

    // reprodução original (MediaPlayer)
    private var player: MediaPlayer? = null

    // reprodução com efeito (AudioTrack em thread, troca por geração)
    @Volatile private var fxGen = 0
    @Volatile private var fxTrack: AudioTrack? = null
    // true enquanto o efeito está tocando OU sendo processado ("Aplicando…"):
    // é o que faz o botão virar toggle (parar) em vez de reiniciar.
    @Volatile private var fxBusy = false

    // gravação do microfone
    @Volatile private var recording = false
    private var recThread: Thread? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startRecording()
            else Toast.makeText(
                this, "Permissão de microfone negada — conceda nas permissões do app.", Toast.LENGTH_LONG,
            ).show()
        }

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) { copyToLab(uri) }
                if (f == null) {
                    Toast.makeText(this@VoiceLabActivity, "Não consegui abrir esse áudio.", Toast.LENGTH_SHORT).show()
                } else {
                    setSource(f, "Arquivo aberto: ${f.nameAfterPrefix()}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enquanto o programa está em desenvolvimento: tela sempre acesa.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_voice_lab)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_record).setOnClickListener { toggleRecording() }
        findViewById<MaterialButton>(R.id.btn_open).setOnClickListener {
            openLauncher.launch(arrayOf("audio/*"))
        }
        findViewById<MaterialButton>(R.id.btn_play_orig).setOnClickListener { toggleOriginal() }
        findViewById<MaterialButton>(R.id.btn_play_fx).setOnClickListener { toggleFx() }
        findViewById<MaterialButton>(R.id.btn_save_voice).setOnClickListener { saveAsVoice() }
        findViewById<MaterialButton>(R.id.btn_transcribe).setOnClickListener { transcribe() }
        findViewById<MaterialButton>(R.id.btn_speak).setOnClickListener { speak() }
        findViewById<MaterialButton>(R.id.btn_share_text).setOnClickListener {
            transcript?.let {
                ShareUtils.shareText(this, "Transcrição — $sourceLabel", it)
            }
        }
        findViewById<MaterialButton>(R.id.btn_export).setOnClickListener { exportWithEffect() }

        refreshVoices(Prefs.voiceFx.takeIf { it != VoiceLibrary.NONE_ID })

        intent.getStringExtra("audioPath")?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 1000) {
                setSource(f, intent.getStringExtra("sourceLabel") ?: "Gravação da ligação")
            }
        }
    }

    // ---------- fonte ----------

    private fun setSource(file: File, label: String) {
        sourceFile = file
        sourceLabel = label
        stopOriginal()
        stopFx()
        transcript = null
        findViewById<TextView>(R.id.tv_transcript).apply {
            visibility = android.view.View.GONE
            text = ""
        }
        findViewById<MaterialButton>(R.id.btn_share_text).visibility = android.view.View.GONE
        findViewById<TextView>(R.id.tv_source).text = "Fonte: $label"
    }

    private fun copyToLab(uri: android.net.Uri): File? {
        return try {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "audio"
            val dir = File(filesDir, "lab").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
            val dest = File(dir, "aberto_${System.currentTimeMillis()}_$safe")
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
            if (dest.length() < 1000) { dest.delete(); null } else dest
        } catch (_: Throwable) {
            null
        }
    }

    private fun File.nameAfterPrefix(): String =
        name.substringAfterLast('_').ifBlank { name }

    // ---------- gravação ----------

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (recThread?.isAlive == true) return
        if (!Permissions.granted(this, Manifest.permission.RECORD_AUDIO)) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopOriginal()
        stopFx()
        val rate = VoiceClone.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Toast.makeText(this, "Microfone indisponível neste aparelho.", Toast.LENGTH_SHORT).show()
            return
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 32000),
            )
        } catch (_: Throwable) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Throwable) { }
            Toast.makeText(this, "Não consegui abrir o microfone.", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(filesDir, "lab").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "voz_$stamp.wav")
        val wav = WavWriter(file, rate)

        val btn = findViewById<MaterialButton>(R.id.btn_record)
        val tv = findViewById<TextView>(R.id.tv_source)
        recording = true
        btn.text = "⏹  Parar"
        tv.text = "Gravando… 0s — toque em Parar quando terminar"

        recThread = thread(name = "lab-rec") {
            try { rec.startRecording() } catch (_: Throwable) { }
            val buf = ShortArray(1600) // 100 ms
            val t0 = System.currentTimeMillis()
            var lastUi = 0L
            var frames = 0L
            while (recording) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                if (n > 0) {
                    wav.append(buf, n)
                    frames += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 500) {
                        lastUi = now
                        val sec = (now - t0) / 1000
                        runOnUiThread {
                            if (recording) tv.text = "Gravando… ${sec}s — toque em Parar"
                        }
                    }
                } else if (n < 0) {
                    break
                }
                if (frames >= rate * 120L) break // teto de 2 minutos
            }
            try { rec.stop() } catch (_: Throwable) { }
            try { rec.release() } catch (_: Throwable) { }
            wav.close()
            runOnUiThread {
                recording = false
                btn.text = "⏺  Gravar voz"
                if (wav.hasContent && file.length() > 44 + rate) {
                    setSource(file, "Gravação do laboratório")
                    tv.text = "Fonte: Gravação do laboratório (${Fmt.duration((frames / rate).toInt())})"
                } else {
                    file.delete()
                    tv.text = "Fonte: nenhuma — grave sua voz ou abra um áudio."
                    Toast.makeText(this@VoiceLabActivity, "Não capturei áudio — tente de novo.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopRecording() {
        recording = false
    }

    // ---------- tocar ----------

    private fun toggleOriginal() {
        if (player != null) {
            stopOriginal()
            return
        }
        val f = sourceFile ?: run {
            Toast.makeText(this, "Grave ou abra um áudio primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                try { p.release() } catch (_: Throwable) { }
                if (player === p) player = null
                findViewById<MaterialButton>(R.id.btn_play_orig).text = "▶  Original"
            }
            p.start()
            player = p
            findViewById<MaterialButton>(R.id.btn_play_orig).text = "■  Parar"
        } catch (t: Throwable) {
            Toast.makeText(this, "Não foi possível tocar: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopOriginal() {
        try { player?.stop() } catch (_: Throwable) { }
        try { player?.release() } catch (_: Throwable) { }
        player = null
        findViewById<MaterialButton>(R.id.btn_play_orig).text = "▶  Original"
    }

    /** Toggle: se está tocando/processando, PARA; senão, inicia. */
    private fun toggleFx() {
        if (fxBusy) stopFx()
        else playWithEffect()
    }

    /** Ouve o áudio atual com o efeito escolhido no seletor (DSP local). */
    private fun playWithEffect() {
        val def = selectedVoice() ?: run {
            Toast.makeText(this, "Escolha uma voz no seletor de efeitos.", Toast.LENGTH_SHORT).show()
            return
        }
        val f = sourceFile ?: run {
            Toast.makeText(this, "Grave ou abra um áudio primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        stopOriginal()
        stopFx()
        val btn = findViewById<MaterialButton>(R.id.btn_play_fx)
        btn.text = "⏳  Aplicando…"
        fxBusy = true
        val gen = fxGen
        lifecycleScope.launch {
            val pcm = withContext(Dispatchers.IO) { loadPcm16k(f) }
            if (gen != fxGen) return@launch   // o usuário parou enquanto lia o arquivo
            if (pcm == null) {
                fxBusy = false
                btn.text = "🎧  Com o efeito"
                Toast.makeText(this@VoiceLabActivity, "Não consegui ler esse áudio.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val out = withContext(Dispatchers.Default) {
                try { VoiceFx.apply(pcm, def.params, VoiceClone.SAMPLE_RATE) }
                catch (_: Throwable) { pcm }
            }
            if (gen != fxGen) return@launch   // o usuário parou enquanto processava
            startFxPlayback(out, def)
        }
    }

    private fun startFxPlayback(pcm: ShortArray, def: VoiceDef) {
        val btn = findViewById<MaterialButton>(R.id.btn_play_fx)
        btn.text = "■  Parar (${def.label})"
        fxBusy = true
        val gen = fxGen
        thread(name = "lab-fx") {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(VoiceClone.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val min = AudioTrack.getMinBufferSize(
                    VoiceClone.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val t = AudioTrack(
                    attrs, fmt, maxOf(min * 2, 8192), AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                fxTrack = t
                t.play()
                val chunk = 3200
                var idx = 0
                while (fxGen == gen && idx < pcm.size) {
                    val len = minOf(chunk, pcm.size - idx)
                    val w = t.write(pcm, idx, len)
                    if (w <= 0) break
                    idx += w
                }
                try { t.stop() } catch (_: Throwable) { }
                try { t.release() } catch (_: Throwable) { }
            } catch (_: Throwable) { }
            if (fxGen == gen) {
                // terminou sozinho: libera o toggle para o próximo toque
                fxBusy = false
                runOnUiThread {
                    if (fxGen == gen) btn.text = "🎧  Com o efeito"
                }
            }
        }
    }

    private fun stopFx() {
        fxGen++
        fxBusy = false
        val t = fxTrack
        fxTrack = null
        try { t?.stop() } catch (_: Throwable) { }
        try { t?.release() } catch (_: Throwable) { }
        findViewById<MaterialButton>(R.id.btn_play_fx).text = "🎧  Com o efeito"
    }

    private fun loadPcm16k(f: File): ShortArray? {
        val dec = MediaDecode.decodeAny(f) ?: return null
        return MediaDecode.resampleTo16k(dec.mono, dec.sampleRate)
    }

    // ---------- seletor de vozes ----------

    private fun refreshVoices(selectId: String?) {
        voices = VoiceLibrary.all(this).filter { it.id != VoiceLibrary.NONE_ID }
        val sp = findViewById<Spinner>(R.id.sp_voice)
        val labels = voices.map { it.label }
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels,
        )
        val idx = voices.indexOfFirst { it.id == selectId }
        sp.setSelection(if (idx >= 0) idx else 0)
    }

    private fun selectedVoice(): VoiceDef? {
        val sp = findViewById<Spinner>(R.id.sp_voice)
        return voices.getOrNull(sp.selectedItemPosition)
    }

    // ---------- salvar como voz (clonagem offline) ----------

    private fun saveAsVoice() {
        val f = sourceFile ?: run {
            Toast.makeText(this, "Grave ou abra um áudio primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        frame.setPadding(pad, pad, pad, 0)
        val edt = EditText(this).apply {
            val def = SimpleDateFormat("dd/MM HH'h'mm", Locale.getDefault()).format(Date())
            setText("Voz $def")
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        frame.addView(edt)
        AlertDialog.Builder(this)
            .setTitle("Salvar como voz")
            .setMessage("Dê um nome a esta voz — ela entra na biblioteca e pode ser usada nas ligações.")
            .setView(frame)
            .setPositiveButton("Salvar") { _, _ ->
                val name = edt.text.toString().trim().ifBlank { "Voz do laboratório" }
                confirmSaveVoice(f, name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmSaveVoice(f: File, name: String) {
        Toast.makeText(this, "Analisando a voz…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.Default) { analyzeFile(f) }
            android.util.Log.d("TChamador", "labClone: arquivo=${f.absolutePath} stats=$stats")
            if (stats == null || !stats.valid) {
                Toast.makeText(
                    this@VoiceLabActivity,
                    "Não consegui medir o tom dessa gravação (áudio curto ou silencioso).",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val baseline = Prefs.voiceBaseline.takeIf { it.isNotBlank() }?.let {
                VoiceClone.baselineFromJson(it)
            }
            val desc = "Tom ${"%.0f".format(Locale.US, stats.pitchHz)} Hz — $sourceLabel"
            val def = VoiceClone.buildClone(name, desc, stats, baseline)
            VoiceLibrary.saveClone(this@VoiceLabActivity, def)
            Prefs.voiceFx = def.id
            CallSession.setLiveEffect(def.id)
            refreshVoices(def.id)
            Toast.makeText(
                this@VoiceLabActivity,
                "Voz \"$name\" salva e ativa. Use ▶ Com o efeito para ouvir, e ela já vale nas ligações.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun analyzeFile(f: File): com.telefonechamador.app.audio.CloneStats? {
        val dec = MediaDecode.decodeAny(f)
        android.util.Log.d("TChamador", "labClone: decode=${dec?.mono?.size ?: -1} rate=${dec?.sampleRate ?: 0} file=${f.name}")
        if (dec == null) return null
        val pcm = MediaDecode.resampleTo16k(dec.mono, dec.sampleRate)
        return VoiceClone.analyze(pcm)
    }

    // ---------- transcrição sem ligação ----------

    private fun transcribe() {
        val f = sourceFile ?: run {
            Toast.makeText(this, "Grave ou abra um áudio primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        // Parakeet/sherpa (UM parágrafo, WER ~5–14) quando a engine dele está
        // escolhida e um modelo está instalado; senão Vosk local ([mm:ss] em blocos).
        val sherpaDef = if (Prefs.sttEngine == Prefs.ENGINE_SHERPA) {
            ModelManager.installedSherpa(this, Prefs.sherpaModel)
        } else null
        var def = ModelManager.byId(Prefs.voskModel)
            ?: ModelManager.MODELS.first { it.id == "vosk_small_pt" }
        if (!ModelManager.isInstalled(this, def)) {
            val alt = ModelManager.MODELS.firstOrNull {
                it.id == "vosk_small_pt" && ModelManager.isInstalled(this, it)
            }
            if (alt != null) def = alt
        }
        if (sherpaDef == null && !ModelManager.isInstalled(this, def)) {
            Toast.makeText(
                this, "Baixe o modelo de transcrição em Configurar → Modelos de transcrição.", Toast.LENGTH_LONG,
            ).show()
            return
        }
        stopOriginal()
        stopFx()
        val btn = findViewById<MaterialButton>(R.id.btn_transcribe)
        btn.isEnabled = false
        btn.text = "⏳  Transcrevendo…"
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                if (sherpaDef != null) {
                    val pcm = loadPcm16k(f)
                    if (pcm != null && pcm.isNotEmpty()) {
                        OfflineFileStt.transcribe(
                            ModelManager.modelDir(this@VoiceLabActivity, sherpaDef),
                            VoiceClone.SAMPLE_RATE,
                            pcm,
                        )
                    } else null
                } else {
                    voskFileText(ModelManager.modelDir(this@VoiceLabActivity, def), f)
                }
            }
            android.util.Log.d(
                "TChamador",
                "labTransc: engine=${if (sherpaDef != null) "sherpa/" + sherpaDef.id else "vosk"} " +
                    "textLen=${text?.length ?: -1}",
            )
            btn.isEnabled = true
            btn.text = "📝  Transcrever"
            if (text.isNullOrBlank()) {
                Toast.makeText(
                    this@VoiceLabActivity,
                    "Não consegui transcrever (áudio vazio ou sem fala reconhecível).",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            transcript = "Transcrição — $sourceLabel\n\n$text"
            findViewById<TextView>(R.id.tv_transcript).apply {
                visibility = android.view.View.VISIBLE
                this.text = transcript
            }
            findViewById<MaterialButton>(R.id.btn_share_text).visibility = android.view.View.VISIBLE
            // O texto transcrito cai no campo de fala: é só tocar em "Falar o texto"
            // (o usuário pode editar antes — este é o fluxo "gravar → transcrever →
            // reproduzir com a voz" pedido pelo dono).
            findViewById<EditText>(R.id.et_tts).setText(text)
        }
    }

    private fun voskFileText(dir: File, f: File): String? = try {
        val pcm = loadPcm16k(f)
        android.util.Log.d("TChamador", "labTransc: pcm=${pcm?.size ?: -1} arquivo=${f.name}")
        if (pcm == null || pcm.isEmpty()) null
        else {
            val model = Model(dir.absolutePath)
            val rec = Recognizer(model, 16000f)
            val sb = StringBuilder()
            val chunk = 3200 // 200 ms
            var idx = 0
            var fed = 0L
            var prevMs = 0L
            while (idx < pcm.size) {
                val len = minOf(chunk, pcm.size - idx)
                val slice = pcm.copyOfRange(idx, idx + len)
                if (rec.acceptWaveForm(slice, len)) {
                    val t = JSONObject(rec.result).optString("text", "")
                    if (t.isNotBlank() && t.any { it.isLetterOrDigit() }) {
                        sb.append('[').append(Fmt.mmss(prevMs)).append("] ").append(t).append("\n\n")
                        prevMs = fed * 1000L / VoiceClone.SAMPLE_RATE
                    }
                }
                fed += len
                idx += len
            }
            val tail = JSONObject(rec.finalResult).optString("text", "")
            if (tail.isNotBlank() && tail.any { it.isLetterOrDigit() }) {
                sb.append('[').append(Fmt.mmss(prevMs)).append("] ").append(tail).append("\n\n")
            }
            try { rec.close() } catch (_: Throwable) { }
            try { model.close() } catch (_: Throwable) { }
            sb.toString().trim()
        }
    } catch (t: Throwable) {
        android.util.Log.d("TChamador", "labTransc: erro $t")
        null
    }

    // ---------- falar texto (TTS Piper: voz GERADA, sem microfone/chiado) ----------

    /**
     * Texto do campo → síntese Piper (sherpa) → WAV no laboratório → vira a
     * fonte atual. Assim o usuário ouve a fala limpa, aplica qualquer efeito
     * e exporta — sem levar junto o ruído de captação do microfone.
     */
    private fun speak() {
        val text = findViewById<EditText>(R.id.et_tts).text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Digite um texto (ou toque em Transcrever para preencher).", Toast.LENGTH_SHORT).show()
            return
        }
        val tts = ModelManager.installedTts(this) ?: run {
            Toast.makeText(
                this,
                "Baixe uma voz em Configurar → Modelos: Kokoro pt-BR (recomendado: voz + fonemas espeak) ou Piper (voz + fonemas espeak).",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!OfflineFileTts.available()) {
            Toast.makeText(
                this,
                "Este build não tem o pacote sherpa-onnx — sem ele a síntese de voz fica indisponível.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        stopOriginal()
        stopFx()
        val btn = findViewById<MaterialButton>(R.id.btn_speak)
        btn.isEnabled = false
        btn.text = "⏳  Gerando fala…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                OfflineFileTts.synthesize(
                    ModelManager.modelDir(this@VoiceLabActivity, tts.first),
                    tts.second,
                    text,
                )
            }
            btn.isEnabled = true
            btn.text = "🔊  Falar o texto"
            val samples = res?.get("samples") as? FloatArray
            val rate = (res?.get("sampleRate") as? Int) ?: 0
            if (samples == null || samples.isEmpty() || rate <= 0) {
                android.util.Log.d("TChamador", "labTts: falhou res=${res != null} rate=$rate")
                Toast.makeText(
                    this@VoiceLabActivity,
                    "Não consegui sintetizar esse texto (modelo ausente ou texto inválido).",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val wav = withContext(Dispatchers.IO) { writeTtsWav(samples, rate) }
            if (wav == null) {
                Toast.makeText(this@VoiceLabActivity, "Fala gerada, mas falhou ao salvar o arquivo.", Toast.LENGTH_LONG).show()
                return@launch
            }
            android.util.Log.d(
                "TChamador",
                "labTts: ok chars=${text.length} samples=${samples.size} rate=$rate wav=${wav.length()}B",
            )
            setSource(wav, "Fala gerada (voz ${if (tts.first.id == "tts_kokoro") "Kokoro pt-BR" else "Piper"})")
            Toast.makeText(
                this@VoiceLabActivity,
                "Fala pronta ✔ — toque em ▶ Original ou escolha um efeito.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** FloatArray do TTS (−1..1) → WAV 16-bit no diretório do laboratório. */
    private fun writeTtsWav(samples: FloatArray, rate: Int): File? = try {
        val dir = File(filesDir, "lab").apply { mkdirs() }
        val file = File(dir, "tts_${System.currentTimeMillis()}.wav")
        val wav = WavWriter(file, rate)
        val shorts = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i] * 32767.0f).coerceIn(-32768f, 32767f)
            shorts[i] = v.toInt().toShort()
        }
        wav.append(shorts, shorts.size)
        wav.close()
        if (wav.hasContent && file.length() > 44 + rate) file else { file.delete(); null }
    } catch (_: Throwable) {
        null
    }

    // ---------- exportar ----------

    private fun exportWithEffect() {
        val f = sourceFile ?: run {
            Toast.makeText(this, "Grave ou abra um áudio primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val def = selectedVoice() ?: run {
            Toast.makeText(this, "Escolha uma voz no seletor de efeitos.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Aplicando ${def.label}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                ShareUtils.exportWithEffect(this@VoiceLabActivity, f, def)
            }
            if (out != null) ShareUtils.shareAudio(this@VoiceLabActivity, out)
            else Toast.makeText(this@VoiceLabActivity, "Falha ao aplicar o efeito.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- ciclo de vida ----------

    override fun onDestroy() {
        recording = false
        stopFx()
        stopOriginal()
        super.onDestroy()
    }
}
