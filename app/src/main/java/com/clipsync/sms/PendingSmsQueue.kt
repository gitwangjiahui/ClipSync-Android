package com.clipsync.sms

import android.content.Context
import org.json.JSONArray

/**
 * 待发短信的持久化队列。
 *
 * SmsReceiver.onReceive 只有 ~10s 生命周期，而且 MIUI/HyperOS 会在划掉 App 后
 * 拒绝后台 Service 冷启动。为了验证码永远不丢：
 * 1) SmsReceiver 第一时间把短信写进这个队列（SharedPreferences，进程被杀也留着）
 * 2) 然后 goAsync().finish()，广播接收器安全退出
 * 3) 之后不管是 Service 成功启动还是用户重新打开 App，任何路径只要看到队列非空
 *    就会先 flush 出去，然后清空队列
 */
object PendingSmsQueue {

    private const val PREF = "clipsync_pending_sms"
    private const val KEY = "queue"

    data class Item(val text: String, val preview: String, val ts: Long)

    fun enqueue(context: Context, text: String, preview: String) {
        val list = load(context).toMutableList()
        list.add(Item(text, preview, System.currentTimeMillis()))
        save(context, list)
    }

    fun load(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                Item(
                    text = obj.optString("text", ""),
                    preview = obj.optString("preview", ""),
                    ts = obj.optLong("ts", 0L)
                )
            )
        }
        return out
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    private fun save(context: Context, list: List<Item>) {
        val arr = JSONArray()
        for (it in list) {
            val obj = org.json.JSONObject()
            obj.put("text", it.text)
            obj.put("preview", it.preview)
            obj.put("ts", it.ts)
            arr.put(obj)
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
