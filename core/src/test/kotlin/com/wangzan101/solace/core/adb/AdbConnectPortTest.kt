package com.wangzan101.solace.core.adb

import com.wangzan101.solace.core.adb.AdbConnectionException.Reason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AdbConnectPortTest {
    private class Cache(var port: Int? = null) : PortCache {
        override fun load() = port
        override fun save(port: Int) { this.port = port }
    }

    private val attempts = mutableListOf<Int>()
    private var mdnsAsked = 0

    private fun chain(prop: Int?, mdns: Int?, cache: Cache, propThrows: Boolean = false, mdnsAll: List<Int>? = null) = AdbConnectPort(
        property = { if (propThrows) throw SecurityException("denied") else prop },
        finder = { _, _ -> mdnsAsked++; mdnsAll ?: listOfNotNull(mdns) },
        cache = cache,
    )

    private fun opener(vararg ok: Int, unauthorized: Int? = null): (Int) -> String = { port ->
        attempts += port
        when (port) {
            unauthorized -> throw AdbConnectionException(Reason.UNAUTHORIZED, "no")
            in ok -> "conn@$port"
            else -> throw AdbConnectionException(Reason.CONNECT_FAILED, "closed")
        }
    }

    @Test
    fun propertyFirstAndMdnsNotAskedWhenItWorks() = runTest {
        val cache = Cache(1111)
        assertEquals("conn@4000", chain(4000, 5000, cache).connect(opener(4000)))
        assertEquals(listOf(4000), attempts)
        assertEquals(0, mdnsAsked)
        assertEquals(4000, cache.port)
    }

    @Test
    fun unreadableOrZeroPropertyFallsToMdnsThenCache() = runTest {
        val cache = Cache(1111)
        assertEquals("conn@5000", chain(0, 5000, cache).connect(opener(5000)))
        assertEquals(5000, cache.port)
        attempts.clear()
        assertEquals("conn@5000", chain(null, null, Cache(5000), propThrows = true).connect(opener(5000)))
        assertEquals(listOf(5000), attempts)
    }

    @Test
    fun stalePortsAreSkippedAndDuplicatesTriedOnce() = runTest {
        // 属性和 mDNS 都给了旧端口(都连不上),缓存里是对的
        val cache = Cache(6000)
        assertEquals("conn@6000", chain(4000, 4000, cache).connect(opener(6000)))
        assertEquals(listOf(4000, 6000), attempts)
    }

    @Test
    fun unauthorizedStopsTheChain() = runTest {
        try {
            chain(4000, 5000, Cache(6000)).connect(opener(5000, 6000, unauthorized = 4000))
            fail("expected UNAUTHORIZED")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.UNAUTHORIZED, e.reason)
        }
        assertEquals(listOf(4000), attempts)
    }

    @Test
    fun nothingFoundIsConnectFailed() = runTest {
        val cache = Cache()
        try {
            chain(null, null, cache).connect(opener())
            fail("expected CONNECT_FAILED")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertNull(cache.port)
        try {
            chain(4000, null, cache).connect(opener())
            fail("expected CONNECT_FAILED")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertNull(cache.port)
    }

    @Test
    fun everyMdnsPortIsTriedWhenAStaleRecordComesFirst() = runTest {
        // 关了又开无线调试:旧端口的记录还在,新的和它一起回来
        val cache = Cache(4000)
        assertEquals("conn@5000", chain(null, null, cache, mdnsAll = listOf(4000, 5000)).connect(opener(5000)))
        assertEquals(listOf(4000, 5000), attempts)
        assertEquals(5000, cache.port)
    }

    /** 每次连接结束都报一次各来源给了几个候选:连上的、全失败的都报。 */
    @Test
    fun reportsCandidateCounts() = runTest {
        val seen = mutableListOf<ConnectCandidates>()
        val ok = AdbConnectPort({ null }, { _, _ -> listOf(5000, 5001) }, Cache(5001), report = { seen += it })
        assertEquals("conn@5001", ok.connect(opener(5001)))
        assertEquals(ConnectCandidates(property = 0, mdns = 2, cache = 0, connected = true), seen.single())
        seen.clear()
        val none = AdbConnectPort({ null }, { _, _ -> emptyList() }, Cache(), report = { seen += it })
        try {
            none.connect(opener())
            fail()
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertEquals(ConnectCandidates(0, 0, 0, connected = false), seen.single())
    }
}
