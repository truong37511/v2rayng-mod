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
import android.widget.TextView
import android.widget.Toast
import com.v2ray.ang.util.OtpManager

object OtpDialog {

    private val BLUE_DARK   = Color.parseColor("#0D47A1")
    private val BLUE_TEXT   = Color.parseColor("#1976D2")
    private val BLUE_BORDER = Color.parseColor("#90CAF9")
    private val HINT_COLOR  = Color.parseColor("#90CAF9")

    fun show(context: Context, onSuccess: () -> Unit) {

        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // ── Card chứa toàn bộ nội dung ──────────────────────────────────
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 28.dp(), 24.dp(), 20.dp())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 28.dp().toFloat()
            }
        }

        // ── Tiêu đề ──────────────────────────────────────────────────────
        card.addView(TextView(context).apply {
            text = "Xác minh OTP"
            textSize = 18f
            setTextColor(BLUE_DARK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 20.dp() })

        // ── OTP input ────────────────────────────────────────────────────
        val etOtp = EditText(context).apply {
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
        ).apply { bottomMargin = 20.dp() })

        // ── Divider ───────────────────────────────────────────────────────
        card.addView(android.view.View(context).apply {
            setBackgroundColor(Color.parseColor("#E3F2FD"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
        ).apply { bottomMargin = 16.dp() })

        // ── Hướng dẫn ────────────────────────────────────────────────────
        val guides = listOf(
            "⏰" to "mã OTP có thời hạn 30 giây, khi nhận được mã phải nhập nhanh vào.",
            "\uD83D\uDCF1" to "Hỗ trợ SIM Trung Quốc, không cần tháo SIM khi dùng TikTok.",
            "\u26A0\uFE0F" to "TikTok cập nhật thuật toán thường xuyên. Các bản mod cũng phải cập nhật lại",
            "\uD83D\uDDD1\uFE0F" to "Cần xóa bỏ ứng dụng TikTok cũ trước khi bấm cài — chưa xóa sẽ không cài được.",
            "\uD83D\uDEAB" to "Sau khi cài xong, không được cập nhật lại nó qua CH Play hay bất kỳ chợ ứng dụng nào khác."
        )

        guides.forEachIndexed { index, (icon, text) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            row.addView(TextView(context).apply {
                this.text = icon
                textSize = 14f
                setPadding(0, 1.dp(), 10.dp(), 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            row.addView(TextView(context).apply {
                // Tô màu đỏ "30 giây" nếu có trong text
                val spannable = android.text.SpannableString(text)
                val keyword = "30 giây"
                val start = text.indexOf(keyword)
                if (start >= 0) {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(Color.RED),
                        start,
                        start + keyword.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.StyleSpan(Typeface.BOLD),
                        start,
                        start + keyword.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                this.text = spannable
                textSize = 12.5f
                setTextColor(BLUE_TEXT)
                setLineSpacing(4f, 1f)
                includeFontPadding = true
                setPadding(0, 0, 0, 2.dp())
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))

            card.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index < guides.lastIndex) bottomMargin = 12.dp()
            })
        }

        // ── Divider ───────────────────────────────────────────────────────
        card.addView(android.view.View(context).apply {
            setBackgroundColor(Color.parseColor("#E3F2FD"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
        ).apply {
            topMargin = 16.dp()
            bottomMargin = 12.dp()
        })

        // ── Nút Hủy ──────────────────────────────────────────────────────
        val btnCancel = TextView(context).apply {
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

        // ── Wrapper padding trái/phải ─────────────────────────────────────
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 0, 20.dp(), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // ── Dialog ───────────────────────────────────────────────────────
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(wrapper)
        dialog.setCanceledOnTouchOutside(true)

        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            val params = attributes
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = (context.resources.displayMetrics.heightPixels * 0.18f).toInt()
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            attributes = params
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            (context as? androidx.appcompat.app.AppCompatActivity)
                ?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        // ── Auto-show keyboard ───────────────────────────────────────────
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        etOtp.requestFocus()
        etOtp.postDelayed({ imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT) }, 200)

        // ── Auto-verify khi đủ 6 số ─────────────────────────────────────
        etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s?.toString()?.trim() ?: return
                if (code.length < 6) return

                imm.hideSoftInputFromWindow(etOtp.windowToken, 0)

                if (OtpManager.verify(code)) {
                    TiktokDownloadPermission.markVerified(context)
                    dialog.dismiss()
                    onSuccess()
                } else {
                    Toast.makeText(context, "Mã OTP không đúng, vui lòng thử lại!", Toast.LENGTH_SHORT).show()
                    etOtp.postDelayed({
                        etOtp.setText("")
                        etOtp.requestFocus()
                        imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
                    }, 600)
                }
            }
        })
    }
}