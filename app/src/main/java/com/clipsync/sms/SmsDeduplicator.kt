package com.clipsync.sms

/**
 * 短信上传去重器。
 *
 * SmsReceiver（SMS 广播，明文）和 NotificationSmsListener（通知，可能被 MIUI 打码为 ******）
 * 都会试图上传同一条短信。通知往往先到但内容是打码后的，广播稍后到但拿到的是明文。
 *
 * 策略：短时间内同一发件人的短信只允许上传一次；有明文优先明文。
 * - 广播先到 → 直接上传，记录发件人+时间戳
 * - 通知稍后到 → 若发件人已在最近 15 秒内传过，忽略通知（因为已经发过明文）
 * - 通知先到（比如 MIUI 有时广播被拦截）→ 直接上传，广播稍后到再补一条明文（覆盖 preview）
 */
object SmsDeduplicator {

    private const val WINDOW_MS = 15_000L

    @Volatile
    private var lastFrom: String = ""
    @Volatile
    private var lastTime: Long = 0L
    @Volatile
    private var lastWasPlaintext: Boolean = false

    /**
     * 判断是否允许上传。
     * @param from 发件人号码 / 联系人名
     * @param isPlaintext true = 来自 SMS 广播（明文）；false = 来自通知（可能打码）
     * @return true = 上传；false = 抑制
     */
    fun shouldUpload(from: String, isPlaintext: Boolean): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val sameSender = from == lastFrom && now - lastTime < WINDOW_MS

            // 同一发件人且上一条已是明文 → 通知里的星号版本没必要再发
            if (sameSender && lastWasPlaintext && !isPlaintext) {
                return false
            }
            // 同一发件人短时间内同类型 → 去重
            if (sameSender && (isPlaintext == lastWasPlaintext)) {
                return false
            }

            lastFrom = from
            lastTime = now
            lastWasPlaintext = isPlaintext
            return true
        }
    }
}
