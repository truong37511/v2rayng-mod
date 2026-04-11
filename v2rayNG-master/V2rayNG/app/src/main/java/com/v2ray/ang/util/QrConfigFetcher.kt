package com.v2ray.ang.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Gọi API lấy URL ảnh QR mới nhất → download → decode → trả về config string
 * Dùng lại QRCodeDecoder có sẵn
 */
object QrConfigFetcher {

    private const val API_BASE_URL = "https://admin.zhenxishop.com/api.php"
    private const val API_KEY      = "v2rayng_secret_2024"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Kết quả trả về cho UI
     */
    sealed class FetchResult {
        data class Success(val configString: String, val qrId: Int) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    /**
     * Hàm chính: gọi API → download ảnh → decode QR → trả FetchResult
     * Phải gọi trong coroutine (suspend)
     */
    suspend fun fetchLatestConfig(): FetchResult = withContext(Dispatchers.IO) {
        try {
            // ── Bước 1: Gọi API lấy thông tin QR mới nhất ──────────────
            val apiUrl = "$API_BASE_URL?key=$API_KEY&action=latest"
            val apiRequest = Request.Builder().url(apiUrl).build()
            val apiResponse = client.newCall(apiRequest).execute()

            if (!apiResponse.isSuccessful) {
                return@withContext FetchResult.Error("API lỗi: HTTP ${apiResponse.code}")
            }

            val json = apiResponse.body?.string()
                ?: return@withContext FetchResult.Error("API không trả về dữ liệu")

            // ── Bước 2: Parse JSON đơn giản (không cần Gson) ────────────
            val success = json.contains("\"success\":true")
            if (!success) {
                return@withContext FetchResult.Error("Chưa có QR nào trên server")
            }

            val imageUrl = extractJsonString(json, "url")
                ?: return@withContext FetchResult.Error("Không tìm thấy URL ảnh QR")

            val qrId = extractJsonInt(json, "version") ?: 0

            // ── Bước 3: Download ảnh QR về Bitmap ───────────────────────
            val imgRequest = Request.Builder().url(imageUrl).build()
            val imgResponse = client.newCall(imgRequest).execute()

            if (!imgResponse.isSuccessful) {
                return@withContext FetchResult.Error("Không download được ảnh QR")
            }

            val imageBytes = imgResponse.body?.bytes()
                ?: return@withContext FetchResult.Error("Ảnh QR rỗng")

            val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return@withContext FetchResult.Error("Không đọc được file ảnh")

            // ── Bước 4: Decode QR bằng QRCodeDecoder có sẵn ─────────────
            val configString = QRCodeDecoder.syncDecodeQRCode(bitmap)
                ?: return@withContext FetchResult.Error("Không decode được mã QR. Ảnh có thể mờ hoặc không đúng định dạng")

            // ── Bước 5: Kiểm tra config hợp lệ ──────────────────────────
            if (!isValidV2RayConfig(configString)) {
                return@withContext FetchResult.Error("QR không chứa cấu hình V2Ray hợp lệ\nNội dung: ${configString.take(50)}...")
            }

            FetchResult.Success(configString, qrId)

        } catch (e: java.net.UnknownHostException) {
            FetchResult.Error("Không có kết nối mạng")
        } catch (e: java.net.SocketTimeoutException) {
            FetchResult.Error("Kết nối timeout. Thử lại sau")
        } catch (e: Exception) {
            FetchResult.Error("Lỗi: ${e.message}")
        }
    }

    /**
     * Kiểm tra config string có phải V2Ray/Xray hợp lệ không
     */
    private fun isValidV2RayConfig(config: String): Boolean {
        val validPrefixes = listOf(
            "vmess://", "vless://", "trojan://",
            "ss://", "ssr://", "hysteria2://",
            "tuic://", "wireguard://"
        )
        return validPrefixes.any { config.startsWith(it) }
    }

    // ── JSON helpers nhỏ (tránh thêm dependency) ──────────────────────────
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
}
