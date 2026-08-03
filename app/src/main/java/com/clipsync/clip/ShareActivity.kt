package com.clipsync.clip

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.clipsync.service.SyncService

/**
 * 分享菜单入口：用户在任意 App 选中文字/图片 → 分享到 ClipSync → 上推云端
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        handleIntent(intent)
        // 回 OK 让支持结果码的来源分享页自行收起
        setResult(RESULT_OK)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val mime = intent.type ?: "text/plain"
                if (mime.startsWith("text/")) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    if (text.isBlank()) {
                        toast("分享内容为空")
                        return
                    }
                    val preview = text.take(20)
                    forwardToService(
                        type = "manual_share",
                        text = text,
                        mime = "text/plain",
                        preview = preview
                    )
                } else if (mime.startsWith("image/")) {
                    val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (uri == null) {
                        toast("未获取到图片")
                        return
                    }
                    // 拿持久化权限（finish 后后台线程仍可读）
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) { }
                    val appContext = applicationContext
                    Thread {
                        val result = com.clipsync.clipboard.ImageHelper.readAndCompress(appContext, uri)
                        if (result == null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(appContext, "图片处理失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@Thread
                        }
                        val (base64, outMime) = result
                        val svc = android.content.Intent(appContext, SyncService::class.java).apply {
                            action = SyncService.ACTION_SEND_SHARE
                            putExtra(SyncService.EXTRA_TYPE, "manual_share")
                            putExtra(SyncService.EXTRA_DATA, base64)
                            putExtra(SyncService.EXTRA_MIME, outMime)
                            putExtra(SyncService.EXTRA_PREVIEW, "图片 ${base64.length / 1024}KB")
                        }
                        appContext.startService(svc)
                        Log.i("ClipSync", "↑ 分享图片已转发 (${base64.length / 1024}KB)")
                    }.start()
                } else {
                    toast("不支持的内容类型")
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun forwardToService(type: String, text: String? = null, data: String? = null, mime: String, preview: String) {
        val svc = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_SEND_SHARE
            putExtra(SyncService.EXTRA_TYPE, type)
            text?.let { putExtra(SyncService.EXTRA_TEXT, it) }
            data?.let { putExtra(SyncService.EXTRA_DATA, it) }
            putExtra(SyncService.EXTRA_MIME, mime)
            putExtra(SyncService.EXTRA_PREVIEW, preview)
        }
        startService(svc)
        Log.i("ClipSync", "↑ 分享内容已转发 ($type)")
    }
}
