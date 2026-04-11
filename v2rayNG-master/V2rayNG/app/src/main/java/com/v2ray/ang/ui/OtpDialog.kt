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
import com.v2ray.ang.R
import com.v2ray.ang.util.OtpManager

object OtpDialog {

    // isVerified dùng để phân biệt user thoát dialog sau khi verify thành công
    // hay bấm Hủy/back mà chưa verify
    private var isVerified = false

    fun show(context: Context, onSuccess: () -> Unit) {

        isVerified = false

        val inputs = ArrayList<EditText>()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvTitle = TextView(context).apply {
            text = "Chức năng chỉ dành cho Admin"
            textSize = 14f
            setTextColor(Color.BLACK)
        }

        val otpLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        for (i in 0 until 6) {
            val et = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(100, 120).apply {
                    setMargins(8, 0, 8, 0)
                }
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(Color.BLACK)
                setBackgroundResource(R.drawable.otp_box)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                maxLines = 1
            }
            inputs.add(et)
            otpLayout.addView(et)
        }

        layout.addView(tvTitle)
        layout.addView(otpLayout)

        val activity = context as? androidx.appcompat.app.AppCompatActivity

        val dialog = AlertDialog.Builder(context)
            .setTitle("Xác minh OTP")
            .setView(layout)
            .setNegativeButton("Hủy") { _, _ ->
                // Bấm Hủy mà chưa verify → đóng Activity
                if (!isVerified) {
                    (context as? android.app.Activity)?.finish()
                }
            }
            .create()

        dialog.show()
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Xóa dim overlay khi dialog đóng — tránh màn hình bị xám sau khi dismiss
        dialog.setOnDismissListener {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Nếu user bấm back (không qua nút Hủy) mà chưa verify → đóng Activity
            if (!isVerified) {
                activity?.finish()
            }
        }

        inputs[0].requestFocus()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputs[0].postDelayed({
            imm.showSoftInput(inputs[0], InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        // ── Click vào bất kỳ ô nào → xóa toàn bộ và focus về ô đầu ──────
        inputs.forEachIndexed { i, et ->
            et.setOnClickListener {
                val code = inputs.joinToString("") { it.text.toString() }
                if (code.isNotEmpty()) {
                    inputs.forEach { box -> box.setText("") }
                    inputs[0].requestFocus()
                    imm.showSoftInput(inputs[0], InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        for (i in inputs.indices) {
            inputs[i].addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val text = s.toString()

                    if (text.length == 1 && i < 5) {
                        inputs[i + 1].requestFocus()
                    }

                    inputs[i].post {
                        val code = inputs.joinToString("") { it.text.toString() }
                        if (code.length == 6) {
                            if (OtpManager.verify(code)) {
                                isVerified = true
                                dialog.dismiss()
                                onSuccess()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Mã OTP không đúng, vui lòng thử lại!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                inputs.forEach { it.setText("") }
                                inputs[0].requestFocus()
                                imm.showSoftInput(inputs[0], InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            inputs[i].setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                    && event.action == android.view.KeyEvent.ACTION_DOWN
                    && inputs[i].text.isEmpty()
                    && i > 0
                ) {
                    inputs[i - 1].requestFocus()
                    inputs[i - 1].setText("")
                    true
                } else {
                    false
                }
            }
        }
    }
}