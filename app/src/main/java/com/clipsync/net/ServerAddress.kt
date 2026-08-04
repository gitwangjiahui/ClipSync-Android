package com.clipsync.net

/**
 * 服务器地址规范化。
 *
 * 用户在设置里只需要填 `192.168.1.10:8080` 或 `example.com`，
 * `ws://` 前缀由程序补齐。443 端口和 `https` 输入会归一到 `wss://`。
 *
 * 对应 Mac 端 ServerAddress.swift，两端行为保持一致。
 */
object ServerAddress {

    /**
     * 把用户输入补成完整的 WebSocket 地址。
     *
     * 空输入原样返回空串，交由调用方提示"请填写服务器地址"。
     */
    fun normalize(raw: String): String {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        return when {
            s.startsWith("ws://") || s.startsWith("wss://") -> s
            // http/https 是常见误填，直接映射到对应的 WebSocket scheme
            s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
            s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
            // 443 端口默认按 TLS 处理，省得用户再手填 wss
            s.endsWith(":443") -> "wss://$s"
            else -> "ws://$s"
        }
    }

    /** 界面展示用：去掉 scheme，输入框里就不必出现 `ws://` 了 */
    fun displayForm(raw: String): String =
        raw.trim()
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
}
