package com.telefonechamador.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.appbar.MaterialToolbar
import com.telefonechamador.app.R
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.service.CallSessionService
import com.telefonechamador.app.service.OverlayService
import com.telefonechamador.app.util.Permissions
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { maybeAskScreening() }
    private val screeningLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.app_name)
        })

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val f = when (item.itemId) {
                R.id.nav_contatos -> ContactsFragment()
                R.id.nav_buscar -> SearchFragment()
                R.id.nav_ajustes -> SettingsFragment()
                else -> HistoryFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, f)
                .commit()
            true
        }
        findViewById<FloatingActionButton>(R.id.fab_dial).setOnClickListener {
            DialpadDialogFragment().show(supportFragmentManager, "dial")
        }

        // Banner fixo: volta para a tela da ligação com um toque
        val banner = findViewById<LinearLayout>(R.id.banner_call)
        banner.setOnClickListener {
            try {
                startActivity(Intent(this, OngoingCallActivity::class.java))
            } catch (_: Throwable) { }
        }
        lifecycleScope.launch {
            com.telefonechamador.app.call.CallController.state.collect { s ->
                banner.isVisible = s.phase == "active" || s.phase == "ringing" || s.phase == "dialing"
                findViewById<TextView>(R.id.banner_call_text).text =
                    when (s.phase) {
                        "ringing" -> "☎  Chamada tocando — atender"
                        "dialing" -> "☎  Discando ${s.number} — abrir tela"
                        else -> "☎  Ligação em andamento — abrir tela da ligação"
                    }
            }
        }

        if (savedInstanceState == null) nav.selectedItemId = R.id.nav_historico

        if (Prefs.consentAccepted) {
            askPermissions()
            maybeAskDialerRole()
        } else {
            showConsent()
        }

        handleIntent(intent)

        // monitor de ligações vivo desde já
        try {
            ContextCompat.startForegroundService(this, Intent(this, CallSessionService::class.java))
        } catch (_: Throwable) { }
        if (Prefs.overlayEnabled && Permissions.canDrawOverlays(this)) {
            OverlayService.start(this)
        }

        if (CallUiStateHolderIsActive()) {
            startActivity(Intent(this, OngoingCallActivity::class.java))
        }
    }

    private fun CallUiStateHolderIsActive(): Boolean =
        com.telefonechamador.app.call.CallController.state.value.phase == "active"

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_last_call", false) == true) {
            lifecycleScope.launch {
                val last = CallRepository(this@MainActivity).observeCalls().first().firstOrNull()
                if (last != null) {
                    startActivity(
                        Intent(this@MainActivity, CallDetailActivity::class.java)
                            .putExtra("callId", last.id)
                    )
                }
            }
        }
        if (intent?.action == Intent.ACTION_DIAL) {
            val num = intent.data?.schemeSpecificPart ?: ""
            if (num.isNotBlank()) {
                val d = DialpadDialogFragment()
                d.arguments = Bundle().apply { putString("number", num) }
                d.show(supportFragmentManager, "dial")
            }
        }
    }

    private fun showConsent() {
        AlertDialog.Builder(this)
            .setTitle("Antes de começar")
            .setMessage(
                "O Telefone Chamador grava e transcreve ligações no seu aparelho — os dados " +
                    "ficam 100% locais e não são enviados para lugar nenhum.\n\n" +
                    "Gravar a ligação sem avisar a outra pessoa pode ser vedado por lei no seu " +
                    "estado/país. Ao usar este app, você declara entender e cumprir as leis " +
                    "aplicáveis.\n\nConversas não favoritas são apagadas automaticamente " +
                    "(padrão: 10 dias)."
            )
            .setPositiveButton("Concordo e entendo") { _, _ ->
                Prefs.consentAccepted = true
                askPermissions()
                maybeAskDialerRole()
            }
            .setNegativeButton("Sair") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun askPermissions() {
        val missing = Permissions.missing(this, Permissions.withCallLog.toList()).toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.notificationsGranted(this)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            try { permLauncher.launch(missing.toTypedArray()) } catch (_: Throwable) { }
        }
    }

    private fun maybeAskDialerRole() {
        if (Prefs.dialerRoleAsked) return
        if (!Permissions.dialerAvailable(this)) {
            Prefs.dialerRoleAsked = true
            return
        }
        if (Permissions.isDialer(this)) {
            Prefs.dialerRoleAsked = true
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Definir como telefone padrão")
            .setMessage(
                "Para o modo discador completo — tela de ligação integrada com transcrição, " +
                    "teclado e histórico — defina o Telefone Chamador como app de telefone " +
                    "padrão. Você pode continuar usando o app sem isso; a transcrição funciona " +
                    "como janela flutuante."
            )
            .setPositiveButton("Definir agora") { _, _ ->
                try {
                    val rm = getSystemService(RoleManager::class.java)
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    return@setPositiveButton
                } catch (_: Throwable) { }
                Prefs.dialerRoleAsked = true
            }
            .setNegativeButton("Depois") { _, _ -> Prefs.dialerRoleAsked = true }
            .show()
    }

    private fun maybeAskScreening() {
        Prefs.dialerRoleAsked = true
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val rm = getSystemService(RoleManager::class.java) ?: return
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            ) {
                screeningLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
        } catch (_: Throwable) { }
    }

    /** Reabre a tela da ligação em andamento vinda da notificação. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}
