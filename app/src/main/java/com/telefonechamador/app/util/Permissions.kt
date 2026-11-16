package com.telefonechamador.app.util

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {

    val core = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_NUMBERS,
    )

    val withCallLog: Array<String> =
        if (Build.VERSION.SDK_INT >= 29) core + Manifest.permission.READ_CALL_LOG else core

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, perms: List<String>): List<String> =
        perms.filter { !granted(context, it) }

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isDialer(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else false

    fun dialerAvailable(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_DIALER) == true
        } else false
}
