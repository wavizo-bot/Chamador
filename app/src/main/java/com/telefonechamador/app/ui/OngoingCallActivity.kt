package com.telefonechamador.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.telefonechamador.app.R
import com.telefonechamador.app.call.CallController
import com.telefonechamador.app.service.OverlayService
import com.telefonechamador.app.util.Fmt
import com.telefonechamador.app.util.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tela de ligação em andamento com a transcrição ao vivo. */
class OngoingCallActivity : AppCompatActivity() {

    private val adapter = SegmentsAdapter()
    private lateinit var recycler: RecyclerView
    private var shownSegs = 0
    private var defaultOverlayTint: android.content.res.ColorStateList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ongoing_call)

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val btnAnswer = findViewById<MaterialButton>(R.id.btn_answer)
        val btnReject = findViewById<MaterialButton>(R.id.btn_reject)
        val btnMute = findViewById<MaterialButton>(R.id.btn_mute)
        val btnSpeaker = findViewById<MaterialButton>(R.id.btn_speaker)
        val btnEnd = findViewById<MaterialButton>(R.id.btn_end)
        val btnOverlay = findViewById<MaterialButton>(R.id.btn_overlay)
        defaultOverlayTint = btnOverlay.backgroundTintList

        btnAnswer.setOnClickListener { CallController.answer(this) }
        btnReject.setOnClickListener { CallController.reject(this) }
        btnEnd.setOnClickListener { CallController.end(this) }
        btnMute.setOnClickListener { CallController.toggleMute(this) }
        btnSpeaker.setOnClickListener { CallController.toggleSpeaker(this) }
        btnOverlay.setOnClickListener {
            if (!Permissions.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Janela flutuante")
                    .setMessage("Permita que o Telefone Chamador desenhe sobre outros apps para ver a transcrição flutuante.")
                    .setPositiveButton("Abrir ajustes") { _, _ ->
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        } catch (_: Throwable) { }
                    }
                    .setNegativeButton("Depois", null)
                    .show()
            } else if (OverlayService.isRunning) {
                OverlayService.stop(this)
                Toast.makeText(this, "Janela flutuante desligada.", Toast.LENGTH_SHORT).show()
            } else {
                OverlayService.start(this)
                Toast.makeText(this, "Janela flutuante ligada.", Toast.LENGTH_SHORT).show()
            }
            refreshOverlayBtn()
        }

        // cronômetro + estado do botão flutuante
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val s = CallController.state.value
                if (s.phase == "active" && s.startedAt > 0) {
                    findViewById<TextView>(R.id.tv_timer).text =
                        Fmt.mmss(System.currentTimeMillis() - s.startedAt)
                }
                refreshOverlayBtn()
            }
        }

        lifecycleScope.launch {
            CallController.state.collect { s ->
                when (s.phase) {
                    "idle" -> finish()
                    "ringing" -> {
                        btnAnswer.visibility = View.VISIBLE
                        btnReject.visibility = View.VISIBLE
                        btnMute.visibility = View.GONE
                        btnSpeaker.visibility = View.GONE
                        btnEnd.visibility = View.GONE
                    }
                    "dialing" -> {
                        findViewById<TextView>(R.id.tv_timer).text = "Discando…"
                        btnAnswer.visibility = View.GONE
                        btnReject.visibility = View.GONE
                        btnMute.visibility = View.GONE
                        btnSpeaker.visibility = View.GONE
                        btnEnd.visibility = View.VISIBLE
                    }
                    "active" -> {
                        btnAnswer.visibility = View.GONE
                        btnReject.visibility = View.GONE
                        btnMute.visibility = View.VISIBLE
                        btnSpeaker.visibility = View.VISIBLE
                        btnEnd.visibility = View.VISIBLE
                        btnMute.text = if (s.muted) "🔇  Mudo: sim" else "🎙  Mudo: não"
                        btnSpeaker.text = if (s.speaker) "🔊  Viva-voz: ligado" else "📞  Viva-voz: desligado"
                    }
                    "ended" -> {
                        findViewById<TextView>(R.id.tv_status).text = "Ligação encerrada"
                        btnAnswer.visibility = View.GONE
                        btnReject.visibility = View.GONE
                        btnMute.visibility = View.GONE
                        btnSpeaker.visibility = View.GONE
                        btnEnd.visibility = View.GONE
                        lifecycleScope.launch {
                            delay(2500)
                            if (CallController.state.value.phase == "ended") finish()
                        }
                    }
                }
                findViewById<TextView>(R.id.tv_name).text =
                    s.contactName ?: s.number.ifBlank { "Ligação" }
                findViewById<TextView>(R.id.tv_note).apply {
                    if (s.note.isNullOrBlank()) visibility = View.GONE
                    else { visibility = View.VISIBLE; text = s.note }
                }
                if (s.segments.size != shownSegs) {
                    val grew = s.segments.size > shownSegs
                    shownSegs = s.segments.size
                    adapter.submitList(s.segments) {
                        if (grew) recycler.scrollToPosition(adapter.itemCount - 1)
                    }
                }
                findViewById<TextView>(R.id.tv_empty).isVisible =
                    s.phase == "active" && s.segments.isEmpty() && s.partial.isBlank()
                findViewById<TextView>(R.id.tv_partial).text =
                    if (s.partial.isBlank()) "" else "… ${s.partial}"
            }
        }
    }

    /** Botão Flutuante reflete o estado real da janela (ligada/desligada). */
    private fun refreshOverlayBtn() {
        val btn = findViewById<MaterialButton>(R.id.btn_overlay)
        val on = OverlayService.isRunning
        btn.text = if (on) "◉  Flutuante: ligada" else "◔  Flutuante"
        btn.backgroundTintList =
            if (on) ContextCompat.getColorStateList(this, R.color.cc_green_deep)
            else defaultOverlayTint
    }

    override fun onBackPressed() {
        // sai da tela sem encerrar a ligação
        super.onBackPressed()
    }
}
