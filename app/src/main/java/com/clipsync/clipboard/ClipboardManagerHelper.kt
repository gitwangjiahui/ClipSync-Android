package com.clipsync.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clipsync.model.MessageType
import com.clipsync.net.WsClient

/**
 * Android 剪贴板管理器（前台监听 + 上传/写入）。
 */
object ClipboardManagerHelper {

    private const val TAG = "ClipSync"

    private var wsClient: WsClient? = null
    private var clipboardManager: ClipboardManager? = null
    private var context: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    var uploadEnabled: Boolean = true
    var autoApplyEnabled: Boolean = true

    fun loadPrefs(ctx: Context) {
        val sp = ctx.getSharedPreferences("clipsync", Context.MODE_PRIVATE)
        uploadEnabled = sp.getBoolean("upload_enabled", true)
        autoApplyEnabled = sp.getBoolean("auto_apply", true)
    }

    fun savePrefs(ctx: Context) {
        val sp = ctx.getSharedPreferences("clipsync", Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean("upload_enabled", uploadEnabled)
            .putBoolean("auto_apply", autoApplyEnabled)
            .apply()
    }

    private var suppressCount: Int = 0

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    fun init(appContext: Context, ws: WsClient) {
        context = appContext.applicationContext
        wsClient = ws
        clipboardManager = appContext.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    fun bindWs(ws: WsClient) {
        wsClient = ws
    }

    /** 只注册剪贴板变化监听（不主动读一次）。SyncService 启动时用。 */
    fun startListening() {
        clipboardManager?.removePrimaryClipChangedListener(listener)
        clipboardManager?.addPrimaryClipChangedListener(listener)
        Log.i(TAG, "🟢 剪贴板监听已开启")
    }

    fun onForeground() {
        clipboardManager?.removePrimaryClipChangedListener(listener)
        clipboardManager?.addPrimaryClipChangedListener(listener)
        Log.i(TAG, "🟢 剪贴板监听已开启（前台）")
        // onResume 时窗口还没拿到 input focus（MIUI 会拒绝），延迟 300ms 再读
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            readAndUpload()
        }, 300)
    }

    fun onBackground() {
        clipboardManager?.removePrimaryClipChangedListener(listener)
        Log.i(TAG, "⚪ 剪贴板监听已关闭")
    }

    fun suppressNext() {
        suppressCount += 1
    }

    fun applyRemoteText(text: String) {
        val cm = clipboardManager ?: return
        suppressNext()
        val clip = ClipData.newPlainText("ClipSync", text)
        cm.setPrimaryClip(clip)
        Log.i(TAG, "↓ 收到远端文本: ${text.take(40)}")
    }

    private fun onClipboardChanged() {
        if (!uploadEnabled) return
        if (suppressCount > 0) {
            suppressCount -= 1
            return
        }
        readAndUpload()
    }

    private fun readAndUpload() {
        if (!uploadEnabled) return
        val cm = clipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return

        val ts = try {
            clip.description.timestamp
        } catch (_: Throwable) { 0L }

        if (clip.description.hasMimeType("image/*")) {
            uploadImage(clip, ts)
        } else {
            uploadText(clip, ts)
        }
    }

    private fun uploadText(clip: ClipData, timestamp: Long) {
        val text = clip.getItemAt(0).text?.toString()
        if (text.isNullOrEmpty()) return

        if (!UploadDeduplicator.shouldUpload(text, timestamp)) return

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

    private fun uploadImage(clip: ClipData, timestamp: Long) {
        val uri = clip.getItemAt(0).uri ?: return
        val ctx = context ?: return

        val (base64, mime) = ImageHelper.readAndCompress(ctx, uri) ?: run {
            Log.w(TAG, "✗ 图片处理失败")
            return
        }

        if (!UploadDeduplicator.shouldUpload("img:${base64.length}", timestamp)) return

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
}
