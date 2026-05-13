package com.v2ray.ang.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var btnCancel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val BLUE_DARK   = Color.parseColor("#0D47A1")
    private val BLUE_TEXT   = Color.parseColor("#1976D2")
    private val BLUE_BORDER = Color.parseColor("#90CAF9")
    private val HINT_COLOR  = Color.parseColor("#90CAF9")

    private enum class State { INPUT, LOADING, SUCCESS, ERROR }

    fun show() {
        dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)

        val view = buildDialogView()
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = (activity.resources.displayMetrics.heightPixels * 0.18f).toInt()
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            attributes = params
        }

        dialog.setOnDismissListener {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        setupListeners()
        setState(State.INPUT)

        etOtp.postDelayed({
            etOtp.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun buildDialogView(): android.view.View {
        val dp = activity.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // ── Wrapper padding kiri/kanan ──────────────────────────────────
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 0, 20.dp(), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Card ────────────────────────────────────────────────────────
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 28.dp(), 24.dp(), 20.dp())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 28.dp().toFloat()
            }
        }

        // ── Tiêu đề ──────────────────────────────────────────────────────
        card.addView(TextView(activity).apply {
            text = "Kích hoạt nhanh 5 phút"
            textSize = 18f
            setTextColor(BLUE_DARK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dp() })

        // ── Subtitle ─────────────────────────────────────────────────────
        card.addView(TextView(activity).apply {
            text = "Nhập mã xác minh từ Google Authenticator."
            textSize = 13f
            setTextColor(Color.parseColor("#607D8B"))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dp() })

        // ── Hint ─────────────────────────────────────────────────────────
        card.addView(TextView(activity).apply {
            text = "Chức năng chỉ dành cho Admin"
            textSize = 13f
            setTextColor(BLUE_TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 20.dp() })

        // ── OTP input ────────────────────────────────────────────────────
        etOtp = EditText(activity).apply {
            hint = "• • • • • •"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 20f
            setTextColor(BLUE_DARK)
            setHintTextColor(HINT_COLOR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            letterSpacing = 0.4f
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14.dp().toFloat()
                setStroke(2.dp(), BLUE_BORDER)
                setColor(Color.WHITE)
            }
        }
        etOtp.setOnClickListener { etOtp.setText("") }

        card.addView(etOtp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16.dp() })

        // ── Progress bar ─────────────────────────────────────────────────
        progressBar = ProgressBar(activity).apply {
            visibility = android.view.View.GONE
            isIndeterminate = true
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(BLUE_TEXT)
        }
        card.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 4.dp()
        })

        // ── Status text ──────────────────────────────────────────────────
        tvStatus = TextView(activity).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#E53935"))
            gravity = Gravity.CENTER
            maxLines = 5
            isSingleLine = false
            ellipsize = null
        }
        card.addView(tvStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8.dp() })

        // ── Divider ───────────────────────────────────────────────────────
        card.addView(android.view.View(activity).apply {
            setBackgroundColor(Color.parseColor("#E3F2FD"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
        ).apply {
            topMargin = 8.dp()
            bottomMargin = 12.dp()
        })

        // ── Nút HỦY ──────────────────────────────────────────────────────
        btnCancel = TextView(activity).apply {
            text = "HỦY"
            textSize = 14f
            setTextColor(BLUE_DARK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 4.dp(), 4.dp(), 0)
        }
        card.addView(btnCancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        wrapper.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return wrapper
    }

    private fun setupListeners() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s.toString().trim()
                if (code.length == 6) {
                    imm.hideSoftInputFromWindow(etOtp.windowToken, 0)
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

        TiktokDownloadPermission.markVerified(activity)
        setState(State.LOADING)

        activity.lifecycleScope.launch(Dispatchers.IO) {
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
                                "✅ Cập nhật thành công\n⏱ Server sẽ tự xóa sau 5 phút."
                            )
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
                    }
                }
            }
        }
    }

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
                1, TimeUnit.MINUTES
            )
            .build()
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
        val dp = activity.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()
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
                btnCancel.text = "ĐÓNG"
                btnCancel.isEnabled = true
            }
            State.ERROR -> {
                progressBar.visibility = android.view.View.GONE
                tvStatus.text = message
                tvStatus.setTextColor(Color.parseColor("#E53935"))
                btnCancel.isEnabled = true
                etOtp.isEnabled = true
                etOtp.postDelayed({
                    etOtp.setText("")
                    etOtp.requestFocus()
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
                }, 600)
            }
        }
    }
}