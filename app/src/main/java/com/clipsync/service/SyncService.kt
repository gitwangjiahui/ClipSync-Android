package com.clipsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipsync.MainActivity
import com.clipsync.accessibility.ClipSyncAccessibilityService
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.history.HistoryStore
import com.clipsync.model.MessageType
import com.clipsync.net.WsClient
import com.clipsync.state.ConnectionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 前台常驻同步服务：维护 WebSocket 长连接，接收 SmsReceiver / ShareActivity 的发送请求。
 *
 * 通知栏文案会随 WebSocket 连接状态更新：
 *   - 已连接：ClipSync · 已连接
 *   - 连接中：ClipSync · 连接中…
 *   - 未连接：ClipSync · 未连接
 * 但是渠道优先级仍然是 IMPORTANCE_MIN，不会打扰用户。
 */
class SyncService : Service() {
    companion object {
        const val ACTION_SEND_SMS_CODE = "com.clipsync.SEND_SMS_CODE"
        const val ACTION_SEND_SHARE = "com.clipsync.SEND_SHARE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TYPE = "type"

        private const val CHANNEL_ID = "clipsync_fg"
        private const val NOTIFICATION_ID = 1
    }

    private var ws: WsClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var incomingJob: Job? = null
    private var stateJob: Job? = null

    /** 是否曾经进入过 CONNECTING/OPEN；用于区分"服务刚起、还没连"和"已连过又断了" */
    private var hasBeenConnectingOrOpen: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("ClipSync", "🟢 同步服务已启动")
        startForeground(buildNotification(ConnectionBus.STATE_CONNECTING))
        acquireWakeLock()
        connectWs()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ClipSync::SyncServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun connectWs() {
        // 幂等：已经在连接/已连接就不重复创建
        if (ws != null) {
            Log.i("ClipSync", "⏸ 同步服务已在运行，忽略重复启动")
            return
        }
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val server = sp.getString("server", null) ?: com.clipsync.BuildConfig.DEFAULT_SERVER
        val token = sp.getString("token", null) ?: com.clipsync.BuildConfig.DEFAULT_TOKEN
        Log.i("ClipSync", "→ 正在连接: $server")
        if (token.isBlank()) {
            Log.w("ClipSync", "✗ 未配置 token，无法连接")
            ConnectionBus.publish(ConnectionBus.STATE_CLOSED)
            return
        }
        // 立即通知 UI 进入连接中状态
        ConnectionBus.publish(ConnectionBus.STATE_CONNECTING)

        val deviceID = UUID.randomUUID().toString()
        val wsClient = WsClient(server, token, deviceID).also { it.start() }
        ws = wsClient

        ClipboardManagerHelper.init(applicationContext, wsClient)
        // 服务启动时注册剪贴板监听，但**不主动读一次**：
        // 服务启动瞬间读到的往往是之前就复制过的旧内容，会造成"点一次启动就推送一次"的错觉。
        ClipboardManagerHelper.startListening()
        ClipSyncAccessibilityService.wsClient = wsClient

        subscribeIncoming(wsClient)
        subscribeState(wsClient)
    }

