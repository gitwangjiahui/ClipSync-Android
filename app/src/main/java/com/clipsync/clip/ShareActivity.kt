package com.clipsync.clip

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.clipsync.service.SyncService
import java.io.ByteArrayOutputStream

/**
 * 分享菜单入口：用户在任意 App 选中文字/图片 → 分享到 ClipSync → 上推云端
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val mime = intent.type ?: "text/plain"
                if (mime.startsWith("text/")) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    if (text.isBlank()) return
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
                    uri ?: return
                    val base64 = uriToBase64(uri)
                    if (base64 != null) {
                        forwardToService(
                            type = "manual_share",
                            data = base64,
                            mime = mime,
                            preview = "图片 ${base64.length / 1024}KB"
                        )
                    }
                }
            }
        }
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

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("ClipSync", "✗ 图片处理失败: ${e.message}")
            null
        }
    }
}
