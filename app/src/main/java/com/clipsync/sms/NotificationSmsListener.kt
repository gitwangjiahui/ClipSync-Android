package com.clipsync.sms

import android.app.Notification
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.clipsync.service.SyncService

/**
 * 通知监听服务：读取系统弹出的短信通知（绕开 MIUI/HyperOS 的 SMS 广播拦截）。
 * 不做验证码匹配，直接把标题 + 正文全量上行。
 * 用户需在「设置 → 通知使用权」中授权本 App。
 */
class NotificationSmsListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifSmsListener"

        // 常见短信/通知 App 包名
        private val SMS_APPS = setOf(
            "com.android.mms",                    // AOSP / MIUI 系统短信
            "com.google.android.apps.messaging",  // Google 短信
            "com.samsung.android.messaging",
            "com.miui.securitycenter"             // 小米安全中心可能中转
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "🟢 通知监听已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 同步未启用就不拉服务，与 SmsReceiver 保持一致。
        if (!SyncService.isUserEnabled(this)) return

        val pkg = sbn.packageName ?: return
        // 只处理短信 App 的通知
        val isSmsApp = SMS_APPS.contains(pkg) ||
            pkg.contains("mms", ignoreCase = true) ||
            pkg.contains("message", ignoreCase = true)
        if (!isSmsApp) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        // 通知里的正文可能被系统折叠：EXTRA_BIG_TEXT 也不一定是全文，
        // 优先从系统 SMS 数据库拉最近 60 秒内的原始短信（拿到即为准）；
        // 拿不到再退回到通知的 bigText / text。
        val notifBody = if (bigText.isNotBlank()) bigText else text
        val (dbBody, dbFrom) = queryLatestSms()

        // 采信优先级：数据库原文 > 通知 bigText > 通知 text
        val body = when {
            !dbBody.isNullOrBlank() -> dbBody
            notifBody.isNotBlank() -> notifBody
            else -> return
        }

        // 检测正文是否被系统"锁屏防偷窥"打码：内容里连续 5 个及以上 * 视为打码
        val looksMasked = Regex("\\*{5,}").containsMatchIn(body)
        if (looksMasked) {
            Log.i(TAG, "⏸ 通知短信正文被系统打码（******），等待 SMS 广播的明文")
            return
        }

        // 检测正文是否被系统"通知栏预览折叠"截断：
        // MIUI/HyperOS 的通知栏对长短信会把开头切掉，只保留最后一段并在最前面加 "..." 或 "…"。
        // 这种情况下 body 是残缺的，也让位给广播的明文版本。
        val looksTruncated = body.trimStart().let { it.startsWith("...") || it.startsWith("…") }
        if (looksTruncated) {
            Log.i(TAG, "⏸ 通知短信被系统折叠（开头 …），等待 SMS 广播的明文")
            return
        }

        // 发件人：优先数据库里查到的（更真实），其次是通知标题（可能是联系人名）
        val sender = when {
            !dbFrom.isNullOrBlank() -> dbFrom
            title.isNotBlank() -> title
            else -> "未知"
        }
        // 完整文本：如果发件人是号码/联系人，前面补【发件人】方便下游识别
        val fullText = "【$sender】$body"
        val preview = "来自 $sender 的短信"
        if (!SmsDeduplicator.shouldUpload(sender, isPlaintext = false)) {
            Log.i(TAG, "⏸ 短信去重（通知）: $sender")
            return
        }
        Log.i(TAG, "↑ 收到短信(通知): ${fullText.take(40)}")

        val svc = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_SEND_SMS_CODE
            putExtra(SyncService.EXTRA_TEXT, fullText)
            putExtra(SyncService.EXTRA_PREVIEW, preview)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 短信转发失败: ${e.message}")
        }
    }

    /**
     * 从系统短信数据库查最近 60 秒内的一条收信，返回 (body, address)。
     * 这是绕过 MIUI 通知栏折叠 body 的关键：数据库里存的一定是完整原文。
     * 若未授予 READ_SMS 权限或查询失败，返回 (null, null)，调用方自行退回通知里的内容。
     */
    private fun queryLatestSms(): Pair<String?, String?> {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            )
            // 只看 60 秒内、且是接收类型的短信，防止拿到旧短信
            val since = System.currentTimeMillis() - 60_000L
            val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ?"
            val args = arrayOf(since.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 1"

            contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                    val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val b = if (bodyIdx >= 0) c.getString(bodyIdx) else null
                    val a = if (addrIdx >= 0) c.getString(addrIdx) else null
                    return b to a
                }
            }
            null to null
        } catch (e: Exception) {
            Log.w(TAG, "查询 SMS 数据库失败: ${e.message}")
            null to null
        }
    }
}
