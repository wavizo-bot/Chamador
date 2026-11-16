package com.telefonechamador.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.telefonechamador.app.R
import com.telefonechamador.app.audio.AudioSourceProbe
import com.telefonechamador.app.retention.RetentionWorker
import com.telefonechamador.app.util.Notifications
import com.telefonechamador.app.util.Permissions
import com.telefonechamador.app.util.ShizukuSupport
import com.telefonechamador.app.util.Prefs
import com.telefonechamador.app.util.PrefsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    private val ringtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val uri: Uri? =
                    res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (uri != null) {
                    Prefs.notifRingtone = uri.toString()
                    Notifications.refreshChannels(requireContext())
                }
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Ajustes gravam direto no Prefs (mesmo arquivo que o motor lê).
        preferenceManager.preferenceDataStore = PrefsDataStore()
        setPreferencesFromResource(R.xml.preferences, null)

        find("models")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ModelActivity::class.java)); true
        }
        find("test_capture")?.setOnPreferenceClickListener { testCapture(); true }
        find("retention_days")?.setOnPreferenceClickListener { askRetention(); true }
        find("notif_ringtone")?.setOnPreferenceClickListener { pickRingtone(); true }
        find("blocked")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BlockedNumbersActivity::class.java)); true
        }
        find("dialer_role")?.setOnPreferenceClickListener { askRole(); true }
        find("battery")?.setOnPreferenceClickListener { askBattery(); true }
        find("shizuku")?.setOnPreferenceClickListener { shizukuFlow(); true }
        find("shizuku_capture")?.setOnPreferenceClickListener { shizukuCaptureFlow(); true }
        find("legal")?.setOnPreferenceClickListener { showLegal(); true }
        find("about")?.setOnPreferenceClickListener { showAbout(); true }
        updateSummaries()
    }

    private fun find(key: String): Preference? = findPreference<Preference>(key)

    override fun onResume() {
        super.onResume()
        updateSummaries()
    }

    private fun updateSummaries() {
        find("retention_days")?.summary =
            "${Prefs.retentionDays} dias (1 a 99 — favoritos com ★ não são apagados)"
        find("about")?.summary = "${versaoInstalada()} — uso pessoal, dados 100% locais"
    }

    // Regra 2.2 (Regras.txt): o número que o usuário vê é o do pacote instalado,
    // lido do PackageManager em tempo de execução — nunca texto fixo do código.
    private fun versaoInstalada(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            .versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun testCapture() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val caps = AudioSourceProbe.probe(ctx)
            Prefs.captureCapability = caps.toJson()
            val msg = buildString {
                append("Resultado no seu aparelho:\n\n")
                if (caps.mix) append("✔ VOICE_CALL — áudio completo da ligação (melhor cenário)\n")
                if (caps.down) append("✔ VOICE_DOWNLINK — voz recebida\n")
                if (caps.up) append("✔ VOICE_UPLINK — a sua voz\n")
                if (caps.media) append("✔ MediaRecorder nativo — grava; transcrição gerada ao encerrar\n")
                if (caps.mic) append("✔ Microfone — ligue o alto-falante para captar a outra voz\n")
                if (!caps.usable) append("✘ Nenhuma fonte disponível — verifique a permissão de microfone.")
            }
            AlertDialog.Builder(ctx)
                .setTitle("Diagnóstico de captação")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun askRetention() {
        val ctx = requireContext()
        val frame = FrameLayout(ctx)
        val pad = (20 * resources.displayMetrics.density).toInt()
        frame.setPadding(pad, pad, pad, 0)
        val edt = EditText(ctx).apply {
            setText(Prefs.retentionDays.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2))
        }
        frame.addView(edt)
        AlertDialog.Builder(ctx)
            .setTitle("Manter conversas por (dias)")
            .setView(frame)
            .setPositiveButton("Salvar") { _, _ ->
                edt.text.toString().toIntOrNull()?.let { Prefs.retentionDays = it }
                updateSummaries()
                RetentionWorker.runOnce(ctx)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickRingtone() {
        try {
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Som das notificações")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                Prefs.notifRingtone.takeIf { it.isNotBlank() }?.let {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                }
            }
            ringtoneLauncher.launch(i)
        } catch (_: Throwable) {
            Toast.makeText(requireContext(), "Seletor de toque indisponível.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askRole() {
        try {
            val rm = requireContext().getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
                startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
                return
            }
        } catch (_: Throwable) { }
        try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (_: Throwable) { }
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + requireContext().packageName),
                )
            )
        } catch (_: Throwable) {
            Toast.makeText(requireContext(), "Opção indisponível neste aparelho.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shizukuFlow() {
        val ctx = requireContext()
        if (!ShizukuSupport.isInstalled(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle("Shizuku não instalado")
                .setMessage(
                    "O Shizuku é um utilitário que concede privilégios de ADB sem root. " +
                        "Instale-o (Play Store ou GitHub — rikkashizuku/Shizuku), inicie o " +
                        "serviço por ADB sem fio ou cabo e volte aqui."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!ShizukuSupport.isRunning()) {
            AlertDialog.Builder(ctx)
                .setTitle("Shizuku parado")
                .setMessage("Abra o app Shizuku e inicie o serviço (via ADB). Depois volte aqui.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!ShizukuSupport.hasPermission()) {
            ShizukuSupport.requestPermission()
            Toast.makeText(ctx, "Conceda a permissão no aviso do Shizuku e toque novamente.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { ShizukuSupport.applyPrivileges(ctx) }
            AlertDialog.Builder(ctx)
                .setTitle("Shizuku aplicado")
                .setMessage(result)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun shizukuCaptureFlow() {
        val ctx = requireContext()
        if (!ShizukuSupport.ready()) {
            shizukuFlow()
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) { ShizukuSupport.probeCapture(ctx, 2) }
            val msg = if (res == null) {
                "Não foi possível falar com o serviço do Shizuku."
            } else {
                val names = listOf("MIC", "VOICE_COMMUNICATION", "VOICE_CALL", "VOICE_UPLINK", "VOICE_DOWNLINK")
                val b = StringBuilder("Energia média (RMS) por fonte, no processo do Shizuku:\n")
                for (i in names.indices) {
                    val st = res[i * 3]
                    val rms = res[i * 3 + 1]
                    val blocks = res[i * 3 + 2]
                    val line = when (st) {
                        1 -> if (rms > 0) "som real ✔ (rms=$rms, $blocks blocos)" else "abriu, mas silêncio (rms=0)"
                        0 -> "não inicializou"
                        else -> "falhou ao abrir"
                    }
                    b.append("\n• ").append(names[i]).append(": ").append(line)
                }
                b.append("\n\nFaça o teste fora de uma ligação e depois DURANTE uma ligação. Se o RMS subir durante a chamada, a transcrição real de ligações funciona por este caminho.")
                b.toString()
            }
            AlertDialog.Builder(ctx)
                .setTitle("Teste de captura (Shizuku)")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showLegal() {
        AlertDialog.Builder(requireContext())
            .setTitle("Aviso legal")
            .setMessage(
                "• Tudo é processado no aparelho; nada sai do telefone.\n" +
                    "• A leis sobre gravação de ligações variam — em vários lugares é " +
                    "obrigatório avisar a outra pessoa.\n" +
                    "• Conversas não favoritas são apagadas automaticamente (1 a 99 dias).\n" +
                    "• Em muitos aparelhos o Android restringe o áudio da chamada; neste caso " +
                    "o app usa o microfone com o alto-falante ligado (captação acústica)."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(requireContext())
            .setTitle("Telefone Chamador")
            .setMessage(
                "1.0.0\n\nTranscrição offline (Vosk / Parakeet), gravação com fallback, " +
                    "VAD anti-silêncio, efeitos de voz e janela flutuante. " +
                    "Modo discador completo quando definido como telefone padrão."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
