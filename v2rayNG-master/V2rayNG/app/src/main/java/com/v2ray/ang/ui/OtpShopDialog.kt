package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.util.SubConfigFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private class ShopPulseDotsView(context: Context, dotColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
    private val scales = floatArrayOf(1f, 1f, 1f)
    private val animators = mutableListOf<ValueAnimator>()
    init {
        for (i in 0..2) {
            val anim = ValueAnimator.ofFloat(1f, 1.6f, 1f).apply {
                duration = 750; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
                startDelay = (i * 180).toLong(); interpolator = DecelerateInterpolator()
                addUpdateListener { scales[i] = it.animatedValue as Float; invalidate() }
            }
            animators.add(anim)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animators.forEach { it.start() } }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animators.forEach { it.cancel() } }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val dp = resources.displayMetrics.density
        setMeasuredDimension((72 * dp).toInt(), (20 * dp).toInt())
    }
    override fun onDraw(c: Canvas) {
        val dp = resources.displayMetrics.density
        val r = 7f * dp; val gap = 14f * dp
        val cx = width / 2f; val cy = height / 2f
        val offsets = floatArrayOf(-gap, 0f, gap)
        for (i in 0..2) c.drawCircle(cx + offsets[i], cy, r * scales[i], paint)
    }
}

private class ShopCountdownRingView(context: Context, ringColor: Int) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#E3EAF5")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = ringColor
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = ringColor
    }
    private val oval = RectF()
    var progress: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var secondsLeft: Int = 120
        set(v) { field = v; invalidate() }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val dp = resources.displayMetrics.density
        val s = (88 * dp).toInt(); setMeasuredDimension(s, s)
    }
    override fun onDraw(c: Canvas) {
        val dp = resources.displayMetrics.density
        val stroke = 7f * dp
        trackPaint.strokeWidth = stroke; arcPaint.strokeWidth = stroke
        val pad = stroke / 2f + dp
        oval.set(pad, pad, width - pad, height - pad)
        c.drawArc(oval, -90f, 360f, false, trackPaint)
        c.drawArc(oval, -90f, 360f * progress, false, arcPaint)
        val cx = width / 2f; val cy = height / 2f
        textPaint.textSize = 22f * dp
        c.drawText("$secondsLeft", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        textPaint.textSize = 9f * dp
        c.drawText("giây", cx, cy + 16f * dp, textPaint)
    }
}

