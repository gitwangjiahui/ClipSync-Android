package com.clipsync.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.clipsync.accessibility.ClipSyncAccessibilityService
import com.clipsync.model.MessageType

/**
 * 透明一帧 Activity，用来在 MIUI/HyperOS 上"合法"读剪贴板。
 *
 * 关键点（前一版踩过的坑）：
 *   1. 窗口必须可以拿 input focus，不能设 FLAG_NOT_FOCUSABLE，否则
 *      ClipboardService 判定 "application is not in focus" 依然拒绝。
 *   2. 不能在 onCreate 里立刻读——那时窗口还没绘制、更没拿到 focus。
 *      必须在 onWindowFocusChanged(true) 里读，那才是真正"拿到前台焦点"的时刻。
 *   3. 窗口尽量透明无感：1×1 大小、完全透明背景、无标题、无动画。
 */
class ClipReaderActivity : Activity() {

    companion object {
        private const val TAG = "ClipSync"

        /** 从任意 Context 启动本 Activity。 */
        fun launch(context: Context) {
            val intent = Intent(context, ClipReaderActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "✗ 启动 ClipReaderActivity 失败: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 完全透明背景 + 1×1 无感窗口。注意：允许 focus，否则读不到。
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val lp = window.attributes
        lp.width = 1
        lp.height = 1
        lp.gravity = Gravity.START or Gravity.TOP
        lp.x = 0
        lp.y = 0
        lp.dimAmount = 0f
        window.attributes = lp
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // 一个空 View 就够占位
        setContentView(View(this))
        Log.i(TAG, "🪟 ClipReaderActivity 已创建，等待 window focus")
        // 兜底：如果 4 秒还没拿到 focus / 读到内容，主动 finish
        handler.postDelayed({
            if (!done) {
                Log.w(TAG, "⚠️ ClipReaderActivity 4s 内未成功读取，主动退出")
                safeFinish()
            }
        }, 4000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "🪟 onWindowFocusChanged hasFocus=$hasFocus done=$done")
        if (!hasFocus || done) return
        // 已经拿到前台焦点，读一次剪贴板
        readAndUpload()
        done = true
        // 读完立刻退出，让原来的前台 App 回到最上层
        handler.post { safeFinish() }
    }

    private fun safeFinish() {
        try { finish() } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** 拿到前台焦点后立刻读，读到就交给无障碍服务的上传通路。 */
    private fun readAndUpload() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Log.w(TAG, "⚠️ ClipReaderActivity 读到空剪贴板")
                return
            }
            val ts = try { clip.description.timestamp } catch (_: Throwable) { 0L }

            if (clip.description.hasMimeType("image/*")) {
                val uri = clip.getItemAt(0).uri ?: return
                val (base64, mime) = ImageHelper.readAndCompress(this, uri) ?: run {
                    Log.w(TAG, "✗ 图片处理失败")
                    return
                }
                if (!UploadDeduplicator.shouldUpload("img:${base64.length}", ts)) return
                val client = ClipSyncAccessibilityService.wsClient ?: return
                client.send(
                    type = MessageType.CLIPBOARD,
                    payloadData = base64,
                    mime = mime,
                    preview = "[图片]",
                    kind = "image"
                )
                Log.i(TAG, "↑ 上传图片 via ClipReader (${base64.length / 1024}KB)")
            } else {
                val text = clip.getItemAt(0).text?.toString()
                    ?: clip.getItemAt(0).coerceToText(this)?.toString()
                    ?: return
                if (text.isEmpty()) return
                if (!UploadDeduplicator.shouldUpload(text, ts)) return
                val client = ClipSyncAccessibilityService.wsClient ?: return
                client.send(
                    type = MessageType.CLIPBOARD,
                    payloadText = text,
                    mime = "text/plain",
                    preview = text.take(30),
                    kind = "text"
                )
                Log.i(TAG, "↑ 上传文本 via ClipReader: ${text.take(40)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ ClipReaderActivity 读取异常: ${e.message}")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
