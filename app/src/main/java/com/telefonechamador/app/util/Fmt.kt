package com.telefonechamador.app.util

import android.content.Context
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {

    // Regra 4.3 (Regras.txt, obrigatória): ":" NUNCA separa hora e minuto na
    // interface, em logs ou textos gerados — usar "h" (06h30) e, quando houver
    // segundos, o formato "20h31m22".
    fun mmss(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%02dh%02dm%02d", h, m, s)
        else String.format(Locale.US, "%02dm%02d", m, s)
    }

    fun duration(seconds: Int): String {
        if (seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%02dh%02dm", h, m)
            m > 0 -> String.format(Locale.US, "%02dm%02ds", m, s)
            else -> "${s}s"
        }
    }

    fun whenLabel(context: Context, at: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    fun stamp(at: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH'h'mm", Locale.getDefault()).format(Date(at))

    fun normalize(number: String): String = number.filter { it.isDigit() || it == '+' }
}
