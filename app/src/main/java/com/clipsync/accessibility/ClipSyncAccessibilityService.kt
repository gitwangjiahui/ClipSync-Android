package com.clipsync.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.clipsync.model.MessageType
import com.clipsync.net.WsClient

/**
 * ClipSync 无障碍服务。绕开 MIUI 后台剪贴板读写限制。
 */
class ClipSyncAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipSync"

        @Volatile
        var instance: ClipSyncAccessibilityService? = null
            private set

        @Volatile
        var wsClient: WsClient? = null

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected =
                "${context.packageName}/${ClipSyncAccessibilityService::class.java.name}"
            return flat.split(":").any { TextUtils.equals(it, expected) }
        }

        fun openSystemSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var clipboard: ClipboardManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var suppressCount: Int = 0

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // 【已禁用剪贴板自动上传】
        // 反复调优后 MIUI 的后台限制无法在不牺牲整机流畅度的前提下绕开，
        // 因此暂时关闭剪贴板变化监听。分享菜单里的"分享给 ClipSync"仍可手动推送。
        // 如需恢复，取消下一行注释即可。
        // clipboard.addPrimaryClipChangedListener(listener)
        Log.i(TAG, "🟢 无障碍服务已启动（剪贴板自动上传已禁用）")
    }

    override fun onDestroy() {
        try {
            clipboard.removePrimaryClipChangedListener(listener)
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        Log.i(TAG, "⚪ 无障碍服务已关闭")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 【已禁用剪贴板自动上传】不再基于无障碍事件读取剪贴板。
        // 保留 onServiceConnected 里的服务生命周期，是为了让 applyRemoteText
        // （Mac → Android 的下行写剪贴板）继续可用。
    }

    private var readScheduled: Boolean = false
    /** 上次通过 ClipReaderActivity 兜底的时间戳，做节流用 */
    @Volatile
    private var lastFallbackLaunchAt: Long = 0L

    private fun scheduleReadIfNeeded() {
        // 事件路径的读取节流：150ms 内只发起一次。
        // 之所以要有一个短延迟，是因为无障碍事件（长按选中/菜单弹出）到 App 实际
        // 把内容写进剪贴板之间常常隔 30~100ms，直接读一半是空的。
        //
        // 注意：这条路径读到 null 时【坚决不拉 ClipReaderActivity 兜底】。
        // 因为无障碍事件只是"可能会有复制"的猜测（长按/选中文字都会触发，
        // 但用户根本还没点复制），如果每次都抢焦点，会让整个系统操作卡顿。
        // 真正可靠的"确认有新剪贴板内容"信号是 OnPrimaryClipChangedListener，
        // 只在那条路径上做兜底。
        if (readScheduled) return
        readScheduled = true
        handler.postDelayed({
            readScheduled = false
            tryReadClipboard()
        }, 150)
    }

    /** 3 秒内最多拉起一次 ClipReaderActivity，避免频繁抢焦点让整机卡 */
    private fun maybeLaunchFallback(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastFallbackLaunchAt < 3000L) {
            Log.d(TAG, "⏸ 兜底节流：$reason 3s 内已触发过，跳过")
            return
        }
        lastFallbackLaunchAt = now
        Log.w(TAG, "✗ 读取剪贴板失败，启动 ClipReaderActivity 兜底 (via $reason)")
        com.clipsync.clipboard.ClipReaderActivity.launch(this)
    }

    override fun onInterrupt() {}

    fun applyRemoteText(text: String) {
        if (text.isEmpty()) return
        suppressCount += 1
        try {
            val clip = ClipData.newPlainText("ClipSync", text)
            clipboard.setPrimaryClip(clip)
            Log.i(TAG, "↓ 收到远端文本: ${text.take(40)}")
        } catch (e: Exception) {
            Log.w(TAG, "✗ 写入剪贴板失败: ${e.message}")
        }
    }

    private fun onClipboardChanged() {
        if (suppressCount > 0) {
            suppressCount -= 1
            Log.d(TAG, "🔕 剪贴板变化被 suppress 抑制（自己写入引起）")
            return
        }
        // 剪贴板变化事件本身就是"真的有新写入"的强信号，直接读；
        // 但 MIUI 有时候第一次立即读到 null，加一次 100ms 后重试兜底。
        Log.i(TAG, "🔔 检测到剪贴板变化，尝试读取")
        val ok = tryReadClipboard()
        if (!ok) {
            Log.d(TAG, "🔁 首次读取失败，100ms 后重试")
            handler.postDelayed({
                val ok2 = tryReadClipboard()
                if (!ok2) {
                    maybeLaunchFallback("onClipboardChanged")
                }
            }, 100)
        }
    }

    /** 上次成功读到剪贴板时的系统时间戳（用于识别"这是不是一次真正的新复制"） */
    @Volatile
    private var lastClipTimestamp: Long = 0L

    /**
     * 尝试读取剪贴板并上传。
     * @return true = 成功读到并已交给上传流程；false = 读到 null / 拒绝，可考虑重试
     */
    private fun tryReadClipboard(): Boolean {
        if (suppressCount > 0) {
            suppressCount -= 1
            return true // 主动抑制视为"已处理"
        }

        val wl = acquireTempWakeLock()
        try {
            val clip = readClipFromClipboardManager()
            if (clip == null) {
                Log.w(TAG, "✗ 读取剪贴板返回 null（后台限制？稍后重试）")
                return false
            }

            // 取出 ClipData 的写入时间戳，交给上传去重器统一判断：
            // - 时间戳未变 → 只是 UI 事件读到旧内容，去重器会拦截
            // - 时间戳变化 → 一次真正的新复制（即使内容相同也放行）
            val ts = try {
                clip.description.timestamp
            } catch (_: Throwable) { 0L }
            if (ts != 0L && ts == lastClipTimestamp) {
                Log.d(TAG, "⏸ 剪贴板无新写入（时间戳未变），跳过")
                return true
            }
            if (ts != 0L) lastClipTimestamp = ts

            uploadClip(clip, ts)
            return true
        } finally {
            releaseTempWakeLock(wl)
        }
    }

    /** 取得一个短时部分唤醒锁，保证后台读取剪贴板与上传期间 CPU 不休眠，超时自动释放。 */
    private fun acquireTempWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipSync:ClipRead").apply {
                setReferenceCounted(false)
                acquire(10 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取唤醒锁失败: ${e.message}")
            null
        }
    }

    private fun releaseTempWakeLock(wl: PowerManager.WakeLock?) {
        try {
            if (wl?.isHeld == true) wl.release()
        } catch (_: Exception) {
        }
    }

    /** 读取剪贴板 ClipData。为空 / 被拦截 / 异常时返回 null。 */
    private fun readClipFromClipboardManager(): ClipData? {
        try {
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val item = clip.getItemAt(0)

            // 图片
            if (clip.description.hasMimeType("image/*") && item.uri != null) {
                return clip
            }
            // 文本
            val text = item.text?.toString() ?: item.coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) return clip
        } catch (e: Exception) {
            Log.w(TAG, "✗ 读取剪贴板异常: ${e.message}")
        }
        return null
    }

    private fun uploadClip(clip: ClipData, timestamp: Long) {
        if (clip.description.hasMimeType("image/*")) {
            uploadImageFromUri(clip, timestamp)
        } else {
            val text = clip.getItemAt(0).text?.toString()
                ?: clip.getItemAt(0).coerceToText(this)?.toString()
                ?: ""
            if (text.isNotEmpty()) uploadText(text, timestamp)
        }
    }

    private fun uploadImageFromUri(clip: ClipData, timestamp: Long) {
        val uri = clip.getItemAt(0).uri ?: return
        val (base64, mime) = com.clipsync.clipboard.ImageHelper.readAndCompress(this, uri) ?: run {
            Log.w(TAG, "✗ 图片处理失败")
            return
        }
        if (!com.clipsync.clipboard.UploadDeduplicator.shouldUpload("img:${base64.length}", timestamp)) return

        val client = wsClient
        if (client == null) {
            Log.e(TAG, "✗ 未连接服务器，无法上传")
            return
        }
        client.send(
            type = MessageType.CLIPBOARD,
            payloadData = base64,
            mime = mime,
            preview = "[图片]",
            kind = "image"
        )
        Log.i(TAG, "↑ 上传图片 (${base64.length / 1024}KB)")
    }

    private fun uploadText(text: String, timestamp: Long) {
        if (!com.clipsync.clipboard.UploadDeduplicator.shouldUpload(text, timestamp)) return

        val client = wsClient
        if (client == null) {
            Log.e(TAG, "✗ 未连接服务器，无法上传")
            return
        }
        client.send(
            type = MessageType.CLIPBOARD,
            payloadText = text,
            mime = "text/plain",
            preview = text.take(30),
            kind = "text"
        )
        Log.i(TAG, "↑ 上传文本: ${text.take(40)}")
    }
}
