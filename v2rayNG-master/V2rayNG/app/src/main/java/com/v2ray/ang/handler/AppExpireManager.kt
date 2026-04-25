package com.v2ray.ang.handler

import android.content.Context

/**
 * Quản lý ngày hết hạn app riêng biệt — hoàn toàn độc lập với subscription link.
 *
 * Lưu trữ: SharedPreferences "app_expire_prefs", key "app_expire_timestamp"
 * Giá trị: Unix timestamp (milliseconds). 0L = chưa cài (không chặn).
 *
 * Cách dùng:
 *   - AppExpireManager.setExpireDate(context, timestampMs)   // Admin set
 *   - AppExpireManager.isExpired(context)                    // Kiểm tra hết hạn
 *   - AppExpireManager.getExpireTimestamp(context)           // Lấy timestamp hiện tại
 *   - AppExpireManager.clear(context)                        // Xóa / bỏ giới hạn
 */
object AppExpireManager {

    private const val PREFS_NAME = "app_expire_prefs"
    private const val KEY_EXPIRE_TS = "app_expire_timestamp"

    /**
     * Lưu ngày hết hạn app.
     * @param timestampMs Unix timestamp tính bằng milliseconds (0L = xóa giới hạn)
     */
    fun setExpireDate(context: Context, timestampMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_EXPIRE_TS, timestampMs)
            .apply()
    }

    /**
     * Lấy ngày hết hạn app hiện tại.
     * @return Unix timestamp (ms). 0L nếu chưa cài.
     */
    fun getExpireTimestamp(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRE_TS, 0L)
    }

    /**
     * Kiểm tra app đã hết hạn chưa.
     * - Chưa cài ngày hết hạn (0L) → false (không chặn)
     * - Đã cài và quá ngày       → true  (chặn FAB)
     */
    fun isExpired(context: Context): Boolean {
        val ts = getExpireTimestamp(context)
        if (ts == 0L) return false
        return System.currentTimeMillis() > ts
    }

    /**
     * Xóa ngày hết hạn → app không còn bị chặn.
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EXPIRE_TS)
            .apply()
    }
}