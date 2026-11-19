package com.telefonechamador.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.telefonechamador.app.R
import com.telefonechamador.app.stt.ModelManager
import com.telefonechamador.app.stt.OfflineFileStt
import com.telefonechamador.app.stt.SttModelDef
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.launch

/** Download e seleção dos modelos de transcrição (Vosk / Parakeet). */
class ModelActivity : AppCompatActivity() {

    private val adapter: ModelAdapter = ModelAdapter { def, pos -> handleAction(def, pos) }

    private fun handleAction(def: SttModelDef, pos: Int) {
        lifecycleScope.launch {
            if (ModelManager.isInstalled(this@ModelActivity, def)) {
                ModelManager.delete(this@ModelActivity, def)
                adapter.refresh()
                Toast.makeText(this@ModelActivity, "Modelo removido.", Toast.LENGTH_SHORT).show()
            } else {
                setStatus("Baixando ${def.label}…")
                adapter.setBusy(pos)
                val ok = ModelManager.download(this@ModelActivity, def) { pct, msg ->
                    runOnUiThread {
                        adapter.setProgress(pos, pct)
                        setStatus("$pct% • $msg")
                    }
                }
                adapter.clearBusy()
                setStatus(if (ok) "Concluído ✔" else "Download interrompido ou falhou.")
                if (ok && def.engine == ModelManager.ENGINE_SHERPA && def.kind == "asr") {
                    // Modelo Parakeet instalado vira o preferido da engine sherpa
                    // (só ASR: um TTS nunca pode virar o modelo de transcrição).
                    Prefs.sherpaModel = def.id
                }
                if (!ok) Toast.makeText(
                    this@ModelActivity,
                    "Falha no download — toque em Baixar de novo (retoma de onde parou).",
                    Toast.LENGTH_LONG,
                ).show()
                adapter.refresh()
            }
        }
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.tv_status).text = text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        findViewById<MaterialToolbar>(R.id.toolbar_detail).setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val rgEngine = findViewById<RadioGroup>(R.id.rg_engine)
        val rgVosk = findViewById<RadioGroup>(R.id.rg_vosk)
        findViewById<RadioButton>(R.id.rb_vosk).isChecked = Prefs.sttEngine == Prefs.ENGINE_VOSK
        findViewById<RadioButton>(R.id.rb_sherpa).isChecked = Prefs.sttEngine == Prefs.ENGINE_SHERPA
        findViewById<RadioButton>(R.id.rb_small).isChecked = Prefs.voskModel == "vosk_small_pt"
        findViewById<RadioButton>(R.id.rb_big).isChecked = Prefs.voskModel == "vosk_big_pt"

        rgEngine.setOnCheckedChangeListener { _, id ->
            Prefs.sttEngine = if (id == R.id.rb_sherpa) Prefs.ENGINE_SHERPA else Prefs.ENGINE_VOSK
            if (id == R.id.rb_sherpa) {
                val libOk = OfflineFileStt.available()
                val hasModel = ModelManager.MODELS.any {
                    it.kind == "asr" && it.engine == ModelManager.ENGINE_SHERPA &&
                        ModelManager.isInstalled(this, it)
                }
                val msg = when {
                    !libOk -> "Parakeet exige o pacote sherpa-onnx incluído no build; sem ele, o app volta ao Vosk."
                    !hasModel -> "Motor Parakeet ativado — baixe um modelo Parakeet abaixo para usá-lo."
                    else -> "Motor Parakeet ativado — transcrição em parágrafo completo."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
        rgVosk.setOnCheckedChangeListener { _, id ->
            Prefs.voskModel = if (id == R.id.rb_big) "vosk_big_pt" else "vosk_small_pt"
        }

        setStatus(if (ModelManager.MODELS.any { ModelManager.isInstalled(this, it) }) "Modelos instalados prontos." else "Baixe um modelo para começar.")
    }
}

class ModelAdapter(private val onAction: (SttModelDef, Int) -> Unit) :
    RecyclerView.Adapter<ModelAdapter.VH>() {

    private val busy = HashSet<Int>()
    private val progress = HashMap<Int, Int>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_name)
        val status: TextView = v.findViewById(R.id.tv_status)
        val action: Button = v.findViewById(R.id.btn_action)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false))

    override fun getItemCount() = ModelManager.MODELS.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val def = ModelManager.MODELS[pos]
        val ctx = h.itemView.context
        val installed = ModelManager.isInstalled(ctx, def)
        h.name.text = def.label
        h.status.text = buildString {
            append(if (installed) "Instalado" else "${def.sizeMb} MB para baixar")
            append(" • ")
            append(when {
                def.kind != "asr" -> "voz do Laboratório"
                def.engine == ModelManager.ENGINE_SHERPA -> "motor Parakeet"
                else -> "motor Vosk"
            })
        }
        h.action.text = if (installed) "Excluir" else "Baixar"
        h.progress.visibility = if (busy.contains(pos)) View.VISIBLE else View.GONE
        h.progress.progress = progress[pos] ?: 0
        h.action.setOnClickListener { onAction(def, pos) }
    }

    fun refresh() { progress.clear(); busy.clear(); notifyDataSetChanged() }
    fun setBusy(pos: Int) { busy.add(pos); notifyItemChanged(pos) }
    fun clearBusy() { val old = HashSet(busy); busy.clear(); old.forEach { notifyItemChanged(it) } }
    fun setProgress(pos: Int, pct: Int) { progress[pos] = pct; notifyItemChanged(pos) }
}
