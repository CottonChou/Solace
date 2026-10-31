package com.wangzan101.mika.task

/**
 * External(DSH)任务通知的纯逻辑:通知 id 的 slot 分配、锁屏公开版本标题、审批超时。
 * 不依赖 Android 类,便于在纯 JVM 单测里验证。
 */
internal object ExternalNotifyPolicy {
    /** external 进度通知的会话分组;groupSummary 用同一个组。 */
    const val GROUP = "mika-dsh-tasks"

    /** 审批超时:WaitingConfirmation 超过这个时间没人处理就按拒绝完成。 */
    const val CONFIRM_TIMEOUT_MS = 300_000L

    /** 每个 external 任务占 3 个连续 id;分组摘要用固定 slot。 */
    const val ID_EXTERNAL_FIRST = 20_000
    const val ID_EXTERNAL_RANGE = 10_000
    const val SUMMARY_ID = ID_EXTERNAL_FIRST - 1

    /** 锁屏公开版本的固定标题;reason/message/审批内容一概不进 public version。 */
    const val TITLE_ATTENTION = "任务需要你"
    const val TITLE_PROGRESS = "任务进行中"

    /** slot 0:常驻进度通知(前台服务 startForeground 用)。 */
    fun progressId(key: String): Int = base(key)

    /** slot 1:WaitingConfirmation / WaitingTakeover 共用的 attention 通知。 */
    fun attentionId(key: String): Int = base(key) + 1

    /** slot 2:WaitingInput;与 attention 分开,同一任务的两类提醒不互相顶掉。 */
    fun inputId(key: String): Int = base(key) + 2

    fun summaryTitle(count: Int): String = "$count 个 DeepSeek Harness 任务进行中"

    /** 脱敏策略:[sensitive] 标注调用处原本会放动态内容,但公开版本一律丢弃,只回固定标题。 */
    fun publicTitle(attention: Boolean, sensitive: String? = null): String =
        if (attention) TITLE_ATTENTION else TITLE_PROGRESS

    fun approvalExpired(postedAtMs: Long, nowMs: Long): Boolean = nowMs - postedAtMs >= CONFIRM_TIMEOUT_MS

    private fun base(key: String): Int = ID_EXTERNAL_FIRST + (key.hashCode().mod(ID_EXTERNAL_RANGE) * 3)
}
