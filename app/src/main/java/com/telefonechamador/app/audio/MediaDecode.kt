package com.telefonechamador.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
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
