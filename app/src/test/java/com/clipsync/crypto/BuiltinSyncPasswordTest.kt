package com.clipsync.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 内置默认同步密码的跨端锚点。
 *
 * 用户开了加密但没填密码时，三端都会回落到这个值。只要任何一端的常量被改动，
 * 派生出的密钥就不一样，两端立刻互相解不开 —— 所以把指纹写死在这里当哨兵。
 *
 * 期望指纹由 PBKDF2-HMAC-SHA256(内置密码, SHA-256("clipsync-e2ee-v1"), 200000)
 * 取 SHA-256 前 16 个 hex 字符得到，与 Mac / Server 端常量一致。
 */
class BuiltinSyncPasswordTest {

    private companion object {
        const val EXPECTED_PASSWORD = "cs1-louuMZxNFCXgL1AcXjlBCly2E54NeH5T"
        const val EXPECTED_FINGERPRINT = "6cc296bd5bca6b1d"
        const val SALT_SEED = "clipsync-e2ee-v1"
        const val ITERATIONS = 200_000
    }

    /** 常量本身不能被悄悄改掉：改了就得三端同步改，并更新这里的期望值 */
    @Test
    fun `内置密码常量固定不变`() {
        assertEquals(EXPECTED_PASSWORD, E2EECrypto.BUILTIN_SYNC_PASSWORD)
    }

    /**
     * 用 JVM 自带的 JCE 独立重算一遍派生，验证指纹符合预期。
     * 不直接调 E2EECrypto.deriveKey 是因为它依赖 android.util.Base64。
     */
    @Test
    fun `内置密码派生出的密钥指纹符合三端约定`() {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(SALT_SEED.toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(
            E2EECrypto.BUILTIN_SYNC_PASSWORD.toCharArray(), salt, ITERATIONS, 256
        )
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        val fp = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }
            .take(16)
        assertEquals(EXPECTED_FINGERPRINT, fp)
    }

    /** 内置密码不能撞上用户可能设的弱密码，否则"自设密码"就白设了 */
    @Test
    fun `内置密码不是常见弱口令`() {
        listOf("", "123456", "password", "clipsync").forEach {
            assertNotEquals(it, E2EECrypto.BUILTIN_SYNC_PASSWORD)
        }
    }
}
