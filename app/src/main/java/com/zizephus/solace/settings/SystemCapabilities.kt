package com.zizephus.solace.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.zizephus.solace.backend.SolaceNotificationListener
import com.zizephus.solace.task.TriggerRuntime

/** DeepSeek Harness 可调用、但需要用户单独授予的手机能力。 */
enum class SystemCapability {
    NotificationListener,
    AllFiles,
    Sms,
    Contacts,
    Calendar,
    WriteSettings,
    Dnd,
    ExactAlarm,
}

/**
 * 能力自检页读到的一组独立开关。短信和日历按实际工具需要的多项权限合并展示:
 * 只有整组权限都可用时才算已授权。
 */
data class SystemCapabilities(
    val notificationListener: Boolean = false,
    val allFiles: Boolean = false,
    val sms: Boolean = false,
    val contacts: Boolean = false,
    val calendar: Boolean = false,
    val writeSettings: Boolean = false,
    val dnd: Boolean = false,
    val exactAlarm: Boolean = false,
) {
    fun isGranted(capability: SystemCapability): Boolean = when (capability) {
        SystemCapability.NotificationListener -> notificationListener
        SystemCapability.AllFiles -> allFiles
        SystemCapability.Sms -> sms
        SystemCapability.Contacts -> contacts
        SystemCapability.Calendar -> calendar
        SystemCapability.WriteSettings -> writeSettings
        SystemCapability.Dnd -> dnd
        SystemCapability.ExactAlarm -> exactAlarm
    }

    val grantedCount: Int get() = SystemCapability.entries.count(::isGranted)
    val allGranted: Boolean get() = grantedCount == SystemCapability.entries.size
}

/** 读取状态，并为每项能力提供对应的运行时权限或系统授权页。 */
object SystemCapabilityAccess {
    fun read(context: Context): SystemCapabilities {
        val app = context.applicationContext
        val notifications = app.getSystemService(NotificationManager::class.java)
        return SystemCapabilities(
            notificationListener = notificationListenerEnabled(app),
            allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager(),
            sms = app.hasPermissions(runtimePermissions(SystemCapability.Sms)),
            contacts = app.hasPermissions(runtimePermissions(SystemCapability.Contacts)),
            calendar = app.hasPermissions(runtimePermissions(SystemCapability.Calendar)),
            writeSettings = Settings.System.canWrite(app),
            dnd = notifications.isNotificationPolicyAccessGranted,
            exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        )
    }

    fun runtimePermissions(capability: SystemCapability): Array<String> = when (capability) {
        SystemCapability.Sms -> arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        SystemCapability.Contacts -> arrayOf(Manifest.permission.READ_CONTACTS)
        SystemCapability.Calendar -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        else -> emptyArray()
    }

    fun systemSettingsIntents(context: Context, capability: SystemCapability): List<Intent> {
        val app = context.applicationContext
        val packageUri = Uri.parse("package:${app.packageName}")
        return when (capability) {
            SystemCapability.NotificationListener -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            SystemCapability.AllFiles -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            SystemCapability.WriteSettings -> listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri))
            SystemCapability.Dnd -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            SystemCapability.ExactAlarm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(TriggerRuntime.exactAlarmSettings(app), Intent(Settings.ACTION_SETTINGS))
            } else {
                listOf(Intent(Settings.ACTION_SETTINGS))
            }
            SystemCapability.Sms,
            SystemCapability.Contacts,
            SystemCapability.Calendar,
            -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    private fun Context.hasPermissions(permissions: Array<String>): Boolean =
        permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun notificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val mine = ComponentName(context, SolaceNotificationListener::class.java)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .any { it == mine }
    }
}
