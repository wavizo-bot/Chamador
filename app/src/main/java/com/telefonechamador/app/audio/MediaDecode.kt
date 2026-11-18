package com.telefonechamador.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodifica a gravação nativa (AAC/M4A) para PCM mono e reamostra para 16 kHz. */
object MediaDecode {

    data class Decoded(val sampleRate: Int, val mono: ShortArray)

    fun decodeToMono(file: File): Decoded? {
        return try {
            val mex = MediaExtractor()
            mex.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until mex.trackCount) {
                val f = mex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) {
                mex.release()
                return null
            }
            mex.selectTrack(track)
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val chunks = ArrayList<ShortArray>()
            var rate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }
            var channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 1 }
            var eof = false

            while (!eof) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    val n = mex.readSampleData(ib, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eof = true
                    } else {
                        val pts = try { mex.sampleTime } catch (_: Exception) { 0L }
                        codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        mex.advance()
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, 10_000)
                while (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val shorts = ShortArray(sb.remaining())
                    sb.get(shorts)
                    chunks.add(toMono(shorts, channels))
                    codec.releaseOutputBuffer(outIdx, false)
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f2 = codec.outputFormat
                    rate = try { f2.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { rate }
                    channels = try { f2.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { channels }
                }
            }

            codec.stop()
            codec.release()
            mex.release()

            val total = chunks.sumOf { it.size }
            val all = ShortArray(total)
            var off = 0
            chunks.forEach {
                System.arraycopy(it, 0, all, off, it.size)
                off += it.size
            }
            Decoded(rate, all)
        } catch (_: Throwable) {
            null
        }
    }

    private fun toMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val out = ShortArray(samples.size / channels)
        for (i in out.indices) {
            var sum = 0
            for (c in 0 until channels) sum += samples[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /**
     * Qualquer áudio do aparelho: WAV lido direto do cabeçalho (RIFF/PCM,
     * o MediaExtractor não abre) e o resto pela via MediaCodec (m4a/aac/mp3…).
     * Usado pelo Laboratório de voz (ouvir com efeito, clonar, transcrever).
     */
    fun decodeAny(file: File): Decoded? {
        return if (isRiff(file)) readWav(file) else decodeToMono(file)
    }

    private fun isRiff(file: File): Boolean = try {
        file.inputStream().use { input ->
            val b = ByteArray(4)
            input.read(b) == 4 && String(b, Charsets.US_ASCII) == "RIFF"
        }
    } catch (_: Throwable) {
        false
    }

    /** Lê WAV PCM 8/16-bit (mono ou estéreo → mono) parseando os chunks RIFF. */
    fun readWav(file: File): Decoded? {
        return try {
            val b = file.readBytes()
            if (b.size < 44) return null
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != 0x46464952) return null // "RIFF"
            var pos = 12
            var channels = 1
            var rate = 0
            var bits = 16
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val size = bb.getInt(pos + 4)
                if (size < 0) break
                when (id) {
                    "fmt " -> {
                        if (pos + 24 > b.size) break
                        channels = bb.getShort(pos + 10).toInt().coerceIn(1, 2)
                        rate = bb.getInt(pos + 12)
                        bits = bb.getShort(pos + 22).toInt()
                    }
                    "data" -> {
                        dataOff = pos + 8
                        dataLen = if (size <= 0 || size > b.size - dataOff) b.size - dataOff else size
                    }
                }
                if (rate > 0 && dataOff >= 0) break
                pos += 8 + size + (size and 1)
            }
            if (rate <= 0 || dataOff < 0 || dataLen <= 0) return null
            when (bits) {
                16 -> {
                    val all = ShortArray(dataLen / 2)
                    bb.position(dataOff)
                    bb.asShortBuffer().get(all)
                    if (channels == 1) {
                        Decoded(rate, all)
                    } else {
                        val mono = ShortArray(all.size / channels)
                        for (i in mono.indices) {
                            var sum = 0
                            for (c in 0 until channels) sum += all[i * channels + c]
                            mono[i] = (sum / channels).toShort()
                        }
                        Decoded(rate, mono)
                    }
                }
                8 -> {
                    val mono = ShortArray(dataLen / channels)
                    for (i in mono.indices) {
                        var sum = 0
                        for (c in 0 until channels) {
                            sum += ((b[dataOff + i * channels + c].toInt() and 0xFF) - 128) shl 8
                        }
                        mono[i] = (sum / channels).toShort()
                    }
                    Decoded(rate, mono)
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun resampleTo16k(input: ShortArray, inRate: Int): ShortArray {
        if (inRate == 16000 || input.isEmpty()) return input
        val outLen = (input.size.toLong() * 16000 / inRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = inRate.toDouble() / 16000.0
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - pos.toInt()).toFloat()
            val a = input[i0]
            val b = input[i1]
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }
}
