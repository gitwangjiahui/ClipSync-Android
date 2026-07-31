package com.clipsync.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.clipsync.history.HistoryStore
import com.clipsync.service.SyncService
import java.util.UUID

/**
 * 短信接收器：收到短信 → 拼完整短信 → 存本地历史 + 交给 SyncService 上行
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val fromNumber = messages.firstOrNull()?.originatingAddress ?: "未知号码"
        val body = buildString {
            for (m in messages) append(m.displayMessageBody ?: "")
        }
        if (body.isBlank()) return

        val fullText = "【$fromNumber】$body"
        val preview = "来自 $fromNumber 的短信"

        if (!SmsDeduplicator.shouldUpload(fromNumber, isPlaintext = true)) {
            Log.i("ClipSync", "⏸ 短信去重（广播）: $fromNumber")
            return
        }
        Log.i("ClipSync", "↑ 收到短信(明文): ${fullText.take(40)}")

        // 转发给后台服务
        val svc = Intent(context, SyncService::class.java).apply {
            action = SyncService.ACTION_SEND_SMS_CODE
            putExtra(SyncService.EXTRA_TEXT, fullText)
            putExtra(SyncService.EXTRA_PREVIEW, preview)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.e("ClipSync", "✗ 短信转发失败: ${e.message}")
        }
    }
}
