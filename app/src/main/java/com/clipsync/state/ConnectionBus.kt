package com.clipsync.state

import android.os.Handler
import android.os.Looper

/**
 * 连接状态总线。SyncService 更新状态，MainActivity 注册监听刷新 UI。
 * 用静态回调实现，不引入新依赖。
 */
object ConnectionBus {

    const val STATE_CONNECTING = "connecting"
    const val STATE_OPEN = "open"
    const val STATE_CLOSED = "closed"

    @Volatile
    var current: String = STATE_CLOSED
        private set

    private val listeners = mutableListOf<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun publish(state: String) {
        current = state
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
