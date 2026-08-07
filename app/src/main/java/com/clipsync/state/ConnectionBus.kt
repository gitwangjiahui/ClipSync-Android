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

    /**
     * 是否因密码被管理端重置/封禁而被踢下线。
     * 为 true 时 UI 显示「修改密码」按钮，不自动重连。
     * 用户修改密码后调 [clearKicked] 清除。
     */
    @Volatile
    var kicked: Boolean = false
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
        // 进入连接中/已连接时清除踢下线标记
        if (state != STATE_CLOSED) kicked = false
        val snapshot: List<(String) -> Unit>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it(state) }
        }
    }

    /** 标记被踢下线（密码重置/封禁），UI 据此显示修改密码按钮 */
    fun publishKicked(reason: String? = null) {
        current = STATE_CLOSED
        failureReason = reason
        kicked = true
        val snapshot: List<(String) -> Unit>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it(STATE_CLOSED) }
        }
    }

    /** 用户修改密码后清除踢下线标记 */
    fun clearKicked() {
        kicked = false
        failureReason = null
    }

    fun addListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
        // 立即同步一次当前状态。不能用 mainHandler.post，否则会延迟到 onResume
        // 之后的代码（含 autoConnectIfNeeded → renderState(CONNECTING)）都执行完
        // 才跑，把刚渲染好的"连接中"覆盖回"未连接"。
        // addListener 目前只在主线程（onResume）调用，直接调用是安全的。
        l(current)
    }

    fun removeListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }
}
