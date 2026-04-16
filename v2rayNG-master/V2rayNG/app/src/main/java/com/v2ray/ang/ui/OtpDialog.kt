package com.v2ray.ang.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.v2ray.ang.util.OtpManager

object OtpDialog {

    // isVerified dùng để phân biệt user thoát dialog sau khi verify thành công
    // hay bấm Hủy/back mà chưa verify
    private var isVerified = false

    fun show(context: Context, onSuccess: () -> Unit) {

        isVerified = false

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvTitle = TextView(context).apply {
            text = "Chức năng chỉ dành cho Admin"
            textSize = 14f
            setTextColor(Color.BLACK)
        }

        // ── 1 ô OTP duy nhất ─────────────────────────────────────────────
        val etOtp = EditText(context).apply {
            hint = "______"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 32f
            setTextColor(Color.parseColor("#1B5E20"))
            setHintTextColor(Color.parseColor("#A5D6A7"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#66BB6A"))
                setColor(Color.parseColor("#E8F5E9"))
            }
            letterSpacing = 0.3f
            setPadding(24, 20, 24, 20)
        }
        etOtp.setOnClickListener { etOtp.setText("") }

        val otpLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(etOtp, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // ── Divider ────────────────────────────────────────────────────────
        val divider = android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 24, 0, 20) }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }

        // ── Tiêu đề hướng dẫn ─────────────────────────────────────────────
        // ── Helper tạo từng mục hướng dẫn ─────────────────────────────────
        fun guideItem(icon: String, titleText: String, titleColor: String, bodyText: String, bodyColor: String = "#424242"): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14 }
            }
            row.addView(TextView(context).apply {
                text = "$icon  $titleText"
                textSize = 12.5f
                setTextColor(Color.parseColor(titleColor))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            })
            row.addView(TextView(context).apply {
                text = bodyText
                textSize = 12f
                setTextColor(Color.parseColor(bodyColor))
                setLineSpacing(0f, 1.3f)
            })
            return row
        }

        // ── Hộp cảnh báo "Tại sao phải cập nhật" ─────────────────────────
        val warningBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 28
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8E1"))
                cornerRadius = 10f
                setStroke(2, Color.parseColor("#FF6F00"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
        }

        val warningTitle = TextView(context).apply {
            text = "⚠️  Tại sao phải cập nhật liên tục?"
            textSize = 12.5f
            setTextColor(Color.parseColor("#BF360C"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }

        val warningContent = android.text.SpannableStringBuilder().apply {
            fun bold(s: String) {
                val start = length; append(s)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#BF360C")), start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            fun normal(s: String) { append(s) }
            bold("• TikTok liên tục cập nhật thuật toán phát hiện SIM.")
            normal(" Mỗi phiên bản mới có thể chặn lại SIM Trung Quốc.\n\n")
            bold("• Chúng tôi phát hành bản vá mới")
            normal(" sau mỗi lần TikTok thay đổi thuật toán.\n\n")
            bold("• Chỉ bản tải từ đây")
            normal(" mới đảm bảo hoạt động ổn định — không cập nhật qua Play Store.")
        }

        val warningBody = TextView(context).apply {
            setText(warningContent, android.widget.TextView.BufferType.SPANNABLE)
            textSize = 12f
            setTextColor(Color.parseColor("#3E2723"))
            setLineSpacing(0f, 1.3f)
        }

        warningBox.addView(warningTitle)
        warningBox.addView(warningBody)

        // ── Ghép tất cả vào layout rồi bọc trong ScrollView ───────────────
        layout.addView(tvTitle)
        layout.addView(otpLayout)
        layout.addView(divider)
        layout.addView(guideItem("📱", "Bản không tháo SIM Trung Quốc", "#1565C0",
            "Bạn có thể gắn SIM Trung Quốc và dùng bình thường mà không cần tháo SIM ra khi sử dụng TikTok."))
        layout.addView(warningBox)
        layout.addView(guideItem("🗑️", "Xóa bản TikTok cũ trước khi cài", "#C2185B",
            "Bắt buộc phải gỡ hoàn toàn mọi phiên bản TikTok hiện có. Nếu không gỡ sẽ không cài được.", "#C2185B"))
        layout.addView(guideItem("🚫", "Không cập nhật qua Cửa hàng Play", "#7B1FA2",
            "Tuyệt đối không cập nhật qua Google Play, APKPure hoặc chợ ứng dụng khác.", "#7B1FA2"))

        val activity = context as? androidx.appcompat.app.AppCompatActivity

        // Bọc trong ScrollView để cuộn được trên màn hình nhỏ
        val scrollView = android.widget.ScrollView(context).apply {
            addView(layout)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Xác minh OTP")
            .setView(scrollView)
            .setNegativeButton("Hủy") { d, _ ->
                d.dismiss()
            }
            .create()

        dialog.show()
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 32f
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

        etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s.toString().trim()
                if (code.length == 6) {
                    imm.hideSoftInputFromWindow(etOtp.windowToken, 0)
                    if (OtpManager.verify(code)) {
                        isVerified = true
                        TiktokDownloadPermission.markVerified(context)
                        dialog.dismiss()
                        onSuccess()
                    } else {
                        Toast.makeText(
                            context,
                            "Mã OTP không đúng, vui lòng thử lại!",
                            Toast.LENGTH_SHORT
                        ).show()
                        etOtp.postDelayed({
                            etOtp.setText("")
                            etOtp.requestFocus()
                            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
                        }, 600)
                    }
                }
            }
        })
    }
}