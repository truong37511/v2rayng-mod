package com.v2ray.ang.util

import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Gọi API lấy thông báo từ web admin → hiển thị marquee trong app.
 * Dùng ProtectedSocketFactory để bypass VPN tunnel trên thiết bị thật.
 */
object AnnouncementFetcher {

    private const val API_BASE_URL = "https://admin.zhenxishop.com/api.php"
    private const val API_KEY      = "v2rayng_secret_2024"

    private class ProtectedSocketFactory(
        private val vpnService: VpnService?,
        private val delegate: SocketFactory = getDefault()
    ) : SocketFactory() {
        private fun protect(socket: Socket): Socket {
            vpnService?.protect(socket)
            return socket
        }
        override fun createSocket(): Socket = protect(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket =
            protect(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            protect(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(address: InetAddress?, port: Int): Socket =
            protect(delegate.createSocket(address, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            protect(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun buildClient(vpnService: VpnService?): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .socketFactory(ProtectedSocketFactory(vpnService))
            .build()

    sealed class FetchResult {
        data class Success(
            val message: String,
            val color: String = "#E65100",
            val textColor: String = "#FFFFFF"
        ) : FetchResult()
        object Hidden : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    suspend fun fetchAnnouncement(vpnService: VpnService? = null): FetchResult = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(vpnService)
            val url = "$API_BASE_URL?key=$API_KEY&action=announcement"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext FetchResult.Error("API lỗi: HTTP ${response.code}")
            }

            val json = response.body?.string()
                ?: return@withContext FetchResult.Error("Không có dữ liệu")

            if (!json.contains("\"success\":true")) {
                return@withContext FetchResult.Hidden
            }

            if (!json.contains("\"active\":true")) {
                return@withContext FetchResult.Hidden
            }

            val message = extractJsonString(json, "message")
            if (message == null || message.isBlank()) {
                return@withContext FetchResult.Hidden
            }

            val bgColor   = extractJsonString(json, "color")      ?: "#E65100"
            val textColor = extractJsonString(json, "text_color") ?: "#FFFFFF"

            FetchResult.Success(message, bgColor, textColor)

        } catch (e: java.net.UnknownHostException) {
            FetchResult.Error("Không có kết nối mạng")
        } catch (e: java.net.SocketTimeoutException) {
            FetchResult.Error("Kết nối timeout")
        } catch (e: Exception) {
            FetchResult.Error("Lỗi: ${e.message}")
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }
}