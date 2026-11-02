package com.zizephus.solace.backend

import com.zizephus.solace.harness.core.engine.SolaceHarnessCapabilityDescriptors
import com.zizephus.solace.harness.core.engine.SolaceHarnessCapabilityRisk
import java.util.concurrent.CopyOnWriteArrayList

/** 一次能力评估的结论:[Allowed] 直接放行,[Confirm] 要用户点头,[Denied] 宿主级硬拒绝。 */
sealed interface SafetyVerdict {
    data class Allowed(val reason: String) : SafetyVerdict
    data class Confirm(val reason: String) : SafetyVerdict
    data class Denied(val reason: String) : SafetyVerdict
}

/**
 * Harness 设备桥的宿主级安全闸门:每个能力调用先过这里,再进 Harness 自己的审批流。
 * 硬拒绝名单、系统包只读、每次写入确认都不受任务级授权影响;授权只在任务内有效,换任务即失效。
 */
class HostSafetyPolicy(private val ownAppPackage: String = "com.zizephus.solace") {
    data class Unlocks(
        val systemPages: Boolean = false,
        val secretFields: Boolean = false,
        val ownApp: Boolean = false,
    )

    @Volatile var denyPackages: Set<String> = DEFAULT_DENY_PACKAGES
        private set
    @Volatile var confirmEveryWritePackages: Set<String> = DEFAULT_CONFIRM_WRITE_PACKAGES
        private set
    @Volatile var systemPackages: Set<String> = DEFAULT_SYSTEM_PACKAGES
        private set
    @Volatile var unlocks: Unlocks = Unlocks()
        private set

    /** 打开开关的唯一入口;不给的字段保持默认(关)。 */
    fun setPolicies(
        denyPackages: Set<String> = this.denyPackages,
        confirmEveryWritePackages: Set<String> = this.confirmEveryWritePackages,
        systemPackages: Set<String> = this.systemPackages,
        unlocks: Unlocks = this.unlocks,
    ) {
        this.denyPackages = denyPackages
        this.confirmEveryWritePackages = confirmEveryWritePackages
        this.systemPackages = systemPackages
        this.unlocks = unlocks
    }

    private class Grant(
        val taskId: String,
        val packageName: String?,
        val ability: String?,
        val once: Boolean,
    )

    private val grants = CopyOnWriteArrayList<Grant>()
    @Volatile private var activeTaskId: String? = null

    fun grantOnce(taskId: String, packageName: String, ability: String) {
        grants += Grant(taskId, packageName, ability, once = true)
    }

    fun grantPackageForTask(taskId: String, packageName: String) {
        grants += Grant(taskId, packageName, ability = null, once = false)
    }

    fun grantAllForTask(taskId: String) {
        grants += Grant(taskId, packageName = null, ability = null, once = false)
    }

    fun clearGrants(taskId: String? = null) {
        if (taskId == null) grants.clear() else grants.removeAll { it.taskId == taskId }
    }

    fun evaluate(
        ability: String,
        packageName: String?,
        taskId: String,
        risk: SolaceHarnessCapabilityRisk? = null,
    ): SafetyVerdict {
        if (activeTaskId != null && activeTaskId != taskId) {
            grants.clear()
            activeTaskId = taskId
        }
        val r = risk
            ?: SolaceHarnessCapabilityDescriptors.all.firstOrNull { it.name == ability }?.risk
            ?: SolaceHarnessCapabilityRisk.WRITE
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() }

        if (pkg != null && pkg in denyPackages) {
            return SafetyVerdict.Denied("宿主安全策略:$pkg 在禁止操作名单内,能力 $ability 一律不放行。")
        }
        if (pkg != null && pkg in systemPackages) {
            if (r == SolaceHarnessCapabilityRisk.READ) return SafetyVerdict.Allowed("系统包 $pkg 只读放行 $ability。")
            if (ability in SYSTEM_PAGE_ABILITIES && unlocks.systemPages) {
                return SafetyVerdict.Allowed("已解锁系统页面操作:系统包 $pkg 允许 $ability。")
            }
            return SafetyVerdict.Denied("系统包 $pkg 只读:$ability 属于 ${r.wire} 能力,宿主安全策略拒绝。")
        }
        if (pkg == ownAppPackage) {
            if (!unlocks.ownApp) {
                return SafetyVerdict.Denied("目标是宿主应用 $ownAppPackage,ownApp 未解锁,拒绝 $ability。")
            }
            return SafetyVerdict.Allowed("已解锁宿主应用操作:$ownAppPackage 允许 $ability。")
        }
        if (r == SolaceHarnessCapabilityRisk.SENSITIVE && !unlocks.secretFields) {
            return SafetyVerdict.Denied("敏感字段未解锁(secretFields 关闭),拒绝 $ability。")
        }

        val needsConfirm = r != SolaceHarnessCapabilityRisk.READ
        if (needsConfirm && pkg != null && pkg in confirmEveryWritePackages) {
            return SafetyVerdict.Confirm("对 $pkg 的每次 ${r.wire} 操作都要确认:$ability 需要用户点头。")
        }

        if (pkg != null) {
            val hit = grants.firstOrNull { g ->
                g.taskId == taskId &&
                    (g.packageName == null || g.packageName == pkg) &&
                    (g.ability == null || g.ability == ability)
            }
            if (hit != null) {
                if (hit.once) grants.remove(hit)
                return SafetyVerdict.Allowed("任务级授权放行:$pkg 的 $ability 已获本任务同意。")
            }
        }

        return if (needsConfirm) {
            SafetyVerdict.Confirm("$ability 属于 ${r.wire} 能力,需要用户确认。")
        } else {
            SafetyVerdict.Allowed("$ability 是只读能力,放行。")
        }
    }

    private companion object {
        val SYSTEM_PAGE_ABILITIES = setOf("app_launch", "app_open_link", "app_send_intent")

        val DEFAULT_DENY_PACKAGES = setOf(
            "com.eg.android.AlipayGphone",
            "com.tencent.midas.oversea",
            "com.unionpay",
            "com.chinamworld.main",
            "com.chinamworld.bocmbci",
            "com.android.bankabc",
            "com.icbc",
            "com.cmbchina.ccd.pluto.cmbActivity",
            "com.chinamworld.ccwt",
            "com.ccb.ccbnetpay",
            "com.mobilebank.cn",
            "com.chinamworld.mbspay",
            "com.hexin.plat.android",
            "com.ths.jiayou",
            "com.eastmoney.android.fund",
            "com.antfortune.wealth",
            "com.hexin.plat.androidHexin",
        )

        val DEFAULT_CONFIRM_WRITE_PACKAGES = setOf(
            "com.tencent.mm",
            "com.whatsapp",
            "jp.naver.line.android",
            "org.telegram.messenger",
            "com.sg.singularis",
            "com.facebook.orca",
            "com.skype.raider",
            "com.viber.voip",
            "kik.android",
            "com.snapchat.android",
        )

        val DEFAULT_SYSTEM_PACKAGES = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending",
            "com.android.server.telecom",
            "android",
        )
    }
}
