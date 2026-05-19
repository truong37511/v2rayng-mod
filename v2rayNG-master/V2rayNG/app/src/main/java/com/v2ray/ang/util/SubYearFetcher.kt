package com.v2ray.ang.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Gọi API lấy gói đăng ký 1 năm (action=year) → ưu tiên sub_url, fallback sang ảnh QR.
 *
 * Thứ tự fetch:
 *   1. Direct (không proxy)
 *   2. Nếu lỗi mạng/timeout → thử lại qua HTTP Proxy
 *
 * Thứ tự lấy config trong mỗi lần fetch:
 *   a. Thử dùng sub_url (link sub trực tiếp) nếu API trả về
 *   b. Nếu sub_url trống/lỗi → fallback decode ảnh QR
 */
object SubYearFetcher {

    private const val API_URL  = "https://admin.zhenxishop.com/api.php"
    private const val API_KEY  = "v2rayng_secret_2024"
    private const val TAG      = "SubYearFetcher"

    // ── Cấu hình proxy ───────────────────────────────────────────────────
    private const val PROXY_HOST = "127.0.0.1"
    private const val PROXY_PORT = 10808

    sealed class FetchResult {
        data class Success(
            val subContent: String,   // base64 body — dự phòng nếu cần
            val subUrl: String = "",  // URL gốc của sub link — dùng để import đúng cách
            val expireDate: Long = 0L,
            val expireSource: String = "auto"
        ) : FetchResult()
        data class Error(val message: String, val isNetworkError: Boolean = false) : FetchResult()
    }

    suspend fun fetchYearConfig(): FetchResult = withContext(Dispatchers.IO) {
        // Thử direct trước
        val directResult = doFetch(useProxy = false)
        if (directResult is FetchResult.Success) return@withContext directResult

        val directErr = directResult as FetchResult.Error

        // Chỉ fallback proxy khi lỗi mạng thực sự (không reach server)
        // Nếu server đã phản hồi nhưng không có data → không cần thử proxy
        if (!directErr.isNetworkError) {
            Log.w(TAG, "Server phản hồi nhưng không có data, không fallback proxy.")
            return@withContext directErr
        }

        Log.w(TAG, "Direct thất bại do mạng (${directErr.message}), thử qua proxy...")
        doFetch(useProxy = true)
    }

