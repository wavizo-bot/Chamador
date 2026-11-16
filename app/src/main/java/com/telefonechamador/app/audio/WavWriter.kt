package com.telefonechamador.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Grava PCM 16-bit mono em arquivo WAV (header corrigido no close). */
class WavWriter(file: File, private val sampleRate: Int) {

    private val raf = RandomAccessFile(file, "rw")
    private var frames = 0L

    init {
        raf.setLength(0)
        raf.write(header(0L))
    }

    fun append(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(samples, 0, count)
        raf.write(bb.array())
        frames += count
    }

    fun close() {
        try {
            raf.seek(0)
            raf.write(header(frames))
        } finally {
            raf.close()
        }
    }

    val hasContent: Boolean get() = frames > 0

    private fun header(frameCount: Long): ByteArray {
        val dataLen = frameCount * 2
        val total = 36 + dataLen
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(total.toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1.toShort())          // PCM
        b.putShort(1.toShort())          // mono
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2)         // byte rate
        b.putShort(2.toShort())          // block align
        b.putShort(16.toShort())         // bits
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataLen.toInt())
        return b.array()
    }
}
