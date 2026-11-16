package com.telefonechamador.app.service

import android.app.Service
import android.content.Intent
import android.graphics.Typeface
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.telefonechamador.app.R
import com.telefonechamador.app.call.CallController
import com.telefonechamador.app.util.CallUtils
import com.telefonechamador.app.util.Fmt
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Janela flutuante com a transcrição ao vivo (fallback quando não somos o discador). */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var body: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        if (!Prefs.overlayEnabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)

        val title = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.cc_cream_on_dark))
            textSize = 14f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val bodyTv = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.cc_cream_faint))
            textSize = 14f
            maxLines = 8
        }
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.overlay_bg)
            gravity = Gravity.TOP
            addView(title)
            addView(bodyTv)
        }
        root = l
        body = bodyTv

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (240 * resources.displayMetrics.density).toInt()
        }
        try {
            wm?.addView(l, params)
        } catch (_: Throwable) {
            stopSelf()
            return
        }

        scope.launch {
            CallController.state.collect { s ->
                if (s.phase != "active") {
                    stopSelf()
                    return@collect
                }
                title.text = s.contactName ?: s.number.ifBlank { "Ligação" }
                val sb = StringBuilder()
                val last = s.segments.takeLast(4)
                for (seg in last) {
                    sb.append("[").append(Fmt.mmss(seg.offsetMs)).append("] ")
                    if (seg.side == "sent") sb.append("Você: ")
                    sb.append(seg.text).append("\n")
                }
                if (s.partial.isNotBlank()) {
                    sb.append("… ").append(s.partial)
                }
                bodyTv.text = sb.toString().trim().ifBlank { "Ouvindo a ligação…" }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { root?.let { wm?.removeView(it) } } catch (_: Throwable) { }
        root = null
        super.onDestroy()
    }

    companion object {
        /** Estado visível do toggle na tela da ligação. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: android.content.Context) {
            if (!Prefs.overlayEnabled || !Settings.canDrawOverlays(context)) return
            try { context.startService(Intent(context, OverlayService::class.java)) } catch (_: Throwable) { }
        }

        fun stop(context: android.content.Context) {
            try { context.stopService(Intent(context, OverlayService::class.java)) } catch (_: Throwable) { }
        }

        /** Liga o alto-falante para o overlay fazer sentido (voz alta no microfone). */
        fun maybeSpeaker(context: android.content.Context) {
            if (Prefs.overlayEnabled && !CallUtils.isSpeakerOn(context)) {
                CallUtils.setSpeakerOn(context, true)
            }
        }
    }
}
