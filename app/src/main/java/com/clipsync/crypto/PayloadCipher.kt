package com.clipsync.crypto

import android.content.Context
import android.util.Log
import com.clipsync.model.MessagePayload
import kotlinx.serialization.json.Json

/**
 * 在「业务 payload」和「加密信封」之间来回转换。
 *
 * 发送：MessagePayload(明文) → JSON → AES-GCM → MessagePayload(仅 enc + 占位 preview)
 * 接收：MessagePayload(含 enc) → 解密 → MessagePayload(明文)
 *
 * 密钥按密码缓存，避免每条消息都跑一次 20 万轮 PBKDF2。
 * 对应 Mac 端 E2EEEnvelope.swift 的 PayloadCipher。
 */
object PayloadCipher {
    /** 加密消息在 UI / 日志里的占位文案（不含任何真实内容） */
    const val PLACEHOLDER = "🔒 加密消息"

    private const val PREFS = "clipsync"
    private const val KEY_SYNC_PASSWORD = "sync_password"
    private const val KEY_E2EE_ENABLED = "e2ee_enabled"

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Volatile
    private var cachedPassword: String? = null

    @Volatile
    private var cachedKey: ByteArray? = null

    private val lock = Any()

    // ===== 设置读写 =====

    /** 本机同步密码；只存在 SharedPreferences，从不上传。 */
    fun syncPassword(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SYNC_PASSWORD, "") ?: ""

    fun setSyncPassword(ctx: Context, password: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SYNC_PASSWORD, password).apply()
        invalidateKeyCache()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_E2EE_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_E2EE_ENABLED, enabled).apply()
    }

    /** 加密实际生效需要同时满足：开关打开 + 密码非空 */
    fun isActive(ctx: Context): Boolean = isEnabled(ctx) && syncPassword(ctx).isNotEmpty()

    // ===== 密钥缓存 =====

    fun keyFor(password: String): ByteArray? {
        if (password.isEmpty()) return null
        synchronized(lock) {
            val cached = cachedKey
            if (cached != null && cachedPassword == password) return cached
            val derived = E2EECrypto.deriveKey(password) ?: return null
            cachedPassword = password
            cachedKey = derived
            return derived
        }
    }

    fun invalidateKeyCache() {
        synchronized(lock) {
            cachedPassword = null
            cachedKey = null
        }
    }

    /** 当前密钥指纹，设置页展示用 */
    fun fingerprint(password: String): String? =
        keyFor(password)?.let { E2EECrypto.fingerprint(it) }

    // ===== 发送方向 =====

    /**
     * 把明文 payload 封成密文 payload。
     * 未设置密码时原样返回（服务端 e2ee.require=false 才会接受）。
     */
    fun encrypt(payload: MessagePayload, password: String): MessagePayload {
        val key = keyFor(password) ?: return payload
        val plain = runCatching { json.encodeToString(MessagePayload.serializer(), payload) }
            .getOrNull() ?: return payload
        val env = E2EECrypto.seal(plain.toByteArray(Charsets.UTF_8), key)
        if (env == null) {
            Log.w("ClipSync", "⚠ 加密失败，退回明文发送")
            return payload
        }
        // 只保留信封 + 占位预览；kind 保留以便收端在解密前做分类
        return MessagePayload(
            text = null,
            mime = null,
            data = null,
            preview = PLACEHOLDER,
            kind = payload.kind,
            sender = null,
            enc = env
        )
    }

    // ===== 接收方向 =====

    /** 解密结果：区分"本来就是明文"、"解开了"、"解不开"三种情况 */
    sealed interface Outcome {
        data class Plaintext(val payload: MessagePayload) : Outcome
        data class Decrypted(val payload: MessagePayload) : Outcome
        data class Failed(val fingerprint: String) : Outcome
    }

    fun decrypt(payload: MessagePayload, password: String): Outcome {
        val env = payload.enc ?: return Outcome.Plaintext(payload)
        val key = keyFor(password) ?: return Outcome.Failed(env.fp)
        val plain = E2EECrypto.open(env, key) ?: return Outcome.Failed(env.fp)
        val decoded = runCatching {
            json.decodeFromString(MessagePayload.serializer(), plain.toString(Charsets.UTF_8))
        }.getOrNull() ?: return Outcome.Failed(env.fp)
        return Outcome.Decrypted(decoded.copy(enc = null))
    }
}
