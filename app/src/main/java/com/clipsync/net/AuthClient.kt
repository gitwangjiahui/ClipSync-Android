package com.clipsync.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 用用户名 + 密码换 token，取代原来"手填 Token"的流程。
 *
 * 服务端行为：
 *  - 当前账号没有客户端在线 → 新签发 token
 *  - 已有客户端在线 → 返回同一个 token（reused = true）
 * 所以手机和电脑各自登录同一账号，就会自动落进同一个同步分组。
 */
object AuthClient {
    private const val PREFS = "clipsync"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 登录成功后的会话信息 */
    data class Session(
        val token: String,
        val userId: Long,
        val username: String,
        val expiresAt: String?,
        /** true = 复用了已在线客户端的 token */
        val reused: Boolean,
        /** 服务端是否强制要求端到端加密 */
        val e2eeRequired: Boolean,
        val onlineDevices: Int
    )

    /** 业务失败（凭据错误、注册关闭等）带上服务端给的中文提示 */
    class AuthException(message: String) : Exception(message)

    /**
     * 把 ws:// / wss:// 转成 http:// / https://。
     * 设置页里填的是 WebSocket 地址，认证接口走同一端口的 HTTP。
     */
    fun httpBase(serverUrl: String): String {
        var s = serverUrl.trim().removeSuffix("/")
        return when {
            s.startsWith("wss://") -> "https://" + s.removePrefix("wss://")
            s.startsWith("ws://") -> "http://" + s.removePrefix("ws://")
            s.startsWith("http://") || s.startsWith("https://") -> s
            else -> "http://$s"
        }
    }

    suspend fun login(serverUrl: String, username: String, password: String): Session =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
            val json = post(serverUrl, "/auth/login", body, token = null)
            val token = json.optString("token")
            if (token.isEmpty()) throw AuthException("响应缺少 token")
            Session(
                token = token,
                userId = json.optLong("user_id"),
                username = json.optString("username", username),
                expiresAt = json.optString("expires_at").ifEmpty { null },
                reused = json.optBoolean("reused", false),
                e2eeRequired = json.optBoolean("e2ee_required", false),
                onlineDevices = json.optInt("online_devices", 0)
            )
        }

    suspend fun register(serverUrl: String, username: String, password: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
            post(serverUrl, "/auth/register", body, token = null)
            Unit
        }

    /** GET /auth/session —— 启动时确认本地 token 还有效 */
    suspend fun checkSession(serverUrl: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(httpBase(serverUrl) + "/auth/session")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            runCatching {
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrElse {
                Log.w("ClipSync", "✗ 会话检查失败: ${it.message}")
                false
            }
        }

    suspend fun logout(serverUrl: String, token: String) = withContext(Dispatchers.IO) {
        runCatching { post(serverUrl, "/auth/logout", JSONObject(), token) }
        Unit
    }

    // ===== 本地会话存取 =====

    fun savedToken(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun savedUsername(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("username", "") ?: ""

    fun saveSession(ctx: Context, session: Session) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", session.token)
            .putString("username", session.username)
            .apply()
    }

    fun clearSession(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("token")
            .apply()
    }

    fun isLoggedIn(ctx: Context): Boolean = savedToken(ctx).isNotEmpty()

    // ===== 内部 =====

    private fun post(
        serverUrl: String,
        path: String,
        body: JSONObject,
        token: String?
    ): JSONObject {
        val builder = Request.Builder()
            .url(httpBase(serverUrl) + path)
            .post(body.toString().toRequestBody(jsonMedia))
        if (token != null) builder.header("Authorization", "Bearer $token")

        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                val msg = json.optString("error").ifEmpty { "服务端返回 ${resp.code}" }
                throw AuthException(msg)
            }
            return json
        }
    }
}
