package com.telefonechamador.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.telefonechamador.app.audio.VoiceDef
import com.telefonechamador.app.audio.VoiceFx
import com.telefonechamador.app.audio.WavWriter
import com.telefonechamador.app.data.SegmentEntity
import com.telefonechamador.app.util.Fmt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compartilhar (transcrição/áudio) e exportar com efeito — Android padrão. */
object ShareUtils {

    const val SIDE_RECEIVED = "received"
    const val SIDE_SENT = "sent"
    const val SIDE_MIXED = "mixed"

    fun transcriptText(
        name: String?,
        number: String,
        startedAt: Long,
        durationSec: Int,
        segments: List<SegmentEntity>,
    ): String {
        val sb = StringBuilder()
        sb.append("Telefone Chamador\n")
        sb.append("Ligação: ").append(name?.takeIf { it.isNotBlank() } ?: number).append("\n")
        sb.append("Data: ").append(Fmt.stamp(startedAt)).append("\n")
        if (durationSec > 0) sb.append("Duração: ").append(Fmt.duration(durationSec)).append("\n")
        sb.append("\n")
        for (s in segments) {
            sb.append("[").append(Fmt.mmss(s.offsetMs)).append("] ")
            when (s.side) {
                SIDE_SENT -> sb.append("Você: ")
                SIDE_MIXED -> { }
                else -> sb.append("Interlocutor: ")
            }
            sb.append(s.text).append("\n\n")
        }
        return sb.toString().trim()
    }

    fun shareText(context: Context, title: String, text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(i, "Compartilhar transcrição"))
        } catch (_: Exception) { }
    }

    fun shareAudio(context: Context, file: File) {
        if (!file.exists()) return
        try {
            val type = if (file.name.endsWith(".m4a")) "audio/mp4" else "audio/wav"
            val uri: Uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file,
            )
            val i = Intent(Intent.ACTION_SEND).apply {
                setType(type)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(i, "Compartilhar áudio")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } catch (_: Exception) { }
    }

    /** Exporta o WAV com um efeito aplicado (offline); devolve o arquivo pronto. */
    fun exportWithEffect(context: Context, source: File, def: VoiceDef): File? = try {
        val bytes = source.readBytes()
        if (bytes.size <= 44 || !String(bytes, 0, 4, Charsets.US_ASCII).contains("RIFF")) {
            null
        } else {
            val shorts = ShortArray((bytes.size - 44) / 2)
            ByteBuffer.wrap(bytes, 44, bytes.size - 44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)
            val fx = VoiceFx.apply(shorts, def.params, 16000)
            val dir = File(context.getExternalFilesDir(null), "exportado").apply { mkdirs() }
            val out = File(dir, source.nameWithoutExtension + "-" + def.id + ".wav")
            val w = WavWriter(out, 16000)
            try { w.append(fx, fx.size) } finally { w.close() }
            out
        }
    } catch (_: Throwable) {
        null
    }
}
