package com.clipsync.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clipsync.model.MessageType
import com.clipsync.net.WsClient

/** 剪贴板 MIME 常量，避免在代码里散落 image 通配符 mime 字符串导致编译器误判注释。 */
object ClipDescriptionMime {
    const val IMAGE = "image/*"
}

/**
 * Android 剪贴板管理器（前台监听 + 上传/写入）。
 */
object ClipboardManagerHelper {

    private const val TAG = "ClipSync"

    private var wsClient: WsClient? = null

    /** 推送结果三态 */
    enum class UploadResult { SENT, QUEUED, FAILED }

    /** 推送用的 WS：优先取正在运行的服务实例，避免绑定遗漏指向死实例 */
    private fun liveWs(): WsClient? =
        com.clipsync.service.SyncService.activeWs() ?: wsClient
    private var clipboardManager: ClipboardManager? = null
    private var context: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    var uploadEnabled: Boolean = false
    var autoApplyEnabled: Boolean = true

    fun loadPrefs(ctx: Context) {
        val sp = ctx.getSharedPreferences("clipsync", Context.MODE_PRIVATE)
        uploadEnabled = sp.getBoolean("upload_enabled", false)
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

    /** ClipReaderActivity 兜底节流：3s 内最多拉起一次，避免频繁抢焦点 */
    @Volatile
    private var lastFallbackLaunchAt: Long = 0L

    private fun launchReaderFallback(reason: String) {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (now - lastFallbackLaunchAt < 3000L) {
            Log.d(TAG, "⏸ 兜底节流：$reason 3s 内已触发过，跳过")
            return
        }
        lastFallbackLaunchAt = now
        Log.w(TAG, "✗ 读剪贴板失败（$reason），启动 ClipReaderActivity 兜底")
        ClipReaderActivity.launch(ctx)
    }

    /** 最近从远端收到的文本，用于防止回环（Mac→Android→Mac echo） */
    @Volatile
    private var lastRemoteText: String? = null

    /** 外部订阅剪贴板变化（用于刷新主页预览）。 */
    private val previewListeners = mutableListOf<() -> Unit>()

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    fun addPreviewListener(cb: () -> Unit) {
        previewListeners.add(cb)
    }

    fun removePreviewListener(cb: () -> Unit) {
        previewListeners.remove(cb)
    }

    fun notifyPreview() {
        handler.post {
            previewListeners.forEach { it() }
        }
    }

    fun init(appContext: Context, ws: WsClient?) {
        context = appContext.applicationContext
        wsClient = ws
        clipboardManager = appContext.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ScreenshotWatcher.start(appContext)
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
            retryWriteRecentImage()
            readAndUpload()
        }, 300)
    }

    /**
     * 回前台时把最近 60s 内的新内容补写进剪贴板，保证
     * 预览、推送、真实剪贴板三者一致：
     * - 有更新的截图 → 截图进剪贴板（MIUI 截图默认不进剪贴板）
     * - 否则后台收到的 PC 图片（后台 setPrimaryClip 被系统拒绝的）
     */
    private fun retryWriteRecentImage() {
        val ctx = context ?: return

        val shot = ScreenshotWatcher.peekPending()
        if (shot != null) {
            val cur = clipboardManager?.primaryClip
            val curTs = if (cur != null && cur.itemCount > 0) {
                try { cur.description.timestamp } catch (_: Throwable) { 0L }
            } else 0L
            val alreadyInClip = cur != null && cur.itemCount > 0 &&
                cur.getItemAt(0).uri?.toString() == shot.toString()
            if (!alreadyInClip && ScreenshotWatcher.pendingTimestamp() >= curTs) {
                suppressNext()
                val name = if (shot.scheme == "content") {
                    // 已在私有目录里（采纳时拷的副本）
                    ClipboardImageStore.nameFromUri(shot)
                } else {
                    ClipboardImageStore.saveFromFile(ctx, java.io.File(shot.path ?: ""))
                }
                if (name != null && ClipboardImageStore.writeToClipboard(ctx, name)) {
                    Log.i(TAG, "🔁 截图已写入剪贴板: $name")
                }
            }
            notifyPreview()
            return
        }

        retryWriteLastReceived()
    }

    /**
     * 后台时系统会拒绝 setPrimaryClip（"not in focus"），
     * 回前台后把最近 60s 内收到的图片补写进剪贴板。
     */
    private fun retryWriteLastReceived() {
        val ctx = context ?: return
        val name = ClipboardImageStore.lastReceived ?: return
        if (System.currentTimeMillis() - ClipboardImageStore.lastReceivedAt > 60_000) return

        // 有更新的截图待推送时不补写旧图，避免把截图预览顶掉
        if (ScreenshotWatcher.peekPending() != null &&
            ScreenshotWatcher.pendingTimestamp() >= ClipboardImageStore.lastReceivedAt
        ) {
            Log.i(TAG, "📷 有更新截图，跳过补写旧接收图")
            return
        }

        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val cur = clip.getItemAt(0).uri?.toString()
            if (cur != null && cur.contains(name)) return
            val ts = try { clip.description.timestamp } catch (_: Throwable) { 0L }
            // 用户在我们收图之后又复制了新内容，不覆盖
            if (ts >= ClipboardImageStore.lastReceivedAt && ts > 0) return
        }

        // 后台收图时那次 suppressNext 没被消费（写被系统拒绝、无事件），
        // 正好抵消这次补写产生的监听事件，不用再 suppress
        if (ClipboardImageStore.writeToClipboard(ctx, name)) {
            Log.i(TAG, "🔁 回前台补写最近收到的图片: $name")
            notifyPreview()
        }
    }

    fun onBackground() {
        clipboardManager?.removePrimaryClipChangedListener(listener)
        Log.i(TAG, "⚪ 剪贴板监听已关闭")
    }

    /**
     * 读取当前剪贴板的纯文本内容（仅预览，不上传）。
     * 返回 null 表示剪贴板为空或不是文本。
     */
    fun peekText(): String? {
        val cm = clipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    /**
     * 当前剪贴板的图片 URI（仅预览用，不上传）。
     * 优先真实剪贴板；剪贴板里没有可用内容时，回落到最近 60s 内的新截图
     * （MIUI 截图默认不进剪贴板，靠 ScreenshotWatcher 监听媒体库兜底）。
     */
    fun peekImageUri(): Uri? {
        val cm = clipboardManager ?: run {
            Log.w(TAG, "peekImageUri: clipboardManager 未初始化")
            return null
        }
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clipImageUri(clip)?.let { return it }
            // 剪贴板里有文本时不回落截图，文本预览优先
            val hasText = !clip.getItemAt(0).text.isNullOrBlank()
            if (hasText) return null
        }

        val shot = ScreenshotWatcher.peekPending()
        if (shot != null) {
            Log.i(TAG, "peekImageUri: 剪贴板无内容，回落新截图 $shot")
            return shot
        }
        return null
    }

    /** 只看真实剪贴板里取图片 URI（item.uri / intent / text 里的 content://） */
    private fun clipImageUri(clip: ClipData): Uri? {
        val item = clip.getItemAt(0)
        val mime = clip.description.hasMimeType(ClipDescriptionMime.IMAGE)
        val text = item.text?.toString()
        val intent = item.intent

        Log.i(TAG, "clipImageUri: itemCount=${clip.itemCount} mime_hasImage=$mime " +
                "uri=${item.uri} intent=${intent?.data} text=${text?.take(60)}")

        item.uri?.let { return it }
        intent?.data?.let { return it }

        if (!text.isNullOrBlank()) {
            val trimmed = text.trim()
            if (trimmed.startsWith("content://") || trimmed.startsWith("file://")) {
                return try {
                    Uri.parse(trimmed)
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    /** 只看真实剪贴板（不含截图回落），用于主页预览区分来源 */
    fun peekClipboardImageUri(): Uri? {
        val cm = clipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clipImageUri(clip)
    }

    /** 剪贴板内容的写入时间戳（和截图比新旧用） */
    fun clipTimestamp(): Long {
        val clip = clipboardManager?.primaryClip ?: return 0L
        if (clip.itemCount == 0) return 0L
        return try { clip.description.timestamp } catch (_: Throwable) { 0L }
    }

    /** 把任意图片 URI 解码为预览 Bitmap（等比缩放，长边不超过 maxEdge）。 */
    fun decodeImageUri(uri: Uri, maxEdge: Int = 512): Bitmap? {
        val ctx = context ?: return null
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                val longEdge = maxOf(bmp.width, bmp.height)
                if (longEdge <= maxEdge) bmp
                else {
                    val ratio = maxEdge.toFloat() / longEdge
                    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
                    if (scaled !== bmp) bmp.recycle()
                    scaled
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ 预览图片解码失败 uri=$uri err=${e.message}")
            null
        }
    }

    /**
     * 将剪贴板里的图片 URI 解码为预览 Bitmap（等比缩放，长边不超过 512）。
     * 仅供主页预览使用；上传走的是 ImageHelper.readAndCompress，独立处理。
     */
    fun peekImageBitmap(maxEdge: Int = 512): Bitmap? {
        val uri = peekImageUri() ?: return null
        return decodeImageUri(uri, maxEdge)
    }

    /**
     * 当前是否有可推送的内容（文本 / 剪贴板图片 / 最近 60s 内的新截图）。
     */
    fun hasContent(): Boolean {
        val cm = clipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            if (clipImageUri(clip) != null) return true
            if (!clip.getItemAt(0).text.isNullOrEmpty()) return true
        }
        return ScreenshotWatcher.peekPending() != null
    }

    /**
     * 手动推送当前剪贴板内容到服务器。
     * 与自动监听的 readAndUpload 不同，这里会强制放行（绕过内容去重），
     * 因为用户明确点了「推送」按钮，应当无条件执行。
     */
    /** @return SENT = 已立即发出；QUEUED = 进离线队列（连接恢复后自动发）；FAILED = 失败 */
    fun manualUpload(): UploadResult {
        android.util.Log.i(TAG, "manualUpload: 用户点击推送，强制上传")
        // 重置去重器，确保用户手动触发的推送一定能发出去
        UploadDeduplicator.reset()
        // 手动推送强制走读+上传，绕过 uploadEnabled 开关
        return readAndUpload(force = true)
    }

    fun suppressNext() {
        suppressCount += 1
    }

    /** 标记刚从远端收到的文本，防止写入剪贴板后被监听器重新上传（回环） */
    fun markRemoteText(text: String) {
        lastRemoteText = text
    }

    fun applyRemoteText(text: String) {
        val cm = clipboardManager ?: return
        suppressNext()
        val clip = ClipData.newPlainText("ClipSync", text)
        cm.setPrimaryClip(clip)
        Log.i(TAG, "↓ 收到远端文本: ${text.take(40)}")
    }

    private fun onClipboardChanged() {
        // 剪贴板变了 → 通知预览刷新（始终通知，即使 suppressCount>0，因为预览需要看到新内容）
        notifyPreview()

        if (!uploadEnabled) return
        if (suppressCount > 0) {
            suppressCount -= 1
            return
        }
        val result = readAndUpload()
        if (result == UploadResult.FAILED) {
            // 后台读不到内容（MIUI/HyperOS 限制）→ 延迟一小会儿重试一次，
            // 仍失败就拉透明 Activity 前台读（截图复制场景的关键兜底）
            handler.postDelayed({
                if (readAndUpload() == UploadResult.FAILED) launchReaderFallback("onClipboardChanged")
            }, 150)
        }
    }

    private fun readAndUpload(force: Boolean = false): UploadResult {
        if (!force && !uploadEnabled) return UploadResult.QUEUED
        val cm = clipboardManager ?: return UploadResult.FAILED
        val clip = cm.primaryClip
        val clipTs = if (clip != null && clip.itemCount > 0) {
            try { clip.description.timestamp } catch (_: Throwable) { 0L }
        } else 0L

        // 截图比剪贴板内容新 → 推截图（预览显示的也是它，保证所见即所推）
        val shot = ScreenshotWatcher.peekPending()
        if (shot != null && ScreenshotWatcher.pendingTimestamp() >= clipTs) {
            Log.i(TAG, "↑ 截图更新，推送截图 $shot")
            return uploadImage(shot, ScreenshotWatcher.pendingTimestamp())
        }

        if (clip != null && clip.itemCount > 0) {
            val imageUri = clipImageUri(clip)
            if (imageUri != null) {
                return uploadImage(imageUri, clipTs)
            }

            // 兜底：很多系统（MIUI/三星/鸿蒙）截图的 description 不带 image 通配符 mime，
            // 但实际是图片 —— 用 MIME + Uri + Intent 多重判断
            if (looksLikeImage(clip)) {
                Log.w(TAG, "⚠ 检测到疑似图片但无法取出 Uri，跳过本次推送")
                return UploadResult.QUEUED
            }

            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                return uploadText(clip, clipTs)
            }
        }

        if (shot != null) {
            Log.i(TAG, "↑ 剪贴板为空，推送最近截图 $shot")
            return uploadImage(shot, System.currentTimeMillis())
        }

        return UploadResult.FAILED
    }

    /**
     * 启发式判断当前剪贴板是不是图片。
     * 不同厂商 ROM（MIUI/三星/鸿蒙/ColorOS）对 description.hasMimeType image 不可靠，
     * 这里综合 MIME、Uri、Intent、文本长度等因素做兜底。
     */
    private fun looksLikeImage(clip: ClipData): Boolean {
        if (clip.description.hasMimeType(ClipDescriptionMime.IMAGE)) return true
        val item = clip.getItemAt(0)
        if (item.uri != null) return true
        if (item.intent != null) return true
        val text = item.text?.toString().orEmpty()
        // 部分系统把图片 URI 写到了 text 里（content://...）
        if (text.startsWith("content://") && text.contains("image")) return true
        return false
    }

    private fun uploadText(clip: ClipData, timestamp: Long): UploadResult {
        val text = clip.getItemAt(0).text?.toString()
        if (text.isNullOrEmpty()) return UploadResult.FAILED

        // 防回环：如果内容与刚从远端收到的相同，跳过上传
        if (text == lastRemoteText) {
            lastRemoteText = null
            Log.d(TAG, "⏸ 内容与远端收到的相同，跳过上传（防回环）")
            return UploadResult.QUEUED
        }

        if (!UploadDeduplicator.shouldUpload(text, timestamp)) return UploadResult.QUEUED

        val client = liveWs() ?: run {
            Log.e(TAG, "✗ 未连接服务器，无法上传")
            return UploadResult.FAILED
        }
        val ok = client.send(
            type = MessageType.CLIPBOARD,
            payloadText = text,
            mime = "text/plain",
            preview = text.take(30),
            kind = "text"
        )
        Log.i(TAG, "↑ 上传文本: ${text.take(40)} 立即发出=$ok")
        return if (ok) UploadResult.SENT else UploadResult.QUEUED
    }

    private fun uploadImage(uri: Uri, timestamp: Long): UploadResult {
        val ctx = context ?: return UploadResult.FAILED

        val (base64, mime) = ImageHelper.readAndCompress(ctx, uri) ?: run {
            Log.w(TAG, "✗ 图片处理失败")
            return UploadResult.FAILED
        }

        if (!UploadDeduplicator.shouldUpload("img:${base64.length}", timestamp)) {
            return UploadResult.QUEUED
        }

        val client = liveWs() ?: run {
            Log.e(TAG, "✗ 未连接服务器，无法上传")
            return UploadResult.FAILED
        }
        val ok = client.send(
            type = MessageType.CLIPBOARD,
            payloadData = base64,
            mime = mime,
            preview = "[图片]",
            kind = "image"
        )
        Log.i(TAG, "↑ 上传图片 (${base64.length / 1024}KB) 立即发出=$ok")
        return if (ok) UploadResult.SENT else UploadResult.QUEUED
    }
}
