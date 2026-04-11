package com.v2ray.ang.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Gọi API lấy QR cửa hàng (toàn bộ gói đăng ký) → download ảnh → decode → trả config string
 * Dùng cho chức năng "Admin cửa hàng"
 */
object SubConfigFetcher {

    private const val API_BASE_URL = "https://admin.zhenxishop.com/api.php"
    private const val API_KEY      = "v2rayng_secret_2024"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed class FetchResult {
        data class Success(val subContent: String, val qrId: Int) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    /**
     * Hàm chính: gọi API action=sub → download ảnh QR → decode → trả FetchResult
     */
    suspend fun fetchSubConfig(): FetchResult = withContext(Dispatchers.IO) {
        try {
            // ── Bước 1: Gọi API lấy thông tin QR cửa hàng ──────────────
            val apiUrl = "$API_BASE_URL?key=$API_KEY&action=sub"
            val apiRequest = Request.Builder().url(apiUrl).build()
            val apiResponse = client.newCall(apiRequest).execute()

            if (!apiResponse.isSuccessful) {
                return@withContext FetchResult.Error("API lỗi: HTTP ${apiResponse.code}")
            }

            val json = apiResponse.body?.string()
                ?: return@withContext FetchResult.Error("API không trả về dữ liệu")

            // ── Bước 2: Kiểm tra success ─────────────────────────────────
            if (!json.contains("\"success\":true")) {
                return@withContext FetchResult.Error("Chưa có QR cửa hàng nào trên server")
            }

            // ── Bước 3: Lấy url ảnh QR (field "url" giống action=latest) ─
            val imageUrl = extractJsonString(json, "url")
                ?: return@withContext FetchResult.Error("Không tìm thấy URL ảnh QR trong response")

            val qrId = extractJsonInt(json, "id") ?: 0

            // ── Bước 4: Download ảnh QR về Bitmap ───────────────────────
            val imgRequest = Request.Builder().url(imageUrl).build()
            val imgResponse = client.newCall(imgRequest).execute()

            if (!imgResponse.isSuccessful) {
                return@withContext FetchResult.Error("Không download được ảnh QR")
            }

            val imageBytes = imgResponse.body?.bytes()
                ?: return@withContext FetchResult.Error("Ảnh QR rỗng")

            val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return@withContext FetchResult.Error("Không đọc được file ảnh")

            // ── Bước 5: Decode QR bằng QRCodeDecoder có sẵn ─────────────
            val configString = QRCodeDecoder.syncDecodeQRCode(bitmap)
                ?: return@withContext FetchResult.Error("Không decode được mã QR. Ảnh có thể mờ hoặc không đúng định dạng")

            FetchResult.Success(configString, qrId)

        } catch (e: java.net.UnknownHostException) {
            FetchResult.Error("Không có kết nối mạng")
        } catch (e: java.net.SocketTimeoutException) {
            FetchResult.Error("Kết nối timeout. Thử lại sau")
        } catch (e: Exception) {
            FetchResult.Error("Lỗi: ${e.message}")
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
}