    /** 订阅 WS 状态，实时更新通知栏 + 广播给 UI */
    private fun subscribeState(wsClient: WsClient) {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            wsClient.state.collect { s ->
                val state = when (s) {
                    WsClient.State.OPEN -> ConnectionBus.STATE_OPEN
                    WsClient.State.CONNECTING -> ConnectionBus.STATE_CONNECTING
                    WsClient.State.CLOSED -> ConnectionBus.STATE_CLOSED
                }
                ConnectionBus.publish(state)
                // 更新通知栏文案
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(state))

                // 连接失败/断开 → 自动停服，不做重连；用户在主界面再点一次才会重连。
                // 注意：先从 CONNECTING/OPEN 变为 CLOSED 才停，避免服务刚启动就被停掉。
                if (s == WsClient.State.CLOSED && hasBeenConnectingOrOpen) {
                    Log.i("ClipSync", "⚪ 连接已结束，自动停止同步服务")
                    stopSelf()
                }
                if (s == WsClient.State.CONNECTING || s == WsClient.State.OPEN) {
                    hasBeenConnectingOrOpen = true
                }
            }
        }
    }

    /** 收到消息 → 自动写入本机剪贴板 + 存历史 */
    private fun subscribeIncoming(wsClient: WsClient) {
        incomingJob?.cancel()
        incomingJob = serviceScope.launch {
            wsClient.incoming.collect { msg ->
                // Mac 端可能会用 clipboard / clipboard_text / clipboard_image 三种 type
                val isClipboardMsg = msg.type == MessageType.CLIPBOARD ||
                    msg.type.startsWith("clipboard")
                when {
                    isClipboardMsg -> handleClipboardIn(msg)
                    msg.type == MessageType.NOTIFY_MOBILE ||
                        msg.type == MessageType.NOTIFY_ALL -> handleGenericIn(msg)
                    else -> handleGenericIn(msg)
                }
            }
        }
    }

    private fun handleClipboardIn(msg: com.clipsync.model.Message) {
        val text = msg.payload.text.orEmpty()
        val isImage = msg.payload.mime?.startsWith("image/") == true ||
            msg.payload.kind == "image" ||
            msg.type == "clipboard_image"

        if (isImage) {
            val preview = msg.payload.preview ?: "[图片]"
            Log.i("ClipSync", "↓ 收到图片")
            HistoryStore.addClip(
                this,
                HistoryStore.HistoryItem(
                    id = msg.id,
                    kind = "image",
                    text = "",
                    preview = preview,
                    direction = "in",
                    ts = msg.ts
                )
            )
            // Android 端暂不支持图片写入剪贴板（需要 URI 权限），只存历史
            return
        }

        if (text.isEmpty()) return
        Log.i("ClipSync", "↓ 收到剪贴板: ${text.take(40)}")

        // 存历史
        HistoryStore.addClip(
            this,
            HistoryStore.HistoryItem(
                id = msg.id,
                kind = msg.payload.kind ?: "text",
                text = text,
                preview = msg.payload.preview ?: text.take(30),
                direction = "in",
                ts = msg.ts
            )
        )

        if (ClipboardManagerHelper.autoApplyEnabled) {
            val a11y = ClipSyncAccessibilityService.instance
            if (a11y != null) a11y.applyRemoteText(text)
            else ClipboardManagerHelper.applyRemoteText(text)
        }
    }

    private fun handleGenericIn(msg: com.clipsync.model.Message) {
        // 兜底：其他类型消息也进剪贴板历史，方便查看
        val text = msg.payload.text ?: msg.payload.preview ?: return
        HistoryStore.addClip(
            this,
            HistoryStore.HistoryItem(
                id = msg.id,
                kind = msg.payload.kind ?: msg.type,
                text = text,
                preview = msg.payload.preview ?: text.take(30),
                direction = "in",
                ts = msg.ts
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_SMS_CODE -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: "短信验证码"
                Log.i("ClipSync", "↑ 上传短信: ${text.take(40)}")
                ws?.send(
                    type = MessageType.NOTIFY_PC,
                    payloadText = text,
                    mime = "text/plain",
                    preview = preview,
                    kind = "sms_code"
                )
                // 存短信历史（出）
                HistoryStore.addSms(
                    this,
                    HistoryStore.HistoryItem(
                        id = UUID.randomUUID().toString(),
                        kind = "sms",
                        text = text,
                        preview = preview,
                        direction = "out",
                        ts = System.currentTimeMillis() / 1000
                    )
                )
            }
            ACTION_SEND_SHARE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                val data = intent.getStringExtra(EXTRA_DATA)
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "text/plain"
                val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: "分享内容"
                val kind = if (mime.startsWith("image/")) "image" else "text"
                Log.i("ClipSync", "↑ 上传分享: ${text?.take(40)}")
                ws?.send(
                    type = MessageType.CLIPBOARD,
                    payloadText = text,
                    payloadData = data,
                    mime = mime,
                    preview = preview,
                    kind = kind
                )
                if (!text.isNullOrEmpty()) {
                    HistoryStore.addClip(
                        this,
                        HistoryStore.HistoryItem(
                            id = UUID.randomUUID().toString(),
                            kind = kind,
                            text = text,
                            preview = preview,
                            direction = "out",
                            ts = System.currentTimeMillis() / 1000
                        )
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i("ClipSync", "⚪ 同步服务已停止")
        incomingJob?.cancel()
        stateJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        ws?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ConnectionBus.publish(ConnectionBus.STATE_CLOSED)
        super.onDestroy()
    }

    // MARK: - 通知构建

    private fun startForeground(notif: Notification) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "同步服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "ClipSync 后台常驻通知，保持消息同步在线"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(state: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val stateText = when (state) {
            ConnectionBus.STATE_OPEN -> "已连接"
            ConnectionBus.STATE_CONNECTING -> "连接中…"
            else -> "未连接"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync · $stateText")
            .setContentText("点击打开应用")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }
}
