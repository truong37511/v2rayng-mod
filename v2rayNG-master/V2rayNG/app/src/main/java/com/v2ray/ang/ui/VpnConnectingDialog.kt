package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Overlay dialog hiển thị progress arc khi VPN đang khởi động.
 *
 * Flow:
 *   1. show() → arc chạy 0% → 100% trong 3000ms → hiện dấu tích, dừng chờ.
 *   2. notifyConnected() → VPN bật thành công → đóng dialog.
 *
 * Cách dùng:
 *   VpnConnectingDialog.show(context) {
 *       // callback này được gọi nếu timeout 10s xảy ra
 *       stopFabSpinLoop()
 *       binding.fab.isEnabled = true
 *   }
 *   // Khi VPN báo connected:
 *   VpnConnectingDialog.notifyConnected()
 */
object VpnConnectingDialog {

    private var currentDialog: Dialog? = null
    private var arcAnimator: ValueAnimator? = null
    private var arcViewRef: ArcProgressView? = null
    // Timeout tự đóng nếu VPN không bật được sau 10 giây (phòng treo dialog)
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ✅ FIX: timeoutRunnable không còn là val cố định — được tạo mới mỗi lần show()
    // để có thể gọi onTimeout callback về MainActivity cleanup FAB + fabSpinJob.
    // Trước đây: private val timeoutRunnable = Runnable { dismiss() }
    // → timeout chỉ đóng dialog, FAB vẫn disabled + xoay vĩnh viễn.
    private var timeoutRunnable: Runnable? = null

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * @param onTimeout Callback được gọi khi timeout 10 giây xảy ra (VPN không bật được).
     *                  Dùng để MainActivity có thể cleanup FAB: stopFabSpinLoop() + fab.isEnabled = true.
     */
    fun show(context: Context, onTimeout: (() -> Unit)? = null): Dialog {
        dismiss()

        val dp = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 20 * dp
            }
        }

        // Arc view
        val iconSize = (64 * dp).toInt()
        val arcView = ArcProgressView(context, dp).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).also {
                it.bottomMargin = (14 * dp).toInt()
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        arcViewRef = arcView

        // Label với dấu chấm chạy
        val label = TextView(context).apply {
            text = "Đang kết nối."
            textSize = 13f
            setTextColor(Color.parseColor("#185FA5"))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isSingleLine = true
        }

        root.addView(arcView)
        root.addView(label)

        // Animate dấu chấm: . → .. → ... → . lặp mỗi 500ms
        var dotCount = 1
        val dotRunnable = object : Runnable {
            override fun run() {
                if (label.isAttachedToWindow) {
                    label.text = "Đang khởi động máy chủ" + ".".repeat(dotCount)
                    dotCount = if (dotCount >= 3) 1 else dotCount + 1
                    label.postDelayed(this, 500)
                }
            }
        }
        label.post(dotRunnable)

        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCancelable(false)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (240 * dp).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                setDimAmount(0.4f)
            }
        }

        dialog.setOnDismissListener {
            arcAnimator?.cancel()
            arcAnimator = null
            arcViewRef = null
        }

        dialog.show()

        // 0% → 100% trong 3000ms → hiện dấu tích, CHỜ notifyConnected() mới đóng
        arcAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            interpolator = DecelerateInterpolator()
            addUpdateListener { arcView.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    arcView.showCheckmark = true
                }
            })
        }
        arcAnimator?.start()

        currentDialog = dialog

        // ✅ FIX: tạo Runnable mới mỗi lần show() để capture onTimeout callback
        // Sau khi dismiss(), gọi onTimeout để MainActivity cleanup FAB + fabSpinJob
        val newTimeoutRunnable = Runnable {
            dismiss()
            onTimeout?.invoke()
        }
        timeoutRunnable = newTimeoutRunnable
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutHandler.postDelayed(newTimeoutRunnable, 10_000L)

        return dialog
    }

    /**
     * Gọi khi VPN đã kết nối thành công → đóng dialog.
     * Dấu tích đã hiện từ giây thứ 3, giờ chỉ cần dismiss.
     */
    fun notifyConnected() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
        arcAnimator?.cancel()
        arcAnimator = null
        currentDialog?.takeIf { it.isShowing }?.dismiss()
        currentDialog = null
    }

    fun isShowing(): Boolean = currentDialog?.isShowing == true

    fun dismiss() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
        arcAnimator?.cancel()
        arcAnimator = null
        arcViewRef = null
        currentDialog?.takeIf { it.isShowing }?.dismiss()
        currentDialog = null
    }

    // ---------------------------------------------------------------------------
    // Inner View
    // ---------------------------------------------------------------------------

    private class ArcProgressView(context: Context, private val dp: Float) : View(context) {

        var progress: Float = 0f
            set(value) { field = value; invalidate() }

        var showCheckmark: Boolean = false
            set(value) { field = value; invalidate() }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6F1FB")
            style = Paint.Style.STROKE
            strokeWidth = 2 * dp
            strokeCap = Paint.Cap.ROUND
        }

        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#185FA5")
            style = Paint.Style.STROKE
            strokeWidth = 2 * dp
            strokeCap = Paint.Cap.ROUND
        }

        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#185FA5")
            style = Paint.Style.STROKE
            strokeWidth = 2 * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val tickPath = Path()
        private val oval = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val cx = w / 2f
            val cy = h / 2f
            val r = cx - trackPaint.strokeWidth
            oval.set(cx - r, cy - r, cx + r, cy + r)

            // Dấu tích căn giữa vòng tròn
            val s = cx * 0.38f
            tickPath.reset()
            tickPath.moveTo(cx - s, cy)
            tickPath.lineTo(cx - s * 0.1f, cy + s * 0.9f)
            tickPath.lineTo(cx + s, cy - s * 0.8f)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawArc(oval, -90f, 360f, false, trackPaint)
            canvas.drawArc(oval, -90f, progress * 360f, false, arcPaint)

            if (showCheckmark) {
                canvas.drawPath(tickPath, tickPaint)
            }
        }
    }
}