    private fun doFetch(useProxy: Boolean): FetchResult {
        val tag   = if (useProxy) "PROXY" else "DIRECT"
        val proxy = if (useProxy)
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, PROXY_PORT))
        else
            Proxy.NO_PROXY

        return try {
            // 1. Gọi API lấy metadata gói 1 năm
            val metaUrl  = "$API_URL?key=$API_KEY&action=year"
            val (httpCode, metaJson) = httpGet(metaUrl, proxy)

            if (metaJson == null) {
                return if (httpCode != null) {
                    // Kết nối được nhưng server báo lỗi HTTP
                    Log.w(TAG, "[$tag] HTTP $httpCode")
                    FetchResult.Error("Admin chưa cập nhật gói 1 năm.\nVui lòng liên hệ admin để được hỗ trợ.")
                } else {
                    // Không kết nối được server
                    FetchResult.Error("Không thể kết nối máy chủ.\nVui lòng kiểm tra kết nối mạng và thử lại.", isNetworkError = true)
                }
            }

            val meta = JSONObject(metaJson)
            if (!meta.optBoolean("success", false)) {
                val serverErr = meta.optString("error", "").trim()
                // Nếu server báo chưa có sub → thông báo thân thiện
                val msg = if (serverErr.isBlank()) {
                    "Admin chưa cập nhật gói 1 năm.\nVui lòng liên hệ admin để được hỗ trợ."
                } else {
                    "$serverErr\nVui lòng liên hệ admin để được hỗ trợ."
                }
                return FetchResult.Error(msg)
            }

            val metaExpire = meta.optLong("expire", 0L)
            val metaSource = meta.optString("expire_source", "auto").trim()
            Log.d(TAG, "[$tag] expire=$metaExpire source=$metaSource")

            val expireArg = if (metaExpire > 0L) metaExpire else 0L

            // ── Bước 2a: Ưu tiên sub_url — fetch nội dung thực sự ────────
            val subUrl = meta.optString("sub_url", "").trim()
            if (subUrl.isNotBlank()) {
                Log.d(TAG, "[$tag] Thử fetch sub_url: $subUrl")
                val (subCode, subBody, subUserinfo) = httpGetWithHeaders(subUrl, proxy)

                if (subCode != null && subCode in 200..299 && !subBody.isNullOrBlank()) {
                    // Đọc expire từ header Subscription-Userinfo — ưu tiên hơn giá trị admin
                    val headerExpireTs = Regex("expire=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(subUserinfo ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                    // Ưu tiên: (1) header sub link, (2) expire từ admin API
                    val finalExpire = if (headerExpireTs > 0L) headerExpireTs else expireArg
                    val finalSource = if (headerExpireTs > 0L) "sub" else metaSource

                    Log.d(TAG, "[$tag] sub_url fetch OK, body=${subBody.length} chars, expire=$finalExpire (header=$headerExpireTs admin=$expireArg)")
                    return FetchResult.Success(subBody.trim(), subUrl, finalExpire, finalSource)
                }
                Log.w(TAG, "[$tag] sub_url HTTP=$subCode body=${subBody?.length ?: 0}, fallback sang QR.")
            }

            // ── Bước 2b: Fallback decode ảnh QR ──────────────────────────
            val qrUrl = meta.optString("url", "").trim()
            if (qrUrl.isNotBlank()) {
                Log.d(TAG, "[$tag] Thử decode QR từ: $qrUrl")
                val qrContent = decodeQrFromUrl(qrUrl, proxy)
                if (!qrContent.isNullOrBlank()) {
                    Log.d(TAG, "[$tag] Decode QR thành công")
                    return FetchResult.Success(qrContent, "", expireArg, metaSource)
                }
                Log.w(TAG, "[$tag] Decode QR thất bại.")
            }

            FetchResult.Error("Admin chưa cập nhật gói 1 năm.\nVui lòng liên hệ admin để được hỗ trợ.")

        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "[$tag] UnknownHost", e)
            FetchResult.Error("Không có kết nối mạng.\nVui lòng kiểm tra Wi-Fi hoặc dữ liệu di động.", isNetworkError = true)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "[$tag] Timeout", e)
            FetchResult.Error("Kết nối đến máy chủ quá chậm.\nVui lòng thử lại sau.", isNetworkError = true)
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] doFetch error", e)
            FetchResult.Error("Đã xảy ra lỗi không mong muốn.\nVui lòng thử lại sau.", isNetworkError = true)
        }
    }

    /**
     * Trả về Pair(httpCode, body):
     *   - httpCode = null  → không kết nối được (lỗi mạng/exception)
     *   - httpCode != null, body = null → kết nối được nhưng HTTP không 200
     *   - httpCode = 200, body != null  → thành công
     */
    private fun httpGet(urlStr: String, proxy: Proxy): Pair<Int?, String?> {
        return try {
            val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
            conn.requestMethod  = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout    = 10_000
            conn.setRequestProperty("X-API-KEY", API_KEY)

            val code = conn.responseCode
            if (code != 200) return Pair(code, null)

            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            Pair(code, body)
        } catch (e: Exception) {
            Log.e(TAG, "httpGet error: $urlStr", e)
            Pair(null, null)
        }
    }

    /**
     * Fetch sub URL, trả về Triple(httpCode, body, subscription-userinfo header).
     * Dùng User-Agent giống V2RayNG để sub server nhận diện đúng.
     */
    private fun httpGetWithHeaders(urlStr: String, proxy: Proxy): Triple<Int?, String?, String?> {
        return try {
            val conn = java.net.URL(urlStr).openConnection(proxy) as HttpURLConnection
            conn.requestMethod  = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout    = 12_000
            conn.setRequestProperty("User-Agent", "v2rayng/1.8.0")
            conn.setRequestProperty("Accept", "*/*")

            val code = conn.responseCode
            if (code !in 200..299) return Triple(code, null, null)

            val body     = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val userinfo = conn.getHeaderField("subscription-userinfo")
                ?: conn.getHeaderField("Subscription-Userinfo")
            Triple(code, body, userinfo)
        } catch (e: Exception) {
            Log.e(TAG, "httpGetWithHeaders error: $urlStr", e)
            Triple(null, null, null)
        }
    }

    private fun decodeQrFromUrl(qrUrl: String, proxy: Proxy): String? {
        return try {
            val conn = URL(qrUrl).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout    = 10_000
            val bytes = conn.inputStream.use { it.readBytes() }

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null

            val width  = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source       = com.google.zxing.RGBLuminanceSource(width, height, pixels)
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