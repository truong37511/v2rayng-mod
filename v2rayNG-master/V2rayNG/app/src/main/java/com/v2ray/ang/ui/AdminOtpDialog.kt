package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Dialog OTP đơn giản, chỉ dùng để xác minh Admin.
 * KHÔNG chứa nội dung hướng dẫn TikTok.
 * Dùng cho: Settings, Routing Setting, và bất kỳ chức năng Admin nào
 * không liên quan đến tải TikTok.
 *
 * @param context Context (Activity)
 * @param title   Tiêu đề hiển thị trong dialog
 * @param onSuccess Callback khi xác minh OTP thành công
 */
class AdminOtpDialog(
    private val context: Context,
    private val title: String = "Xác minh Admin",
    private val onSuccess: () -> Unit
) {

    fun show() {
        val activity = context as? AppCompatActivity
        val dp = context.resources.displayMetrics.density

        // ── Root layout ───────────────────────────────────────────────────
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }

        // ── Icon + Tiêu đề ────────────────────────────────────────────────
        val tvTitle = TextView(context).apply {
            text = "🔐  $title"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        }

        val tvSub = TextView(context).apply {
            text = "Nhập mã 6 số từ Google Authenticator"
            textSize = 13f
            setTextColor(Color.parseColor("#546E7A"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (22 * dp).toInt() }
        }

        layout.addView(tvTitle)
        layout.addView(tvSub)

        // ── 1 ô OTP duy nhất ─────────────────────────────────────────────
        val etOtp = EditText(context).apply {
            hint = "______"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 32f
            setTextColor(Color.parseColor("#1B5E20"))
            setHintTextColor(Color.parseColor("#A5D6A7"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (20 * dp)
                setColor(Color.parseColor("#E8F5E9"))
                setStroke((2 * dp).toInt(), Color.parseColor("#66BB6A"))
            }
            letterSpacing = 0.3f
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        etOtp.setOnClickListener { etOtp.setText("") }
        layout.addView(etOtp)

        // ── Dòng trạng thái lỗi ───────────────────────────────────────────
        val tvError = TextView(context).apply {
            text = ""
            textSize = 12.5f
            setTextColor(Color.parseColor("#C62828"))
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
            }
        }
        layout.addView(tvError)

        val scrollView = android.widget.ScrollView(context).apply { addView(layout) }

        // ── Tạo dialog ────────────────────────────────────────────────────
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(scrollView)
            .setNegativeButton("Hủy") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 28 * dp
            }
        )

        dialog.setOnDismissListener {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        etOtp.requestFocus()
        etOtp.postDelayed({
            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        // ── TextWatcher ───────────────────────────────────────────────────
        etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s.toString().trim()
                if (code.length == 6) {
                    imm.hideSoftInputFromWindow(etOtp.windowToken, 0)
                    if (com.v2ray.ang.util.OtpManager.verify(code)) {
                        (etOtp.background as? android.graphics.drawable.GradientDrawable)
                            ?.setStroke((2 * dp).toInt(), Color.parseColor("#2E7D32"))
                        dialog.dismiss()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSuccess()
                        }
                    } else {
                        (etOtp.background as? android.graphics.drawable.GradientDrawable)
                            ?.setStroke((2 * dp).toInt(), Color.parseColor("#C62828"))
                        tvError.text = "❌  Mã OTP không đúng hoặc đã hết hạn!"
                        etOtp.postDelayed({
                            etOtp.setText("")
                            (etOtp.background as? android.graphics.drawable.GradientDrawable)
                                ?.setStroke((2 * dp).toInt(), Color.parseColor("#66BB6A"))
                            tvError.text = ""
                            etOtp.requestFocus()
                            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
                        }, 800)
                    }
                }
            }
        })
    }
}