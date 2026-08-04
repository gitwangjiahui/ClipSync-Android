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
import com.clipsync.net.AuthClient
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

        @Volatile
        private var currentWs: WsClient? = null

        /** 当前活跃的 WS 实例（服务没启动时为 null） */
        fun activeWs(): WsClient? = currentWs

        /** 回前台时调用：让 WS 跳过退避等待立即重连 */
        fun kick() { currentWs?.kick() }

        /**
         * 重启同步服务：登录 / 退出登录 / 改同步密码后调用，
         * 让新的 token 和加密密钥生效（连接参数是在 onCreate 时读取的）。
         */
        fun restart(ctx: android.content.Context) {
            val intent = Intent(ctx, SyncService::class.java)
            ctx.stopService(intent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private var ws: WsClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var incomingJob: Job? = null
    private var stateJob: Job? = null
    private var authJob: Job? = null

    /** 是否曾经进入过 CONNECTING/OPEN；用于区分"服务刚起、还没连"和"已连过又断了" */
    private var hasBeenConnectingOrOpen: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("ClipSync", "🟢 同步服务已启动")
        startForeground(buildNotification(ConnectionBus.STATE_CONNECTING))
        acquireWakeLock()
        com.clipsync.clipboard.ScreenshotWatcher.start(this)
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
        Log.i("ClipSync", "→ 正在连接: $server")

        if (!AuthClient.isLoggedIn(this) && !AuthClient.hasCredentials(this)) {
            Log.w("ClipSync", "✗ 尚未填写账号密码，无法连接（请在设置里填写）")
            ConnectionBus.publish(ConnectionBus.STATE_CLOSED)
            return
        }

        // 立即通知 UI 进入连接中状态：换 token 也算连接过程的一部分
        ConnectionBus.publish(ConnectionBus.STATE_CONNECTING)

        // 没有 token 就先用账号密码换一个，所以不需要单独的"登录"按钮。
        // 这一步走网络，必须在协程里做，不能阻塞 onCreate。
        serviceScope.launch {
            val token = AuthClient.ensureToken(this@SyncService, server)
            if (token.isNullOrBlank()) {
                Log.w("ClipSync", "✗ 账号密码校验失败，连接中止")
                ConnectionBus.publish(ConnectionBus.STATE_CLOSED)
                return@launch
            }
            openWs(server, token)
        }
    }

    /** 拿到 token 后真正建立 WebSocket 连接 */
    private fun openWs(server: String, token: String) {
        if (ws != null) return

        val deviceID = UUID.randomUUID().toString()
        // 加密开启且密码非空时传入同步密码；空串表示走明文
        val syncPassword = if (com.clipsync.crypto.PayloadCipher.isActive(this)) {
            com.clipsync.crypto.PayloadCipher.syncPassword(this)
        } else {
            ""
        }
        val wsClient = WsClient(server, token, deviceID, syncPassword).also { it.start() }
        ws = wsClient
        currentWs = wsClient

        // 绑定 ws 客户端，但不要重复 init（MainActivity 已经初始化过 clipboardManager）
        ClipboardManagerHelper.bindWs(wsClient)
        ClipboardManagerHelper.startListening()
        ClipSyncAccessibilityService.wsClient = wsClient

        subscribeIncoming(wsClient)
        subscribeState(wsClient)
        subscribeAuthFailure(wsClient, server)
    }

    /**
     * token 失效（WS 握手被 401 拒）时自动重新登录一次。
     *
     * 密码存在本地，所以这里能悄悄换一个新 token 接着连，用户不用管。
     * 若重新登录也失败（比如密码被改了），就停在未连接状态等用户去改设置。
     */
    private fun subscribeAuthFailure(wsClient: WsClient, server: String) {
        authJob?.cancel()
        authJob = serviceScope.launch {
            wsClient.authFailed.collect { failed ->
                if (!failed) return@collect
                Log.w("ClipSync", "🔒 token 失效，尝试用已保存的账号密码重新登录")
                AuthClient.clearSession(this@SyncService)
                if (!AuthClient.hasCredentials(this@SyncService)) {
                    Log.w("ClipSync", "✗ 无账号密码可用，请到设置里填写")
                    ConnectionBus.publish(ConnectionBus.STATE_CLOSED)
                    return@collect
                }
                val fresh = AuthClient.ensureToken(this@SyncService, server)
                if (fresh.isNullOrBlank()) {
                    Log.w("ClipSync", "✗ 重新登录失败，停止重连")
                    ConnectionBus.publish(ConnectionBus.STATE_CLOSED)
                    return@collect
                }
        // 换掉旧连接，用新 token 重新建立
                ws?.stop()
                ws = null
                currentWs = null
                openWs(server, fresh)
            }
        }
    }

    /** 订阅 WS 状态，实时更新通知栏 + 广播给 UI */
    private fun subscribeState(wsClient: WsClient) {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            // StateFlow 会立即发射当前值（初始为 CLOSED），
            // 这会让 UI 误判"连接失败"。用 dropWhile 跳过首个 CLOSED。
            var seenNonClosed = false
            wsClient.state.collect { s ->
                if (!seenNonClosed) {
                    if (s == WsClient.State.CLOSED) {
                        // 还没进入 CONNECTING/OPEN 前的 CLOSED 都是初始值，忽略
                        return@collect
                    }
                    seenNonClosed = true
                }
                val state = when (s) {
                    WsClient.State.OPEN -> ConnectionBus.STATE_OPEN
                    WsClient.State.CONNECTING -> ConnectionBus.STATE_CONNECTING
                    WsClient.State.CLOSED -> ConnectionBus.STATE_CLOSED
                }
                ConnectionBus.publish(state)
                // 更新通知栏文案
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(state))

                // 断线不杀服务：WsClient 内部有指数退避自动重连，
                // 回前台时 MainActivity 会 kick 加速重连
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
            val data = msg.payload.data
            if (data.isNullOrEmpty()) {
                Log.w("ClipSync", "↓ 收到图片但 data 为空，跳过")
                return
            }
            Log.i("ClipSync", "↓ 收到图片 (${data.length / 1024}KB base64)")
            val fileName = com.clipsync.clipboard.ClipboardImageStore.saveBase64(
                this, data, msg.payload.mime
            )
            if (fileName != null) {
                com.clipsync.clipboard.ClipboardImageStore.markReceived(fileName)
            }
            if (fileName == null) {
                Log.w("ClipSync", "✗ 图片落盘失败，仅记录历史")
                HistoryStore.addClip(
                    this,
                    HistoryStore.HistoryItem(
                        id = msg.id,
                        kind = "image",
                        text = "",
                        preview = msg.payload.preview ?: "[图片]",
                        direction = "in",
                        ts = msg.ts
                    )
                )
                return
            }
            HistoryStore.addClip(
                this,
                HistoryStore.HistoryItem(
                    id = msg.id,
                    kind = "image",
                    text = "",
                    preview = msg.payload.preview ?: "[图片]",
                    direction = "in",
                    ts = msg.ts,
                    imageName = fileName
                )
            )
            if (ClipboardManagerHelper.autoApplyEnabled) {
                com.clipsync.clipboard.ClipboardManagerHelper.suppressNext()
                val ok = com.clipsync.clipboard.ClipboardImageStore.writeToClipboard(this, fileName)
                android.widget.Toast.makeText(
                    this,
                    if (ok) "收到图片，已复制到剪贴板" else "收到图片，可在历史中查看",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                android.widget.Toast.makeText(this, "收到图片，可在历史中查看", android.widget.Toast.LENGTH_SHORT).show()
            }
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
            // 先标记远端文本，防止写入剪贴板后被监听器重新上传（回环）
            ClipboardManagerHelper.markRemoteText(text)
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
                } else if (kind == "image" && !data.isNullOrEmpty()) {
                    val fileName = com.clipsync.clipboard.ClipboardImageStore.saveBase64(
                        this, data, mime
                    )
                    HistoryStore.addClip(
                        this,
                        HistoryStore.HistoryItem(
                            id = UUID.randomUUID().toString(),
                            kind = "image",
                            text = "",
                            preview = preview,
                            direction = "out",
                            ts = System.currentTimeMillis() / 1000,
                            imageName = fileName ?: ""
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
        authJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        ws?.stop()
        ws = null
        currentWs = null
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
