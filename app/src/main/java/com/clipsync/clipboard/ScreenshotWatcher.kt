package com.clipsync.clipboard

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 截图监听：MIUI/HyperOS 上截图默认不进剪贴板，
 * 用户截屏后 60 秒内，主页预览卡展示这张截图、点"推送"即可发到电脑。
 *
 * 实现：FileObserver 直接监听截图目录（MIUI 固定写在
 * DCIM/Screenshots 或 Pictures/Screenshots），不依赖媒体库扫描
 * （MediaScanner 有几秒延迟，ContentObserver + query 会漏）。
 */
object ScreenshotWatcher {

    private const val TAG = "ClipSync"
    private const val PENDING_TTL_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    @Volatile
    private var appCtx: android.content.Context? = null

    @Volatile
    private var pendingUri: Uri? = null
    @Volatile
    private var pendingAt: Long = 0L

    private val observers = mutableListOf<FileObserver>()

    private fun screenshotDirs(): List<File> = listOf(
        File("/storage/emulated/0/DCIM/Screenshots"),
        File("/storage/emulated/0/Pictures/Screenshots"),
        File("/storage/emulated/0/DCIM/Camera")
    )

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (started) return
        started = true

        for (dir in screenshotDirs()) {
            if (!dir.exists()) {
                Log.i(TAG, "📷 截图目录不存在，跳过: ${dir.path}")
                continue
            }
            @Suppress("DEPRECATION")
            val fo = object : FileObserver(
                dir.path,
                FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
            ) {
                override fun onEvent(event: Int, name: String?) {
                    if (name == null) return
                    val lower = name.lowercase()
                    if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) return
                    Log.i(TAG, "📷 FileObserver 事件: ${dir.path}/$name (event=$event)")
                    val f = File(dir, name)
                    // 等文件写完再采纳
                    handler.postDelayed({ adopt(f) }, 400)
                    handler.postDelayed({ adopt(f) }, 1500)
                }
            }
            fo.startWatching()
            observers.add(fo)
            Log.i(TAG, "📷 已监听截图目录: ${dir.path}")
        }
    }

    private fun adopt(f: File) {
        try {
            if (!f.exists() || !f.isFile) return
            if (f.length() < 10_000) return
            if (System.currentTimeMillis() - f.lastModified() > PENDING_TTL_MS) return
            // MIUI 截图编辑器的"发送"会删掉原截图文件，
            // 采纳时立即拷进私有目录，后续预览/推送都用副本的 content URI
            val uri = copyToLocal(f) ?: Uri.fromFile(f)
            pendingUri = uri
            pendingAt = System.currentTimeMillis()
            Log.i(TAG, "📷 采纳新截图: ${f.name} (${f.length() / 1024}KB) → 进入待推送预览 uri=$uri")
            ClipboardManagerHelper.notifyPreview()
        } catch (e: Exception) {
            Log.w(TAG, "✗ 采纳截图异常: ${e.message}")
        }
    }

    private fun copyToLocal(f: File): Uri? {
        val ctx = appCtx ?: return null
        val name = ClipboardImageStore.saveFromFile(ctx, f) ?: return null
        return ClipboardImageStore.uriFor(ctx, name)
    }

    /** 只看不取（预览可重复调用；去重交给推送侧） */
    fun peekPending(): Uri? {
        val uri = pendingUri ?: return null
        if (System.currentTimeMillis() - pendingAt > PENDING_TTL_MS) {
            pendingUri = null
            return null
        }
        return uri
    }

    /** 待推送截图的采纳时间（用于和剪贴板内容比新旧） */
    fun pendingTimestamp(): Long = pendingAt

    fun clear() {
        pendingUri = null
    }
}
