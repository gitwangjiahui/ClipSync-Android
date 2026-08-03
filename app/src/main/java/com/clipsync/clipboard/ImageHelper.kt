package com.clipsync.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 图片剪贴板处理工具。
 *
 * 流程：
 * 1. 通过 ContentResolver 读取 URI 指向的图片
 * 2. 缩放到长边不超过 1024 像素（避免 base64 太大撑爆 WebSocket）
 * 3. JPEG 压缩（质量 80），转 base64
 * 4. 返回 (base64String, mimeType) 供上传使用
 *
 * 注意：Android 10+ 后台读取剪贴板图片 URI 也会被系统限制，
 * 但前台 + 配合无障碍服务做兜底，至少前台场景能工作。
 */
object ImageHelper {

    private const val TAG = "ClipSync"
    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 80

    /**
     * 读取并压缩图片，返回 base64 字符串和 mime 类型。
     * 失败返回 null。
     */
    fun readAndCompress(context: Context, uri: Uri): Pair<String, String>? {
        try {
            val bytes = openStream(context, uri)?.use { it.readBytes() } ?: run {
                Log.w(TAG, "✗ 无法打开图片: $uri")
                return null
            }
            val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                Log.w(TAG, "✗ 图片解码失败")
                return null
            }
            val compressed = compressBitmap(originalBitmap)

            val baos = ByteArrayOutputStream()
            compressed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            // 不需要缩放时 compressed 与 originalBitmap 是同一个对象，
            // 必须等压缩完成后再回收，且只回收一次
            if (compressed !== originalBitmap) originalBitmap.recycle()
            compressed.recycle()
            val outBytes = baos.toByteArray()
            val base64 = Base64.encodeToString(outBytes, Base64.NO_WRAP)
            Log.i(TAG, "🖼 图片压缩: ${bytes.size / 1024}KB → ${outBytes.size / 1024}KB")
            return base64 to "image/jpeg"
        } catch (e: Exception) {
            Log.w(TAG, "✗ 图片处理异常: ${e.message}")
            return null
        }
    }

    private fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "✗ 打开图片流失败: ${e.message}")
            null
        }
    }

    /** 等比缩放，长边限制在 MAX_EDGE */
    private fun compressBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val longEdge = maxOf(w, h)
        if (longEdge <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / longEdge
        val nw = (w * ratio).toInt()
        val nh = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
