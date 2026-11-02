package com.zizephus.solace.task

import com.zizephus.solace.core.run.Channel
import com.zizephus.solace.core.run.ChannelInput
import com.zizephus.solace.core.run.ChannelInputSink
import com.zizephus.solace.core.run.ChannelPeer
import com.zizephus.solace.core.run.Delivery
import com.zizephus.solace.core.run.SendOutcome

/** 手机上的任务卡片和通知：任务服务仍绘制现有界面，此适配器承接送达账本。 */
class LocalChannel(private val deliverLocal: suspend (ChannelPeer, Delivery) -> SendOutcome) : Channel {
    override val id = ID
    override val confirms = true
    private var sink: ChannelInputSink? = null

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome = deliverLocal(peer, delivery)
    suspend fun receive(input: ChannelInput) { sink?.receive(input) }

    companion object { const val ID = "local" }
}
