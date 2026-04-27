package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Dialog OTP đơn giản, chỉ dùng để xác minh Admin.
 * KHÔNG chứa nội dung hướng dẫn TikTok.
 * Dùng cho: Settings, Routing Setting, và bất kỳ chức năng Admin nào
 * không liên quan đến tải TikTok.
 *
 * @param context   Context (Activity)
 * @param title     Tiêu đề hiển thị trong dialog
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
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * dp).toInt(),
                (22 * dp).toInt(),
                (20 * dp).toInt(),
                (16 * dp).toInt()
            )
        }

        // ── Icon khoá ────────────────────────────────────────────────────
        val tvIcon = TextView(context).apply {
            text = "🔐"
            textSize = 28f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }

        // ── Tiêu đề ───────────────────────────────────────────────────────
        val tvTitle = TextView(context).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
        }

        // ── Subtitle ──────────────────────────────────────────────────────
        val tvSub = TextView(context).apply {
            text = "Nhập mã 6 số từ Authenticator"
            textSize = 12f
            setTextColor(Color.parseColor("#78909C"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (18 * dp).toInt() }
        }

        layout.addView(tvIcon)
        layout.addView(tvTitle)
        layout.addView(tvSub)

        // ── Ô nhập OTP ────────────────────────────────────────────────────
        val etOtp = EditText(context).apply {
            hint = "· · · · · ·"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 28f
            setTextColor(Color.parseColor("#1B5E20"))
            setHintTextColor(Color.parseColor("#B0BEC5"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            letterSpacing = 0.4f
            setPadding(
                (12 * dp).toInt(),
                (12 * dp).toInt(),
                (12 * dp).toInt(),
                (12 * dp).toInt()
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * dp
                setColor(Color.parseColor("#F1F8E9"))
                setStroke((1.5 * dp).toInt(), Color.parseColor("#81C784"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        }
        etOtp.setOnClickListener { etOtp.setText("") }
        layout.addView(etOtp)

        // ── Dòng lỗi ─────────────────────────────────────────────────────
        val tvError = TextView(context).apply {
            text = ""
            textSize = 11.5f
            setTextColor(Color.parseColor("#E53935"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (2 * dp).toInt()
            }
        }
        layout.addView(tvError)

        // ── Tạo dialog ────────────────────────────────────────────────────
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(layout)
            .setNegativeButton("Huỷ") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        // ── Giới hạn chiều rộng: tối đa 300dp, min 82% màn hình ─────────
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 22 * dp
                    setStroke((1.5 * dp).toInt(), Color.parseColor("#3949AB"))
                }
            )
            val maxWidthPx = (300 * dp).toInt()
            val screenWidth = context.resources.displayMetrics.widthPixels
            val w = minOf(maxWidthPx, (screenWidth * 0.82f).toInt())
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        dialog.setOnDismissListener {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        etOtp.requestFocus()
        etOtp.postDelayed({
            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        // ── TextWatcher: tự xác minh khi nhập đủ 6 số ───────────────────
        etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s.toString().trim()
                if (code.length < 6) {
                    // Đang nhập → reset border về bình thường
                    (etOtp.background as? android.graphics.drawable.GradientDrawable)
                        ?.setStroke((1.5 * dp).toInt(), Color.parseColor("#81C784"))
                    tvError.text = ""
                    return
                }

                imm.hideSoftInputFromWindow(etOtp.windowToken, 0)

                if (com.v2ray.ang.util.OtpManager.verify(code)) {
                    // ✅ Đúng → border xanh đậm → dismiss → callback
                    (etOtp.background as? android.graphics.drawable.GradientDrawable)
                        ?.setStroke((2 * dp).toInt(), Color.parseColor("#2E7D32"))
                    dialog.dismiss()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onSuccess()
                    }
                } else {
                    // ❌ Sai → border đỏ → thông báo → xoá sau 800ms
                    (etOtp.background as? android.graphics.drawable.GradientDrawable)
                        ?.setStroke((2 * dp).toInt(), Color.parseColor("#E53935"))
                    tvError.text = "❌  Mã không đúng hoặc đã hết hạn"
                    etOtp.postDelayed({
                        etOtp.setText("")
                        (etOtp.background as? android.graphics.drawable.GradientDrawable)
                            ?.setStroke((1.5 * dp).toInt(), Color.parseColor("#81C784"))
                        tvError.text = ""
                        etOtp.requestFocus()
                        imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
                    }, 800)
                }
            }
        })
    }
}