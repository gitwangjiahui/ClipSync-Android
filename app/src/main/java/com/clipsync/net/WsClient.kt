package com.clipsync.net

import android.util.Log
import com.clipsync.model.ClientRole
import com.clipsync.model.Message
import com.clipsync.model.MessagePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket 长连接客户端：负责连到 ClipSync-Server，发消息、收消息、断线重连。
 * Android 客户端以 role=mobile 注册。
 */
class WsClient(
    private val serverUrl: String,
    private val token: String,
    private val deviceID: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var ws: WebSocket? = null
    private var running = false
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 收到的消息流 */
    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val incoming: SharedFlow<Message> = _incoming

    /** 连接状态流（StateFlow 保证新订阅者立即拿到当前状态） */
    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state

    enum class State { CONNECTING, OPEN, CLOSED }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 长连接
        .build()

    /** 连接未就绪时的消息缓存队列 */
    private val pendingQueue = mutableListOf<String>()

    fun start() {
        if (running) return
        running = true
        job = scope.launch { loop() }
    }

    fun stop() {
        running = false
        kickRequested = true
        job?.cancel()
        ws?.close(1000, "bye")
        ws = null
    }

    /** 回前台时调用：跳过退避等待立即重连 */
    @Volatile
    private var kickRequested = false

    fun kick() {
        kickRequested = true
    }

    private suspend fun loop() {
        // 断线自动重连：2s 起步指数退避，封顶 30s；连上后重置退避。
        // stop() 时 running=false 退出循环。
        var backoff = 2_000L
        while (running) {
            try {
                connectOnce()
                backoff = 2_000L
            } catch (e: Exception) {
                Log.w("ClipSync", "✗ 连接异常: ${e.message}")
            }
            if (!running) break
            _state.value = State.CONNECTING
            Log.i("ClipSync", "🔁 ${backoff / 1000}s 后自动重连")
            val deadline = System.currentTimeMillis() + backoff
            while (running && System.currentTimeMillis() < deadline) {
                if (kickRequested) break
                kotlinx.coroutines.delay(200)
            }
            kickRequested = false
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        running = false
        _state.value = State.CLOSED
    }

    /**
     * 建立一次 WebSocket 连接，挂起协程直到连接关闭或失败。
     * 使用 CompletableDeferred 替代 Thread.sleep，确保连接失败/关闭后能正确返回并重试。
     */
    private suspend fun connectOnce() {
        val url = "$serverUrl/ws?token=$token&device=$deviceID&role=${ClientRole.MOBILE}"
        val req = Request.Builder().url(url).build()
        _state.value = State.CONNECTING

        val closed = CompletableDeferred<Unit>()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("ClipSync", "🟢 已连接服务器")
                _state.value = State.OPEN
                flushPending()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val msg = json.decodeFromString<Message>(text)
                    _incoming.tryEmit(msg)
                }.onFailure { Log.w("ClipSync", "✗ 消息解析失败: ${it.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("ClipSync", "✗ 连接失败: ${t.message}")
                _state.value = State.CLOSED
                if (!closed.isCompleted) closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("ClipSync", "⚪ 连接已断开 (code=$code)")
                if (!closed.isCompleted) closed.complete(Unit)
            }
        })

        // 挂起协程直到连接关闭或失败，然后 loop() 会进入下一轮重试
        try {
            closed.await()
        } catch (_: Exception) {
            // 协程被取消（如 stop() 调用），静默退出
        }
    }

    /**
     * 通用发送方法。
     * @param type 走 MessageType 里的常量：NOTIFY_PC / NOTIFY_MOBILE / NOTIFY_ALL / CLIPBOARD
     * @param kind 业务子类型，比如 "sms_code" / "text" / "image" / "share"
     */
    /** @return true = 立即发出；false = 未连接，已进离线队列，连接恢复后自动发 */
    fun send(
        type: String,
        payloadText: String? = null,
        payloadData: String? = null,
        mime: String? = null,
        preview: String? = null,
        kind: String? = null,
        to: String = "*"
    ): Boolean {
        val msg = Message(
            id = UUID.randomUUID().toString(),
            type = type,
            from = deviceID,
            to = to,
            ts = System.currentTimeMillis() / 1000,
            payload = MessagePayload(
                text = payloadText,
                data = payloadData,
                mime = mime,
                preview = preview,
                kind = kind
            )
        )
        val raw = json.encodeToString(msg)
        val sent = ws?.send(raw)
        if (sent != true) {
            synchronized(pendingQueue) { pendingQueue.add(raw) }
            Log.w("ClipSync", "⏸ 暂存消息 (未连接): $type")
            return false
        }
        return true
    }

    /** 连接恢复后，把队列里的消息发出去 */
    private fun flushPending() {
        val toSend = synchronized(pendingQueue) {
            if (pendingQueue.isEmpty()) return
            val copy = pendingQueue.toList()
            pendingQueue.clear()
            copy
        }
        var success = 0
        toSend.forEach { raw ->
            if (ws?.send(raw) == true) success++
        }
        if (toSend.isNotEmpty()) {
            Log.i("ClipSync", "↑ 重发暂存消息 $success/${toSend.size} 条")
        }
    }
}
