package com.v2ray.ang.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil

/**
 * Worker chạy ngầm (kể cả khi app bị tắt) để xóa các server
 * được import bởi "Admin mã nhỏ" (OtpUpdateDialog) sau đúng 5 phút.
 * Sau khi xóa server đang chọn → tự động tắt VPN luôn.
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
        var needStopVpn = false
        var deletedCount = 0

        for (guid in guidsToDelete) {
            if (guid == null) continue

            val config = MmkvManager.decodeServerConfig(guid)
            if (config == null) continue

            if (guid == selectedGuid) needStopVpn = true
            MmkvManager.removeServer(guid)
            deletedCount++
        }

        // Chuyển select sang server còn lại nếu có
        if (needStopVpn) {
            val firstRemaining = MmkvManager.decodeAllServerList().firstOrNull()
            if (!firstRemaining.isNullOrBlank()) {
                MmkvManager.setSelectServer(firstRemaining)
            }

            // Gửi lệnh tắt VPN về service — giống cách MainActivity.handleFabAction() làm
            MessageUtil.sendMsg2Service(applicationContext, AppConfig.MSG_STATE_STOP, "")
        }

        if (deletedCount == 0) {
            val anyStillExists = guidsToDelete.any { guid ->
                guid != null && MmkvManager.decodeServerConfig(guid) != null
            }
            if (anyStillExists) return Result.retry()
        }

        return Result.success()
    }
}