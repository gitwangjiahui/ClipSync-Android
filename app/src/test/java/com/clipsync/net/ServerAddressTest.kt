package com.clipsync.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 地址规范化：用户免填 ws:// 前缀。
 * 用例要和 Mac 端 ServerAddress.swift 保持同一套行为。
 */
class ServerAddressTest {

    @Test
    fun `裸 host port 自动补 ws 前缀`() {
        assertEquals("ws://192.168.1.10:8080", ServerAddress.normalize("192.168.1.10:8080"))
        assertEquals("ws://example.com", ServerAddress.normalize("example.com"))
    }

    @Test
    fun `已有 scheme 时原样保留`() {
        assertEquals("ws://a.com:8080", ServerAddress.normalize("ws://a.com:8080"))
        assertEquals("wss://a.com", ServerAddress.normalize("wss://a.com"))
    }

    @Test
    fun `http 前缀映射到对应的 WebSocket scheme`() {
        assertEquals("ws://a.com:8080", ServerAddress.normalize("http://a.com:8080"))
        assertEquals("wss://a.com", ServerAddress.normalize("https://a.com"))
    }

    @Test
    fun `443 端口按 TLS 处理`() {
        assertEquals("wss://a.com:443", ServerAddress.normalize("a.com:443"))
    }

    @Test
    fun `去掉首尾空白和结尾斜杠`() {
        assertEquals("ws://a.com:8080", ServerAddress.normalize("  a.com:8080/  "))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", ServerAddress.normalize(""))
        assertEquals("", ServerAddress.normalize("   "))
    }

    @Test
    fun `展示形式去掉 scheme`() {
        assertEquals("a.com:8080", ServerAddress.displayForm("ws://a.com:8080"))
        assertEquals("a.com", ServerAddress.displayForm("wss://a.com/"))
        assertEquals("a.com:8080", ServerAddress.displayForm("a.com:8080"))
    }

    @Test
    fun `规范化是幂等的`() {
        val once = ServerAddress.normalize("a.com:8080")
        assertEquals(once, ServerAddress.normalize(once))
    }
}
