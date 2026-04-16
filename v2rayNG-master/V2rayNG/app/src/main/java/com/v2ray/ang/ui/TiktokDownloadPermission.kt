package com.v2ray.ang.ui

/**
 * Helper lưu/đọc trạng thái "đã xác minh OTP ít nhất 1 lần".
 *
 * Bất kỳ OTP nào xác minh thành công (OtpDialog, OtpUpdateDialog,
 * OtpShopDialog, OtpYearDialog) đều gọi [markVerified] để mở khóa
 * nút tải TikTok vĩnh viễn trên thiết bị này.
 */
object TiktokDownloadPermission {

    private const val PREFS_NAME = "tiktok_download_prefs"
    private const val KEY_VERIFIED = "otp_verified"

    fun isVerified(context: android.content.Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_VERIFIED, false)
    }

    fun markVerified(context: android.content.Context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VERIFIED, true)
            .apply()
    }
}