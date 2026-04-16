package com.v2ray.ang.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.v2ray.ang.util.OtpManager
import com.v2ray.ang.util.QrConfigFetcher
import com.v2ray.ang.worker.AutoDeleteServerWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Dialog nhập OTP → verify TOTP → gọi API lấy QR → import config vào V2RayNG.
 *
 * ⚠️ Các server được import bởi dialog này sẽ tự động bị XÓA sau đúng 5 phút,
 *    kể cả khi user đã tắt app (sử dụng WorkManager).
 *
 * @param activity        Activity cha
 * @param onImportSuccess Callback được gọi sau khi import thành công
 */
class OtpUpdateDialog(
    private val activity: AppCompatActivity,
    private val onImportSuccess: (() -> Unit)? = null
) {

    private lateinit var dialog: Dialog
    private lateinit var etOtp: EditText
    private lateinit var btnCancel: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView

    private enum class State { INPUT, LOADING, SUCCESS, ERROR }

    fun show() {
        dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = buildDialogView()
        dialog.setContentView(view)
        dialog.setCancelable(true)

        setupListeners()
        setState(State.INPUT)

        dialog.show()

        dialog.setOnDismissListener {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        etOtp.postDelayed({
            etOtp.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun buildDialogView(): android.view.View {
        val card = androidx.cardview.widget.CardView(activity).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(12).toFloat()
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val inner = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(24))
        }

        // Accent bar trên cùng — xanh dương
        val accentBar = android.view.View(activity).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#1976D2"), Color.parseColor("#42A5F5"))
            ).also {
                it.cornerRadii = floatArrayOf(
                    dp(4).toFloat(), dp(4).toFloat(),
                    dp(4).toFloat(), dp(4).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { bottomMargin = dp(20) }
        }
        inner.addView(accentBar)

        // Title
        tvTitle = TextView(activity).apply {
            text = "Admin mã nhỏ"
            textSize = 22f
            setTextColor(Color.parseColor("red"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        inner.addView(tvTitle)

        // Subtitle
        val tvSub = TextView(activity).apply {
            text = "Nhập mã xác minh từ Google Authenticator để tiếp tục."
            textSize = 13f
            setTextColor(Color.parseColor("#607D8B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        inner.addView(tvSub)

        // Hint
        val tvHint = TextView(activity).apply {
            text = "Chức năng chỉ dành cho Admin"
            textSize = 15f
            setTextColor(Color.parseColor("blue"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        inner.addView(tvHint)

        // OTP Input
        etOtp = EditText(activity).apply {
            hint = "******"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 32f
            setTextColor(Color.parseColor("#1565C0"))
            setHintTextColor(Color.parseColor("#B0BEC5"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            background = null
            letterSpacing = 0.3f
        }

        val etWrap = android.widget.FrameLayout(activity).apply {
            background = buildRoundedBorder()
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            addView(
                etOtp,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) }
            )
        }
        inner.addView(etWrap)

        etOtp.setOnClickListener {
            if (etOtp.text.isNotEmpty()) etOtp.setText("")
        }

        // Progress bar
        progressBar = ProgressBar(activity).apply {
            visibility = android.view.View.GONE
            isIndeterminate = true
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#1976D2"))
        }
        val pbWrap = android.widget.LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            addView(progressBar)
        }
        inner.addView(pbWrap)

        // Status text
        tvStatus = TextView(activity).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#E53935"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            gravity = android.view.Gravity.CENTER
            maxLines = 5
            isSingleLine = false
        }
        inner.addView(tvStatus)

        // Nút Đóng
        val btnRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            gravity = android.view.Gravity.END
        }

        btnCancel = Button(activity).apply {
            text = "Đóng"
            setTextColor(Color.parseColor("#1976D2"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#E3F2FD"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
            )
            setPadding(dp(20), 0, dp(20), 0)
        }

        btnRow.addView(btnCancel)
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    private fun buildRoundedBorder(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setStroke(dp(2), Color.parseColor("#90CAF9"))
            setColor(Color.parseColor("#F0F7FF"))
        }
    }

    private fun setupListeners() {
        etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s.toString().trim()
                if (code.length == 6) {
                    hideKeyboard()
                    handleVerifyAndFetch(code)
                }
            }
        })

        btnCancel.setOnClickListener { dialog.dismiss() }
    }

    private fun handleVerifyAndFetch(otpCode: String) {
        if (!OtpManager.verifyApi(otpCode)) {
            setState(State.ERROR, "Mã OTP không hợp lệ hoặc đã hết hạn. Vui lòng thử lại.")
            return
        }

        // OTP đúng → mở khóa nút tải TikTok ngay, không phụ thuộc kết quả import
        TiktokDownloadPermission.markVerified(activity)
        setState(State.LOADING)

        activity.lifecycleScope.launch(Dispatchers.IO) {

            // ── 1. Snapshot GUID hiện có TRƯỚC khi import ───────────────────
            val guidsBefore = com.v2ray.ang.handler.MmkvManager
                .decodeAllServerList().toHashSet()

            val result = QrConfigFetcher.fetchLatestConfig()

            withContext(Dispatchers.Main) {
                when (result) {
                    is QrConfigFetcher.FetchResult.Error -> {
                        setState(State.ERROR, result.message)
                    }

                    is QrConfigFetcher.FetchResult.Success -> {
                        val imported = withContext(Dispatchers.IO) {
                            tryImportViaAngConfigManager(result.configString)
                        }

                        val guidsAfter = com.v2ray.ang.handler.MmkvManager
                            .decodeAllServerList().toHashSet()

                        val newGuids: Array<String?> =
                            (guidsAfter - guidsBefore).map { it as String? }.toTypedArray()

                        if (imported && newGuids.isNotEmpty()) {
                            scheduleAutoDeleteViaWorkManager(newGuids)
                            setState(
                                State.SUCCESS,
                                "✅ Cập nhật thành công\n" +
                                        "⏱ Server sẽ tự xóa sau 5 phút."
                            )
                            // Gọi onImportSuccess SAU KHI dialog đã dismiss
                            etOtp.postDelayed({
                                dialog.dismiss()
                                onImportSuccess?.invoke()
                            }, 3500)
                        } else {
                            setState(
                                State.ERROR,
                                "Gói đã có trên thiết bị.\nKhông cần nhập lại."
                            )
                        }
                    }       // đóng is QrConfigFetcher.FetchResult.Success
                }           // đóng when (result)
            }               // đóng withContext(Dispatchers.Main)
        }                   // đóng launch
    }                       // đóng handleVerifyAndFetch

    /**
     * Dùng WorkManager để đảm bảo xóa đúng 5 phút kể từ lúc nhập OTP,
     * kể cả khi user đã tắt app hoàn toàn.
     */
    private fun scheduleAutoDeleteViaWorkManager(guidsToDelete: Array<String?>) {
        val workManager = WorkManager.getInstance(activity.applicationContext)

        val inputData = Data.Builder()
            .putStringArray(AutoDeleteServerWorker.KEY_GUIDS, guidsToDelete)
            .build()

        val deleteRequest = OneTimeWorkRequestBuilder<AutoDeleteServerWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setInputData(inputData)
            .addTag(AutoDeleteServerWorker.WORK_TAG)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                1, TimeUnit.MINUTES  // retry sau 1 phút nếu Worker trả về Result.retry()
            )
            .build()

        // Mỗi lần import tạo job độc lập, không hủy job cũ
        workManager.enqueueUniqueWork(
            AutoDeleteServerWorker.WORK_TAG + "_" + System.currentTimeMillis(),
            androidx.work.ExistingWorkPolicy.KEEP,
            deleteRequest
        )
    }

    private fun tryImportViaAngConfigManager(configString: String): Boolean {
        return try {
            val (configCount, subCount) = com.v2ray.ang.handler.AngConfigManager.importBatchConfig(
                configString, "", false
            )
            configCount > 0 || subCount > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun setState(state: State, message: String = "") {
        when (state) {
            State.INPUT -> {
                progressBar.visibility = android.view.View.GONE
                tvStatus.text = ""
                btnCancel.isEnabled = true
                etOtp.isEnabled = true
            }
            State.LOADING -> {
                progressBar.visibility = android.view.View.VISIBLE
                tvStatus.text = "Đang lấy cấu hình từ máy chủ..."
                tvStatus.setTextColor(Color.parseColor("#F57C00"))
                btnCancel.isEnabled = false
                etOtp.isEnabled = false
            }
            State.SUCCESS -> {
                progressBar.visibility = android.view.View.GONE
                tvStatus.text = message
                tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                btnCancel.text = "Đóng"
                btnCancel.isEnabled = true
            }
            State.ERROR -> {
                progressBar.visibility = android.view.View.GONE
                tvStatus.text = message
                tvStatus.setTextColor(Color.parseColor("#E53935"))
                btnCancel.isEnabled = true
                etOtp.isEnabled = true
                // Xóa OTP cũ và focus lại để user nhập mã mới
                etOtp.postDelayed({
                    etOtp.setText("")
                    etOtp.requestFocus()
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
                }, 600)
            }
        }
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etOtp.windowToken, 0)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}