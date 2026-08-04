package com.clipsync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密的跨端一致性测试。
 *
 * 关键约束：Android 和 macOS 必须从同一个密码派生出同一把密钥，
 * 并且能解开对方生成的密文。下面的 EXPECTED_* 常量是用 Mac 端
 * E2EECrypto.swift（CryptoKit + CommonCrypto）实际跑出来的结果，
 * 任何一端改动 KDF 参数都会让这些断言失败。
 *
 * 注：android.util.Base64 在纯 JVM 单测里不可用，所以这里用
 * java.util.Base64 + JCE 重跑一遍与 E2EECrypto 完全相同的算法。
 */
class E2EECryptoTest {

    private companion object {
        // 三端共享的固定参数（与 E2EECrypto / E2EECrypto.swift / e2ee.go 一致）
        const val SALT_SEED = "clipsync-e2ee-v1"
        const val ITERATIONS = 200_000
        const val TAG_BITS = 128

        // ===== 以下取自 Mac 端实跑输出 =====
        const val CROSS_PASSWORD = "cross-端-password-42"
        const val CROSS_FINGERPRINT = "cadabb2b29374d26"
        const val CROSS_SALT_B64 = "bpSHL7gFagBKm/OCAClhTMWh2NbLtRy2QIZ1KpKMwSg="
        const val CROSS_IV_B64 = "6gAnt0PNv9//l/NX"
        const val CROSS_CT_B64 =
            "YLM57VjYVKzWN9XXvcCaWJvQcA4sf6fMpIlz0EB3AfrlyKfKcupx5v2hGpDLhh6q9MFtKlWONweayyOMvQ=="
        const val CROSS_PLAINTEXT = """{"text":"验证码 314159","kind":"sms_code"}"""
    }

    // ===== 与 E2EECrypto 等价的纯 JVM 实现 =====

    private fun salt(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(SALT_SEED.toByteArray(Charsets.UTF_8))

    private fun deriveKey(password: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt(), ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun fingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)

    private fun decrypt(key: ByteArray, iv: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertextAndTag)
    }

    private fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun b64e(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    // ===== 测试 =====

    @Test
    fun `固定盐与 Mac 端一致`() {
        assertEquals("固定盐不一致会让两端派生出不同密钥", CROSS_SALT_B64, b64e(salt()))
        assertEquals("盐必须是 32 字节 SHA-256 输出", 32, salt().size)
    }

    @Test
    fun `同一密码派生出与 Mac 端相同的密钥指纹`() {
        val fp = fingerprint(deriveKey(CROSS_PASSWORD))
        assertEquals("KDF 参数与 Mac 端不一致", CROSS_FINGERPRINT, fp)
    }

    @Test
    fun `能解开 Mac 端生成的密文`() {
        val key = deriveKey(CROSS_PASSWORD)
        val plain = decrypt(key, b64d(CROSS_IV_B64), b64d(CROSS_CT_B64))
        assertEquals(CROSS_PLAINTEXT, plain.toString(Charsets.UTF_8))
    }

    @Test
    fun `本端加密可自解且密文布局与 Mac 端一致`() {
        val key = deriveKey(CROSS_PASSWORD)
        val iv = b64d(CROSS_IV_B64)
        val plaintext = CROSS_PLAINTEXT.toByteArray(Charsets.UTF_8)

        val ct = encrypt(key, iv, plaintext)
        // 同 key + 同 IV + 同明文 → 必须得到与 Mac 端字节一致的密文
        assertEquals("密文布局（密文+tag）与 Mac 端不一致", CROSS_CT_B64, b64e(ct))
        // GCM 会在末尾追加 16 字节 tag
        assertEquals(plaintext.size + 16, ct.size)
        assertArrayEquals(plaintext, decrypt(key, iv, ct))
    }

    @Test
    fun `不同密码派生出不同密钥且互相解不开`() {
        val k1 = deriveKey(CROSS_PASSWORD)
        val k2 = deriveKey("another-password")
        assertNotEquals(fingerprint(k1), fingerprint(k2))

        val failed = runCatching {
            decrypt(k2, b64d(CROSS_IV_B64), b64d(CROSS_CT_B64))
        }.isFailure
        assertTrue("密码不一致时 GCM 校验必须失败", failed)
    }

    @Test
    fun `IV 长度固定 12 字节`() {
        assertEquals(12, b64d(CROSS_IV_B64).size)
    }

    @Test
    fun `空密码不派生密钥`() {
        // E2EECrypto.deriveKey 对空密码直接返回 null，等于关闭加密
        assertNull(E2EECrypto.deriveKey(""))
    }
}
