package com.telefonechamador.app.audio

import android.content.Context
import com.telefonechamador.app.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parâmetros do DSP de voz — presets de fábrica e clones do usuário falam a
 * mesma linguagem. Processamento 100% local (ver VoiceFx).
 */
data class FxParams(
    val pitch: Double = 1.0,      // razão de pitch (shift doppler)
    val ringHz: Double = 0.0,     // ring modulation (0 = desligado)
    val radio: Boolean = false,   // band-pass 300–3400 Hz + saturação
    val echo: Boolean = false,    // eco com feedback
    val tiltDb: Double = 0.0,     // timbre: >0 escurece (mais grave), <0 clareia
    val gain: Double = 1.0,
)

data class VoiceDef(
    val id: String,
    val label: String,
    val params: FxParams,
    val builtin: Boolean,
    val desc: String = "",
)

/**
 * Biblioteca de vozes: presets de fábrica inspirados em arquétipos de voz
 * (narrador, locutor, robô, desenho animado…) + clones criados pelo usuário
 * a partir do áudio de uma ligação do histórico. Clones ficam no Prefs como
 * JSON — nada sai do aparelho.
 */
object VoiceLibrary {

    const val NONE_ID = "none"

    private val builtins = listOf(
        VoiceDef("none", "Voz original", FxParams(), true, "Sem efeito"),
        VoiceDef("deep", "Narrador de cinema", FxParams(pitch = 0.78, tiltDb = 6.0), true, "Grave e calmo"),
        VoiceDef("villain", "Vilão de suspense", FxParams(pitch = 0.62, tiltDb = 8.0), true, "Bem mais grave"),
        VoiceDef("baritone", "Locutor de rádio", FxParams(pitch = 0.86, tiltDb = 3.0), true, "Barítono apresentador"),
        VoiceDef("announcer", "Anunciante clássico", FxParams(pitch = 0.80, radio = true), true, "Grave com filtro de rádio"),
        VoiceDef("tenor", "Cantor lírico", FxParams(pitch = 1.18, tiltDb = 2.0), true, "Agudo encorpado"),
        VoiceDef("chipmunk", "Desenho animado", FxParams(pitch = 1.32, tiltDb = -4.0), true, "Agudo e engraçado"),
        VoiceDef("fairy", "Fada do bosque", FxParams(pitch = 1.45, tiltDb = -3.0), true, "Bem agudo e claro"),
        VoiceDef("robot", "Robô de ficção", FxParams(pitch = 0.92, ringHz = 55.0), true, "Metálico"),
        VoiceDef("radio", "Rádio de época", FxParams(radio = true), true, "Band-pass de telefone antigo"),
        VoiceDef("ghost", "Eco do além", FxParams(pitch = 0.70, echo = true, tiltDb = 4.0), true, "Grave com eco"),
        VoiceDef("whisper", "Sussurro misterioso", FxParams(pitch = 0.95, tiltDb = -8.0, gain = 1.5), true, "Ar e brilho, quase sussurro"),
    )

    fun builtinsList(): List<VoiceDef> = builtins

    /** Presets de fábrica + clones do usuário. */
    fun all(context: Context): List<VoiceDef> = builtins + clones(context)

    fun clones(context: Context): List<VoiceDef> = try {
        val arr = JSONArray(Prefs.voiceClones)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VoiceDef(
                id = o.optString("id"),
                label = o.optString("label"),
                params = FxParams(
                    pitch = o.optDouble("pitch", 1.0),
                    ringHz = o.optDouble("ringHz", 0.0),
                    radio = o.optBoolean("radio", false),
                    echo = o.optBoolean("echo", false),
                    tiltDb = o.optDouble("tiltDb", 0.0),
                    gain = o.optDouble("gain", 1.0),
                ),
                builtin = false,
                desc = o.optString("desc"),
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun byId(context: Context, id: String): VoiceDef? =
        all(context).firstOrNull { it.id == id }

    /** Parâmetros do DSP para o id; null quando "none" (sem efeito). */
    fun paramsOf(context: Context, id: String): FxParams? {
        if (id == NONE_ID) return null
        return byId(context, id)?.params
    }

    fun labelOf(context: Context, id: String): String =
        byId(context, id)?.label ?: "Voz original"

    fun saveClone(context: Context, def: VoiceDef) {
        val list = clones(context).filterNot { it.id == def.id } + def
        Prefs.voiceClones = toJson(list)
    }

    fun deleteClone(context: Context, id: String) {
        Prefs.voiceClones = toJson(clones(context).filterNot { it.id == id })
    }

    private fun toJson(list: List<VoiceDef>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("desc", c.desc)
                    .put("pitch", c.params.pitch)
                    .put("ringHz", c.params.ringHz)
                    .put("radio", c.params.radio)
                    .put("echo", c.params.echo)
                    .put("tiltDb", c.params.tiltDb)
                    .put("gain", c.params.gain)
            )
        }
        return arr.toString()
    }
}
