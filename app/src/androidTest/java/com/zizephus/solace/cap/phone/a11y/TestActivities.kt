package com.zizephus.solace.cap.phone.a11y

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** 带防截屏标志的页面:虚拟屏上画成黑色,但无障碍树仍可读。 */
class SecureTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(TextView(this).apply { text = "secure page"; textSize = 32f })
    }
}

/** 普通页面,用来模拟另一个 App 抢到前台。 */
class PlainTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "plain page"; textSize = 32f })
    }
}

/**
 * 点一下按钮,交给另一个进程里的服务([SpillLaunchService])带 NEW_TASK 打开 [EXTRA_TARGET] 那个 App 的启动页:
 * 和 App 经后台的进程(服务、SDK、跳转)打开另一个 App 一样,发起的进程在 Solace 的屏上没有页面,系统按默认放到主屏。
 * 用来重现「开到用户主屏上」。(同一进程里从应用上下文打开时,安卓按这个进程最上面页面所在的屏放,不会外溢。)
 */
class SpillTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET) ?: "com.android.settings"
        setContentView(android.widget.Button(this).apply {
            text = "open elsewhere"
            textSize = 32f
            setOnClickListener {
                startService(android.content.Intent(this@SpillTestActivity, SpillLaunchService::class.java).putExtra(EXTRA_TARGET, target))
            }
        })
    }

    companion object {
        const val EXTRA_TARGET = "target"
    }
}

/** 在单独的进程里打开 App 的启动页,打开就停。 */
class SpillLaunchService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(SpillTestActivity.EXTRA_TARGET)
        target?.let { packageManager.getLaunchIntentForPackage(it) }?.let {
            startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
