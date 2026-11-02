package com.zizephus.solace.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * OPPO/ColorOS(含 OnePlus/realme)的后台保活专用入口。这些开关是厂商私有页面,
 * 没有公开 API 能查到是否已授权,只能把用户带到对应页面;页面可能随 ColorOS
 * 版本移动,所以每个入口都按候选顺序尝试,失败退回本 App 的系统详情页。
 */
object VendorKeepAlive {

    enum class Entry { Battery, AutoStart, BackgroundPopup }

    /** 一个候选目标:厂商包内的组件,或最后的系统详情页。 */
    sealed interface Target {
        data class Component(val pkg: String, val cls: String, val packageExtra: String? = null) : Target
        data object AppDetails : Target
    }

    data class Status(
        val batteryEntryAvailable: Boolean,
        val autoStartEntryAvailable: Boolean,
        val backgroundPopupEntryAvailable: Boolean,
        val manufacturer: String?,
    )

    private const val SAFECENTER = "com.coloros.safecenter"
    private const val POWERMANAGER = "com.coloros.oppoguardelf"
    private const val EXTRA_APP_PACKAGE = "appPackage"

    /** Build.MANUFACTURER 小写前缀匹配 OPPO 系;null 表示其他厂商,UI 不显示入口。 */
    fun vendorManufacturer(manufacturer: String?): String? {
        val n = manufacturer?.trim()?.lowercase() ?: return null
        return if (n.startsWith("oppo") || n.startsWith("oneplus") || n.startsWith("realme")) n else null
    }

    fun isVendorDevice(): Boolean = vendorManufacturer(Build.MANUFACTURER) != null

    /**
     * 各入口的候选页,按优先级排列。电池与自启动共用自启动管理页;
     * 「后台弹出界面」是 ColorOS 权限单页,用 extra 带上本 App 包名。
     */
    fun candidateTargets(entry: Entry, appPackage: String): List<Target> = when (entry) {
        Entry.Battery -> listOf(
            Target.Component(SAFECENTER, "com.coloros.safecenter.permission.startup.StartupProfileActivity"),
            Target.Component(SAFECENTER, "com.coloros.safecenter.permission.startup.StartupListActivity"),
            Target.Component(POWERMANAGER, "com.coloros.powermanager.fuel.FuelSummaryActivity"),
            Target.AppDetails,
        )
        Entry.AutoStart -> listOf(
            Target.Component(SAFECENTER, "com.coloros.safecenter.permission.startup.StartupProfileActivity"),
            Target.Component(SAFECENTER, "com.coloros.safecenter.permission.startup.StartupListActivity"),
            Target.AppDetails,
        )
        Entry.BackgroundPopup -> listOf(
            Target.Component(
                SAFECENTER,
                "com.coloros.safecenter.permission.singlepage.PermissionSinglePageActivity",
                packageExtra = EXTRA_APP_PACKAGE,
            ),
            Target.AppDetails,
        )
    }

    fun intentFor(target: Target, appPackage: String): Intent = when (target) {
        is Target.Component -> Intent().setComponent(ComponentName(target.pkg, target.cls)).apply {
            target.packageExtra?.let { putExtra(it, appPackage) }
        }
        Target.AppDetails ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appPackage, null))
    }

    fun readStatus(context: Context): Status {
        val app = context.applicationContext
        val pkg = app.packageName
        val manufacturer = vendorManufacturer(Build.MANUFACTURER)
        val pm = app.packageManager
        fun available(entry: Entry): Boolean = candidateTargets(entry, pkg).any { target ->
            if (target is Target.AppDetails) return@any true
            val intent = intentFor(target, pkg)
            runCatching { pm.resolveActivity(intent, 0) }.getOrNull() != null
        }
        return Status(available(Entry.Battery), available(Entry.AutoStart), available(Entry.BackgroundPopup), manufacturer)
    }

    /**
     * 依次尝试候选页,第一个能启动的就停。全部失败返回 false,并把最后一个
     * 尝试过的厂商组件名写进 [lastFailedComponent](都退到系统详情页时为 null)。
     */
    fun tryStart(context: Context, entry: Entry, lastFailedComponent: (String?) -> Unit): Boolean {
        val app = context.applicationContext
        val pkg = app.packageName
        var failed: String? = null
        for (target in candidateTargets(entry, pkg)) {
            if (target is Target.Component) failed = "${target.pkg}/${target.cls}"
            val started = runCatching { app.startActivity(intentFor(target, pkg)) }.isSuccess
            if (started) {
                lastFailedComponent(null)
                return true
            }
        }
        lastFailedComponent(failed)
        return false
    }
}
