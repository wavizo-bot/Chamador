package com.telefonechamador.app.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class SttModelDef(
    val id: String,
    val engine: String,          // "vosk" | "sherpa"
    val label: String,
    val sizeMb: Int,
    val url: String,
    val dirName: String,
    val format: String,          // "zip" | "tbz"
    val requiredFiles: List<String>,
)

object ModelManager {
    const val ENGINE_VOSK = "vosk"
    const val ENGINE_SHERPA = "sherpa"

    val MODELS = listOf(
        SttModelDef(
            "vosk_small_pt", ENGINE_VOSK,
            "Vosk pt-BR — pequeno e rápido (recomendado)", 31,
            "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
            "vosk-model-small-pt-0.3", "zip", listOf("am/final.mdl"),
        ),
        SttModelDef(
            "vosk_big_pt", ENGINE_VOSK,
            "Vosk pt-BR FalaBrazil — maior precisão (GPLv3)", 1650,
            "https://alphacephei.com/vosk/models/vosk-model-pt-fb-v0.1.1-20220516_2113.zip",
            "vosk-model-pt-fb-v0.1.1-20220516_2113", "zip", listOf("am/final.mdl"),
        ),
        SttModelDef(
            "parakeet_v3", ENGINE_SHERPA,
            "Parakeet TDT 0.6b v3 — 25 idiomas, inclui pt-BR", 660,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "tbz",
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        ),
    )

    @Volatile
    var cancelRequested = false

    fun byId(id: String): SttModelDef? = MODELS.firstOrNull { it.id == id }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "stt-models").apply { mkdirs() }

    fun modelDir(context: Context, def: SttModelDef): File =
        File(modelsDir(context), def.dirName)

    fun isInstalled(context: Context, def: SttModelDef): Boolean {
        val d = modelDir(context, def)
        if (satisfied(d, def)) return true
        return d.listFiles()?.any { sub -> sub.isDirectory && satisfied(sub, def) } == true
    }

    /** Aceita o layout novo (final.mdl na raiz) e o Kaldi clássico (am/final.mdl). */
    private fun satisfied(d: File, def: SttModelDef): Boolean {
        if (!d.isDirectory) return false
        if (def.engine == ENGINE_VOSK) return File(d, "final.mdl").exists() || File(d, "am/final.mdl").exists()
        return def.requiredFiles.all { File(d, it).exists() }
    }

    /** Baixa e extrai o modelo. Retoma com Range em caso de queda; até 4 tentativas. Progresso em % e texto amigável. */
    suspend fun download(
        context: Context,
        def: SttModelDef,
        onProgress: (Int, String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        cancelRequested = false
        val dir = modelDir(context, def)
        if (isInstalled(context, def)) return@withContext true
        dir.mkdirs()
        // Arquivo parcial persiste entre tentativas e entre toques: retoma de onde parou.
        val tmpArchive = File(context.cacheDir, def.dirName + ".download")
        val tmpExtract = File(context.cacheDir, def.dirName + ".tmp").apply { mkdirs() }
        var result = false
        var msgFinal = "falha"
        var badArchive = false
        try {
            for (attempt in 1..4) {
                if (cancelRequested) { msgFinal = "cancelado"; break }
                if (badArchive) { tmpArchive.delete(); badArchive = false }
                try {
                    val already = if (tmpArchive.exists()) tmpArchive.length() else 0L
                    val conn = URL(def.url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 60_000
                    if (already > 0L) conn.setRequestProperty("Range", "bytes=$already-")
                    conn.connect()
                    val code = conn.responseCode
                    if (code == 416 && tmpArchive.exists()) {
                        // Já baixamos todos os bytes em tentativa anterior: extrair direto.
                        onProgress(99, "finalizando…")
                        extract(tmpArchive, def.format, tmpExtract)
                        val root = findRoot(tmpExtract, def)
                        if (root == null) {
                            badArchive = true
                            msgFinal = "arquivo inesperado"
                            continue
                        }
                        moveAll(root, dir)
                        result = isInstalled(context, def)
                        if (!result) msgFinal = "verificação falhou"
                        break
                    }
                    val appending = code == 206 && already > 0L
                    if (!appending && already > 0L) tmpArchive.delete()  // servidor não aceitou Range
                    val base = if (appending) already else 0L
                    val total = conn.contentLengthLong + base
                    conn.inputStream.use { input ->
                        FileOutputStream(tmpArchive, appending).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var read = base
                            while (true) {
                                if (cancelRequested) break
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                val pct = if (total > 0) ((read * 100) / total).toInt() else 0
                                val tag = if (attempt > 1) " • tentativa $attempt" else ""
                                onProgress(pct.coerceAtMost(99), mbLabel(read, total) + tag)
                            }
                        }
                    }
                    val got = tmpArchive.length()
                    val expect = if (total > 0) total else got
                    if (got >= expect) {
                        onProgress(100, "extraindo…")
                        extract(tmpArchive, def.format, tmpExtract)
                        val root = findRoot(tmpExtract, def)
                        if (root == null) {
                            badArchive = true   // baixado por completo mas inválido: baixar de novo
                            msgFinal = "arquivo inesperado"
                            continue
                        }
                        moveAll(root, dir)
                        result = isInstalled(context, def)
                        if (!result) msgFinal = "verificação falhou"
                        break
                    } else {
                        msgFinal = "conexão interrompida"
                        continue  // nova tentativa retomando de $got
                    }
                } catch (t: Throwable) {
                    msgFinal = "falha: ${t.message ?: t.javaClass.simpleName}"
                    if (attempt < 4) delay(2_000)
                }
            }
        } finally {
            tmpExtract.deleteRecursively()
            if (result) tmpArchive.delete()
        }
        if (!result) onProgress(100, msgFinal)
        result
    }

    suspend fun delete(context: Context, def: SttModelDef) = withContext(Dispatchers.IO) {
        modelDir(context, def).deleteRecursively()
    }

    private fun mbLabel(read: Long, total: Long): String =
        if (total > 0) "${read / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
        else "${read / (1024 * 1024)} MB"

    private fun extract(archive: File, format: String, dest: File) {
        if (format == "zip") {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory) writeEntry(zin, File(dest, stripRoot(e.name)))
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        } else {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tin ->
                var e = tin.nextTarEntry
                while (e != null) {
                    if (!e.isDirectory) writeEntry(tin, File(dest, stripRoot(e.name)))
                    e = tin.nextTarEntry
                }
            }
        }
    }

    private fun writeEntry(input: java.io.InputStream, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { input.copyTo(it) }
    }

    private fun stripRoot(name: String): String {
        val clean = name.replace('\\', '/')
        val i = clean.indexOf('/')
        return if (i > 0) clean.substring(i + 1) else clean
    }

    private fun findRoot(dir: File, def: SttModelDef): File? {
        if (satisfied(dir, def)) return dir
        return dir.listFiles()?.firstOrNull { sub -> sub.isDirectory && satisfied(sub, def) }
    }

    private fun moveAll(src: File, dst: File) {
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            val target = File(dst, f.name)
            if (f.isDirectory) {
                moveAll(f, target)
                f.deleteRecursively()
            } else {
                f.copyTo(target, overwrite = true)
                f.delete()
            }
        }
    }
}