class OtpShopDialog(
    private val activity: AppCompatActivity,
    private val onImportSuccess: (() -> Unit)? = null
) {

    private lateinit var dialog: Dialog
    private lateinit var tvStatus: TextView
    private lateinit var dotsView: ShopPulseDotsView
    private lateinit var ringView: ShopCountdownRingView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvPin: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnRetry: Button
    private lateinit var iconView: ImageView
    private lateinit var sadFaceView: ShopSadFaceView

    private var pinCode: String = ""

    private val BLUE_DARK = Color.parseColor("#0D47A1")
    private val BLUE_TEXT = Color.parseColor("#1976D2")

    private val API_BASE_URL = "https://admin.zhenxishop.com/api.php"
    private val API_KEY      = "v2rayng_secret_2024"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null
    private var currentRequestId: String? = null

    private fun getDeviceBrand(): String {
        val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return "$brand ${Build.MODEL}".take(40)
    }

    private fun getDeviceId(): String =
        (Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown").take(32)

    fun show() {
        dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(buildDialogView())
        dialog.setCanceledOnTouchOutside(false); dialog.setCancelable(false); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val p = attributes
            p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            p.y = (activity.resources.displayMetrics.heightPixels * 0.18f).toInt()
            attributes = p
        }
        dialog.setOnDismissListener {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            pollJob?.cancel()
        }
        startRequestFlow()
    }

    private fun buildDialogView(): View {
        val dp = activity.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 0, 20.dp(), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 28.dp(), 24.dp(), 24.dp())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 28.dp().toFloat() }
        }

        card.addView(TextView(activity).apply {
            text = "Kích hoạt đại lý"; textSize = 18f; setTextColor(BLUE_DARK)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 6.dp() })

        tvSubtitle = TextView(activity).apply {
            text = "Đang gửi yêu cầu đến admin..."; textSize = 13f
            setTextColor(Color.parseColor("#607D8B")); gravity = Gravity.CENTER
        }
        card.addView(tvSubtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 22.dp() })

        val ringFrame = FrameLayout(activity)

        ringView = ShopCountdownRingView(activity, BLUE_TEXT)
        ringView.visibility = View.INVISIBLE
        ringFrame.addView(ringView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        dotsView = ShopPulseDotsView(activity, BLUE_TEXT)
        ringFrame.addView(dotsView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        iconView = ImageView(activity).apply {
            visibility = View.GONE
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        }
        ringFrame.addView(iconView, FrameLayout.LayoutParams(128.dp(), 128.dp(), Gravity.CENTER))

        sadFaceView = ShopSadFaceView(activity).apply { visibility = View.GONE }
        ringFrame.addView(sadFaceView, FrameLayout.LayoutParams(128.dp(), 128.dp(), Gravity.CENTER))

        card.addView(ringFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (144 * dp).toInt()).apply { bottomMargin = 14.dp() })

        tvStatus = TextView(activity).apply {
            text = "Giữ nguyên màn hình này\nTự động kích hoạt máy chủ khi admin duyệt"
            textSize = 13f; setTextColor(BLUE_TEXT); gravity = Gravity.CENTER; maxLines = 5; isSingleLine = false
        }
        card.addView(tvStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 10.dp() })

        tvPin = TextView(activity).apply {
            text = "Mã xác nhận: ----"
            textSize = 15f; setTextColor(BLUE_DARK); typeface = Typeface.DEFAULT; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#E3F2FD")); cornerRadius = 12.dp().toFloat() }
            setPadding(0, 10.dp(), 0, 10.dp())
        }
        card.addView(tvPin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 16.dp() })

        // ── Hàng nút ngang (Đóng | Gửi lại) căn giữa ──
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnCancel = Button(activity).apply {
            text = "Hủy"; textSize = 11f
            setTextColor(Color.parseColor("#1976D2"))
            typeface = Typeface.DEFAULT_BOLD
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(2.dp(), Color.parseColor("#1976D2"))
                cornerRadius = 50.dp().toFloat()
            }
            setPadding(14.dp(), 4.dp(), 14.dp(), 4.dp())
            minimumHeight = 0; minimumWidth = 0
            setOnClickListener { onCancelClicked() }
        }
        btnRow.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 36.dp())
            .apply { marginEnd = 8.dp() })

        btnRetry = Button(activity).apply {
            text = "Gửi lại"; textSize = 11f
            setTextColor(Color.parseColor("#1976D2")); typeface = Typeface.DEFAULT_BOLD
            backgroundTintList = null; stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(2.dp(), Color.parseColor("#1976D2"))
                cornerRadius = 50.dp().toFloat()
            }
            setPadding(14.dp(), 4.dp(), 14.dp(), 4.dp())
            minimumHeight = 0; minimumWidth = 0
            visibility = View.GONE
            setOnClickListener { onRetryClicked() }
        }
        btnRow.addView(btnRetry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 36.dp()))

        card.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        wrapper.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrapper
    }

    private fun onCancelClicked() {
        pollJob?.cancel()
        val rid = currentRequestId
        currentRequestId = null
        if (rid != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = "$API_BASE_URL?key=$API_KEY&action=quick_activate_cancel&request_id=${
                        java.net.URLEncoder.encode(rid, "UTF-8")
                    }"
                    client.newCall(Request.Builder().url(url).build()).execute().close()
                } catch (_: Exception) {}
            }
        }
        dialog.dismiss()
    }

    private fun onRetryClicked() {
        pollJob?.cancel()
        activity.runOnUiThread {
            iconView.visibility = View.GONE
            btnRetry.visibility = View.GONE
            tvSubtitle.visibility = View.VISIBLE
            tvSubtitle.text = "Đang gửi lại yêu cầu..."
            dotsView.visibility = View.VISIBLE
            ringView.visibility = View.INVISIBLE
            tvStatus.text = "Đang gửi lại yêu cầu..."
            tvStatus.setTextColor(BLUE_TEXT)
            btnCancel.text = "Hủy"
            btnCancel.setTextColor(Color.parseColor("#1976D2"))
            btnCancel.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * activity.resources.displayMetrics.density).toInt(), Color.parseColor("#1976D2"))
                cornerRadius = 50 * activity.resources.displayMetrics.density
            }
            tvPin.visibility = View.VISIBLE
            tvPin.text = "Mã xác nhận: ----"
        }
        startRequestFlow()
    }

    private fun startRequestFlow() {
        pinCode = (1000..9999).random().toString()
        activity.runOnUiThread { tvPin.text = "Mã xác nhận: $pinCode" }
        pollJob = activity.lifecycleScope.launch {
            val deviceId = getDeviceId()
            val sendResult = withContext(Dispatchers.IO) { sendRequest(deviceId) }
            when (sendResult) {
                is SendResult.Ok -> { currentRequestId = sendResult.requestId; updateStatusMsg("Đã gửi yêu cầu.\nVui lòng chờ admin duyệt..."); pollStatus(sendResult.requestId) }
                SendResult.QueueFull   -> showError("Admin đang có nhiều yêu cầu.\nVui lòng thử lại sau 1-2 phút.")
                SendResult.RateLimited -> showError("Gửi quá nhanh.\nVui lòng thử lại sau giây lát.")
                SendResult.Error       -> showError("Không thể kết nối server.\nKiểm tra mạng và thử lại.")
            }
        }
    }

    private suspend fun pollStatus(requestId: String) {
        val startTime = System.currentTimeMillis(); val timeoutMs = 120_000L
        activity.runOnUiThread { dotsView.visibility = View.GONE; ringView.visibility = View.VISIBLE }
        while (currentCoroutineContext().isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMs) { currentRequestId = null; showTimeout(); return }
            val remainingSec = ((timeoutMs - elapsed) / 1000).toInt()
            activity.runOnUiThread {
                ringView.progress = remainingSec / 120f; ringView.secondsLeft = remainingSec
                tvStatus.text = "Giữ nguyên màn hình này\nTự động kích hoạt khi admin duyệt"
                tvStatus.setTextColor(BLUE_TEXT)
            }
            val status = withContext(Dispatchers.IO) { checkStatus(requestId) }
            when (status) {
                "approved" -> { currentRequestId = null; activity.runOnUiThread { btnCancel.visibility = View.GONE; showIconApproved() }; updateStatusMsg("✓ Admin đã duyệt!\nĐang lấy gói đại lý..."); fetchSubConfig(); return }
                "expired"  -> { currentRequestId = null; showTimeout(); return }
            }
            delay(1_000)
        }
    }

    private suspend fun fetchSubConfig() {
        when (val result = SubConfigFetcher.fetchSubConfig()) {
            is SubConfigFetcher.FetchResult.Success -> {
                val imported = withContext(Dispatchers.IO) { tryImportSubContent(result.subUrl, result.subContent) }
                if (imported) { showSuccess("✅ Kích hoạt đại lý thành công!"); activity.runOnUiThread { dialog.dismiss(); onImportSuccess?.invoke() } }
                else showError("Gói đại lý đã có trên thiết bị.\nKhông cần kích hoạt lại.")
            }
            is SubConfigFetcher.FetchResult.Error -> showError(result.message)
        }
    }

    private fun tryImportSubContent(subUrl: String, subContent: String): Boolean {
        return try {
            val input = if (subUrl.isNotBlank()) subUrl else subContent.trim()
            android.util.Log.d("OtpShopDialog", "tryImportSubContent: dùng ${if (subUrl.isNotBlank()) "subUrl" else "base64"}")
            val (configCount, subCount) = com.v2ray.ang.handler.AngConfigManager.importBatchConfig(input, "", false)
            android.util.Log.d("OtpShopDialog", "importBatchConfig: configs=$configCount subs=$subCount")
            configCount > 0 || subCount > 0
        } catch (e: Exception) { android.util.Log.e("OtpShopDialog", "exception", e); false }
    }

    sealed class SendResult {
        data class Ok(val requestId: String) : SendResult()
        object QueueFull   : SendResult()
        object RateLimited : SendResult()
        object Error       : SendResult()
    }

    private fun sendRequest(deviceId: String): SendResult {
        return try {
            val url = "$API_BASE_URL?key=$API_KEY&action=quick_activate_request&type=shop&device_id=${java.net.URLEncoder.encode(deviceId,"UTF-8")}&device_brand=${java.net.URLEncoder.encode(getDeviceBrand(),"UTF-8")}&pin_code=${pinCode}"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return SendResult.Error
            val json = JSONObject(body)
            when {
                json.optBoolean("success")                 -> SendResult.Ok(json.optString("request_id"))
                json.optString("reason") == "queue_full"   -> SendResult.QueueFull
                json.optString("reason") == "rate_limited" -> SendResult.RateLimited
                else                                       -> SendResult.Error
            }
        } catch (e: Exception) { android.util.Log.e("OtpShopDialog","sendRequest exception",e); SendResult.Error }
    }

    private fun checkStatus(requestId: String): String {
        return try {
            val url = "$API_BASE_URL?key=$API_KEY&action=quick_activate_status&request_id=${java.net.URLEncoder.encode(requestId,"UTF-8")}"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return "pending"
            JSONObject(body).optString("status", "pending")
        } catch (e: Exception) { "pending" }
    }

    private fun updateStatusMsg(msg: String) {
        activity.runOnUiThread {
            dotsView.visibility = View.VISIBLE; ringView.visibility = View.INVISIBLE
            tvStatus.text = msg; tvStatus.setTextColor(BLUE_TEXT)
            btnRetry.visibility = View.GONE
            btnCancel.visibility = View.VISIBLE; btnCancel.text = "Hủy"
            btnCancel.setTextColor(Color.parseColor("#1976D2"))
            btnCancel.backgroundTintList = null
            btnCancel.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * activity.resources.displayMetrics.density).toInt(), Color.parseColor("#1976D2"))
                cornerRadius = 50 * activity.resources.displayMetrics.density
            }
        }
    }

    private fun showTimeout() {
        activity.runOnUiThread {
            dotsView.visibility = View.GONE; ringView.visibility = View.INVISIBLE
            tvSubtitle.visibility = View.GONE
            tvPin.visibility = View.GONE
            showIconTimeout()
            tvStatus.text = "⏰ Admin chưa duyệt\nBạn có muốn gửi lại yêu cầu không?"
            tvStatus.setTextColor(Color.parseColor("#E65100"))
            tvStatus.gravity = Gravity.CENTER
            (tvStatus.layoutParams as? LinearLayout.LayoutParams)?.apply {
                topMargin = (20 * activity.resources.displayMetrics.density).toInt()
                bottomMargin = (20 * activity.resources.displayMetrics.density).toInt()
            }
            btnCancel.visibility = View.VISIBLE; btnCancel.text = "Đóng"
            btnCancel.setTextColor(Color.parseColor("#1976D2"))
            btnCancel.backgroundTintList = null
            btnCancel.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * activity.resources.displayMetrics.density).toInt(), Color.parseColor("#1976D2"))
                cornerRadius = 50 * activity.resources.displayMetrics.density
            }
            btnRetry.visibility = View.VISIBLE
            btnRetry.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * activity.resources.displayMetrics.density).toInt(), Color.parseColor("#1976D2"))
                cornerRadius = 50 * activity.resources.displayMetrics.density
            }
        }
    }

    private fun showError(msg: String) {
        activity.runOnUiThread {
            dotsView.visibility = View.GONE; ringView.visibility = View.INVISIBLE
            showIconError()
            tvStatus.text = msg; tvStatus.setTextColor(Color.parseColor("#B71C1C"))
            btnCancel.visibility = View.VISIBLE; btnCancel.text = "Đóng"
            btnCancel.setTextColor(Color.parseColor("#1976D2"))
            btnCancel.backgroundTintList = null
            btnCancel.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * activity.resources.displayMetrics.density).toInt(), Color.parseColor("#1976D2"))
                cornerRadius = 50 * activity.resources.displayMetrics.density
            }
        }
    }

    private fun showSuccess(msg: String) {
        activity.runOnUiThread {
            dotsView.visibility = View.GONE; ringView.visibility = View.INVISIBLE
            tvStatus.text = msg; tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            btnCancel.visibility = View.GONE
        }
    }

    private fun showIconApproved() {
        val dp = activity.resources.displayMetrics.density
        val size = (128 * dp).toInt()
        iconView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#E3F2FD"), Color.parseColor("#BBDEFB"))
        ).apply { shape = GradientDrawable.OVAL }
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val cx = size / 2f; val cy = size / 2f; val r = size * 0.31f
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f * dp
            p.color = Color.parseColor("#42A5F5"); p.alpha = 50
            c.drawCircle(cx, cy, r + 7f * dp, p)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.FILL
            p.shader = android.graphics.RadialGradient(cx, cy - r * 0.2f, r,
                intArrayOf(Color.parseColor("#42A5F5"), Color.parseColor("#1565C0")),
                null, android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dp
            p.color = Color.parseColor("#90CAF9"); p.alpha = 160
            c.drawCircle(cx, cy, r - dp, p)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f * dp
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = Color.parseColor("#0D47A1"); p.alpha = 60
            val sh = android.graphics.Path().apply {
                moveTo(cx - r * 0.52f, cy + 1.5f * dp); lineTo(cx - r * 0.08f, cy + r * 0.46f + 1.5f * dp)
                lineTo(cx + r * 0.52f, cy - r * 0.42f + 1.5f * dp)
            }; c.drawPath(sh, p)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 5.5f * dp
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = Color.WHITE
            val path = android.graphics.Path().apply {
                moveTo(cx - r * 0.52f, cy); lineTo(cx - r * 0.08f, cy + r * 0.45f)
                lineTo(cx + r * 0.52f, cy - r * 0.43f)
            }; c.drawPath(path, p)
        }
        iconView.setImageBitmap(bmp); iconView.visibility = View.VISIBLE
    }

    private fun showIconTimeout() {
        activity.runOnUiThread {
            dotsView.visibility = View.GONE
            ringView.visibility = View.INVISIBLE
            iconView.visibility = View.GONE
            sadFaceView.visibility = View.VISIBLE
        }
    }

    private fun showIconError() {
        activity.runOnUiThread {
            dotsView.visibility = View.GONE
            ringView.visibility = View.INVISIBLE
            iconView.visibility = View.GONE
            sadFaceView.visibility = View.VISIBLE
        }
    }

    private inner class ShopSadFaceView(context: Context) : View(context) {
        private val facePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL;   color = Color.parseColor("#FEF0F0") }
        private val borderPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C62828") }
        private val featurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL;   color = Color.parseColor("#C62828") }
        private val mouthPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C62828"); strokeCap = Paint.Cap.ROUND }
        private val tearPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL;   color = Color.parseColor("#C62828") }
        private var eyeScaleY = 1f; private var tearAlpha = 0f; private var tearOffsetY = 0f; private var bodyOffsetY = 0f
        private val eyeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            addUpdateListener { t ->
                val f = t.animatedFraction
                eyeScaleY = when { f < 0.9f -> 1f; f < 0.93f -> 1f - (f - 0.9f) / 0.03f * 0.9f; f < 0.97f -> 0.1f; else -> 0.1f + (f - 0.97f) / 0.03f * 0.9f }
                invalidate()
            }
        }
        private val tearAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000; startDelay = 1000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            addUpdateListener { t ->
                val f = t.animatedFraction
                tearAlpha = when { f < 0.1f -> 0f; f < 0.2f -> (f - 0.1f) / 0.1f; f < 0.7f -> 1f - (f - 0.2f) / 0.5f; else -> 0f }
                tearOffsetY = if (f > 0.1f) (f - 0.1f) * 30f else 0f; invalidate()
            }
        }
        private val sighAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { bodyOffsetY = (it.animatedValue as Float) * 2f; invalidate() }
        }
        override fun onAttachedToWindow()  { super.onAttachedToWindow();  eyeAnim.start(); tearAnim.start(); sighAnim.start() }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); eyeAnim.cancel(); tearAnim.cancel(); sighAnim.cancel() }
        override fun onMeasure(w: Int, h: Int) { val s = (128 * resources.displayMetrics.density).toInt(); setMeasuredDimension(s, s) }
        override fun onDraw(c: Canvas) {
            val dp = resources.displayMetrics.density
            val cx = width / 2f; val cy = height / 2f + bodyOffsetY * dp; val r = width * 0.44f
            borderPaint.strokeWidth = 2.5f * dp
            c.drawCircle(cx, cy, r, facePaint); c.drawCircle(cx, cy, r, borderPaint)
            val eyeRx = 3f * dp; val eyeRy = 3.5f * dp
            listOf(cx - r * 0.32f, cx + r * 0.32f).forEach { ex ->
                c.save(); c.translate(ex, cy - r * 0.2f); c.scale(1f, eyeScaleY)
                c.drawOval(android.graphics.RectF(-eyeRx, -eyeRy, eyeRx, eyeRy), featurePaint); c.restore()
            }
            mouthPaint.strokeWidth = 2.5f * dp
            val mPath = android.graphics.Path().apply {
                moveTo(cx - r * 0.32f, cy + r * 0.38f); quadTo(cx, cy + r * 0.18f, cx + r * 0.32f, cy + r * 0.38f)
            }; c.drawPath(mPath, mouthPaint)
            if (tearAlpha > 0f) {
                tearPaint.alpha = (tearAlpha * 128).toInt()
                val ty = cy - r * 0.02f + tearOffsetY * dp
                val txL = cx - r * 0.32f
                c.drawOval(android.graphics.RectF(txL - 1.5f * dp, ty - 2.5f * dp, txL + 1.5f * dp, ty + 2.5f * dp), tearPaint)
                val txR = cx + r * 0.32f
                c.drawOval(android.graphics.RectF(txR - 1.5f * dp, ty - 2.5f * dp, txR + 1.5f * dp, ty + 2.5f * dp), tearPaint)
            }
        }
    }
}