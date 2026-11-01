package com.wangzan101.solace.core.adb

import kotlinx.coroutines.CancellationException

/** 上次连上的无线调试端口(App 私有存储)。 */
interface PortCache {
    fun load(): Int?
    fun save(port: Int)
}

/** 一次找连接端口各来源给出了几个候选(不含端口本身,诊断信息用)。 */
data class ConnectCandidates(val property: Int, val mdns: Int, val cache: Int, val connected: Boolean)

/**
 * 找本机无线调试的连接端口并连上。按顺序试:系统属性(读得到才用)、mDNS 查到的每一个、上次连上的端口。
 * 只靠 mDNS 不行:adbd 只在打开无线调试时登记一次服务,mDNS 守护进程重启后登记就没了;
 * mDNS 也可能同时查到已经失效的旧端口,所以查到几个试几个。
 * 端口每次开关无线调试都会变,所以缓存只排最后。
 * 钥匙不被认(UNAUTHORIZED)是真失败,不再换端口试;连不上、握手出错才试下一个。
 */
class AdbConnectPort(
    private val property: () -> Int?,
    private val finder: AdbServiceFinder,
    private val cache: PortCache,
    private val mdnsTimeoutMs: Long = 3_000,
    /** 每次连接结束(连上或失败)报一次各来源的候选个数。 */
    private val report: (ConnectCandidates) -> Unit = {},
) {
    /** 用 [open] 依次试候选端口,返回第一个连上的结果;一个候选都没有时抛 CONNECT_FAILED。 */
    suspend fun <T> connect(open: (Int) -> T): T {
        val tried = HashSet<Int>()
        var last: AdbConnectionException? = null
        val sources: List<suspend () -> List<Int>> = listOf(
            { listOfNotNull(property()) },
            { finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs) },
            { listOfNotNull(cache.load()) },
        )
        val counts = IntArray(sources.size)
        var connected = false
        try {
            for ((i, source) in sources.withIndex()) {
                // 读不到的来源(属性被拦、mDNS 起不来)当作没有,不算失败
                val ports = try {
                    source()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                counts[i] = ports.size
                for (port in ports) {
                    if (port !in 1..65535 || !tried.add(port)) continue
                    try {
                        return open(port).also { cache.save(port); connected = true }
                    } catch (e: AdbConnectionException) {
                        if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) throw e
                        last = e
                    }
                }
            }
            throw last ?: AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "no wireless debugging port found")
        } finally {
            report(ConnectCandidates(counts[0], counts[1], counts[2], connected))
        }
    }
}
