package com.clipsync.crypto

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端隧道加密。
 *
 * 密钥来自「用户在本机设置的同步密码」，服务端从不接触密码或密钥。
 *
 * 参数必须与另外两端逐字节一致：
 *  - 派生：PBKDF2-HMAC-SHA256(password, salt, 200000) → 32 字节
 *  - salt：SHA-256("clipsync-e2ee-v1")，两端写死同一个值，
 *          于是"同一个密码"在任何设备上派生出同一把密钥，无需交换材料
 *  - 加密：AES-256-GCM，12 字节随机 IV，16 字节 tag（Cipher 输出里已附在密文尾部）
 *  - 指纹：SHA-256(key) 的前 16 个 hex 字符
 *
 * 对应实现：Mac 端 E2EECrypto.swift，服务端只校验信封格式（e2ee.go）。
 */
object E2EECrypto {
    const val VERSION = 1
    const val ALGORITHM = "AES-256-GCM"
    const val KDF_NAME = "PBKDF2-HMAC-SHA256"
    const val ITERATIONS = 200_000

    private const val SALT_SEED = "clipsync-e2ee-v1"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** 固定盐：SHA-256(SALT_SEED)，32 字节。改动它会让所有历史密文无法解开。 */
    val salt: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(SALT_SEED.toByteArray(Charsets.UTF_8))
    }

    /** 用同步密码派生 AES-256 密钥；密码为空返回 null（等于关闭加密）。 */
    fun deriveKey(password: String): ByteArray? {
        if (password.isEmpty()) return null
        return runCatching {
            val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }.onFailure { Log.w("ClipSync", "✗ 密钥派生失败: ${it.message}") }.getOrNull()
    }

    /** 密钥指纹：SHA-256(key) 前 16 位 hex，用于提示两端密码是否一致。 */
    fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /** 加密明文，返回可直接放进 payload.enc 的信封。 */
    fun seal(plaintext: ByteArray, key: ByteArray): EncEnvelope? = runCatching {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        // Java 的 GCM 输出已经是 密文 + tag，跟 CryptoKit 的 (ciphertext + tag) 布局一致
        val ciphertextAndTag = cipher.doFinal(plaintext)
        EncEnvelope(
            v = VERSION,
            alg = ALGORITHM,
            kdf = KDF_NAME,
            iter = ITERATIONS,
            salt = b64(salt),
            iv = b64(iv),
            ct = b64(ciphertextAndTag),
            fp = fingerprint(key)
        )
    }.onFailure { Log.w("ClipSync", "✗ 加密失败: ${it.message}") }.getOrNull()

    /** 解开信封。密码不对时 GCM 校验失败 → 返回 null。 */
    fun open(envelope: EncEnvelope, key: ByteArray): ByteArray? {
        if (envelope.v != VERSION || envelope.alg != ALGORITHM) {
            Log.w("ClipSync", "✗ 信封格式不支持 v=${envelope.v} alg=${envelope.alg}")
            return null
        }
        return runCatching {
            val iv = unb64(envelope.iv)
            val ct = unb64(envelope.ct)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.doFinal(ct)
        }.onFailure {
            // 最常见原因就是两端同步密码不一致
            Log.w("ClipSync", "✗ 解密失败（密码可能不一致）: ${it.message}")
        }.getOrNull()
    }

    private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
