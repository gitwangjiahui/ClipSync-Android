package com.clipsync.clipboard

import android.util.Log

/**
 * 全局上传去重器。
 *
 * 场景：Android 端有两个上传通道同时活跃：
 *   1. ClipboardManagerHelper.OnPrimaryClipChangedListener（剪贴板变化回调）
 *   2. ClipSyncAccessibilityService.onAccessibilityEvent（无障碍兜底）
 *
 * 两条路各自独立触发，如果不共享去重状态，同一次复制操作会被上传多次。
 *
 * 策略：内容变化才上传。只要剪贴板内容与上次上传完全一致就跳过，
 * 不依赖时间窗口，避免按钮点击等窗口变化读到旧内容后在时间窗口过后重发。
 * 当外部通过 ClipData 写入时间戳等方式确认发生了一次真正的新复制时，
 * 调用 reset() 放行相同内容的再次同步。
 */
object UploadDeduplicator {

    private const val TAG = "ClipSync"

    @Volatile
    private var lastContent: String = ""

    @Volatile
    private var lastTimestamp: Long = -1L

    /**
     * 检查该内容是否已被去重拦截，如果允许上传则自动记录。
     *
     * @param timestamp ClipData 的写入时间戳；传 0 表示时间戳不可用。
     *   有时间戳时以 (内容 + 时间戳) 作为这次复制的唯一身份：
     *   - 同内容 + 同时间戳 = 同一次复制被多个事件/多条通道重复触发 → 拦截
     *   - 同内容 + 不同时间戳 = 用户真的又复制了一次相同内容 → 放行
     *   时间戳不可用时退回纯内容比较（兜底，仅极少数旧设备）。
     * @return true = 允许上传，false = 被去重拦截
     */
    fun shouldUpload(content: String, timestamp: Long = 0L): Boolean {
        synchronized(this) {
            if (timestamp != 0L) {
                if (content == lastContent && timestamp == lastTimestamp) {
                    Log.d(TAG, "⏸ 同一次复制重复触发，跳过")
                    return false
                }
            } else if (content == lastContent) {
                Log.d(TAG, "⏸ 内容与上次相同，跳过")
                return false
            }
            lastContent = content
            lastTimestamp = timestamp
            return true
        }
    }

    fun reset() {
        synchronized(this) {
            lastContent = ""
            lastTimestamp = -1L
        }
    }
}
