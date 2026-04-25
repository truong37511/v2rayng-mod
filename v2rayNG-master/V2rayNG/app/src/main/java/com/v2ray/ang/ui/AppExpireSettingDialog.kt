package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.handler.AppExpireManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog cho Admin cài đặt ngày hết hạn app.
 */
class AppExpireSettingDialog(
    private val context: Context,
    private val onChanged: () -> Unit = {}
) {

    private val dp  = context.resources.displayMetrics.density
    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    private data class PickerItem(val picker: NumberPicker, val label: String)

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, (10 * dp).toInt())
        }

        // ── Tiêu đề ────────────────────────────────────────────────────────
        root.addView(TextView(context).apply {
            text = "⏰  Cài đặt hạn sử dụng App"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * dp).toInt() }
        })

        root.addView(TextView(context).apply {
            text = "Tới thời điểm này, nút kết nối VPN sẽ bị khóa"
            textSize = 11f
            setTextColor(Color.parseColor("#546E7A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        })

        // ── Divider ────────────────────────────────────────────────────────
        root.addView(android.view.View(context).apply {
            setBackgroundColor(Color.parseColor("#E3E8F0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply { bottomMargin = (8 * dp).toInt() }
        })

        // ── Card ngày hết hạn hiện tại ─────────────────────────────────────
        val currentTs = AppExpireManager.getExpireTimestamp(context)
        val tvCurrentDate = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        updateCurrentDateText(tvCurrentDate, currentTs)

        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (6 * dp).toInt()
            setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F7FF"))
                cornerRadius = 10 * dp
                setStroke((1.5 * dp).toInt(), Color.parseColor("#C5CAE9"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
            addView(TextView(context).apply {
                text = "Ngày hết hạn hiện tại"
                textSize = 11f
                setTextColor(Color.parseColor("#7986CB"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * dp).toInt() }
            })
            addView(tvCurrentDate)
        })

        // ── Calendar khởi tạo ─────────────────────────────────────────────
        val cal = Calendar.getInstance()
        if (currentTs > 0L) cal.timeInMillis = currentTs

        // ── Picker section (luôn hiển thị để chỉnh sửa) ──────────────────
        val pickerSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.VISIBLE
        }

        pickerSection.addView(makeSectionLabel("🕐  Giờ — Phút — Giây"))

        val npHour   = makeNumberPicker(0, 23, cal.get(Calendar.HOUR_OF_DAY))
        val npMinute = makeNumberPicker(0, 59, cal.get(Calendar.MINUTE))
        val npSecond = makeNumberPicker(0, 59, cal.get(Calendar.SECOND))

        pickerSection.addView(
            buildPickerRow(
                PickerItem(npHour,   "Giờ"),
                PickerItem(npMinute, "Phút"),
                PickerItem(npSecond, "Giây")
            ).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FF"))
                    cornerRadius = 10 * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#C5CAE9"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() }
            }
        )

        pickerSection.addView(makeSectionLabel("📅  Ngày — Tháng — Năm"))

        val npDay   = makeNumberPicker(1, 31, cal.get(Calendar.DAY_OF_MONTH))
        val npMonth = makeNumberPicker(1, 12, cal.get(Calendar.MONTH) + 1)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val npYear  = makeNumberPicker(currentYear, currentYear + 20, cal.get(Calendar.YEAR))

        pickerSection.addView(
            buildPickerRow(
                PickerItem(npDay,   "Ngày"),
                PickerItem(npMonth, "Tháng"),
                PickerItem(npYear,  "Năm")
            ).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FF"))
                    cornerRadius = 10 * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#C5CAE9"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * dp).toInt() }
            }
        )

        root.addView(pickerSection)

        // ── Nút LƯU ───────────────────────────────────────────────────────
        val btnSave = Button(context).apply {
            text = "💾  Lưu ngày hết hạn"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * dp).toInt()
            ).apply { bottomMargin = (6 * dp).toInt() }
            setPadding(0, 0, 0, 0)
        }
        root.addView(btnSave)

        // ── Nút XÓA ───────────────────────────────────────────────────────
        val btnClear = Button(context).apply {
            text = "🗑️  Xóa giới hạn (không chặn)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B71C1C"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFEBEE"))
                cornerRadius = 10 * dp
                setStroke((1.5 * dp).toInt(), Color.parseColor("#EF9A9A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * dp).toInt()
            )
            setPadding(0, 0, 0, 0)
        }
        root.addView(btnClear)

        // ── Dựng dialog ────────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(root) })
            .setNegativeButton("Đóng") { d, _ -> d.dismiss() }
            .create()

        btnSave.setOnClickListener {
            val day   = npDay.value
            val month = npMonth.value - 1
            val year  = npYear.value
            val hour  = npHour.value
            val min   = npMinute.value
            val sec   = npSecond.value

            val newCal = Calendar.getInstance().apply {
                set(year, month, day, hour, min, sec)
                set(Calendar.MILLISECOND, 0)
            }
            if (newCal.get(Calendar.DAY_OF_MONTH) != day) {
                Toast.makeText(context, "⚠️ Ngày không hợp lệ cho tháng đã chọn!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newCal.timeInMillis <= System.currentTimeMillis()) {
                Toast.makeText(context, "⚠️ Thời điểm hết hạn phải ở tương lai!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Hiện AdminOtpDialog — nhập đủ 6 số đúng mới lưu
            AdminOtpDialog(context, title = "Xác nhận lưu ngày hết hạn") {
                showSaveConfirmDialog(dialog, tvCurrentDate, btnSave, newCal, pickerSection)
            }.show()
        }

        btnClear.setOnClickListener {
            AdminOtpDialog(context, title = "Xác nhận xóa giới hạn") {
                showClearConfirmDialog(dialog, tvCurrentDate, btnSave)
            }.show()
        }

        dialog.show()
        styleDialog(dialog)
    }

    // ── Label section: chữ lớn hơn, màu xanh dương ───────────────────────
    private fun makeSectionLabel(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize  = 12f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1565C0"))    // xanh dương
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
        }

    // ── NumberPicker nhỏ gọn ─────────────────────────────────────────────
    private fun makeNumberPicker(min: Int, max: Int, initValue: Int): NumberPicker =
        NumberPicker(context).apply {
            minValue = min
            maxValue = max
            value    = initValue.coerceIn(min, max)
            wrapSelectorWheel = (min == 0)
            // Thu nhỏ picker bằng scale
            scaleX = 0.75f
            scaleY = 0.75f
        }

    // ── Row 3 picker ──────────────────────────────────────────────────────
    private fun buildPickerRow(
        a: PickerItem,
        b: PickerItem,
        c: PickerItem
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            val padV = (2 * dp).toInt()
            setPadding(0, padV, 0, padV)
        }

        listOf(a, b, c).forEachIndexed { idx, item ->
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            col.addView(item.picker)
            col.addView(TextView(context).apply {
                text     = item.label
                textSize = 12.5f                          // lớn hơn bản cũ (11f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1565C0")) // xanh dương
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (-4 * dp).toInt() } // kéo sát picker hơn vì scale
            })
            row.addView(col)

            if (idx < 2) {
                row.addView(android.view.View(context).apply {
                    setBackgroundColor(Color.parseColor("#D0D7E8"))
                    layoutParams = LinearLayout.LayoutParams(
                        (1 * dp).toInt(), (40 * dp).toInt()
                    )
                })
            }
        }
        return row
    }

    // ── Dialog xác nhận LƯU ───────────────────────────────────────────────
    private fun showSaveConfirmDialog(
        parentDialog: AlertDialog,
        tvCurrentDate: TextView,
        btnSave: Button,
        newCal: Calendar,
        pickerSection: LinearLayout? = null
    ) {
        val newTs   = newCal.timeInMillis
        val dateStr = sdf.format(newCal.time)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p, p, (12 * dp).toInt())
        }
        root.addView(TextView(context).apply {
            text     = "⚠️  Xác nhận lưu ngày hết hạn?"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E65100"))
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        })
        root.addView(TextView(context).apply {
            text     = dateStr
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B71C1C"))
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        })
        root.addView(TextView(context).apply {
            text     = "Tới thời điểm này, nút kết nối VPN sẽ bị khóa\nvà VPN đang chạy sẽ tự dừng."
            textSize = 13f
            setTextColor(Color.parseColor("#424242"))
            gravity  = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        })

        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton("✅  Xác nhận lưu") { d, _ ->
                AppExpireManager.setExpireDate(context, newTs)
                updateCurrentDateText(tvCurrentDate, newTs)
                btnSave.text = "💾  Lưu ngày hết hạn"
                // pickerSection vẫn VISIBLE để tiếp tục chỉnh sửa nếu cần
                d.dismiss()
                parentDialog.dismiss()
                onChanged()
                Toast.makeText(context, "✅ Đã lưu: hết hạn $dateStr", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy") { d, _ -> d.dismiss() }
            .create()
            .also { dlg ->
                dlg.show()
                styleDialog(dlg)
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#1565C0"))
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#757575"))
            }
    }

    // ── Dialog xác nhận XÓA ───────────────────────────────────────────────
    private fun showClearConfirmDialog(
        parentDialog: AlertDialog,
        tvCurrentDate: TextView,
        btnSave: Button
    ) {
        if (AppExpireManager.getExpireTimestamp(context) == 0L) {
            Toast.makeText(context, "ℹ️ Chưa có ngày hết hạn nào được cài.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle("🗑️  Xóa giới hạn hạn sử dụng?")
            .setMessage("App sẽ không còn bị chặn theo ngày hết hạn nữa.\n\nBạn có chắc chắn?")
            .setPositiveButton("Xóa") { d, _ ->
                AppExpireManager.clear(context)
                updateCurrentDateText(tvCurrentDate, 0L)
                btnSave.text = "💾  Lưu ngày hết hạn"
                d.dismiss()
                onChanged()
                Toast.makeText(context, "✅ Đã xóa giới hạn hạn sử dụng.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy") { d, _ -> d.dismiss() }
            .create()
            .also { dlg ->
                dlg.show()
                styleDialog(dlg)
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#B71C1C"))
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#757575"))
            }
    }

    // ── Cập nhật text ngày hết hạn hiện tại ──────────────────────────────
    private fun updateCurrentDateText(tv: TextView, timestampMs: Long) {
        if (timestampMs == 0L) {
            tv.text = "Chưa cài (không giới hạn)"
            tv.setTextColor(Color.parseColor("#388E3C"))
        } else {
            val dateStr   = sdf.format(Date(timestampMs))
            val isExpired = System.currentTimeMillis() > timestampMs
            if (isExpired) {
                tv.text = "⚠️  $dateStr\n(ĐÃ HẾT HẠN)"
                tv.setTextColor(Color.parseColor("#B71C1C"))
            } else {
                val diffMs    = timestampMs - System.currentTimeMillis()
                val diffDays  = diffMs / (1000L * 60 * 60 * 24)
                val diffHrs   = (diffMs % (1000L * 60 * 60 * 24)) / (1000L * 60 * 60)
                val diffMins  = (diffMs % (1000L * 60 * 60)) / (1000L * 60)
                val remaining = when {
                    diffDays > 0 -> "còn $diffDays ngày $diffHrs giờ"
                    diffHrs  > 0 -> "còn $diffHrs giờ $diffMins phút"
                    else         -> "còn $diffMins phút"
                }
                tv.text = "$dateStr\n($remaining)"
                tv.setTextColor(Color.parseColor("#1565C0"))
            }
        }
    }

    // ── Style dialog ──────────────────────────────────────────────────────
    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24 * dp
            }
        )
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}