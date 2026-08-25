// 工具函数：UUIDv7生成（时间有序，架构§4要求msg_id为UUIDv7）
package com.agentteam.core.util

private val rand = java.security.SecureRandom()

fun newUuidV7(): String {
    val ts = System.currentTimeMillis()
    val b = ByteArray(16)
    rand.nextBytes(b)
    // RFC 9562: 48位unix毫秒 + version7 + variant10
    b[0] = ((ts ushr 40) and 0xFF).toByte(); b[1] = ((ts ushr 32) and 0xFF).toByte()
    b[2] = ((ts ushr 24) and 0xFF).toByte(); b[3] = ((ts ushr 16) and 0xFF).toByte()
    b[4] = ((ts ushr 8) and 0xFF).toByte();  b[5] = (ts and 0xFF).toByte()
    b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte()
    b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = b.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
}
