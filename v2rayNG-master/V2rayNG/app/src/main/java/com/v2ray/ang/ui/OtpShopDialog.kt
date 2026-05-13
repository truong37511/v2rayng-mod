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
import com.v2ray.ang.util.OtpManager
import com.v2ray.ang.util.SubConfigFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OtpShopDialog(
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

        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 0, 20.dp(), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

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
            text = "Kích hoạt đại lý"
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
            text = "Nhập mã xác minh từ Google Authenticator ."
            textSize = 13f
            setTextColor(Color.parseColor("#607D8B"))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dp() })

        // ── Hint ─────────────────────────────────────────────────────────
        card.addView(TextView(activity).apply {
            text = "Chức năng này chỉ dành cho Admin"
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

        setState(State.LOADING)

        activity.lifecycleScope.launch {
            when (val result = SubConfigFetcher.fetchSubConfig()) {
                is SubConfigFetcher.FetchResult.Success -> {
                    val imported = tryImportSubContent(result.subContent)
                    if (imported) {
                        val successColor = if (result.expireSource == "manual") "#1565C0" else "#1976D2"
                        setState(State.SUCCESS, "✅ Tải gói thành công — Gói đã được áp dụng.", successColor)
                        onImportSuccess?.invoke()
                        etOtp.postDelayed({ dialog.dismiss() }, 2000)
                    } else {
                        setState(
                            State.ERROR,
                            "Không thể tải server từ gói đăng ký.\nVui lòng kiểm tra kết nối và thử lại."
                        )
                    }
                }
                is SubConfigFetcher.FetchResult.Error -> {
                    setState(State.ERROR, result.message)
                }
            }
        }
    }

    private suspend fun tryImportSubContent(subContent: String): Boolean {
        return try {
            val trimmed = subContent.trim()
            withContext(Dispatchers.IO) {
                val (configCount, subCount) = com.v2ray.ang.handler.AngConfigManager.importBatchConfig(
                    trimmed, "", false
                )
                configCount > 0 || subCount > 0
            }
        } catch (e: Exception) {
            android.util.Log.e("OtpShopDialog", "tryImportSubContent exception", e)
            false
        }
    }

    private fun setState(state: State, message: String = "", successColor: String = "#1976D2") {
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
                tvStatus.text = "Đang tải gói đăng ký từ máy chủ..."
                tvStatus.setTextColor(Color.parseColor("#F57C00"))
                btnCancel.isEnabled = false
                etOtp.isEnabled = false
            }
            State.SUCCESS -> {
                progressBar.visibility = android.view.View.GONE
                tvStatus.text = message
                tvStatus.setTextColor(Color.parseColor(successColor))
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