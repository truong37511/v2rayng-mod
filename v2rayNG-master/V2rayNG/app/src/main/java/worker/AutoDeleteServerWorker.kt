package com.v2ray.ang.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.handler.MmkvManager
import java.util.concurrent.TimeUnit

/**
 * Worker chạy ngầm (kể cả khi app bị tắt) để xóa các server
 * được import bởi "Admin mã nhỏ" (OtpUpdateDialog) sau đúng 5 phút.
 */
class AutoDeleteServerWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val KEY_GUIDS = "guids_to_delete"
        const val WORK_TAG  = "auto_delete_otp_server"
    }

    override fun doWork(): Result {
        val guidsToDelete = inputData.getStringArray(KEY_GUIDS) ?: return Result.success()
        if (guidsToDelete.isEmpty()) return Result.success()

        val selectedGuid = MmkvManager.getSelectServer()
        var needReselect = false
        var deletedCount = 0

        for (guid in guidsToDelete) {
            if (guid == null) continue

            // Kiểm tra trực tiếp qua profileFullStorage
            // KHÔNG dùng decodeAllServerList() vì MMKV lazy init có thể chưa load xong
            val config = MmkvManager.decodeServerConfig(guid)
            if (config == null) continue  // server không tồn tại hoặc đã bị xóa trước

            if (guid == selectedGuid) needReselect = true
            MmkvManager.removeServer(guid)
            deletedCount++
        }

        if (needReselect) {
            val firstRemaining = MmkvManager.decodeAllServerList().firstOrNull()
            if (!firstRemaining.isNullOrBlank()) {
                MmkvManager.setSelectServer(firstRemaining)
            }
        }

        // Nếu không xóa được gì dù GUID vẫn còn → retry sau 1 phút
        if (deletedCount == 0) {
            val anyStillExists = guidsToDelete.any { guid ->
                guid != null && MmkvManager.decodeServerConfig(guid) != null
            }
            if (anyStillExists) return Result.retry()
        }

        return Result.success()
    }
}