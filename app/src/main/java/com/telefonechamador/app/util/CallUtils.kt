package com.telefonechamador.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast

object CallUtils {

    /**
     * Faz a ligação DE VERDADE, em cascata:
     *  1) somos o discador padrão → caminho do Telecom (número exato, tela integrada);
     *  2) permissão CALL_PHONE → ACTION_CALL (dispara direto);
     *  3) sem permissão → ACTION_DIAL + aviso explícito (nunca falha em silêncio).
     */
    fun placeCall(context: Context, number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        if (Permissions.isDialer(context)) {
            try {
                val tm = context.getSystemService(TelecomManager::class.java)
                if (tm != null) {
                    tm.placeCall(Uri.parse("tel:" + Uri.encode(n)), Bundle())
                    return
                }
            } catch (_: Exception) { }
        }
        if (Permissions.granted(context, Manifest.permission.CALL_PHONE)) {
            try {
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n)))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            context,
            "Permissão de ligar não concedida — abrindo o discador do sistema. Conceda em Ajustes → Ligações e contatos.",
            Toast.LENGTH_LONG,
        ).show()
        val d = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n)))
        d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(d)
    }

    fun endCall(context: Context): Boolean = try {
        val tm = context.getSystemService(TelecomManager::class.java)
        if (tm != null && Permissions.granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) tm.endCall() else false
    } catch (_: Exception) {
        false
    }

    fun answerRingingCall(context: Context): Boolean = try {
        val tm = context.getSystemService(TelecomManager::class.java)
        if (tm != null && Permissions.granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            tm.acceptRingingCall(); true
        } else false
    } catch (_: Exception) {
        false
    }

    fun audioManager(context: Context): AudioManager =
        context.getSystemService(AudioManager::class.java)

    fun setSpeakerOn(context: Context, on: Boolean) {
        val am = audioManager(context)
        if (Build.VERSION.SDK_INT >= 31) {
            val speaker = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val target = if (on) speaker else am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (target != null) try { am.setCommunicationDevice(target) } catch (_: Exception) { }
        } else {
            @Suppress("DEPRECATION")
            try { am.isSpeakerphoneOn = on } catch (_: Exception) { }
        }
    }

    fun isSpeakerOn(context: Context): Boolean {
        val am = audioManager(context)
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = try { am.communicationDevice } catch (_: Exception) { null } ?: return false
            return dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        @Suppress("DEPRECATION")
        return try { am.isSpeakerphoneOn } catch (_: Exception) { false }
    }

    fun setMuted(context: Context, muted: Boolean) {
        try { audioManager(context).isMicrophoneMute = muted } catch (_: Exception) { }
    }

    fun isMuted(context: Context): Boolean =
        try { audioManager(context).isMicrophoneMute } catch (_: Exception) { false }
}
