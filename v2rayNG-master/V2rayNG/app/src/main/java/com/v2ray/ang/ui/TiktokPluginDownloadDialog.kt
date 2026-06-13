package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Progress bar ──────────────────────────────────────────────────────────────
private class TiktokPluginProgressBarView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#DBEEFF")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#2196F3")
    }
    var progress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val dp = resources.displayMetrics.density
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (6 * dp).toInt())
    }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, trackPaint)
        if (progress > 0f)
            c.drawRoundRect(0f, 0f, width * progress, height.toFloat(), r, r, fillPaint)
    }
}

// ── Pulse icon view ───────────────────────────────────────────────────────────
private class PluginPulseIconView(
    context: Context,
    private val circleColor: Int,
    private val iconBmp: Bitmap
) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = circleColor
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var pulseScale = 1f
    private var pulseAlpha = 0f
    private var animator: ValueAnimator? = null

    init { startPulse() }

    private fun startPulse() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                pulseScale = 1f + t * 0.28f
                pulseAlpha = (1f - t) * 0.35f
                invalidate()
            }
        }
        animator?.start()
    }

    fun stopPulse() { animator?.cancel() }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stopPulse() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val baseR = minOf(width, height) / 2f
        pulsePaint.color = circleColor
        pulsePaint.alpha = (pulseAlpha * 255).toInt()
        c.drawCircle(cx, cy, baseR * pulseScale, pulsePaint)
        bgPaint.alpha = 255
        c.drawCircle(cx, cy, baseR * 0.92f, bgPaint)
        val iconSize = (baseR * 1.0f).toInt()
        val left = (cx - iconSize / 2f).toInt()
        val top = (cy - iconSize / 2f).toInt()
        c.drawBitmap(iconBmp,
            Rect(0, 0, iconBmp.width, iconBmp.height),
            Rect(left, top, left + iconSize, top + iconSize), null)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w).coerceAtMost(MeasureSpec.getSize(h))
        setMeasuredDimension(s, s)
    }
}

