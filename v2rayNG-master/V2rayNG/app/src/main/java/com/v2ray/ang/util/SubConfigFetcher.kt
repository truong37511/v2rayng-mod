package com.v2ray.ang.util

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Gọi API lấy gói cửa hàng → decode ảnh QR.
 * Dùng cho chức năng "Admin cửa hàng"
 *
 * Thứ tự fetch:
 *   1. Direct (không proxy)
 *   2. Nếu lỗi mạng/timeout → thử lại qua HTTP Proxy
 */
object SubConfigFetcher {

    private const val API_BASE_URL = "https://admin.zhenxishop.com/api.php"
    private const val API_KEY      = "v2rayng_secret_2024"

    // ── Cấu hình proxy ───────────────────────────────────────────────────
    private const val PROXY_HOST = "127.0.0.1"
    private const val PROXY_PORT = 10808

    private fun buildClient(useProxy: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
        if (useProxy) {
            builder.proxy(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, PROXY_PORT))
            )
        }
        return builder.build()
    }

    sealed class FetchResult {
        data class Success(
            val subContent: String,
            val qrId: Int,
            val expireDate: Long = 0L,
            val expireSource: String = "auto"
        ) : FetchResult()
        data class Error(val message: String, val isNetworkError: Boolean = false) : FetchResult()
    }

    suspend fun fetchSubConfig(): FetchResult = withContext(Dispatchers.IO) {
        // Ưu tiên proxy trước — user đã bật VPN trước khi gọi API
        val proxyResult = doFetch(useProxy = true)
        if (proxyResult is FetchResult.Success) return@withContext proxyResult

        val proxyErr = proxyResult as FetchResult.Error

        // Server phản hồi qua proxy nhưng không có data → không cần thử direct
        if (!proxyErr.isNetworkError) {
            Log.w("SubConfigFetcher", "Server phản hồi qua proxy nhưng không có data, không fallback direct.")
            return@withContext proxyErr
        }

        // Proxy thất bại (VPN chưa bật / port chưa mở) → fallback direct dự phòng
        Log.w("SubConfigFetcher", "Proxy thất bại (${proxyErr.message}), thử direct dự phòng...")
        doFetch(useProxy = false)
    }

    private fun doFetch(useProxy: Boolean): FetchResult {
        val tag    = if (useProxy) "PROXY" else "DIRECT"
        val client = buildClient(useProxy)
        return try {
            // ── Bước 1: Gọi API ──────────────────────────────────────────
            val apiUrl      = "$API_BASE_URL?key=$API_KEY&action=sub"
            val apiResponse = client.newCall(Request.Builder().url(apiUrl).build()).execute()

            if (!apiResponse.isSuccessful) {
                Log.w("SubConfigFetcher", "[$tag] HTTP ${apiResponse.code}")
                return FetchResult.Error("Không thể kết nối máy chủ.\nVui lòng kiểm tra kết nối mạng và thử lại.", isNetworkError = true)
            }

            val json = apiResponse.body?.string()
                ?: return FetchResult.Error("Không thể kết nối máy chủ.\nVui lòng kiểm tra kết nối mạng và thử lại.", isNetworkError = true)

            // ── Bước 2: Parse JSON ────────────────────────────────────────
            val meta = try {
                JSONObject(json)
            } catch (e: Exception) {
                Log.w("SubConfigFetcher", "[$tag] JSON parse error", e)
                return FetchResult.Error("Máy chủ trả về dữ liệu lỗi.\nVui lòng thử lại sau.")
            }

            if (!meta.optBoolean("success", false)) {
                val serverErr = meta.optString("error", "").trim()
                val msg = if (serverErr.isBlank()) {
                    "Admin chưa cập nhật gói cửa hàng.\nVui lòng liên hệ admin để được hỗ trợ."
                } else {
                    "$serverErr\nVui lòng liên hệ admin để được hỗ trợ."
                }
                return FetchResult.Error(msg)
            }

            val qrId       = meta.optInt("id", 0)
            val metaExpire = meta.optLong("expire", 0L)
            val metaSource = meta.optString("expire_source", "auto").trim()
            Log.d("SubConfigFetcher", "[$tag] expire=$metaExpire source=$metaSource")

            val expireArg = if (metaExpire > 0L) metaExpire else 0L

            // ── Bước 3: Decode ảnh QR ─────────────────────────────────────
            val imageUrl = meta.optString("url", "").trim()
            if (imageUrl.isNotBlank()) {
                Log.d("SubConfigFetcher", "[$tag] Thử download + decode QR từ: $imageUrl")

                val imgResponse = runCatching {
                    client.newCall(Request.Builder().url(imageUrl).build()).execute()
                }.getOrNull()

                val imageBytes = imgResponse?.takeIf { it.isSuccessful }?.body?.bytes()
                val bitmap     = imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                val qrContent  = bitmap?.let { QRCodeDecoder.syncDecodeQRCode(it) }

                if (!qrContent.isNullOrBlank()) {
                    Log.d("SubConfigFetcher", "[$tag] Decode QR thành công")
                    return FetchResult.Success(qrContent, qrId, expireArg, metaSource)
                }
                Log.w("SubConfigFetcher", "[$tag] Decode QR thất bại.")
            }

            FetchResult.Error("Admin chưa cập nhật gói cửa hàng.\nVui lòng liên hệ admin để được hỗ trợ.")

        } catch (e: java.net.UnknownHostException) {
            Log.w("SubConfigFetcher", "[$tag] UnknownHost", e)
            FetchResult.Error("Không có kết nối mạng.\nVui lòng kiểm tra Wi-Fi hoặc dữ liệu di động.", isNetworkError = true)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w("SubConfigFetcher", "[$tag] Timeout", e)
            FetchResult.Error("Kết nối đến máy chủ quá chậm.\nVui lòng thử lại sau.", isNetworkError = true)
        } catch (e: Exception) {
            Log.e("SubConfigFetcher", "[$tag] doFetch error", e)
            FetchResult.Error("Đã xảy ra lỗi không mong muốn.\nVui lòng thử lại sau.", isNetworkError = true)
        }
    }

}