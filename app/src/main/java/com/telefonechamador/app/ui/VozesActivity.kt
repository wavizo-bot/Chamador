package com.telefonechamador.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.telefonechamador.app.R
import com.telefonechamador.app.audio.VoiceClone
import com.telefonechamador.app.audio.VoiceDef
import com.telefonechamador.app.audio.VoiceLibrary
import com.telefonechamador.app.call.CallSession
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.data.CallEntity
import com.telefonechamador.app.util.Fmt
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Biblioteca de vozes: escolhe a voz ativa, testa ao vivo, calibra a voz do
 * dono e cria clones a partir do áudio de ligações do histórico.
 */
class VozesActivity : AppCompatActivity() {

    private val repo by lazy { CallRepository(this) }
    private lateinit var list: RecyclerView
    private val adapter = VoiceAdapter(
        onSelect = { def -> select(def) },
        onDelete = { def -> confirmDelete(def) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vozes)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.recycler)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val swLive = findViewById<SwitchMaterial>(R.id.sw_live)
        swLive.isChecked = Prefs.voiceFxLive
        swLive.setOnCheckedChangeListener { b: CompoundButton, on: Boolean ->
            Prefs.voiceFxLive = on
            b.isChecked = on
        }

        findViewById<MaterialButton>(R.id.btn_test).setOnClickListener { testVoice() }
        findViewById<MaterialButton>(R.id.btn_calibrate).setOnClickListener { calibrate() }
        findViewById<MaterialButton>(R.id.btn_clone).setOnClickListener { pickCallToClone() }
        findViewById<MaterialButton>(R.id.btn_lab).setOnClickListener {
            startActivity(android.content.Intent(this, VoiceLabActivity::class.java))
        }

        refresh()
    }

    private fun refresh() {
        adapter.submit(VoiceLibrary.all(this), Prefs.voiceFx)
    }

    private fun select(def: VoiceDef) {
        Prefs.voiceFx = def.id
        CallSession.setLiveEffect(def.id)
        refresh()
        val msg = if (CallSession.liveEffectActive()) "Voz aplicada na ligação agora."
        else "Voz definida: ${def.label}"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Ouve a voz selecionada: grava 4s do microfone e toca com o efeito. */
    private fun testVoice() {
        val def = VoiceLibrary.byId(this, Prefs.voiceFx) ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val raw = VoiceClone.recordMic(4)
            if (raw == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VozesActivity, "Microfone indisponível.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val out = if (def.id == VoiceLibrary.NONE_ID) raw else com.telefonechamador.app.audio.VoiceFx.apply(raw, def.params, VoiceClone.SAMPLE_RATE)
            withContext(Dispatchers.Main) { Toast.makeText(this@VozesActivity, "Tocando com: ${def.label}", Toast.LENGTH_SHORT).show() }
            VoiceClone.play(this@VozesActivity, out)
        }
    }

    /** Mede a voz do dono (6s) para os clones saberem a razão de pitch correta. */
    private fun calibrate() {
        Toast.makeText(this, "Fale normalmente por 6 segundos…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.Default) {
            val raw = VoiceClone.recordMic(6)
            if (raw == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VozesActivity, "Microfone indisponível.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val st = VoiceClone.analyze(raw)
            withContext(Dispatchers.Main) {
                if (st.valid) {
                    Prefs.voiceBaseline = org.json.JSONObject()
                        .put("pitchHz", st.pitchHz)
                        .put("zcr", st.zcr).toString()
                    Toast.makeText(
                        this@VozesActivity,
                        "Calibrada: tom ${"%.0f".format(Locale.US, st.pitchHz)} Hz",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this@VozesActivity, "Não detectei voz — tente de novo mais perto.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Clona a voz de uma ligação do histórico (a voz do interlocutor). */
    private fun pickCallToClone() {
        lifecycleScope.launch {
            val calls = withContext(Dispatchers.IO) {
                repo.observeCalls().first() // snapshot
            }
            val withAudio = calls.filter { it.audioReceived != null || it.audioMixed != null }
            if (withAudio.isEmpty()) {
                Toast.makeText(this@VozesActivity, "Nenhuma ligação com áudio no histórico.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val fmt = SimpleDateFormat("dd/MM/yyyy HH'h'mm", Locale.getDefault())
            val labels = withAudio.map {
                "${fmt.format(it.startedAt)} — ${it.contactName ?: it.number.ifBlank { "Ligação" }}"
            }.toTypedArray()
            AlertDialog.Builder(this@VozesActivity)
                .setTitle("Clonar voz de qual ligação?")
                .setItems(labels) { _, which ->
                    cloneFrom(withAudio[which])
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun cloneFrom(call: CallEntity) {
        val path = call.audioReceived ?: call.audioMixed ?: return
        val f = File(path)
        if (!f.exists()) {
            Toast.makeText(this, "Áudio da ligação não encontrado no aparelho.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Analisando a voz…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.Default) {
            val target = VoiceClone.analyzeWav(f)
            val baseline = Prefs.voiceBaseline.takeIf { it.isNotBlank() }?.let {
                VoiceClone.baselineFromJson(it)
            }
            withContext(Dispatchers.Main) {
                if (target == null || !target.valid) {
                    Toast.makeText(
                        this@VozesActivity,
                        "Não consegui medir o tom dessa gravação (áudio curto ou silencioso).",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@withContext
                }
                val label = "Clone: ${call.contactName ?: call.number.ifBlank { "Ligação" }}"
                val desc = "Tom ${"%.0f".format(Locale.US, target.pitchHz)} Hz da ligação"
                val def = VoiceClone.buildClone(label, desc, target, baseline)
                VoiceLibrary.saveClone(this@VozesActivity, def)
                Prefs.voiceFx = def.id
                CallSession.setLiveEffect(def.id)
                refresh()
                Toast.makeText(this@VozesActivity, "Clone criado e selecionado. Use ▶ Testar voz.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(def: VoiceDef) {
        AlertDialog.Builder(this)
            .setTitle("Apagar voz")
            .setMessage("Apagar \"${def.label}\"? A voz original da ligação não é afetada.")
            .setPositiveButton("Apagar") { _, _ ->
                VoiceLibrary.deleteClone(this, def.id)
                if (Prefs.voiceFx == def.id) {
                    Prefs.voiceFx = VoiceLibrary.NONE_ID
                    CallSession.setLiveEffect(VoiceLibrary.NONE_ID)
                }
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

// ---------- lista ----------

private class VoiceAdapter(
    private val onSelect: (VoiceDef) -> Unit,
    private val onDelete: (VoiceDef) -> Unit,
) : RecyclerView.Adapter<VoiceAdapter.VH>() {

    private var items: List<VoiceDef> = emptyList()
    private var selected: String = "none"

    fun submit(newItems: List<VoiceDef>, selectedId: String) {
        items = newItems
        selected = selectedId
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.tv_label)
        val sub: TextView = v.findViewById(R.id.tv_sub)
        val dot: TextView = v.findViewById(R.id.tv_dot)
        val del: TextView = v.findViewById(R.id.tv_del)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_voice, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val v = items[pos]
        h.label.text = v.label
        h.sub.text = buildString {
            if (v.builtin && v.desc.isNotBlank()) append(v.desc)
            if (!v.builtin) append(if (v.desc.isNotBlank()) v.desc else "Clone")
        }
        h.dot.text = if (v.id == selected) "◉" else "○"
        h.itemView.setOnClickListener { onSelect(v) }
        h.del.visibility = if (v.builtin) View.GONE else View.VISIBLE
        h.del.setOnClickListener { onDelete(v) }
    }
}
