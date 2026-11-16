package com.telefonechamador.app.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.telefonechamador.app.R
import com.telefonechamador.app.audio.VoiceLibrary
import com.telefonechamador.app.call.UiSegment
import com.telefonechamador.app.data.CallEntity
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.data.SegmentEntity
import com.telefonechamador.app.share.ShareUtils
import com.telefonechamador.app.util.Fmt
import kotlinx.coroutines.launch
import java.io.File

class CallDetailActivity : AppCompatActivity() {

    private var callId = 0L
    private var call: CallEntity? = null
    private var segments: List<SegmentEntity> = emptyList()
    private var player: MediaPlayer? = null
    private val repo by lazy { CallRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_detail)
        findViewById<MaterialToolbar>(R.id.toolbar_detail).setNavigationOnClickListener { finish() }
        callId = intent.getLongExtra("callId", 0)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SegmentsAdapter()

        findViewById<ImageButton>(R.id.btn_fav).setOnClickListener {
            val c = call ?: return@setOnClickListener
            lifecycleScope.launch { repo.setFavorite(c.id, !c.favorite); reload() }
        }
        findViewById<MaterialButton>(R.id.btn_share_text).setOnClickListener {
            val c = call ?: return@setOnClickListener
            ShareUtils.shareText(
                this,
                "Transcrição — ${c.contactName ?: c.number}",
                ShareUtils.transcriptText(c.contactName, c.number, c.startedAt, c.durationSec, segments),
            )
        }
        findViewById<MaterialButton>(R.id.btn_share_audio).setOnClickListener { audioFile()?.let { ShareUtils.shareAudio(this, it) } ?: Toast.makeText(this, "Sem gravação para esta ligação.", Toast.LENGTH_SHORT).show() }
        findViewById<MaterialButton>(R.id.btn_play).setOnClickListener { togglePlay() }
        findViewById<MaterialButton>(R.id.btn_fx).setOnClickListener { chooseEffect() }
        findViewById<MaterialButton>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        lifecycleScope.launch { reload() }
    }

    private suspend fun reload() {
        val c = repo.callById(callId)
        if (c == null) {
            finish()
            return
        }
        call = c
        segments = repo.segmentsFor(callId)
        (findViewById<RecyclerView>(R.id.recycler).adapter as? SegmentsAdapter)
            ?.submitList(segments.map { UiSegment(it.side, it.offsetMs, it.text) })

        findViewById<TextView>(R.id.tv_name).text = c.contactName?.takeIf { it.isNotBlank() } ?: c.number
        findViewById<TextView>(R.id.tv_number).text = c.number
        findViewById<TextView>(R.id.tv_meta).text = buildString {
            append(Fmt.stamp(c.startedAt))
            if (c.durationSec > 0) append(" • ").append(Fmt.duration(c.durationSec))
            if (c.segmentCount > 0) append(" • ").append(c.segmentCount).append(" falas")
        }
        findViewById<TextView>(R.id.tv_note).apply {
            val note = c.captureNote
            if (note.isNullOrBlank()) {
                visibility = android.view.View.GONE
            } else {
                visibility = android.view.View.VISIBLE
                text = note
            }
        }
        findViewById<ImageButton>(R.id.btn_fav).setImageResource(
            if (c.favorite) R.drawable.ic_star_filled else R.drawable.ic_star
        )
    }

    private fun audioFile(): File? =
        listOfNotNull(call?.audioMixed, call?.audioReceived, call?.audioSent)
            .map(::File)
            .firstOrNull { it.exists() && it.length() > 1000 }

    private fun togglePlay() {
        val btn = findViewById<MaterialButton>(R.id.btn_play)
        if (player != null) {
            try { player?.stop() } catch (_: Throwable) { }
            try { player?.release() } catch (_: Throwable) { }
            player = null
            btn.text = "▶  Ouvir gravação"
            return
        }
        val f = audioFile() ?: run {
            Toast.makeText(this, "Sem gravação para esta ligação.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                try { p.release() } catch (_: Throwable) { }
                player = null
                btn.text = "▶  Ouvir gravação"
            }
            p.start()
            player = p
            btn.text = "■  Parar"
        } catch (t: Throwable) {
            Toast.makeText(this, "Não foi possível tocar: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseEffect() {
        val f = audioFile() ?: run {
            Toast.makeText(this, "Sem gravação para aplicar efeito.", Toast.LENGTH_SHORT).show()
            return
        }
        val defs = VoiceLibrary.all(this).filter { it.id != VoiceLibrary.NONE_ID }
        val labels = defs.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Exportar com efeito")
            .setItems(labels) { _, which ->
                val def = defs[which]
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val out = ShareUtils.exportWithEffect(this@CallDetailActivity, f, def)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (out != null) ShareUtils.shareAudio(this@CallDetailActivity, out)
                        else Toast.makeText(this@CallDetailActivity, "Falha ao aplicar o efeito.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Excluir conversa")
            .setMessage("Apaga a transcrição e os áudios desta ligação. Não há como desfazer.")
            .setPositiveButton("Excluir") { _, _ ->
                lifecycleScope.launch { repo.deleteCall(callId); finish() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
        super.onDestroy()
    }
}
