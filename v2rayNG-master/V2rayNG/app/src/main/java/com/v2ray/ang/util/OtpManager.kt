package com.v2ray.ang.util

import org.apache.commons.codec.binary.Base32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object OtpManager {

    // 🔑 Key chuẩn Base32 (16 ký tự)
    private const val SECRET_KEY = "JBSWY3DPEHPK3PXP"

    private fun generateTotpForTime(timeStep: Long): String {
        val base32 = Base32()
        val key = base32.decode(SECRET_KEY)

        val data = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            data[i] = (value and 0xFF).toByte()
            value = value shr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(data)

        val offset = hash[hash.size - 1].toInt() and 0xF
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        return String.format("%06d", code % 10.0.pow(6).toInt())
    }

    fun generateTotp(): String {
        val time = System.currentTimeMillis() / 1000 / 30
        return generateTotpForTime(time)
    }

    // 🔥 Verify cho phép lệch thời gian ±1 step
    fun verify(inputCode: String): Boolean {
        val time = System.currentTimeMillis() / 1000 / 30

        val current = generateTotpForTime(time)
        val prev = generateTotpForTime(time - 1)
        val next = generateTotpForTime(time + 1)

        return inputCode == current || inputCode == prev || inputCode == next
    }

    // 🔗 QR URI (đã encode chuẩn)
    fun getQrUri(accountName: String = "YumVPN"): String {
        val encodedName = java.net.URLEncoder.encode(accountName, "UTF-8")
        return "otpauth://totp/$encodedName?secret=$SECRET_KEY&issuer=YumVPN"
    }
}