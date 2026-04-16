package com.v2ray.ang.util

import org.apache.commons.codec.binary.Base32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object OtpManager {

    // 🔑 Secret cũ — dùng cho Admin thường
    // (OtpDialog, AdminOtpDialog)
    private const val SECRET_KEY = "JBSWY3DPEHPK3PXP"

    // 🔑 Secret mới — dùng cho 3 dialog gọi API
    // (OtpUpdateDialog, OtpYearDialog, OtpShopDialog)
    // ⚠️ Thay chuỗi này bằng secret Base32 bạn muốn, rồi thêm vào Google Authenticator
    private const val SECRET_KEY_API = "QPR3S4MCFDIVDUQI"

    private fun generateTotpForTime(timeStep: Long, secret: String = SECRET_KEY): String {
        val base32 = Base32()
        val key = base32.decode(secret)

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
        return generateTotpForTime(time, SECRET_KEY)
    }

    // ✅ Verify Admin thường — OtpDialog, AdminOtpDialog
    fun verify(inputCode: String): Boolean {
        val time = System.currentTimeMillis() / 1000 / 30
        return inputCode == generateTotpForTime(time, SECRET_KEY) ||
                inputCode == generateTotpForTime(time - 1, SECRET_KEY) ||
                inputCode == generateTotpForTime(time + 1, SECRET_KEY)
    }

    // ✅ Verify API — OtpUpdateDialog, OtpYearDialog, OtpShopDialog
    fun verifyApi(inputCode: String): Boolean {
        val time = System.currentTimeMillis() / 1000 / 30
        return inputCode == generateTotpForTime(time, SECRET_KEY_API) ||
                inputCode == generateTotpForTime(time - 1, SECRET_KEY_API) ||
                inputCode == generateTotpForTime(time + 1, SECRET_KEY_API)
    }

    // 🔗 QR URI cho Admin thường
    fun getQrUri(accountName: String = "YumVPN"): String {
        val encodedName = java.net.URLEncoder.encode(accountName, "UTF-8")
        return "otpauth://totp/$encodedName?secret=$SECRET_KEY&issuer=YumVPN"
    }

    // 🔗 QR URI cho API
    fun getQrUriApi(accountName: String = "YumVPN-API"): String {
        val encodedName = java.net.URLEncoder.encode(accountName, "UTF-8")
        return "otpauth://totp/$encodedName?secret=$SECRET_KEY_API&issuer=YumVPN"
    }
}