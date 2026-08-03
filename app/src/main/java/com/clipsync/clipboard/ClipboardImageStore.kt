package com.clipsync.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 剪贴板图片落盘与写回工具。
 *
 * 背景：Android 端收到图片消息后不能像文本那样直接 setPrimaryClip(base64)，
 * 系统剪贴板只接受 URI / Intent / 文本。方案：
 *   1. base64 解码为图片文件，存到 filesDir/clip_images/（FileProvider 暴露）
 *   2. 生成 content:// URI，写入系统剪贴板（ClipData + grantUriPermission），
 *      这样其他 App 粘贴时能正常读取
 *   3. 文件名同时作为历史记录的回查依据（历史里不存大体积 base64）
 */
object ClipboardImageStore {

    private const val TAG = "ClipSync"
    private const val DIR_NAME = "clip_images"

    @Volatile
    var lastReceived: String? = null
    @Volatile
    var lastReceivedAt: Long = 0L

    /** 记录最近一次收到的图片，供回前台时补写剪贴板/预览回落 */
    fun markReceived(name: String) {
        lastReceived = name
        lastReceivedAt = System.currentTimeMillis()
    }

    /** 图片目录（不存在时创建） */
    fun imageDir(ctx: Context): File {
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 把 base64 图片数据落盘，返回相对文件名（不含路径）。
     * 失败返回 null。
     */
    fun saveBase64(ctx: Context, base64: String, mime: String?): String? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val ext = when {
                mime == "image/png" -> "png"
                mime == "image/webp" -> "webp"
                mime == "image/gif" -> "gif"
                else -> "jpg"
            }
            val name = "clip_${System.currentTimeMillis()}_${(bytes.size)}.$ext"
            val file = File(imageDir(ctx), name)
            file.writeBytes(bytes)
            Log.i(TAG, "🖼 图片已落盘: $name (${bytes.size / 1024}KB)")
            name
        } catch (e: Exception) {
            Log.w(TAG, "✗ 图片落盘失败: ${e.message}")
            null
        }
    }

    /** 相对文件名 → 绝对文件 */
    fun fileFor(ctx: Context, name: String): File = File(imageDir(ctx), name)

    /** 相对文件名 → FileProvider content:// URI */
    fun uriFor(ctx: Context, name: String): Uri? {
        return try {
            FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                fileFor(ctx, name)
            )
        } catch (e: Exception) {
            Log.w(TAG, "✗ 生成图片 URI 失败: ${e.message}")
            null
        }
    }

    /** content:// URI → 私有目录里的相对文件名（FileProvider path 末段） */
    fun nameFromUri(uri: Uri): String? {
        val p = uri.path ?: return null
        val name = p.substringAfterLast('/')
        return name.ifEmpty { null }
    }

    /**
     * 把图片写入系统剪贴板。
     * @return true = 写入成功
     */
    fun writeToClipboard(ctx: Context, fileName: String): Boolean {
        val uri = uriFor(ctx, fileName) ?: return false
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return try {
            val mime = when (fileName.substringAfterLast('.', "jpg")) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val clip = ClipData(
                ClipDescription("ClipSync 图片", arrayOf(mime)),
                ClipData.Item(uri)
            )
            cm.setPrimaryClip(clip)
            Log.i(TAG, "↓ 图片已写入剪贴板: $uri")
            true
        } catch (e: Exception) {
            Log.w(TAG, "✗ 图片写入剪贴板失败: ${e.message}")
            false
        }
    }

    /** 把外部图片文件（如截图）拷贝进本仓库，返回可被 FileProvider 暴露的文件名 */
    fun saveFromFile(ctx: Context, src: File): String? {
        return try {
            if (!src.exists()) return null
            val dir = File(ctx.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val ext = src.extension.ifEmpty { "jpg" }
            val name = "clip_${System.currentTimeMillis()}.$ext"
            src.copyTo(File(dir, name), overwrite = true)
            name
        } catch (e: Exception) {
            Log.w(TAG, "✗ 图片拷贝失败: ${e.message}")
            null
        }
    }

    /** 读取图片文件为 Bitmap（历史页缩略图用），等比缩放长边不超过 maxEdge */
    fun loadBitmap(ctx: Context, fileName: String, maxEdge: Int = 384): Bitmap? {
        val file = fileFor(ctx, fileName)
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val longEdge = maxOf(opts.outWidth, opts.outHeight)
            var sample = 1
            while (longEdge / sample > maxEdge * 2) sample *= 2
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts2) ?: return null
            val le = maxOf(bmp.width, bmp.height)
            if (le <= maxEdge) bmp
            else {
                val ratio = maxEdge.toFloat() / le
                val scaled = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true
                )
                if (scaled !== bmp) bmp.recycle()
                scaled
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ 图片缩略图解码失败: ${e.message}")
            null
        }
    }
}