class TiktokPluginDownloadDialog(
    private val activity: AppCompatActivity,
    private val isVpnRunning: Boolean = false
) {
    private val BLUE            = Color.parseColor("#2196F3")
    private val BLUE_DARK       = Color.parseColor("#0D47A1")
    private val BLUE_BG         = Color.parseColor("#DBEEFF")
    private val BLUE_BADGE_TEXT = Color.parseColor("#0C447C")
    private val X_COLOR         = Color.parseColor("#2196F3")
    private val X_BG            = Color.parseColor("#DBEEFF")

    private val APK_URL  = "https://vutruongvpn.com/downloads/tiktokplusgin.apk"
    private val PING_URL = "https://connectivitycheck.gstatic.com/generate_204"

    private val PREFS     = "tiktok_plugin_download_prefs"

    private val ALLOWED_DOMAINS = listOf(
        "zhenxishop.com",
        "chinavpn.fun",
        "chinavpn.vn",
        "vutruongvpn.com"
    )

    private fun isSubAllowed(): Boolean {
        return try {
            val subs = com.v2ray.ang.handler.MmkvManager.decodeSubscriptions()
            subs.any { cache ->
                val url = cache.subscription.url ?: return@any false
                ALLOWED_DOMAINS.any { domain ->
                    url.contains(domain, ignoreCase = true)
                }
            }
        } catch (_: Exception) { false }
    }

    private fun showSubBlockedDialog() {
        val root = android.widget.RelativeLayout(activity).apply { setPadding(dp(20), 0, dp(20), 0) }
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE); cornerRadius = dp(20).toFloat()
                setStroke(dp(2), android.graphics.Color.parseColor("#2196F3"))
            }
            setPadding(dp(24), dp(32), dp(24), dp(28)); id = android.view.View.generateViewId()
        }
        card.addView(android.widget.TextView(activity).apply {
            text = "⛔"; textSize = 37f; gravity = android.view.Gravity.CENTER
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        card.addView(android.widget.TextView(activity).apply {
            text = "Không thể sử dụng chức năng"
            textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#1565C0"))
            gravity = android.view.Gravity.CENTER
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
        card.addView(android.widget.TextView(activity).apply {
            text = "VPN bạn đang dùng không thuộc hệ thống của chúng tôi, liên hệ Wechat ID : Vutruong1692 để mua VPN và sử dụng chức năng này."
            textSize = 15f; setTextColor(android.graphics.Color.parseColor("#1565C0"))
            gravity = android.view.Gravity.CENTER; isSingleLine = false; maxLines = 4
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(card, android.widget.RelativeLayout.LayoutParams(
            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
            android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        val btnX = buildXButton()
        root.addView(btnX, android.widget.RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
            addRule(android.widget.RelativeLayout.ALIGN_TOP, card.id)
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            topMargin = dp(8); marginEnd = dp(8)
        })
        val d = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(root); d.setCanceledOnTouchOutside(false); d.setCancelable(false)
        d.show(); applyWindow(d)
        btnX.setOnClickListener { d.dismiss(); clearDim() }
        d.setOnDismissListener { clearDim() }
    }
    private val KEY_DATE  = "download_date"
    private val KEY_COUNT = "download_count"
    private val MAX_DAILY = 5

    private lateinit var dialog: Dialog
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: TiktokPluginProgressBarView
    private lateinit var tvPct: TextView
    private lateinit var tvMb: TextView
    private lateinit var tvSpeed: TextView
    private var pollJob: Job? = null
    @Volatile private var isPaused: Boolean = false

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * activity.resources.displayMetrics.density

    private fun todayStr() =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())

    private fun getRemainingDownloads(): Int {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_DATE, "") != todayStr()) return MAX_DAILY
        return MAX_DAILY - p.getInt(KEY_COUNT, 0)
    }

    private fun incrementDownloadCount() {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        val count = if (p.getString(KEY_DATE, "") == today) p.getInt(KEY_COUNT, 0) else 0
        p.edit().putString(KEY_DATE, today).putInt(KEY_COUNT, count + 1).apply()
    }

    private fun pingVpn(): Boolean {
        return try {
            val clientBuilder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            if (isVpnRunning) {
                val port = com.v2ray.ang.handler.SettingsManager.getHttpPort()
                clientBuilder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress("127.0.0.1", port)))
            }
            val response = clientBuilder.build()
                .newCall(okhttp3.Request.Builder().url(PING_URL).build()).execute()
            val code = response.code; response.close(); code == 204
        } catch (_: Exception) { false }
    }

    fun show() {
        if (!isSubAllowed()) { showSubBlockedDialog(); return }
        if (!isVpnRunning) { showVpnBlockedDialog(pingFailed = false); return }
        showConfirmDialog()
    }

    // ── Icon makers ───────────────────────────────────────────────────────────
    private fun makePluginIcon(sizePx: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = dp(2.2f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        val s = sizePx.toFloat()
        val path = Path().apply {
            moveTo(s * 0.20f, s * 0.38f)
            lineTo(s * 0.20f, s * 0.82f)
            lineTo(s * 0.80f, s * 0.82f)
            lineTo(s * 0.80f, s * 0.38f)
            lineTo(s * 0.62f, s * 0.38f)
            lineTo(s * 0.62f, s * 0.28f)
            lineTo(s * 0.52f, s * 0.28f)
            lineTo(s * 0.52f, s * 0.18f)
            lineTo(s * 0.48f, s * 0.18f)
            lineTo(s * 0.48f, s * 0.28f)
            lineTo(s * 0.38f, s * 0.28f)
            lineTo(s * 0.38f, s * 0.38f)
            close()
        }
        cvs.drawPath(path, p)
        cvs.drawLine(s * 0.20f, s * 0.55f, s * 0.80f, s * 0.55f, p)
        return bmp
    }

    private fun makeAlertIcon(sizePx: Int, color: Int, isCross: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = dp(2.5f)
            strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        if (isCross) {
            val pad = sizePx * 0.22f
            cvs.drawLine(pad, pad, sizePx - pad, sizePx - pad, p)
            cvs.drawLine(sizePx - pad, pad, pad, sizePx - pad, p)
        } else {
            val cx = sizePx / 2f
            cvs.drawLine(cx, sizePx * 0.15f, cx, sizePx * 0.60f, p)
            val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
            cvs.drawCircle(cx, sizePx * 0.80f, sizePx * 0.08f, pFill)
        }
        return bmp
    }

    private fun makeHourglassIcon(sizePx: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        }
        val s = sizePx.toFloat()
        cvs.drawLine(s * 0.2f, s * 0.12f, s * 0.8f, s * 0.12f, p)
        cvs.drawLine(s * 0.2f, s * 0.88f, s * 0.8f, s * 0.88f, p)
        cvs.drawPath(Path().apply {
            moveTo(s * 0.2f, s * 0.12f); lineTo(s * 0.5f, s * 0.5f); lineTo(s * 0.8f, s * 0.12f)
        }, p)
        cvs.drawPath(Path().apply {
            moveTo(s * 0.2f, s * 0.88f); lineTo(s * 0.5f, s * 0.5f); lineTo(s * 0.8f, s * 0.88f)
        }, p)
        return bmp
    }

    private fun makeXIcon(sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = X_COLOR; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        val pad = sizePx * 0.28f
        cvs.drawLine(pad, pad, sizePx - pad, sizePx - pad, p)
        cvs.drawLine(sizePx - pad, pad, pad, sizePx - pad, p)
        return bmp
    }

    private fun buildXButton(): ImageView {
        val sz = dp(32)
        return ImageView(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), X_COLOR)
            }
            setImageBitmap(makeXIcon(sz)); setPadding(dp(6), dp(6), dp(6), dp(6))
        }
    }

    // ── Dialog xác nhận ───────────────────────────────────────────────────────
    private fun showConfirmDialog() {
        val root = RelativeLayout(activity).apply { setPadding(dp(20), 0, dp(20), 0) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(2), Color.parseColor("#2196F3")) }
            setPadding(dp(24), dp(32), dp(24), dp(24)); id = View.generateViewId()
        }





        // Danh sách hướng dẫn phụ
        val infoBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(0), dp(4), dp(0))
        }
        fun makeEmojiBadge(emoji: String, bgHex: String): TextView {
            return TextView(activity).apply {
                text = emoji; textSize = 14f; gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor(bgHex))
                }
            }
        }

        data class PluginInfoRow(val emoji: String, val bgHex: String, val line: String, val isWarning: Boolean = false)
        listOf(
            PluginInfoRow("🌟", "#FFF9C4", "TikTok Plugin lựa chọn theo quốc gia."),
            PluginInfoRow("📦", "#E8F5E9", "Cài plugin sau khi đã cài xong TikTok.")
        ).forEachIndexed { i, item ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val badgeSize = dp(28)
            row.addView(makeEmojiBadge(item.emoji, item.bgHex),
                LinearLayout.LayoutParams(badgeSize, badgeSize).apply { rightMargin = dp(8) })
            row.addView(TextView(activity).apply {
                text = item.line; textSize = 13f
                setTextColor(Color.BLACK)
                isSingleLine = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            infoBox.addView(row, lp().apply { if (i > 0) topMargin = dp(10) })
        }
        card.addView(infoBox, lp().apply { bottomMargin = dp(14) })

        // Checkbox: tôi đã hiểu
        val checkBoxUnderstand = CheckBox(activity).apply {
            text = "Xác nhận"
            textSize = 14f; setTextColor(Color.parseColor("#2196F3"))
            isChecked = false
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            minimumHeight = 0
        }
        card.addView(checkBoxUnderstand, lp().apply { bottomMargin = dp(16) })

        val btnDownload = android.widget.Button(activity).apply {
            text = "TẢI XUỐNG"; textSize = 15f; setTextColor(Color.WHITE)
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AAAAAA")); cornerRadius = dp(14).toFloat()
            }
            minimumHeight = 0; minimumWidth = 0; isEnabled = false
        }
        card.addView(btnDownload, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(4) })

        root.addView(card, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        val btnX = buildXButton()
        root.addView(btnX, RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
            addRule(RelativeLayout.ALIGN_TOP, card.id); addRule(RelativeLayout.ALIGN_PARENT_END)
            topMargin = dp(8); marginEnd = dp(8)
        })

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(root); d.setCanceledOnTouchOutside(false); d.setCancelable(false)
        d.show(); applyWindow(d)
        btnX.setOnClickListener { d.dismiss(); clearDim() }
        d.setOnDismissListener { clearDim() }

        val updateBtn = {
            val ok = checkBoxUnderstand.isChecked
            btnDownload.isEnabled = ok
            btnDownload.background = GradientDrawable().apply {
                setColor(if (ok) BLUE else Color.parseColor("#AAAAAA"))
                cornerRadius = dp(14).toFloat()
            }
        }
        checkBoxUnderstand.setOnCheckedChangeListener { _, _ -> updateBtn() }
        btnDownload.setOnClickListener {
            d.dismiss(); clearDim()
            showMainDialog()
            pollJob = activity.lifecycleScope.launch {
                val pingOk = withContext(Dispatchers.IO) { pingVpn() }
                if (!pingOk) { dialog.dismiss(); showVpnBlockedDialog(pingFailed = true); return@launch }
                if (getRemainingDownloads() <= 0) { dialog.dismiss(); showLimitDialog(); return@launch }
                startDownload()
            }
        }
    }

    // ── Dialog VPN bị chặn ────────────────────────────────────────────────────
    private fun showVpnBlockedDialog(pingFailed: Boolean) {
        val root = RelativeLayout(activity).apply {
            setPadding(dp(20), 0, dp(20), 0)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
                setStroke(1, Color.parseColor("#BBDEFB"))
            }
            elevation = dp(20).toFloat()
            setPadding(dp(28), dp(36), dp(28), dp(32))
            id = View.generateViewId()
        }

        val iconSize = dp(72)
        val iconView = object : android.view.View(activity) {
            private var scanY = 0f
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private var running = true
            private val tick = object : Runnable {
                override fun run() {
                    if (!running) return
                    scanY += dp(0.8f)
                    if (scanY > iconSize.toFloat()) scanY = 0f
                    invalidate()
                    handler.postDelayed(this, 16)
                }
            }
            init { handler.post(tick) }
            override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f; val cy = height / 2f
                val accentColor = if (pingFailed) Color.parseColor("#FF3B30") else Color.parseColor("#2196F3")
                val bgFill = if (pingFailed) Color.parseColor("#FFF0EE") else Color.parseColor("#E3F2FD")
                canvas.drawCircle(cx, cy, width / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bgFill })
                val shieldPath = android.graphics.Path().apply {
                    val l = cx - dp(14f); val r = cx + dp(14f)
                    val t = cy - dp(16f); val b = cy + dp(16f)
                    moveTo(cx, t); lineTo(r, t + dp(5f)); lineTo(r, b - dp(8f))
                    cubicTo(r, b, cx + dp(6f), b + dp(4f), cx, b + dp(6f))
                    cubicTo(cx - dp(6f), b + dp(4f), l, b, l, b - dp(8f))
                    lineTo(l, t + dp(5f)); close()
                }
                canvas.drawPath(shieldPath, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor; alpha = 30; style = android.graphics.Paint.Style.FILL
                })
                canvas.drawBitmap(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
                    val c2 = android.graphics.Canvas(bmp); c2.clipPath(shieldPath)
                    c2.drawRect(0f, scanY - dp(2f), width.toFloat(), scanY + dp(2f),
                        android.graphics.Paint().apply { color = accentColor; alpha = 120 })
                }, 0f, 0f, null)
                canvas.drawPath(shieldPath, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor; style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(2.5f)
                })
                if (!pingFailed) {
                    canvas.drawLines(floatArrayOf(
                        cx - dp(7f), cy + dp(1f), cx - dp(2f), cy + dp(6f),
                        cx - dp(2f), cy + dp(6f), cx + dp(8f), cy - dp(5f)
                    ), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = accentColor; strokeWidth = dp(2.5f); strokeCap = android.graphics.Paint.Cap.ROUND
                    })
                } else {
                    canvas.drawRoundRect(cx - dp(2.5f), cy - dp(7f), cx + dp(2.5f), cy + dp(2f), dp(2f), dp(2f),
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })
                    canvas.drawCircle(cx, cy + dp(5.5f), dp(2.5f),
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })
                }
            }
        }
        card.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18)
        })

        card.addView(TextView(activity).apply {
            text = if (pingFailed) "Kiểm tra lại kết nối VPN rồi thử lại." else "Cần bật VPN để tải."
            textSize = 15f; setTextColor(Color.parseColor("#2196F3"))
            gravity = Gravity.CENTER; isSingleLine = false
        }, lp().apply { bottomMargin = dp(20) })

        val btnOk = android.widget.Button(activity).apply {
            text = "Đã hiểu"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2196F3"))
            backgroundTintList = null
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#2196F3"))
                cornerRadius = dp(14).toFloat()
            }
            minimumHeight = 0; minimumWidth = 0
            setPadding(0, dp(14), 0, dp(14))
        }
        card.addView(btnOk, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(20) })

        root.addView(card, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(root)
        d.setCanceledOnTouchOutside(false); d.setCancelable(false)
        d.show(); applyWindow(d)
        btnOk.setOnClickListener { d.dismiss(); clearDim() }
        d.setOnDismissListener { clearDim() }
    }


    // ── Dialog hết lượt ───────────────────────────────────────────────────────
    private fun showLimitDialog() {
        val root = RelativeLayout(activity).apply { setPadding(dp(20), 0, dp(20), 0) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(2), Color.parseColor("#2196F3")) }
            setPadding(dp(24), dp(32), dp(24), dp(28)); id = View.generateViewId()
        }

        val iconBmp = makeHourglassIcon(dp(34), Color.parseColor("#5856D6"))
        val pulseView = PluginPulseIconView(activity, Color.parseColor("#EDE7F6"), iconBmp)
        card.addView(pulseView, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
        })

        card.addView(TextView(activity).apply {
            text = "Đã hết lượt tải hôm nay"; textSize = 17f
            setTextColor(Color.parseColor("#2196F3")); gravity = Gravity.CENTER
        }, lp().apply { bottomMargin = dp(10) })
        card.addView(TextView(activity).apply {
            text = "Bạn đã dùng hết $MAX_DAILY lượt tải trong ngày. Vui lòng quay lại vào ngày mai."
            textSize = 13f; setTextColor(Color.parseColor("#2196F3"))
            gravity = Gravity.CENTER; isSingleLine = false; maxLines = 4
        }, lp().apply { bottomMargin = dp(16) })

        val chip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F0FF")); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        chip.addView(TextView(activity).apply {
            text = "📅"; textSize = 16f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(8) })
        chip.addView(TextView(activity).apply {
            text = "Hạn mức đặt lại lúc 00:00 ngày mai"
            textSize = 13f; setTextColor(Color.parseColor("#2196F3"))
        })
        card.addView(chip, lp().apply { bottomMargin = dp(16) })

        // 2 dòng cảnh báo quan trọng
        listOf(
            "⚠️" to "Chỉ tải khi TikTok Plugin cũ không dùng được.",
            "🔒" to "Tải liên tục sẽ bị khóa lượt vĩnh viễn."
        ).forEachIndexed { i, (emoji, line) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            }
            row.addView(TextView(activity).apply {
                text = emoji; textSize = 16f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            row.addView(TextView(activity).apply {
                text = line; textSize = 15f; setTextColor(Color.parseColor("#1565C0"))
                isSingleLine = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row, lp().apply { if (i > 0) topMargin = dp(8) })
        }

        root.addView(card, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        val btnX = buildXButton()
        root.addView(btnX, RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
            addRule(RelativeLayout.ALIGN_TOP, card.id); addRule(RelativeLayout.ALIGN_PARENT_END)
            topMargin = dp(8); marginEnd = dp(8)
        })

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(root); d.setCanceledOnTouchOutside(false); d.setCancelable(false)
        d.show(); applyWindow(d)
        btnX.setOnClickListener { pulseView.stopPulse(); d.dismiss(); clearDim() }
        d.setOnDismissListener { pulseView.stopPulse(); clearDim() }
    }

    // ── Dialog đang tải ───────────────────────────────────────────────────────
    private fun showMainDialog() {
        dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(buildMainView())
        dialog.setCanceledOnTouchOutside(false); dialog.setCancelable(false)
        dialog.show(); applyWindow(dialog)
        dialog.setOnDismissListener { clearDim(); pollJob?.cancel() }
    }

    private fun buildMainView(): View {
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0)
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(2), Color.parseColor("#2196F3")) }
        }
        val topSection = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), 0); gravity = Gravity.CENTER_VERTICAL
        }
        val iconBg = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(BLUE_BG); cornerRadius = dp(12).toFloat() }
        }
        iconBg.addView(ImageView(activity).apply { setImageBitmap(makePluginIcon(dp(18), BLUE_DARK)) },
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        val infoCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
        }
        infoCol.addView(TextView(activity).apply {
            text = "TikTok Plugin"; textSize = 16f
            setTextColor(Color.parseColor("#1565C0")); typeface = Typeface.DEFAULT_BOLD
        })
        infoCol.addView(TextView(activity).apply {
            text = "Đang tải xuống..."
            textSize = 13f; setTextColor(Color.parseColor("#1976D2"))
        })
        tvPct = TextView(activity).apply {
            text = "0%"; textSize = 14f; setTextColor(BLUE_BADGE_TEXT); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(BLUE_BG); cornerRadius = dp(20).toFloat() }
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }
        topSection.addView(iconBg)
        topSection.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topSection.addView(tvPct)

        val midSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), 0)
        }
        progressBar = TiktokPluginProgressBarView(activity)
        midSection.addView(progressBar, lp().apply { bottomMargin = dp(10) })
        val statsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        tvMb = TextView(activity).apply { text = "0 MB / -- MB"; textSize = 13f; setTextColor(Color.parseColor("#2196F3")) }
        tvSpeed = TextView(activity).apply { text = ""; textSize = 13f; setTextColor(Color.parseColor("#2196F3")); gravity = Gravity.END }
        statsRow.addView(tvMb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(tvSpeed)
        midSection.addView(statsRow)

        val bottomSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(14), dp(24), dp(20))
        }
        tvStatus = TextView(activity).apply {
            text = "Đang kiểm tra kết nối..."
            textSize = 13f; setTextColor(Color.parseColor("#2196F3")); gravity = Gravity.CENTER
        }
        bottomSection.addView(tvStatus, lp().apply { bottomMargin = dp(14) })
        bottomSection.addView(android.widget.Button(activity).apply {
            text = "Hủy tải xuống"; textSize = 14f; setTextColor(Color.parseColor("#FF3B30"))
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(dp(2), Color.parseColor("#FF3B30"))
                cornerRadius = dp(10).toFloat()
            }
            minimumHeight = 0; minimumWidth = 0
            setOnClickListener { showCancelConfirmDialog() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        card.addView(topSection)
        card.addView(midSection, lp())
        card.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#F0F0F0")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(20) })
        card.addView(bottomSection)
        wrapper.addView(card)
        return wrapper
    }

    // ── Tải file ──────────────────────────────────────────────────────────────
    private fun startDownload() {
        activity.runOnUiThread { tvStatus.text = "Vui lòng không tắt màn hình" }
        incrementDownloadCount()
        val ts = System.currentTimeMillis()
        val destFile = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "tiktokplugin${ts}.apk"
        )
        try { if (destFile.exists()) destFile.delete() } catch (_: Exception) {}
        pollJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val clientBuilder = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                if (isVpnRunning) {
                    val port = com.v2ray.ang.handler.SettingsManager.getHttpPort()
                    clientBuilder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
                        java.net.InetSocketAddress("127.0.0.1", port)))
                }
                val response = clientBuilder.build()
                    .newCall(okhttp3.Request.Builder().url("$APK_URL?t=$ts")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)").build()).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { showError("Tải thất bại (HTTP ${response.code})") }
                    return@launch
                }
                val total = response.body!!.contentLength()
                val effectiveTotal = if (total > 0) total else 80L * 1024 * 1024
                var downloaded = 0L; var lastUi = 0L
                val startTime = System.currentTimeMillis()
                destFile.outputStream().buffered(256 * 1024).use { out ->
                    response.body!!.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi >= 100) {
                                lastUi = now
                                val pct = (downloaded.toFloat() / effectiveTotal).coerceIn(0f, 0.99f)
                                val dlMb = downloaded / 1_048_576f
                                val totalMb = if (total > 0) "%.0f MB".format(total / 1_048_576f) else "~80 MB"
                                val elapsed = (now - startTime) / 1000f
                                val speed = if (elapsed > 0) downloaded / 1_048_576f / elapsed else 0f
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = pct
                                    tvPct.text = "${(pct * 100).toInt()}%"
                                    tvMb.text = "%.1f MB / $totalMb".format(dlMb)
                                    tvSpeed.text = if (speed >= 0.1f) "%.1f MB/s".format(speed) else ""
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    progressBar.progress = 1f; tvPct.text = "100%"
                    tvStatus.text = "✅ Tải xong!"; tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                    dialog.window?.decorView?.postDelayed({
                        dialog.dismiss()
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                activity, "${activity.packageName}.cache", destFile)
                            (activity as? MainActivity)?.installApk(uri)
                        } catch (_: Exception) {}
                    }, 600)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Lỗi kết nối: ${e.message ?: "thử lại sau"}") }
            }
        }
    }

    private fun showCancelConfirmDialog() {
        val root = RelativeLayout(activity).apply { setPadding(dp(20), 0, dp(20), 0) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#2196F3"))
            }
            setPadding(dp(24), dp(28), dp(24), dp(24)); id = View.generateViewId()
        }

        card.addView(TextView(activity).apply {
            text = "Hủy tải xuống?"
            textSize = 17f; setTextColor(Color.parseColor("#1565C0"))
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, lp().apply { bottomMargin = dp(8) })

        card.addView(TextView(activity).apply {
            text = "Tiến trình tải sẽ bị mất, bạn có chắc muốn hủy?"
            textSize = 15f; setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER; isSingleLine = false; maxLines = 3
        }, lp().apply { bottomMargin = dp(20) })

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val btnContinue = android.widget.Button(activity).apply {
            text = "Tiếp tục tải"; textSize = 15f; setTextColor(BLUE)
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(dp(2), BLUE); cornerRadius = dp(12).toFloat()
            }
            minimumHeight = 0; minimumWidth = 0
        }
        val btnConfirmCancel = android.widget.Button(activity).apply {
            text = "Hủy tải"; textSize = 15f; setTextColor(Color.WHITE)
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF3B30")); cornerRadius = dp(12).toFloat()
            }
            minimumHeight = 0; minimumWidth = 0
        }
        btnRow.addView(btnContinue, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(btnConfirmCancel, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(btnRow, lp())

        root.addView(card, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(root); d.setCanceledOnTouchOutside(false); d.setCancelable(false)
        d.show(); applyWindow(d)

        btnContinue.setOnClickListener { d.dismiss() }
        btnConfirmCancel.setOnClickListener { d.dismiss(); pollJob?.cancel(); dialog.dismiss(); clearDim() }
    }

    private fun showError(msg: String) {
        tvStatus.text = msg; tvStatus.setTextColor(Color.parseColor("#B71C1C"))
        val contentRoot = dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        val wrapper = contentRoot.getChildAt(0) as? LinearLayout ?: return
        val card = wrapper.getChildAt(0) as? LinearLayout ?: return
        val bottomSection = card.getChildAt(card.childCount - 1) as? LinearLayout ?: return
        bottomSection.addView(android.widget.Button(activity).apply {
            text = "Đóng"; textSize = 14f; setTextColor(Color.WHITE)
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply { setColor(BLUE); cornerRadius = dp(10).toFloat() }
            minimumHeight = 0; minimumWidth = 0
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun clearDim() = activity.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

    private fun applyWindow(d: Dialog) {
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(1.0f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val p = attributes
            p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            p.y = (activity.resources.displayMetrics.heightPixels * 0.18f).toInt()
            attributes = p
        }
    }
}