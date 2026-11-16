package com.telefonechamador.app.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Captura de áudio no processo shell (uid 2000) do Shizuku.
 *
 * O AudioFlinger valida o par (uid, packageName) do AttributionSource antes de
 * abrir qualquer fonte. O processo do UserService roda de verdade como uid 2000
 * e "com.android.shell" é um pacote real do sistema com exatamente esse uid e
 * permissões de assinatura de áudio (RECORD_AUDIO, CAPTURE_AUDIO_OUTPUT).
 * Nos apresentamos a ele para o gate de áudio — mesma mecânica pública do
 * FakeContext do scrcpy 2.0 (Apache-2.0, Genymobile) e de gravadores shell
 * open-source (Koel, ShizuCallRecorder).
 *
 * Três peças, todas necessárias:
 *  1. Contexto embrulhado (ShellIdentityContext) cujo packageName /
 *     opPackageName / getAttributionSource() devolvem com.android.shell, mais
 *     patches best-effort no ActivityThread (identidade do processo).
 *  2. Construção do AudioRecord com AudioRecord.Builder().setContext(contexto
 *     embrulhado) — o Builder é o único caminho que leva o AttributionSource
 *     do Context até o gate nativo.
 *  3. Construção no Looper principal do processo: a checagem de AppOps do
 *     AudioFlinger lê thread-locals que só existem no main Looper do
 *     app_process do Shizuku.
 */
object ShellCapture {

    private const val TAG = "TChamador.Shizuku"
    private const val SHELL_PACKAGE = "com.android.shell"

    @Volatile private var hiddenApiDone = false
    @Volatile private var baseContext: Context? = null
    @Volatile private var shellContext: Context? = null

    private fun log(s: String) {
        try { Log.d(TAG, s) } catch (_: Throwable) { }
    }

