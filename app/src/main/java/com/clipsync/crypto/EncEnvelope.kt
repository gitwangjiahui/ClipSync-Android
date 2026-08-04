package com.clipsync.crypto

import kotlinx.serialization.Serializable

/**
 * 端到端加密信封。字段与服务端 e2ee.go 的 EncEnvelope、
 * Mac 端 Models.swift 的 EncEnvelope 一一对应，任何改动都要三端同步。
 */
@Serializable
data class EncEnvelope(
    /** 协议版本 */
    val v: Int,
    /** 加密算法，固定 AES-256-GCM */
    val alg: String,
    /** 密钥派生算法，固定 PBKDF2-HMAC-SHA256 */
    val kdf: String,
    /** KDF 迭代次数 */
    val iter: Int,
    /** base64，KDF 盐 */
    val salt: String,
    /** base64，12 字节 GCM nonce，每条消息随机 */
    val iv: String,
    /** base64，密文 + 16 字节 GCM tag */
    val ct: String,
    /** 密钥指纹（hex 前 16 位），用于判断两端密码是否一致 */
    val fp: String
)
