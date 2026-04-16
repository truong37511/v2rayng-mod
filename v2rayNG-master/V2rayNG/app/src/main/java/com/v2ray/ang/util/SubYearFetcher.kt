package com.v2ray.ang.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gọi API lấy QR gói đăng ký 1 năm (action=year).
 * Đọc ảnh QR từ URL, giải mã thành chuỗi config/sub link rồi trả về.
 */
object SubYearFetcher {

    // ── Đổi thành API key và URL thực của bạn ──
    private const val API_URL = "https://admin.zhenxishop.com/api.php"
    private const val API_KEY = "v2rayng_secret_2024"
    private const val TAG = "SubYearFetcher"

    sealed class FetchResult {
        data class Success(val subContent: String) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    suspend fun fetchYearConfig(): FetchResult = withContext(Dispatchers.IO) {
        try {
            // 1. Gọi API lấy metadata QR gói 1 năm
            val metaUrl = "$API_URL?key=$API_KEY&action=year"
            val metaJson = httpGet(metaUrl)
                ?: return@withContext FetchResult.Error("Không thể kết nối máy chủ.")

            val meta = JSONObject(metaJson)
            if (!meta.optBoolean("success", false)) {
                val err = meta.optString("error", "Lỗi không xác định từ server.")
                return@withContext FetchResult.Error(err)
            }

            val qrUrl = meta.optString("url", "")
            if (qrUrl.isBlank()) {
                return@withContext FetchResult.Error("Server không trả về URL ảnh QR.")
            }

            // 2. Tải ảnh QR về và giải mã
            val subContent = decodeQrFromUrl(qrUrl)
                ?: return@withContext FetchResult.Error("Không thể đọc nội dung QR từ ảnh.")

            if (subContent.isBlank()) {
                return@withContext FetchResult.Error("QR không chứa nội dung hợp lệ.")
            }

            FetchResult.Success(subContent)

        } catch (e: Exception) {
            Log.e(TAG, "fetchYearConfig error", e)
            FetchResult.Error("Lỗi: ${e.message}")
        }
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("X-API-KEY", API_KEY)

            if (conn.responseCode != 200) return null

            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                .use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "httpGet error: $urlStr", e)
            null
        }
    }

    /**
     * Tải ảnh QR từ URL rồi dùng ZXing (hoặc ML Kit) giải mã thành chuỗi text.
     * V2RayNG đã bundle ZXing nên dùng trực tiếp.
     */
    private fun decodeQrFromUrl(qrUrl: String): String? {
        return try {
            val conn = URL(qrUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            val bytes = conn.inputStream.use { it.readBytes() }

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null

            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = com.google.zxing.RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = com.google.zxing.BinaryBitmap(
                com.google.zxing.common.HybridBinarizer(source)
            )

            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to
                        listOf(com.google.zxing.BarcodeFormat.QR_CODE)
            )

            com.google.zxing.MultiFormatReader().decode(binaryBitmap, hints).text
        } catch (e: Exception) {
            Log.e(TAG, "decodeQrFromUrl error: $qrUrl", e)
            null
        }
    }
}