package com.zizephus.solace.core.cap.phone

import com.zizephus.solace.core.capability.Capability
import com.zizephus.solace.core.capability.CapabilityKey
import com.zizephus.solace.core.capability.CapabilityStatus
import com.zizephus.solace.core.capability.PromptEnv
import com.zizephus.solace.core.uistate.Surface
import com.zizephus.solace.core.observation.Look
import com.zizephus.solace.core.prompt.Prompts
import com.zizephus.solace.core.uistate.ScreenPort
import com.zizephus.solace.core.tool.AgentTool
import com.zizephus.solace.core.tool.ArgError
import com.zizephus.solace.core.tool.PACKAGE_NAME_RE
import com.zizephus.solace.core.tool.ToolContext
import com.zizephus.solace.core.tool.Verb
import com.zizephus.solace.core.uistate.sessions

/** 手机:手机那一套屏幕工具、打开 App 与链接。 */
internal class PhoneCapability(
    val screen: ScreenPort,
    /** 提速开关 deepLinks:提供打开链接的工具。 */
    val deepLinks: Boolean,
) : Capability {
    override val id = PHONE
    private val tools = PhoneGroup.tools

    override val folds = true

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = tools

    override val family = PHONE_FAMILY

    override val surface = Surface(PHONE_FAMILY, screen, declaredTarget = { app ->
        if (!PACKAGE_NAME_RE.matches(app)) throw ArgError("app", "must be an Android package name")
        app
    }, targetNote = "the exact package name of the app")

    /** 手机一段跟着打开、列出 App 的工具走;手机那一组收着时不给。 */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || tools.none { it.verb in SECTION_VERBS && it.name in env.offered }) return emptyList()
        return listOf(env.gate(Prompts.capability(PHONE)))
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    /** 再看一眼:此刻手机上那个 App 的界面状态。 */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = ctx.sessions[id]?.freshLook(withImage)

    /** 系统命令连着失败了几次;成功一次清零。 */
    var commandFailures = 0

    /** 本任务已提醒过系统命令换回专门工具:之后再做那几类事的命令不执行。 */
    var commandSteered = false

    companion object {
        val KEY = CapabilityKey<PhoneCapability>(PHONE)
        private val SECTION_VERBS = setOf(Verb.LaunchApp, Verb.ListApps)

        /** 手机一组可能提供的全部工具。 */
        fun tools(): List<AgentTool> = PhoneGroup.tools
    }
}

/** 本任务的手机能力。 */
internal val ToolContext.phone: PhoneCapability get() = this[PhoneCapability.KEY]!!
