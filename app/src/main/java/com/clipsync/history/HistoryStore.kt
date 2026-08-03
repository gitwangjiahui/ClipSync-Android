package com.clipsync.history

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 本地历史存储。分别保存短信/剪贴板两类，各自最多 200 条，超出后丢弃最旧。
 * 存储介质：SharedPreferences 里放 JSON 字符串，简单可靠。
 */
object HistoryStore {

    private const val SP_NAME = "clipsync_history"
    private const val KEY_SMS = "sms_history"
    private const val KEY_CLIP = "clip_history"
    private const val MAX_SIZE = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(HistoryItem.serializer())

    @Serializable
    data class HistoryItem(
        val id: String,
        val kind: String,       // "sms" / "text" / "image" ...
        val text: String,       // 主体内容
        val preview: String,    // 短预览
        val direction: String,  // "in" 收到  / "out" 本机发出
        val ts: Long,           // 秒级时间戳
        val imageName: String = "" // 图片相对文件名（clip_images/ 下），非图片为空
    )

    fun addSms(ctx: Context, item: HistoryItem) = append(ctx, KEY_SMS, item)
    fun addClip(ctx: Context, item: HistoryItem) = append(ctx, KEY_CLIP, item)

    fun listSms(ctx: Context): List<HistoryItem> = read(ctx, KEY_SMS)
    fun listClip(ctx: Context): List<HistoryItem> = read(ctx, KEY_CLIP)

    /** 合并短信 + 剪贴板，按时间倒序 */
    fun listAll(ctx: Context): List<HistoryItem> {
        val merged = read(ctx, KEY_SMS) + read(ctx, KEY_CLIP)
        return merged.sortedByDescending { it.ts }
    }

    fun clearSms(ctx: Context) = write(ctx, KEY_SMS, emptyList())
    fun clearClip(ctx: Context) = write(ctx, KEY_CLIP, emptyList())
    fun clearAll(ctx: Context) {
        clearSms(ctx)
        clearClip(ctx)
    }

    private fun append(ctx: Context, key: String, item: HistoryItem) {
        val list = read(ctx, key).toMutableList()
        list.add(0, item) // 新的放最前面
        while (list.size > MAX_SIZE) list.removeAt(list.size - 1)
        write(ctx, key, list)
    }

    private fun read(ctx: Context, key: String): List<HistoryItem> {
        val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, key: String, list: List<HistoryItem>) {
        val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(key, json.encodeToString(listSerializer, list)).apply()
    }
}
