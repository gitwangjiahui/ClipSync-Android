package com.clipsync.crypto

import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 记录一次密钥派生的实际耗时。
 *
 * 20 万轮 PBKDF2 是笔不小的开销，这个测试的意义是把数字摆出来：只要它明显
 * 超过一帧（16ms），就说明绝不能跟着每次按键跑，更不能在主线程上同步调用。
 * UI 侧的对策：同步密码改为「确定」按钮提交，派生只在确认时于后台协程执行。
 */
class DeriveKeyCostTest {

    @Test
    fun `派生一次密钥远超一帧耗时`() {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("clipsync-e2ee-v1".toByteArray(Charsets.UTF_8))
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

        // 先跑一次预热，避免把 JIT 和类加载算进来
        factory.generateSecret(PBEKeySpec("warmup".toCharArray(), salt, 200_000, 256))

        val start = System.nanoTime()
        factory.generateSecret(PBEKeySpec("benchmark".toCharArray(), salt, 200_000, 256))
        val millis = (System.nanoTime() - start) / 1_000_000

        println("PBKDF2(200000 轮) 单次派生耗时：${millis}ms")
        assertTrue(
            "派生耗时 ${millis}ms 竟然低于一帧，说明迭代次数可能被误改小了",
            millis > 16
        )
    }
}