    /** Exenção de hidden API para tocar no ActivityThread (Android P+). */
    fun enableHiddenApi() {
        if (hiddenApiDone) return
        hiddenApiDone = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/app/",
                    "Landroid/media/",
                    "Landroid/content/",
                    "Landroid/os/",
                )
                log("HiddenApiBypass aplicado")
            } catch (t: Throwable) {
                log("HiddenApiBypass falhou: ${t.message}")
            }
        }
    }

    /**
     * Contexto base do processo: o ActivityThread que o próprio Shizuku cria
     * (systemMain) tem um systemContext pronto. Se não existir, constrói um
     * marshalled para o main Looper (o construtor do ActivityThread cria
     * Handler e exige Looper na thread chamadora).
     */
    private fun obtainBaseContext(): Context {
        baseContext?.let { return it }
        val cls = Class.forName("android.app.ActivityThread")
        val at = try {
            cls.getMethod("currentActivityThread").invoke(null)
        } catch (t: Throwable) {
            log("currentActivityThread falhou: ${t.message}"); null
        }
        if (at != null) {
            val ctx = cls.getMethod("getSystemContext").invoke(at) as Context
            log("baseContext via currentActivityThread")
            baseContext = ctx
            return ctx
        }
        // Sem ActivityThread: systemMain tem de rodar numa thread com Looper.
        val main = Looper.getMainLooper()
        if (main != null && main.thread === Thread.currentThread()) {
            val at2 = cls.getMethod("systemMain").invoke(null)
            val ctx = cls.getMethod("getSystemContext").invoke(at2) as Context
            log("baseContext via systemMain (na própria main)")
            baseContext = ctx
            return ctx
        }
        if (main != null) {
            val result = AtomicReference<Context?>()
            val err = AtomicReference<Throwable?>()
            val latch = CountDownLatch(1)
            Handler(main).post {
                try {
                    val at3 = cls.getMethod("systemMain").invoke(null)
                    result.set(cls.getMethod("getSystemContext").invoke(at3) as Context)
                } catch (t: Throwable) {
                    err.set(t)
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(3, TimeUnit.SECONDS)) {
                error("systemMain no main Looper estourou o tempo")
            }
            err.get()?.let { throw it }
            val ctx = result.get() ?: error("systemMain devolveu null")
            log("baseContext via systemMain (marshalled)")
            baseContext = ctx
            return ctx
        }
        // Última linha: sem Looper nenhum. Tenta na thread atual e aceita o erro.
        val at4 = cls.getMethod("systemMain").invoke(null)
        val ctx = cls.getMethod("getSystemContext").invoke(at4) as Context
        log("baseContext via systemMain (sem looper — improbável)")
        baseContext = ctx
        return ctx
    }

    /** Contexto com identidade shell (uid 2000 + com.android.shell), criado 1x. */
    @Synchronized
    fun shellContext(): Context {
        shellContext?.let { return it }
        enableHiddenApi()
        val raw = obtainBaseContext()
        val wrapped = ShellIdentityContext(raw)
        shellContext = wrapped
        log("shellContext pronto: patches=${wrapped.patchCount}/4")
        return wrapped
    }

    /**
     * Abre um AudioRecord com a identidade shell. Chamável de qualquer thread:
     * a construção acontece no main Looper do processo (exigência do gate de
     * AppOps) e devolve sincronamente.
     */
    fun openRecord(source: Int, sampleRate: Int, channelMask: Int, encoding: Int): AudioRecord? {
        val ctx = shellContext()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            log("getMinBufferSize=$minBuf src=$source")
            return null
        }
        val bufSize = maxOf(minBuf * 2, sampleRate * 2)
        val fmt = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val onMain = Looper.getMainLooper()?.thread === Thread.currentThread()
        if (onMain) return tryBuild(source, fmt, bufSize, ctx)

        val result = AtomicReference<AudioRecord?>()
        val err = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(tryBuild(source, fmt, bufSize, ctx))
            } catch (t: Throwable) {
                err.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            log("ctor no main Looper estourou 2s (src=$source)")
            return null
        }
        err.get()?.let {
            log("ctor lançou: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        return result.get()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO vale por identidade shell
    private fun tryBuild(source: Int, fmt: AudioFormat, bufSize: Int, ctx: Context): AudioRecord? {
        return try {
            val builder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
            // setContext: @hide/@SystemApi conforme a versão — via reflexão.
            val setCtx = builder.javaClass.methods.firstOrNull {
                it.name == "setContext" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Context::class.java
            }
            if (setCtx != null) {
                setCtx.invoke(builder, ctx)
            } else {
                log("AVISO: AudioRecord.Builder.setContext não existe neste runtime")
            }
            val rec = builder.build()
            val ok = rec.state == AudioRecord.STATE_INITIALIZED
            log("openRecord src=$source state=${rec.state} ok=$ok via setContext=${setCtx != null}")
            if (ok) rec else { try { rec.release() } catch (_: Throwable) { }; null }
        } catch (t: Throwable) {
            log("build lançou (src=$source): ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Diagnóstico textual para a sonda e para o logcat. */
    fun diagnostics(): String {
        val sb = StringBuilder()
        try {
            sb.append("uid=").append(Process.myUid())
            sb.append(" mainLooper=").append(Looper.getMainLooper() != null)
            val cls = Class.forName("android.app.ActivityThread")
            sb.append(" activityThread=").append(
                cls.getMethod("currentActivityThread").invoke(null) != null
            )
            sb.append(" setContext=").append(
                AudioRecord.Builder().javaClass.methods.any {
                    it.name == "setContext" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Context::class.java
                }
            )
        } catch (t: Throwable) {
            sb.append(" diag-erro=").append(t.javaClass.simpleName)
        }
        val w = shellContext
        if (w is ShellIdentityContext) sb.append(" patches=").append(w.patchCount).append("/4")
        return sb.toString()
    }
}

/**
 * ContextWrapper que se apresenta ao sistema de áudio como com.android.shell
 * (uid 2000) — um par (uid, pacote) REAL na base de pacotes. Patches no
 * ActivityThread cobrem os caminhos em que a identidade é resolvida por
 * Application/ActivityThread em vez do próprio Context.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
class ShellIdentityContext(base: Context) : ContextWrapper(base) {

    private val opPackage = "com.android.shell"
    var patchCount = 0
        private set

    init {
        val fakeApp: Application? = try {
            Instrumentation.newApplication(Application::class.java, this)
        } catch (t: Throwable) {
            log("newApplication falhou: ${t.message}")
            null
        }

        try {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = atCls.getMethod("currentActivityThread").invoke(null)
                ?: atCls.getMethod("systemMain").invoke(null)
            if (at != null) {
                // sCurrentActivityThread — caminho direto de quem consulta a AT.
                patch("sCurrentActivityThread", atCls, null, at) { f -> f.set(null, at) }
                // mSystemThread = true → marca como processo de sistema.
                patch("mSystemThread", atCls, at, null) { f -> f.setBoolean(at, true) }
                // mInitialApplication — o que currentApplication() devolve.
                if (fakeApp != null) {
                    patch("mInitialApplication", atCls, at, null) { f -> f.set(at, fakeApp) }
                }
                // mBoundApplication.appInfo — identidade alternativa do processo.
                try {
                    val boundField = atCls.getDeclaredField("mBoundApplication")
                    boundField.isAccessible = true
                    val bound = boundField.get(at) ?: run {
                        val bindCls = Class.forName("android.app.ActivityThread\$AppBindData")
                        val ctor = bindCls.getDeclaredConstructor().apply { isAccessible = true }
                        ctor.newInstance().also { boundField.set(at, it) }
                    }
                    val appInfo = ApplicationInfo().apply {
                        packageName = opPackage
                        uid = Process.myUid()
                    }
                    val appInfoField = Class.forName("android.app.ActivityThread\$AppBindData")
                        .getDeclaredField("appInfo")
                    appInfoField.isAccessible = true
                    appInfoField.set(bound, appInfo)
                    patchCount++
                } catch (t: Throwable) {
                    log("patch AppBindData falhou: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log("patches ActivityThread falharam: ${t.message}")
        }
        log("ShellIdentityContext init: pkg=$opPackage uid=${Process.myUid()} patches=$patchCount/4")
    }

    private inline fun patch(
        name: String,
        cls: Class<*>,
        target: Any?,
        staticTarget: Any?,
        block: (java.lang.reflect.Field) -> Unit,
    ) {
        try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            block(f)
            patchCount++
        } catch (t: Throwable) {
            log("patch $name falhou: ${t.message}")
        }
    }

    // ── Identidade que o AppOps / AudioFlinger enxerga ────────────────────

    override fun getPackageName(): String = opPackage

    // getOpPackageName existe nos stubs (é @hide em runtime): override direto.
    override fun getOpPackageName(): String = opPackage

    override fun getAttributionSource(): AttributionSource =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AttributionSource.Builder(Process.myUid())
                .setPackageName(opPackage)
                .build()
        } else {
            super.getAttributionSource()
        }

    override fun getDeviceId(): Int = 0

    /**
     * Serviços que carregam mContext: reatribuir para que consultas futuras
     * de AppOps desses serviços enxerguem a identidade embrulhada.
     */
    override fun getSystemService(name: String): Any? {
        val svc = super.getSystemService(name) ?: return null
        if (name in CONTEXT_BOUND_SERVICES) {
            try {
                val f = svc.javaClass.getDeclaredField("mContext")
                f.isAccessible = true
                f.set(svc, this)
            } catch (_: Throwable) { }
        }
        return svc
    }

    override fun getApplicationContext(): Context = this
    override fun createPackageContext(packageName: String?, flags: Int): Context = this

    companion object {
        private fun log(s: String) {
            try { Log.d("TChamador.Shizuku", s) } catch (_: Throwable) { }
        }

        private val CONTEXT_BOUND_SERVICES = setOf(
            Context.AUDIO_SERVICE,
            Context.TELEPHONY_SERVICE,
            Context.MEDIA_SESSION_SERVICE,
            Context.MEDIA_ROUTER_SERVICE,
            Context.APP_OPS_SERVICE,
        )
    }
}
