package com.clipsync.state

import android.os.Handler
import android.os.Looper

/**
 * 连接状态总线。SyncService 更新状态，MainActivity 注册监听刷新 UI。
 * 用静态回调实现，不引入新依赖。
 *
 * 除状态本身，还带一条可选的 [failureReason]：连接失败的具体原因
 * （账密不对 / 网络不通 / 地址错误），供界面直接展示。
 */
object ConnectionBus {

    const val STATE_CONNECTING = "connecting"
    const val STATE_OPEN = "open"
    const val STATE_CLOSED = "closed"

    @Volatile
    var current: String = STATE_CLOSED
        private set

    /**
     * 最近一次连接失败的原因；null 表示没有已知失败。
     * 进入 CONNECTING / OPEN 时自动清空，避免旧错误留在界面上。
     */
    @Volatile
    var failureReason: String? = null
        private set

    private val listeners = mutableListOf<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param reason 仅在 [STATE_CLOSED] 时有意义；传 null 表示"正常断开，无错误"
     */
    @JvmOverloads
    fun publish(state: String, reason: String? = null) {
        current = state
        failureReason = if (state == STATE_CLOSED) reason else null
        val snapshot: List<(String) -> Unit>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it(state) }
        }
    }

    fun addListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
        // 立刻同步一次当前状态
        mainHandler.post { l(current) }
    }

    fun removeListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }
}
