package com.v2ray.ang.ui

import android.app.DownloadManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.util.AnnouncementFetcher
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.*
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import com.v2ray.ang.handler.AppExpireManager
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Proxy

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()

    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var isFabAnimating = false
    private var fabSpinJob: kotlinx.coroutines.Job? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    // ✅ Lưu URI APK tạm — dùng sau khi user bật quyền cài APK ngoài xong
    // Tự động persist vào SharedPreferences → khôi phục được sau force stop / xóa RAM
    private var pendingInstallUri: android.net.Uri? = null
        set(value) {
            field = value
            val prefs = getSharedPreferences("install_prefs", MODE_PRIVATE)
            if (value != null) prefs.edit().putString("pending_uri", value.toString()).apply()
            else prefs.edit().remove("pending_uri").apply()
        }

    // ✅ SỬA: callback chỉ kiểm tra quyền thực tế, không dùng resultCode
    //         vì Settings không trả về RESULT_OK khi user bật quyền
    private val requestInstallPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val uri = pendingInstallUri ?: return@registerForActivityResult
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && !packageManager.canRequestPackageInstalls()
            ) {
                // User vẫn chưa cấp quyền → hỏi lại, không toast lỗi
                showRetryPermissionDialog(uri)
            } else {
                // Đã có quyền → cài đặt luôn
                launchInstaller(uri)
                pendingInstallUri = null
            }
        }

    // ✅ Helper tập trung: mở installer APK
    private fun launchInstaller(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            showCustomToast("✅ File đã tải xong!\nKéo thông báo xuống → bấm vào để cài.", "#2E7D32")
        }
    }

    // ✅ Dialog hỏi lại khi user chưa cấp quyền (thay vì toast lỗi)
    private fun showRetryPermissionDialog(uri: Uri) {
        val dp = resources.displayMetrics.density
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Cần cấp quyền cài đặt")
            .setMessage(
                "Bạn cần bật \"Cho phép cài ứng dụng từ nguồn không xác định\" để tiếp tục.\n\n" +
                        "Bấm \"Cấp quyền\" để mở Cài đặt, sau đó quay lại ứng dụng."
            )
            .setPositiveButton("CẤP QUYỀN NGAY") { _, _ ->
                pendingInstallUri = uri
                requestInstallPermission.launch(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.WHITE)
                        cornerRadius = 20 * dp
                    }
                )
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(android.graphics.Color.parseColor("#1565C0"))
            }
    }


    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private val requestActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
                restartV2Ray()
            }
            if (SettingsChangeManager.consumeSetupGroupTab()) {
                setupGroupTab()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupToolbar(binding.toolbar, false, "")
        // Hiện version nhỏ mờ bên cạnh tiêu đề "Yum VPN"
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.tvToolbarVersion.text = "v$versionName"
        } catch (e: Exception) {
            binding.tvToolbarVersion.text = ""
        }
        // ✅ Khôi phục pendingInstallUri nếu app bị force stop / xóa RAM giữa chừng
        val installPrefs = getSharedPreferences("install_prefs", MODE_PRIVATE)
        val savedUri = installPrefs.getString("pending_uri", null)
        if (savedUri != null) {
            // Gán trực tiếp vào field (bypass setter) để tránh ghi lại SharedPreferences
            // Kotlin không cho bypass setter của chính class → dùng setter bình thường
            pendingInstallUri = android.net.Uri.parse(savedUri)
        }

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // ── Tô màu xanh dương cho icon hamburger (☰), title và version ──
        val blueColor = ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            .let { android.graphics.Color.parseColor("#1565C0") }
        toggle.drawerArrowDrawable.color = blueColor
        binding.toolbar.setTitleTextColor(blueColor)
        binding.tvToolbarVersion.setTextColor(blueColor)

        // Bấm Back khi drawer đang mở → đóng drawer, không thoát app
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        binding.navView.setNavigationItemSelectedListener(this)
        binding.fab.setOnClickListener { handleFabAction() }

        // ── Tô màu riêng cho các item drawer ─────────────────────────────
        applyDrawerItemColors()

        // ── Set mặc định các setting quan trọng nếu chưa từng mở Cài đặt ─
        val prefs = getSharedPreferences("first_launch_defaults", MODE_PRIVATE)
        if (!prefs.getBoolean("defaults_applied", false)) {
            // UI
            MmkvManager.encodeSettings(AppConfig.PREF_CONFIRM_REMOVE, true)
            MmkvManager.encodeSettings(AppConfig.PREF_START_SCAN_IMMEDIATE, true)
            // VPN
            MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, true)
            MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, true)
            MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, true)
            // Core
            MmkvManager.encodeSettings(AppConfig.PREF_ALLOW_INSECURE, true)
            // Per-app proxy: bật sẵn + bypass mode + 5 app nội địa TQ
            // Lý do: WeChat/Alipay/Taobao/JD/Douyin detect IP nước ngoài → lỗi thanh toán, OTP, khóa tài khoản
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
            MmkvManager.encodeSettings(
                AppConfig.PREF_PER_APP_PROXY_SET,
                mutableSetOf(
                    "com.tencent.mm",              // WeChat
                    "com.eg.android.AlipayGphone", // Alipay
                    "com.taobao.taobao",           // Taobao
                    "com.jingdong.app.mall",       // JD.com
                    "com.ss.android.ugc.aweme"     // Douyin (抖音 - TikTok Trung Quốc)
                )
            )
            // Ngôn ngữ mặc định: Tiếng Việt
            // Phải set trước khi đánh dấu defaults_applied, rồi recreate()
            // để attachBaseContext chạy lại và áp dụng ngôn ngữ ngay lần đầu
            MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, "vi")
            prefs.edit().putBoolean("defaults_applied", true).apply()
            recreate()
            // Ẩn animation chớp khi recreate để user không nhận ra
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }


        // ── Thanh kiểm tra kết nối ────────────────────────────────────────
        updateConnectionTestBar(false)
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value != true) return@setOnClickListener
            binding.tvTestState.text = getString(R.string.connection_test_testing)
            mainViewModel.testCurrentServerRealPing()
        }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        invalidateOptionsMenu()
        updateExpireBanner()
    }

    override fun onResume() {
        super.onResume()
        tabMediator?.detach()
        tabMediator = null
        mainViewModel.reloadServerList()
        setupGroupTab()

        // Kiểm tra các file đã tải xong trong lúc user thoát app
        checkPendingDownloads()
        updateExpireBanner()
        invalidateOptionsMenu() // ✅ Cập nhật nút toolbar mỗi lần resume

        // ✅ Bắt đầu tick tự cập nhật banner hết hạn mỗi 60 giây
        startExpireBannerTick()

        // ✅ Fetch thông báo từ web admin
        fetchAnnouncement()

        // ✅ Nếu đang chờ cài APK mà user chưa cấp quyền → hiện lại dialog bắt buộc
        val uri = pendingInstallUri
        if (uri != null &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            showRetryPermissionDialog(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ Đóng tất cả dialog đang mở để tránh WindowLeak
        activeProgressDialog?.dismiss()
        activeProgressDialog = null
        // ✅ Hủy job download nếu đang chạy
        downloadWatchJob?.cancel()
        downloadWatchJob = null
        // ✅ Hủy job kiểm tra hết hạn app
        stopAppExpireWatchJob()
        // ✅ Dừng tick banner hết hạn
        stopExpireBannerTick()
        // ✅ Hủy job fetch thông báo
        announcementJob?.cancel()
        announcementJob = null
        // ✅ Dừng vòng xoay FAB nếu đang chạy
        fabSpinJob?.cancel()
        fabSpinJob = null
    }

    override fun onPause() {
        super.onPause()
        // ✅ Dừng tick khi app vào background — tránh lãng phí tài nguyên
        stopExpireBannerTick()
    }

    /**
     * Khi user thoát app giữa chừng, downloadWatchJob bị hủy nhưng DownloadManager
     * vẫn tải ngầm. Hàm này kiểm tra lại khi onResume — nếu file nào đã xong thì
     * tự động mở installer, nếu lỗi thì thông báo.
     */
    /**
     * Kiểm tra các download đang chờ khi user quay lại app.
     * Sau khi chuyển sang coroutine download (không dùng DownloadManager),
     * hàm này chỉ cần xóa sạch pendingDownloadIds cũ nếu còn sót.
     */
    private fun checkPendingDownloads() {
        // Download mới dùng coroutine + openConnectionViaProxy, tự quản lý vòng đời.
        // pendingDownloadIds chỉ còn là legacy từ lần chạy cũ → xóa sạch để tránh nhầm lẫn.
        pendingDownloadIds.clear()
    }

    /**
     * Legacy — không còn dùng sau khi chuyển sang coroutine download.
     * Giữ lại để tránh compile error nếu còn nơi nào gọi.
     */
    @Suppress("unused")
    private fun resumeWatchJob(dm: DownloadManager, downloadId: Long, title: String) {
        // No-op: coroutine download tự quản lý, không cần resume từ DownloadManager
    }

    private fun setupViewModel() {
        var wasRunning = false
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(isRunning)
            updateConnectionTestBar(isRunning)

            // Tự động ping ngay khi VPN vừa bật xong (chuyển từ tắt → bật)
            if (isRunning && !wasRunning) {
                binding.tvTestState.text = getString(R.string.connection_test_testing)
                mainViewModel.testCurrentServerRealPing()

                // ✅ Auto update subscription ngầm sau khi VPN kết nối thành công (cooldown 30 phút)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastAutoUpdateMs >= AUTO_UPDATE_COOLDOWN_MS) {
                    lastAutoUpdateMs = nowMs
                    mainViewModel.autoUpdateSubSilent {
                        setupGroupTab()
                        refreshExpireBanner()
                        // ✅ Chỉ hiện toast khi không có dialog kết nối đang mở
                        if (!VpnConnectingDialog.isShowing()) {
                            showCustomToast("✅ Cập nhật máy chủ thành công!", "#2E7D32")
                        }
                        // ✅ Fetch thông báo mới từ web admin sau khi update sub xong
                        fetchAnnouncement()
                    }
                }

                // ✅ Bắt đầu kiểm tra hết hạn app định kỳ khi VPN bật
                startAppExpireWatchJob()
            }

            // Khi VPN vừa tắt (chuyển từ bật → tắt) → dừng job kiểm tra
            if (!isRunning && wasRunning) {
                stopAppExpireWatchJob()
            }

            wasRunning = isRunning
        }

        // Observe kết quả kiểm tra kết nối → hiện lên thanh dưới cùng
        mainViewModel.updateTestResultAction.observe(this) { result ->
            if (!result.isNullOrEmpty()) {
                binding.tvTestState.text = result
            }
        }

        // Dismiss dialog khi VPN fail (isRunning không đổi nên applyRunningState không chạy)
        mainViewModel.vpnStartFailed.observe(this) {
            VpnConnectingDialog.dismiss()
            stopFabSpinLoop()
            binding.fab.isEnabled = true
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    /**
     * Cập nhật text thanh trạng thái kết nối phía dưới màn hình.
     * - VPN bật: text bình thường, bấm được, alpha 1.0
     * - VPN tắt: text mờ, không bấm được (block ở onClick), alpha 0.45
     */
    private fun updateConnectionTestBar(isRunning: Boolean) {
        binding.tvTestState.text = if (isRunning) {
            getString(R.string.connection_connected)
        } else {
            getString(R.string.connection_not_connected)
        }
        binding.layoutTest.alpha = if (isRunning) 1f else 0.45f
    }

    internal fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        // Snapshot groups tại đây và kiểm tra bounds an toàn
        val groupsSnapshot = groups.toList()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            tab.text = if (position < groupsSnapshot.size) groupsSnapshot[position].remarks else ""
        }.also { it.attach() }

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        if (isFabAnimating) return

        // ── Hiệu ứng bấm: scale down → scale up với Overshoot ──────────
        binding.fab.animate()
            .scaleX(0.82f).scaleY(0.82f)
            .setDuration(100)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.fab.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }.start()

        // ── Haptic feedback nhẹ ─────────────────────────────────────────
        binding.fab.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )

        if (mainViewModel.isRunning.value == true) {
            // Luôn cho phép TẮT VPN dù hết hạn
            binding.fab.isEnabled = false
            V2RayServiceManager.stopVService(this)
        } else {
            // Check AppExpireManager (admin cài tay)
            if (AppExpireManager.isExpired(this)) {
                showAppExpiredDialog()
                return
            }
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        }
    }

    /**
     * Dialog thông báo app hết hạn — hiện khi user bấm FAB mà app đã hết hạn.
     */
    private fun showAppExpiredDialog(overrideExpireTs: Long = 0L) {
        val dp = resources.displayMetrics.density

        // FrameLayout bọc ngoài để đặt nút X góc trên phải
        val frame = android.widget.FrameLayout(this)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, (36 * dp).toInt(), pad, (20 * dp).toInt())
        }

        val btnX = android.widget.TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#B71C1C"))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ).apply {
                topMargin = (8 * dp).toInt()
                marginEnd = (8 * dp).toInt()
            }
        }

        frame.addView(root)
        frame.addView(btnX)

        // Icon lớn
        root.addView(android.widget.TextView(this).apply {
            text = "🔒"
            textSize = 38f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        })

        // Tiêu đề
        root.addView(android.widget.TextView(this).apply {
            text = "VPN Đã Hết Hạn"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#B71C1C"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        })

        // Ngày hết hạn cụ thể
        val expireTs = if (overrideExpireTs > 0L) overrideExpireTs else AppExpireManager.getExpireTimestamp(this)
        val dateStr = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(expireTs))
        root.addView(android.widget.TextView(this).apply {
            text = "Hết hạn: $dateStr"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#B71C1C"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (20 * dp).toInt() }
        })

        // Divider
        root.addView(android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#FFCDD2"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply { bottomMargin = (16 * dp).toInt() }
        })

        // Hộp thông báo nội dung
        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padBox = (14 * dp).toInt()
            setPadding(padBox, padBox, padBox, padBox)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#FFFFFF"))
                cornerRadius = 10 * dp
                setStroke((1.5 * dp).toInt(), android.graphics.Color.parseColor("#FF8F00"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (20 * dp).toInt() }

            addView(android.widget.TextView(this@MainActivity).apply {
                text = "🎁 Khách hàng gia hạn đủ 4 lần sẽ được sử dụng Vĩnh Viễn.\n\n" +
                        "Liên hệ Wechat ID: Vutruong1692 để gia hạn."
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#1976D2"))
                gravity = android.view.Gravity.CENTER
                setLineSpacing(0f, 1.4f)
            })
        })

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(frame)
            .setCancelable(true)
            .create()

        btnX.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 24 * dp
            }
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Kiểm tra hết hạn tập trung — dùng cho MỌI chức năng import.
     * Trả về true nếu đã hết hạn (đã hiện dialog thông báo), false nếu còn hạn.
     */
    private fun checkExpiredAndBlock(): Boolean {
        if (!AppExpireManager.isExpired(this)) return false
        showAppExpiredDialog()
        return true
    }

    /**
     * Bắt đầu job kiểm tra định kỳ mỗi 60 giây xem app có hết hạn không.
     * Chỉ gọi khi VPN đang BẬT.
     * Nếu phát hiện hết hạn → tự dừng VPN + hiện dialog.
     */
    private fun startAppExpireWatchJob() {
        appExpireCheckJob?.cancel()
        appExpireCheckJob = lifecycleScope.launch {
            while (isActive) {
                delay(60_000L)
                val now = System.currentTimeMillis()

                // Check AppExpireManager (admin cài tay)
                if (AppExpireManager.isExpired(this@MainActivity)) {
                    if (mainViewModel.isRunning.value == true) {
                        V2RayServiceManager.stopVService(this@MainActivity)
                    }
                    showAppExpiredDialog()
                    break
                }
            }
        }
    }

    /**
     * Dừng job kiểm tra hết hạn — gọi khi VPN tắt hoặc activity destroy.
     */
    private fun stopAppExpireWatchJob() {
        appExpireCheckJob?.cancel()
        appExpireCheckJob = null
    }

    /**
     * Tick mỗi 60 giây để tự cập nhật banner hết hạn ngay trên màn hình,
     * không cần user tắt/mở lại app.
     * Gọi trong onResume(), dừng trong onPause() + onDestroy().
     */
    private fun startExpireBannerTick() {
        expireBannerTickJob?.cancel()
        expireBannerTickJob = lifecycleScope.launch {
            while (isActive) {
                val expireTs = AppExpireManager.getExpireTimestamp(this@MainActivity).let {
                    if (it > 0L) it else {
                        val subs = MmkvManager.decodeSubscriptions()
                        val sub = subs.firstOrNull { s -> s.subscription.enabled && s.subscription.expireDate != null }
                        sub?.subscription?.expireDate?.times(1000L) ?: 0L
                    }
                }
                val remaining = expireTs - System.currentTimeMillis()
                when {
                    remaining <= 0L -> {
                        updateExpireBanner()
                        break
                    }
                    remaining < 3_600_000L -> delay(1_000L)   // < 1 giờ → tick mỗi giây
                    else -> delay(60_000L)                     // >= 1 giờ → tick mỗi phút
                }
                updateExpireBanner()
            }
        }
    }

    private fun stopExpireBannerTick() {
        expireBannerTickJob?.cancel()
        expireBannerTickJob = null
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        binding.fab.isEnabled = false
        startFabSpinLoop()
        // ✅ FIX: truyền onTimeout callback để cleanup FAB khi dialog tự đóng sau 10 giây.
        // Trước đây VpnConnectingDialog.show(this) không có callback → timeout chỉ đóng dialog,
        // fabSpinJob vẫn chạy + fab.isEnabled vẫn false → FAB xoay vĩnh viễn.
        VpnConnectingDialog.show(this) {
            stopFabSpinLoop()
            binding.fab.isEnabled = true
        }
        V2RayServiceManager.startVService(this)
    }

    /**
     * Đổi màu FAB theo trạng thái VPN:
     * - Đang bật  → nền xanh dương đậm + icon stop
     * - Đang tắt  → nền đỏ + icon play
     */
    // true sau lần đầu tiên observe isRunning — tránh animate khi app vừa mở
    private var fabStateInitialized = false

    /**
     * Xoay FAB liên tục 360° mỗi 600ms — chạy khi đang chờ VPN kết nối.
     */
    private fun startFabSpinLoop() {
        fabSpinJob?.cancel()
        fabSpinJob = lifecycleScope.launch {
            // Chờ animation scale-down/up khi bấm FAB xong (~300ms) rồi mới bắt đầu spin
            kotlinx.coroutines.delay(320)
            binding.fab.clearAnimation()
            while (isActive) {
                binding.fab.animate()
                    .rotationBy(360f)
                    .setDuration(600)
                    .setInterpolator(android.view.animation.LinearInterpolator())
                    .start()
                kotlinx.coroutines.delay(620)
            }
        }
    }

    /**
     * Dừng vòng xoay loading FAB — gọi khi VPN đã bật/tắt xong.
     */
    private fun stopFabSpinLoop() {
        fabSpinJob?.cancel()
        fabSpinJob = null
        binding.fab.animate().cancel()
        binding.fab.rotation = 0f
    }

    private fun applyRunningState(isRunning: Boolean) {
        // Đóng dialog connecting dù VPN bật thành công hay thất bại — tránh treo dialog
        VpnConnectingDialog.dismiss()
        // Dừng vòng xoay loading (nếu đang chạy)
        stopFabSpinLoop()
        // Khôi phục FAB sau khi trạng thái thật sự thay đổi
        binding.fab.isEnabled = true

        if (!fabStateInitialized) {
            // Lần đầu (app khởi động): set trực tiếp, không animate
            fabStateInitialized = true
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.fab_running)
                )
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.fab_stopped)
                )
            }
            return
        }

        // ── Hiệu ứng chuyển trạng thái: rotate 360° + pulse ────────────
        isFabAnimating = true
        binding.fab.animate()
            .rotationBy(360f)
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (isRunning) {
                    binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                    binding.fab.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.fab_running)
                    )
                } else {
                    binding.fab.setImageResource(R.drawable.ic_play_24dp)
                    binding.fab.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.fab_stopped)
                    )
                }
                binding.fab.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .withEndAction { isFabAnimating = false }
                    .start()
            }.start()
    }

    // ================= IMPORT =================

    fun importConfigViaSub() {
        if (checkExpiredAndBlock()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            withContext(Dispatchers.Main) {
                val hasSuccess = result.successCount > 0 || result.configCount > 0
                when {
                    hasSuccess -> {
                        tabMediator?.detach()
                        tabMediator = null
                        mainViewModel.reloadServerList()
                        setupGroupTab()
                        refreshExpireBanner()
                        toast(getString(R.string.title_update_subscription_result, result.configCount))
                    }
                    result.failureCount > 0 -> toastError(R.string.toast_update_sub_failure)
                    else -> toast(R.string.toast_no_subscription_found)
                }
            }
        }
    }

    /**
     * ✅ Tự chọn server đầu tiên trong danh sách nếu chưa có server nào được chọn,
     * hoặc luôn chọn server đầu tiên sau khi import thủ công / QR.
     */
    private fun selectFirstServerIfNeeded() {
        val firstGuid = mainViewModel.serversCache.firstOrNull()?.guid
        if (!firstGuid.isNullOrBlank()) {
            MmkvManager.setSelectServer(firstGuid)
        }
    }

    private fun importQRcode() {
        if (checkExpiredAndBlock()) return
        launchQRCodeScanner { result ->
            if (result != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val (configCount, subCount) = AngConfigManager.importBatchConfig(result, "", false)
                    withContext(Dispatchers.Main) {
                        if (configCount > 0 || subCount > 0) {
                            tabMediator?.detach()
                            tabMediator = null
                            mainViewModel.reloadServerList()
                            setupGroupTab()
                            // ✅ Tự chọn server đầu tiên sau khi import QR
                            selectFirstServerIfNeeded()
                            refreshExpireBanner()
                            if (subCount > 0) {
                                toastSuccess(R.string.import_subscription_success)
                            } else {
                                toast(getString(R.string.title_import_config_count, configCount))
                            }
                        } else {
                            toastError(R.string.toast_import_qr_failure)
                        }
                    }
                }
            }
        }
    }

    private fun importClipboard() {
        if (checkExpiredAndBlock()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val text = Utils.getClipboard(this@MainActivity)
            val (configCount, subCount) = AngConfigManager.importBatchConfig(text, "", false)
            withContext(Dispatchers.Main) {
                if (configCount > 0 || subCount > 0) {
                    tabMediator?.detach()
                    tabMediator = null
                    mainViewModel.reloadServerList()
                    setupGroupTab()
                    selectFirstServerIfNeeded()
                    refreshExpireBanner()
                    if (subCount > 0) {
                        toastSuccess(R.string.import_subscription_success)
                    } else {
                        toast(getString(R.string.title_import_config_count, configCount))
                    }
                } else {
                    toastError(R.string.toast_import_clipboard_failure)
                }
            }
        }
    }

    /**
     * Mở OtpUpdateDialog (Admin mã nhỏ - nhận link 1 server qua QR).
     * onResume() sẽ tự reload server list sau khi dialog dismiss.
     */
    private fun importQrOtp() {
        if (checkExpiredAndBlock()) return
        OtpUpdateDialog(this, onImportSuccess = {
            tabMediator?.detach()
            tabMediator = null
            mainViewModel.reloadServerList()
            setupGroupTab()
            binding.viewPager.setCurrentItem(0, false)
            selectFirstServerIfNeeded()
        }).show()
    }

    /**
     * Mở OtpShopDialog (Admin cửa hàng - nhận sub link toàn bộ gói đăng ký).
     * onResume() sẽ tự reload server list sau khi dialog dismiss.
     */
    private fun importShopOtp() {
        if (checkExpiredAndBlock()) return
        OtpShopDialog(this, onImportSuccess = {
            tabMediator?.detach()
            tabMediator = null
            mainViewModel.reloadServerList()
            mainViewModel.subscriptionIdChanged("")
            setupGroupTab()
            binding.viewPager.setCurrentItem(0, false)
            selectFirstServerIfNeeded()
            refreshExpireBanner()
        }).show()
    }

    /**
     * Mở OtpYearDialog (Admin 1 năm - nhận sub link gói đăng ký 1 năm).
     * onResume() sẽ tự reload server list sau khi dialog dismiss.
     */
    private fun importYearOtp() {
        if (checkExpiredAndBlock()) return
        OtpYearDialog(this, onImportSuccess = {
            tabMediator?.detach()
            tabMediator = null
            mainViewModel.reloadServerList()
            mainViewModel.subscriptionIdChanged("")
            setupGroupTab()
            binding.viewPager.setCurrentItem(0, false)
            selectFirstServerIfNeeded()
            refreshExpireBanner()
        }).show()
    }

    /**
     * Tô màu riêng cho các item trong Navigation Drawer:
     * - "Cập nhật Tiktok"   → xanh dương đậm  (#1565C0)
     * - "Tải Tiktok Plusgin" → tím             (#7B1FA2)
     */
    private fun applyDrawerItemColors() {
        val menu = binding.navView.menu
        val colorBlue = android.graphics.Color.parseColor("#1565C0")

        // ── Tô màu xanh dương toàn bộ text các item ──────────────────────
        fun tintItem(itemId: Int, color: Int) {
            val item = menu.findItem(itemId) ?: return
            val spannable = android.text.SpannableString(item.title)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                0, spannable.length,
                android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
            )
            item.title = spannable
        }

        tintItem(R.id.sub_setting,             colorBlue)
        tintItem(R.id.update_tiktok,           colorBlue)
        tintItem(R.id.download_tiktok_plusgin, colorBlue)
        tintItem(R.id.update_yumvpn,           colorBlue)
        tintItem(R.id.tiktok_downloader,       colorBlue)
        tintItem(R.id.app_expire_setting,      colorBlue)
        tintItem(R.id.per_app_proxy_settings,  colorBlue)
        tintItem(R.id.settings,               colorBlue)
        tintItem(R.id.about,                  colorBlue)

        // ── Tô màu xanh dương toàn bộ icon ───────────────────────────────
        binding.navView.itemIconTintList =
            ColorStateList.valueOf(colorBlue)

        // ── Tô màu xanh dương toàn bộ text (fallback cho item không dùng Spannable) ──
        binding.navView.itemTextColor =
            ColorStateList.valueOf(colorBlue)
    }

    /**
     * Hiện dialog hướng dẫn quan trọng về TikTok mod.
     * Việc tải đã được xử lý bởi startDownloadOnce() trước đó.
     */
    private fun showTiktokInfoDialog() {
        val dp = resources.displayMetrics.density

        // Helper tạo SpannableString với màu + bold
        fun styledText(text: String, colorHex: String, bold: Boolean = true): android.text.SpannableString {
            val sp = android.text.SpannableString(text)
            sp.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(colorHex)),
                0, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (bold) sp.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return sp
        }

        // Helper tạo TextView tiêu đề mục
        fun sectionTitle(text: String, colorHex: String) = android.widget.TextView(this).apply {
            setText(styledText(text, colorHex), android.widget.TextView.BufferType.SPANNABLE)
            textSize = 14f
            setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
        }

        // Helper tạo TextView nội dung mục
        fun sectionBody(text: String) = android.widget.TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#212121"))
            setPadding(0, 0, 0, (8 * dp).toInt())
            setLineSpacing(0f, 1.25f)
        }

        // Layout container chính
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // ── Mục 1: Giới thiệu ──────────────────────────────────────────────
        container.addView(sectionTitle("📱  Bản TikTok không cần tháo SIM", "#1565C0"))
        container.addView(sectionBody("Phiên bản này hoạt động bình thường với SIM Việt Nam, không yêu cầu tháo SIM Trung Quốc. Chỉ cần bật VPN là sử dụng được đầy đủ tính năng."))

        // ── Mục 2: Tại sao cập nhật ─────────────────────────────────────────
        val warningBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padBox = (12 * dp).toInt()
            setPadding(padBox, padBox, padBox, padBox)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E3F2FD"))
                cornerRadius = 10 * dp
                setStroke((2 * dp).toInt(), android.graphics.Color.parseColor("#1565C0"))
            }
        }

        val warningTitle = android.widget.TextView(this).apply {
            setText(
                styledText("ℹ️  TẠI SAO CẦN CẬP NHẬT ĐỊNH KỲ?", "#1565C0"),
                android.widget.TextView.BufferType.SPANNABLE
            )
            textSize = 14.5f
            setPadding(0, 0, 0, (6 * dp).toInt())
        }

        val warningContent = android.text.SpannableStringBuilder().apply {
            fun bold(s: String) {
                val start = length
                append(s)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#1565C0")),
                    start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            fun normal(s: String) { append(s) }

            bold("• TikTok thường xuyên cập nhật cơ chế kiểm tra SIM và IP.")
            normal(" Mỗi phiên bản mới có thể chặn lại các SIM không phải Trung Quốc.\n\n")

            bold("• Chúng tôi theo dõi và phát hành bản vá kịp thời")
            normal(" sau mỗi lần TikTok thay đổi thuật toán.\n\n")

            bold("• Chỉ bản tải từ app này mới đảm bảo hoạt động ổn định,")
            normal(" không phải bản từ Play Store hay các nguồn khác.")
        }

        val warningBody = android.widget.TextView(this).apply {
            setText(warningContent, android.widget.TextView.BufferType.SPANNABLE)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#0D47A1"))
            setLineSpacing(0f, 1.3f)
        }

        warningBox.addView(warningTitle)
        warningBox.addView(warningBody)

        val wrapperParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }

        container.addView(warningBox, wrapperParams)

        // ── Mục 3: Xóa bản cũ ───────────────────────────────────────────────
        container.addView(sectionTitle("🗑️  Gỡ TikTok cũ trước khi cài", "#1565C0"))
        container.addView(sectionBody("Gỡ hoàn toàn mọi phiên bản TikTok hiện có trước khi cài bản này. Cài đè lên bản cũ có thể gây lỗi hoặc xung đột."))

        // ── Mục 4: Không cập nhật qua Store ────────────────────────────────
        container.addView(sectionTitle("🚫  Không cập nhật qua Play Store", "#1565C0"))
        container.addView(sectionBody("Sau khi cài xong, không cập nhật TikTok qua Google Play, APKPure hoặc bất kỳ nguồn nào khác. Cập nhật từ bên ngoài sẽ ghi đè và làm mất bản đặc biệt này."))

        // ScrollView bọc ngoài → cuộn được trên MỌI kích thước màn hình
        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋  Hướng dẫn quan trọng")
            .setView(scrollView)
            .setPositiveButton("Đóng") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .create()

        dialog.show()

        // Bo góc dialog
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 24 * dp
            }
        )

        // Tô màu nút Đóng
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(android.graphics.Color.parseColor("#1565C0"))
    }

    // =========================================================
    // ✅ FIX: Cờ chống spam dùng @Volatile để an toàn thread
    //        Dùng synchronized để chắc chắn chỉ 1 tiến trình tải tại 1 thời điểm
    // =========================================================
    @Volatile
    private var isDownloading = false

    // Job theo dõi trạng thái download hiện tại — hủy được khi cần
    private var downloadWatchJob: Job? = null

    // Job kiểm tra hết hạn app định kỳ — chạy khi VPN đang bật
    private var appExpireCheckJob: Job? = null
    private var announcementJob: Job? = null

    // Job tự cập nhật banner hết hạn mỗi 60 giây — luôn chạy khi app foreground
    private var expireBannerTickJob: Job? = null

    // Thời điểm lần cuối auto update sub — cooldown 30 phút
    private var lastAutoUpdateMs: Long = 0L
    private val AUTO_UPDATE_COOLDOWN_MS = 30 * 60 * 1000L  // 30 phút

    // Lưu các downloadId đang chạy — để onResume kiểm tra nếu user thoát app giữa chừng
    private val pendingDownloadIds = mutableMapOf<Long, String>() // downloadId → title

    // ✅ Lưu reference dialog để đóng trong onDestroy, tránh WindowLeak
    private var activeProgressDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * Reset cờ isDownloading và hủy job theo dõi.
     * Gọi mỗi khi download kết thúc (hoàn tất, lỗi, hoặc bị user hủy).
     */
    private fun resetDownloadState() {
        isDownloading = false
        downloadWatchJob?.cancel()
        downloadWatchJob = null
    }

    /**
     * Mở HttpURLConnection qua HTTP proxy local của V2Ray khi VPN đang bật.
     *
     * Lý do cần hàm này:
     * - Proxy-only mode: V2Ray chỉ tạo HTTP/SOCKS proxy ở 127.0.0.1, KHÔNG tạo tun interface.
     *   Mọi kết nối mạng thông thường (HttpURLConnection, DownloadManager) đều đi thẳng
     *   ra ngoài, KHÔNG qua proxy → tải file thất bại khi server chặn IP Việt Nam.
     * - VPN mode (tun): Nhiều ROM (Samsung, Xiaomi, OPPO) whitelist DownloadManager khỏi
     *   tun interface → vẫn bypass proxy. Dùng HTTP proxy tường minh để đảm bảo.
     *
     * Giải pháp: Luôn inject HTTP proxy tường minh khi VPN đang bật (cả 2 mode).
     * V2Ray lắng nghe HTTP proxy tại 127.0.0.1:getHttpPort() (mặc định 10809).
     */
    private fun openConnectionViaProxy(urlStr: String): java.net.HttpURLConnection {
        val url = java.net.URL(urlStr)
        val isVpnRunning = mainViewModel.isRunning.value == true
        return if (isVpnRunning) {
            // HTTP proxy tường minh — hoạt động cho cả VPN mode lẫn Proxy-only mode
            // getHttpPort() = SOCKS port + 1 (v2ray) hoặc SOCKS port (xray) — xem SettingsManager
            val httpPort = SettingsManager.getHttpPort()
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
            url.openConnection(proxy) as java.net.HttpURLConnection
        } else {
            // VPN tắt → kết nối trực tiếp bình thường
            url.openConnection() as java.net.HttpURLConnection
        }
    }

    private fun startDownloadOnce(url: String, fileName: String, title: String, prefKey: String = "", withDialog: Boolean = false) {
        synchronized(this) {
            if (isDownloading) {
                showCustomToast("⏳ Đang có file đang tải, vui lòng chờ hoàn tất!", "#E65100")
                return
            }
            isDownloading = true
        }

        val dp = resources.displayMetrics.density

        // Xóa file cũ trước khi tải để tránh cache cũ
        val destFile = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        try { if (destFile.exists()) destFile.delete() } catch (_: Exception) {}

        // ── Xây dựng dialog tiến trình ────────────────────────────────────
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        val tvTitle = android.widget.TextView(this).apply {
            text = "⬇️  Đang $title"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#1565C0"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        }

        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * dp).toInt()
            ).apply { bottomMargin = (10 * dp).toInt() }
        }

        val tvPercent = android.widget.TextView(this).apply {
            text = "0%"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#1565C0"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        }

        val tvSub = android.widget.TextView(this).apply {
            text = "Vui lòng chờ, đừng tắt ứng dụng..."
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#1565C0"))
        }

        val tvNote = android.widget.TextView(this).apply {
            text = "⏱  Quá trình có thể mất tối đa 3 phút..."
            textSize = 12.5f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#C2185B"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
        }

        root.addView(tvTitle)
        root.addView(progressBar)
        root.addView(tvPercent)
        root.addView(tvSub)
        root.addView(tvNote)

        // ✅ Lưu vào class field để onDestroy có thể đóng, tránh WindowLeak
        activeProgressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(root)
            .setCancelable(false)
            .create()

        activeProgressDialog?.show()
        activeProgressDialog?.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 28 * dp
            }
        )

        // ── Job tải file bằng coroutine + openConnectionViaProxy ─────────
        // Không dùng DownloadManager vì nó chạy trong process hệ thống riêng,
        // không đi qua proxy của app dù VPN đang bật (đặc biệt Proxy-only mode).
        downloadWatchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = openConnectionViaProxy(url)
                conn.connectTimeout = 30_000
                conn.readTimeout    = 60_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    withContext(Dispatchers.Main) {
                        activeProgressDialog?.dismiss()
                        activeProgressDialog = null
                        resetDownloadState()
                        showCustomToast("❌ Tải thất bại (HTTP ${conn.responseCode}), thử lại sau!", "#B71C1C")
                    }
                    return@launch
                }

                val totalBytes = conn.contentLengthLong

                destFile.outputStream().use { out ->
                    val buf = ByteArray(8 * 1024)
                    var downloaded = 0L
                    conn.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt().coerceIn(0, 99)
                                withContext(Dispatchers.Main) {
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = pct
                                    tvPercent.text = "$pct%"
                                    if (pct >= 80) tvNote.visibility = android.view.View.VISIBLE
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    progressBar.isIndeterminate = true
                                    tvPercent.text = "Đang tải..."
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    tvPercent.text = "100%"
                    tvSub.text = "Hoàn tất! Đang mở cài đặt..."
                    resetDownloadState()
                    if (prefKey.isNotEmpty()) markDownloadedToday(prefKey)

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        activeProgressDialog?.dismiss()
                        activeProgressDialog = null
                        // Dùng FileProvider để mở APK installer đúng chuẩn Android 7+
                        try {
                            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity,
                                "$packageName.cache",
                                destFile
                            )
                            launchInstaller(apkUri)
                        } catch (_: Exception) {
                            showCustomToast("✅ $title đã tải xong!\nKéo thông báo xuống → bấm vào để cài.", "#2E7D32")
                        }
                    }, 800)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activeProgressDialog?.dismiss()
                    activeProgressDialog = null
                    resetDownloadState()
                    if (e is CancellationException) return@withContext
                    showCustomToast("❌ Tải thất bại: ${e.message ?: "lỗi kết nối"}", "#B71C1C")
                }
            }
        }

        if (withDialog) {
            showTiktokInfoDialog()
        }
    }

    // ================= GIỚI HẠN TẢI 1 LẦN/NGÀY =================

    private fun getTodayDate(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    /**
     * Kiểm tra file có được phép tải hôm nay không.
     * Mỗi file tính riêng, reset tự động sang ngày mới.
     * Chỉ tính lần tải THÀNH CÔNG (STATUS_SUCCESSFUL).
     */
    private fun canDownloadToday(prefKey: String): Boolean {
        val prefs = getSharedPreferences("download_limit", MODE_PRIVATE)
        val lastDownloadDate = prefs.getString(prefKey, "")
        return lastDownloadDate != getTodayDate()
    }

    /**
     * Lưu ngày tải thành công — gọi sau khi STATUS_SUCCESSFUL.
     */
    private fun markDownloadedToday(prefKey: String) {
        getSharedPreferences("download_limit", MODE_PRIVATE)
            .edit()
            .putString(prefKey, getTodayDate())
            .apply()
    }

    /**
     * Tải Tiktok APK qua DownloadManager + hiện dialog hướng dẫn đồng thời.
     * ✅ FIX: Thêm timestamp vào URL + fileName để bypass cache DownloadManager Android
     *         (Android cache file theo tên → cùng tên = dùng file cũ dù server đã cập nhật)
     */
    private fun downloadTiktok() {
        if (!canDownloadToday("tiktok")) {
            showDownloadLimitDialog("Hôm nay bạn đã tải TikTok rồi!\n\nKiểm tra trong thư mục Downloads để cài.")
            return
        }
        val ts = System.currentTimeMillis()
        startDownloadOnce(
            url        = "https://vutruongvpn.com/downloads/tiktok.apk?v=$ts",
            fileName   = "tiktok_$ts.apk",
            title      = "Cập nhật Tiktok",
            prefKey    = "tiktok"
        )
    }

    /**
     * Tải Tiktok Plusgin APK qua DownloadManager + hiện dialog hướng dẫn đồng thời.
     * ✅ FIX: Thêm timestamp vào URL + fileName để bypass cache DownloadManager Android
     *         (Android cache file theo tên → cùng tên = dùng file cũ dù server đã cập nhật)
     */
    private fun downloadTiktokPlusgin() {
        if (!canDownloadToday("tiktokplusgin")) {
            showDownloadLimitDialog("Hôm nay bạn đã tải TikTok Plusgin rồi!\n\nKiểm tra trong thư mục Downloads để cài.")
            return
        }
        val ts = System.currentTimeMillis()
        startDownloadOnce(
            url        = "https://vutruongvpn.com/downloads/tiktokplusgin.apk?v=$ts",
            fileName   = "tiktokplusgin_$ts.apk",
            title      = "Tải Tiktok Plusgin",
            prefKey    = "tiktokplusgin"
        )
    }

    /**
     * Tải Yum VPN APK qua DownloadManager → hiện thông báo khi xong.
     * ✅ FIX: Thêm timestamp vào URL + fileName để bypass cache DownloadManager Android
     *         (Android cache file theo tên → cùng tên = dùng file cũ dù server đã cập nhật)
     */
    private fun downloadYumVpn() {
        if (!canDownloadToday("yumvpn")) {
            showDownloadLimitDialog("Hôm nay bạn đã tải Yum VPN rồi!\n\nKiểm tra trong thư mục Downloads để cài.")
            return
        }
        val ts = System.currentTimeMillis()
        startDownloadOnce(
            url      = "https://vutruongvpn.com/downloads/yumvpn.apk?v=$ts",
            fileName = "yumvpn_$ts.apk",
            title    = "Cập nhật Yum VPN",
            prefKey  = "yumvpn"
        )
    }

    /**
     * Hiện dialog thông báo đã tải hôm nay — hướng dẫn từng bước rõ ràng để user biết cách cài.
     */
    private fun showDownloadLimitDialog(message: String) {
        val dp = resources.displayMetrics.density

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p, p, (12 * dp).toInt())
        }

        // Tiêu đề
        root.addView(android.widget.TextView(this).apply {
            text = "📦 Bạn đã tải file này rồi!"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
        })

        // Dòng phụ — thông báo giới hạn
        root.addView(android.widget.TextView(this).apply {
            text = "1 ngày chỉ được phép tải 1 lần!"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#D32F2F"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        })

        // Hộp hướng dẫn
        val stepsBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F1F8E9"))
                cornerRadius = 10 * dp
                setStroke((1.5 * dp).toInt(), android.graphics.Color.parseColor("#AED581"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        }

        fun step(number: String, text: String, highlight: Boolean = false) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }

            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = number
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor(if (highlight) "#E65100" else "#388E3C"))
                }
                val size = (22 * dp).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = (10 * dp).toInt()
                    topMargin = (1 * dp).toInt()
                }
            })

            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                textSize = 13.5f
                setTextColor(
                    if (highlight) android.graphics.Color.parseColor("#BF360C")
                    else android.graphics.Color.parseColor("#212121")
                )
                if (highlight) typeface = android.graphics.Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.3f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }

        stepsBox.addView(android.widget.TextView(this).apply {
            text = "Cách cài đặt:"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#33691E"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        })

        stepsBox.addView(step("1", "Kéo thanh thông báo từ trên màn hình xuống"))
        stepsBox.addView(step("2", "Tìm thông báo tải file vừa xong → bấm vào"))
        stepsBox.addView(step("3", "Bấm \"Cài đặt\" khi hộp thoại hiện lên", highlight = true))
        stepsBox.addView(android.widget.TextView(this).apply {
            text = "⚠️ Nếu bị chặn: Cài đặt → Ứng dụng → Cho phép cài từ nguồn không xác định"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            setLineSpacing(0f, 1.3f)
        })

        root.addView(stepsBox)

        // Nút đóng
        val btnRow = android.widget.LinearLayout(this).apply {
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val btn = android.widget.Button(this).apply {
            text = "Đã hiểu"
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#388E3C"))
                cornerRadius = 8 * dp
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                (40 * dp).toInt()
            )
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
        }
        btnRow.addView(btn)
        root.addView(btnRow)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(root)
            .setCancelable(true)
            .create()

        btn.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 20 * resources.displayMetrics.density
            }
        )
    }

    // ================= MENU =================

    /**
     * Tìm và thay thế icon overflow (⋮) thành dấu + trên toolbar.
     * Hỗ trợ nhiều Android version: tìm theo class name "Overflow" (API 21+)
     * và fallback tìm ImageView cuối cùng trong ActionMenuView nếu không match.
     */
    private fun replaceOverflowIcon() {
        try {
            val dp = resources.displayMetrics.density
            for (i in 0 until binding.toolbar.childCount) {
                val view = binding.toolbar.getChildAt(i)
                if (view is androidx.appcompat.widget.ActionMenuView) {
                    var overflowView: android.widget.ImageView? = null

                    for (j in 0 until view.childCount) {
                        val child = view.getChildAt(j)
                        val simpleName = child.javaClass.simpleName
                        if (simpleName.contains("Overflow", ignoreCase = true) ||
                            simpleName.contains("OverflowMenuButton", ignoreCase = true)) {
                            overflowView = child as? android.widget.ImageView
                            break
                        }
                    }

                    if (overflowView == null && view.childCount > 0) {
                        val last = view.getChildAt(view.childCount - 1)
                        if (last is android.widget.ImageView) overflowView = last
                    }

                    overflowView?.apply {
                        setImageResource(R.drawable.ic_add_blue)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#1565C0")
                        )
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        val padH = (16 * dp).toInt()
                        val padV = (8 * dp).toInt()
                        setPadding(padH, padV, padH, padV)
                        (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                            lp.marginEnd = (8 * dp).toInt()
                            layoutParams = lp
                        }
                        // ✅ Thay popup menu bằng BottomSheet — icon hiện 100% mọi ROM
                        setOnClickListener { showMainMenuBottomSheet() }
                    }
                    break
                }
            }
        } catch (e: Exception) { }
    }

    /**
     * BottomSheetDialog thay thế overflow menu — icon hiện 100% trên mọi thiết bị thật.
     * Không dùng reflection, không phụ thuộc ROM.
     */
    private fun showMainMenuBottomSheet() {
        val dp = resources.displayMetrics.density
        val colorBlue = android.graphics.Color.parseColor("#1565C0")
        val colorRed  = android.graphics.Color.parseColor("#B71C1C")

        class MenuEntry(val iconRes: Int, val label: String, val tint: Int = colorBlue, val action: () -> Unit)

        val items = listOf(
            MenuEntry(R.drawable.ic_admin,             "Kích hoạt đại lý")       { importShopOtp() },
            MenuEntry(R.drawable.ic_qr_code,           "Quét mã QR")             { importQRcode() },
            MenuEntry(R.drawable.ic_clipboard,         "Dán từ clipboard")       { importClipboard() },
            MenuEntry(R.drawable.ic_action_done,       "Kích hoạt nhanh")        { importQrOtp() },
            MenuEntry(R.drawable.ic_lock_24dp,         "Kích hoạt 1 năm")        { importYearOtp() },
            MenuEntry(R.drawable.ic_restore_24dp,      "Khởi động lại ứng dụng") {
                toast(R.string.title_service_restart)
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            },
            MenuEntry(iconRes = R.drawable.ic_delete_24dp, label = "Xoá tất cả máy chủ", tint = colorBlue, action = {
                val count = mainViewModel.removeAllServer()
                mainViewModel.reloadServerList()
                setupGroupTab()
                toast(getString(R.string.title_del_config_count, count))
            }),
            MenuEntry(R.drawable.ic_feedback_24dp,     "Ping tất cả máy chủ")   {
                if (mainViewModel.serversCache.isEmpty()) toast(R.string.title_file_chooser)
                else { mainViewModel.testAllTcping(); toast(R.string.title_ping_all_server) }
            },
            MenuEntry(R.drawable.ic_subscriptions_24dp, "Cập nhật máy chủ")     { importConfigViaSub() },
        )

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        // Giới hạn width = 98% màn hình, căn giữa
        val screenWidth = resources.displayMetrics.widthPixels
        val sheetWidth = (screenWidth * 0.98f).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt())
            // Bo tròn 4 góc
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 20 * dp
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(sheetWidth, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }

        // Handle kéo ở đỉnh
        val handle = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (40 * dp).toInt(), (4 * dp).toInt()
            ).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.topMargin = (10 * dp).toInt()
                it.bottomMargin = (8 * dp).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#DDDDDD"))
                cornerRadius = 99 * dp
            }
        }
        container.addView(handle)

        for (entry in items) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((24 * dp).toInt(), (13 * dp).toInt(), (24 * dp).toInt(), (13 * dp).toInt())
                isClickable = true
                isFocusable = true
                background = with(android.util.TypedValue()) {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                    ContextCompat.getDrawable(this@MainActivity, resourceId)
                }
            }
            val iconSize = (22 * dp).toInt()
            val icon = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize).also {
                    it.marginEnd = (16 * dp).toInt()
                }
                setImageResource(entry.iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(entry.tint)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            val label = android.widget.TextView(this).apply {
                text = entry.label
                textSize = 15f
                setTextColor(entry.tint)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            row.addView(icon)
            row.addView(label)
            // Set lại màu sau khi add vào layout để đảm bảo không bị override
            icon.imageTintList = android.content.res.ColorStateList.valueOf(entry.tint)
            label.setTextColor(entry.tint)
            row.setOnClickListener { sheet.dismiss(); entry.action() }
            container.addView(row)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        sheet.setContentView(scrollView)
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(sheetWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
        sheet.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // ✅ Vẫn inflate menu_main để Android tạo ra nút overflow (dấu +) trên toolbar
        // replaceOverflowIcon() hook vào nút đó và gắn BottomSheet thay popup menu
        menuInflater.inflate(R.menu.menu_main, menu)
        binding.toolbar.post {
            binding.toolbar.postDelayed({
                replaceOverflowIcon()
            }, 150)
        }
        return true
    }


    // ✅ Debounce: chống double-tap mở 2 dialog liên tiếp
    private var lastDialogOpenMs = 0L
    private fun canOpenDialog(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDialogOpenMs < 600) return false
        lastDialogOpenMs = now
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_qr_otp -> {
            importQrOtp()
            true
        }
        R.id.import_shop_otp -> {
            importShopOtp()
            true
        }
        R.id.import_year_otp -> {
            importYearOtp()
            true
        }
        R.id.sub_update -> {
            importConfigViaSub()
            true
        }
        R.id.ping_all -> {
            if (mainViewModel.serversCache.isEmpty()) {
                toast(R.string.title_file_chooser)
            } else {
                mainViewModel.testAllTcping()
                toast(R.string.title_ping_all_server)
            }
            true
        }
        R.id.real_ping_all -> {
            mainViewModel.testAllRealPing()
            toast(R.string.title_real_ping_all_server)
            true
        }
        R.id.del_all_config -> {
            val count = mainViewModel.removeAllServer()
            mainViewModel.reloadServerList()
            setupGroupTab()
            toast(getString(R.string.title_del_config_count, count))
            true
        }
        R.id.service_restart -> {
            toast(R.string.title_service_restart)
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Hiện dialog native tải TikTok không logo.
     * Gọi API tikwm.com, hiện thông tin video, tải bằng DownloadManager.
     * Không dùng WebView / HTML.
     */
    private fun showTikTokDownloaderDialog() {
        val dp = resources.displayMetrics.density
        val p16 = (16 * dp).toInt()
        val p12 = (12 * dp).toInt()

        // ── Root layout ──────────────────────────────────────────────────
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(p16, p16, p16, p16)
        }

        // ── Tiêu đề ──────────────────────────────────────────────────────
        root.addView(android.widget.TextView(this).apply {
            text = "⬇️  Tải TikTok không Logo"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * dp).toInt() }
        })

        // ── Input URL + nút Dán / X (nằm BÊN TRONG input, góc phải) ────────
        // Dùng FrameLayout để overlay nút lên trên EditText
        val inputFrame = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }

        // Padding phải của input = đủ chỗ cho nút bên trong
        val btnAreaWidth = (70 * dp).toInt()

        val etUrl = android.widget.EditText(this).apply {
            hint = "Dán link TikTok vào đây..."
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#1A1F36"))
            setHintTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F5F7FF"))
                cornerRadius = 12 * dp
                setStroke((1.5 * dp).toInt(), android.graphics.Color.parseColor("#C5CAE9"))
            }
            // Padding phải rộng hơn để text không chồng lên nút
            setPadding(p12, p12, btnAreaWidth, p12)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 2
            minHeight = (46 * dp).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Nút Dán — overlay bên phải, căn giữa theo chiều dọc
        val btnPaste = android.widget.Button(this).apply {
            text = "DÁN"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#3b82f6"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 999 * dp
                setStroke((1.5 * dp).toInt(), android.graphics.Color.parseColor("#3b82f6"))
            }
            stateListAnimator = null
            val pH = (10 * dp).toInt()
            val pV = (2 * dp).toInt()
            setPadding(pH, pV, pH, pV)
            minHeight = 0
            minimumHeight = 0
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                (32 * dp).toInt(),
                android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            ).apply { rightMargin = (8 * dp).toInt() }
        }

        // Nút X — overlay bên phải, căn giữa theo chiều dọc, ẩn mặc định
        val btnClear = android.widget.Button(this).apply {
            text = "✕"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#ef4444"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#fee2e2"))
                cornerRadius = 999 * dp
            }
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            val sz = (26 * dp).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(sz, sz,
                android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            ).apply { rightMargin = (10 * dp).toInt() }
            visibility = android.view.View.GONE
        }

        inputFrame.addView(etUrl)
        inputFrame.addView(btnPaste)
        inputFrame.addView(btnClear)
        root.addView(inputFrame)

        // ── Status text (loading / error) ─────────────────────────────────
        val tvStatus = android.widget.TextView(this).apply {
            textSize = 13f
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
        root.addView(tvStatus)

        // ── Card kết quả ──────────────────────────────────────────────────
        val cardResult = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F5F7FF"))
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#C5CAE9"))
            }
            setPadding(p12, p12, p12, p12)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(cardResult)

        // ── Thumbnail + meta row ──────────────────────────────────────────
        val metaRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        }

        val ivThumb = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E8EAF6"))
                cornerRadius = 8 * dp
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (64 * dp).toInt(), (84 * dp).toInt()
            ).apply { rightMargin = (12 * dp).toInt() }
        }

        val metaText = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val tvTitle = android.widget.TextView(this).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#1A1F36"))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
        }

        val tvMeta = android.widget.TextView(this).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#5C6BC0"))
        }

        metaText.addView(tvTitle)
        metaText.addView(tvMeta)
        metaRow.addView(ivThumb)
        metaRow.addView(metaText)
        cardResult.addView(metaRow)

        // ── Nút tải video ─────────────────────────────────────────────────
        val btnDownload = android.widget.Button(this).apply {
            text = "⬇️  Tải video chất lượng gốc"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 999 * dp  // bo pill
                setStroke((1.5 * dp).toInt(), android.graphics.Color.parseColor("#E65100"))
            }
            val pH = (20 * dp).toInt()
            val pV = (9 * dp).toInt()
            setPadding(pH, pV, pH, pV)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            stateListAnimator = null
        }
        cardResult.addView(btnDownload)

        // ── Footer: nút Đóng + text ghi chú ──────────────────────────────
        val footerRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() }
        }

        val btnClose = android.widget.Button(this).apply {
            text = "Đóng"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            background = null
            stateListAnimator = null
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvNote = android.widget.TextView(this).apply {
            text = "Chỉ hỗ trợ TikTok Việt Nam"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        footerRow.addView(tvNote)
        footerRow.addView(btnClose)
        root.addView(footerRow)

        // ── ScrollView bọc toàn bộ ───────────────────────────────────────
        val scroll = android.widget.ScrollView(this).apply {
            addView(root)
        }

        // ── Dialog ───────────────────────────────────────────────────────
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setCancelable(true)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        // ── Helper: hiện status ───────────────────────────────────────────
        fun showStatus(msg: String, isError: Boolean = false) {
            tvStatus.text = msg
            tvStatus.setTextColor(
                if (isError) android.graphics.Color.parseColor("#B71C1C")
                else android.graphics.Color.parseColor("#3949AB")
            )
            tvStatus.visibility = android.view.View.VISIBLE
        }
        fun hideStatus() { tvStatus.visibility = android.view.View.GONE }

        // ── Lưu videoUrl để btnDownload dùng ─────────────────────────────
        var currentVideoUrl = ""

        // ── Tải thumbnail từ URL (chạy trên IO thread) ───────────────────
        fun loadThumb(url: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = openConnectionViaProxy(url)
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.connect()
                    val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        if (bmp != null) {
                            // Bo tròn ảnh thumbnail
                            val rounded = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, bmp.config ?: android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(rounded)
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            paint.shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                            canvas.drawRoundRect(android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), 16 * dp, 16 * dp, paint)
                            ivThumb.setImageBitmap(rounded)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // ── Job fetch có thể cancel ───────────────────────────────────────
        var fetchJob: kotlinx.coroutines.Job? = null

        // ── Helper kiểm tra link hợp lệ ──────────────────────────────────
        fun isValidVideoUrl(text: String) = text.contains("tiktok.com") ||
                text.contains("vm.tiktok") || text.contains("vt.tiktok")

        // ── Gọi API tikwm ─────────────────────────────────────────────────
        fun fetchVideo() {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) return
            if (!isValidVideoUrl(url)) {
                showStatus("❌ Link không hợp lệ (chỉ hỗ trợ TikTok)", isError = true)
                return
            }

            // Hủy fetch trước nếu đang chạy
            fetchJob?.cancel()
            cardResult.visibility = android.view.View.GONE
            showStatus("⏳ Đang tìm video...")

            fetchJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val apiUrl = "https://www.tikwm.com/api/?url=${java.net.URLEncoder.encode(url, "UTF-8")}&hd=1"
                    // Timeout 40 giây
                    val result = kotlinx.coroutines.withTimeout(40_000L) {
                        val conn = openConnectionViaProxy(apiUrl)
                        conn.connectTimeout = 40_000
                        conn.readTimeout = 40_000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        conn.connect()
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        body
                    }

                    val json = org.json.JSONObject(result)
                    if (json.optInt("code", -1) != 0 || !json.has("data")) {
                        val msg = json.optString("msg", "").lowercase()
                        // Rate limit → tự động retry sau 2 giây, không báo lỗi ra ngoài
                        if (msg.contains("limit") || msg.contains("too many") || msg.contains("frequent")) {
                            delay(2_000)
                            // Gọi lại API lần 2
                            val retryConn = openConnectionViaProxy(apiUrl)
                            retryConn.connectTimeout = 40_000
                            retryConn.readTimeout = 40_000
                            retryConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            retryConn.connect()
                            val retryBody = retryConn.inputStream.bufferedReader().readText()
                            retryConn.disconnect()
                            val retryJson = org.json.JSONObject(retryBody)
                            if (retryJson.optInt("code", -1) != 0 || !retryJson.has("data")) {
                                withContext(Dispatchers.Main) {
                                    showStatus("❌ Không lấy được video, thử lại sau!", isError = true)
                                }
                                return@launch
                            }
                            // Retry thành công → tiếp tục xử lý với retryJson
                            val data = retryJson.getJSONObject("data")
                            val rawDesc = data.optString("desc").ifEmpty {
                                data.optString("text").ifEmpty { data.optString("title", "") }
                            }
                            val title = rawDesc.split("#").first().trim().ifEmpty { "Video TikTok" }
                            val cover = data.optString("cover").ifEmpty { data.optString("origin_cover", "") }
                            val likes    = data.optLong("digg_count").takeIf { it > 0L } ?: data.optLong("like_count", 0L)
                            val comments = data.optLong("comment_count", 0L)
                            val plays    = data.optLong("play_count").takeIf { it > 0L } ?: data.optLong("view_count", 0L)
                            val hdUrl = data.optString("hdplay").ifEmpty {
                                data.optString("play").ifEmpty { data.optString("wmplay", "") }
                            }
                            withContext(Dispatchers.Main) {
                                hideStatus()
                                if (hdUrl.isEmpty()) { showStatus("❌ Không tìm thấy link tải", isError = true); return@withContext }
                                currentVideoUrl = hdUrl
                                tvTitle.text = title
                                fun fmtNum(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n/1_000_000.0); n >= 1_000 -> "%.1fK".format(n/1_000.0); else -> n.toString() }
                                tvMeta.text = "❤️ ${fmtNum(likes)}   💬 ${fmtNum(comments)}   👁 ${fmtNum(plays)}"
                                if (cover.isNotEmpty()) loadThumb(cover)
                                cardResult.visibility = android.view.View.VISIBLE
                            }
                            return@launch
                        }
                        // Lỗi khác
                        withContext(Dispatchers.Main) {
                            showStatus("❌ Không lấy được video, thử lại sau!", isError = true)
                        }
                        return@launch
                    }

                    val data = json.getJSONObject("data")
                    // desc chứa cả caption + hashtag, cần tách bỏ hashtag
                    // Lấy phần text trước hashtag đầu tiên; nếu không có text thì dùng toàn bộ
                    val rawDesc = data.optString("desc").ifEmpty {
                        data.optString("text").ifEmpty {
                            data.optString("title", "")
                        }
                    }
                    val title = rawDesc
                        .split("#").first()   // cắt bỏ từ # đầu tiên trở đi
                        .trim()
                        .ifEmpty {
                            // Toàn bộ là hashtag, không có caption → hiện "Video TikTok"
                            "Video TikTok"
                        }
                    // Cover fallback: cover → origin_cover
                    val cover = data.optString("cover").ifEmpty { data.optString("origin_cover", "") }
                    // Stats giống HTML
                    val likes    = data.optLong("digg_count").takeIf { it > 0L } ?: data.optLong("like_count", 0L)
                    val comments = data.optLong("comment_count", 0L)
                    val plays    = data.optLong("play_count").takeIf { it > 0L } ?: data.optLong("view_count", 0L)
                    // URL HD → SD fallback → wmplay
                    val hdUrl = data.optString("hdplay").ifEmpty {
                        data.optString("play").ifEmpty { data.optString("wmplay", "") }
                    }

                    withContext(Dispatchers.Main) {
                        hideStatus()

                        if (hdUrl.isEmpty()) {
                            showStatus("❌ Không tìm thấy link tải", isError = true)
                            return@withContext
                        }

                        currentVideoUrl = hdUrl

                        // Điền meta
                        tvTitle.text = title

                        fun fmtNum(n: Long): String = when {
                            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
                            n >= 1_000     -> "%.1fK".format(n / 1_000.0)
                            else           -> n.toString()
                        }
                        tvMeta.text = "❤️ ${fmtNum(likes)}   💬 ${fmtNum(comments)}   👁 ${fmtNum(plays)}"

                        if (cover.isNotEmpty()) loadThumb(cover)
                        cardResult.visibility = android.view.View.VISIBLE
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    withContext(Dispatchers.Main) {
                        showStatus("❌ Quá thời gian chờ (40s) — kiểm tra lại mạng!", isError = true)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showStatus("❌ Lỗi kết nối — thử lại sau!", isError = true)
                    }
                }
            }
        }

        // ── Toggle nút Dán / X theo nội dung input ────────────────────────
        fun toggleInputButtons() {
            val hasText = etUrl.text.toString().isNotEmpty()
            btnPaste.visibility = if (hasText) android.view.View.GONE else android.view.View.VISIBLE
            btnClear.visibility = if (hasText) android.view.View.VISIBLE else android.view.View.GONE
        }

        // Nút Dán: đọc clipboard rồi điền vào input
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                etUrl.setText(text)
                etUrl.setSelection(etUrl.text.length)
                toggleInputButtons()
                if (isValidVideoUrl(text)) fetchVideo()
            } else {
                showStatus("❌ Clipboard trống", isError = true)
            }
        }

        // Nút X: xóa input, reset UI
        btnClear.setOnClickListener {
            etUrl.setText("")
            currentVideoUrl = ""
            cardResult.visibility = android.view.View.GONE
            hideStatus()
            toggleInputButtons()
        }

        // ── Tự động fetch khi user dán link vào ──────────────────────────
        etUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                toggleInputButtons()
                val text = s?.toString()?.trim() ?: return
                if (isValidVideoUrl(text)) fetchVideo()
            }
        })

        // ── Bind actions ──────────────────────────────────────────────────

        btnDownload.setOnClickListener {
            if (currentVideoUrl.isEmpty()) return@setOnClickListener
            try {
                val ts = System.currentTimeMillis()
                val fileName = "tiktok_$ts.mp4"
                dialog.dismiss()

                // ── Progress dialog ───────────────────────────────────────
                val progDp = resources.displayMetrics.density
                val progRoot = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((20 * progDp).toInt(), (20 * progDp).toInt(), (20 * progDp).toInt(), (16 * progDp).toInt())
                }

                val tvProgTitle = android.widget.TextView(this).apply {
                    text = "⬇️  Đang tải video..."
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#1A237E"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (12 * progDp).toInt() }
                }

                val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = 0
                    isIndeterminate = false
                    progressDrawable = android.graphics.drawable.LayerDrawable(
                        arrayOf(
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#E8EAF6"))
                                cornerRadius = 99 * progDp
                            },
                            android.graphics.drawable.ClipDrawable(
                                android.graphics.drawable.GradientDrawable().apply {
                                    setColor(android.graphics.Color.parseColor("#3949AB"))
                                    cornerRadius = 99 * progDp
                                },
                                android.view.Gravity.START,
                                android.graphics.drawable.ClipDrawable.HORIZONTAL
                            )
                        )
                    ).also { ld ->
                        ld.setId(0, android.R.id.background)
                        ld.setId(1, android.R.id.progress)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (10 * progDp).toInt()
                    ).apply { bottomMargin = (10 * progDp).toInt() }
                }

                val tvPercent = android.widget.TextView(this).apply {
                    text = "0%"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#3949AB"))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * progDp).toInt() }
                }

                val tvSize = android.widget.TextView(this).apply {
                    text = "Đang kết nối..."
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#757575"))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                progRoot.addView(tvProgTitle)
                progRoot.addView(progressBar)
                progRoot.addView(tvPercent)
                progRoot.addView(tvSize)

                // ── Nút Hủy (chỉ hiện khi đang tải, ẩn khi xong) ────────
                val btnCancel = android.widget.Button(this).apply {
                    text = "Hủy tải"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#757575"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#F5F5F5"))
                        cornerRadius = 999 * progDp
                    }
                    stateListAnimator = null
                    val pH = (20 * progDp).toInt()
                    val pV = (8 * progDp).toInt()
                    setPadding(pH, pV, pH, pV)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (12 * progDp).toInt()
                        gravity = android.view.Gravity.END
                    }
                }
                val cancelRow = android.widget.LinearLayout(this).apply {
                    gravity = android.view.Gravity.END
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(btnCancel)
                }
                progRoot.addView(cancelRow)

                val progDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setView(progRoot)
                    .setCancelable(false)
                    .create()

                progDialog.show()
                progDialog.window?.apply {
                    setBackgroundDrawable(
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.WHITE)
                            cornerRadius = 20 * progDp
                        }
                    )
                    setLayout(
                        (resources.displayMetrics.widthPixels * 0.88).toInt(),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // ── Tải bằng coroutine + lưu thẳng vào MediaStore (Gallery) ──
                // Không dùng DownloadManager để tránh phụ thuộc vào scan/hãng máy
                var downloadJob: kotlinx.coroutines.Job? = null
                var isCancelled = false

                btnCancel.setOnClickListener {
                    isCancelled = true
                    downloadJob?.cancel()
                    progDialog.dismiss()
                }

                downloadJob = lifecycleScope.launch(Dispatchers.IO) {
                    // ── Biến giữ URI MediaStore để dùng cho Xem/Chia sẻ ─────
                    var savedMediaUri: android.net.Uri? = null

                    try {
                        // 1. Mở kết nối HTTP qua proxy (nếu VPN đang bật)
                        val conn = openConnectionViaProxy(currentVideoUrl)
                        conn.connectTimeout = 30_000
                        conn.readTimeout    = 60_000
                        conn.setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        )
                        conn.setRequestProperty("Referer", "https://www.tikwm.com/")
                        conn.connect()

                        if (conn.responseCode !in 200..299) {
                            withContext(Dispatchers.Main) {
                                if (!isCancelled) {
                                    progDialog.dismiss()
                                    showCustomToast("❌ Tải thất bại (HTTP ${conn.responseCode}), thử lại sau!", "#B71C1C")
                                }
                            }
                            return@launch
                        }

                        val totalBytes = conn.contentLengthLong  // -1 nếu server không trả Content-Length

                        // 2. Insert vào MediaStore trước — lấy URI để ghi stream vào
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            // DCIM/TikTok → hiện thẳng trong app Ảnh của mọi hãng máy
                            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/TikTok")
                            // Đánh dấu đang ghi — hệ thống ẩn file cho đến khi IS_PENDING = 0
                            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                        }
                        val mediaUri = contentResolver.insert(
                            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            values
                        )

                        if (mediaUri == null) {
                            withContext(Dispatchers.Main) {
                                if (!isCancelled) {
                                    progDialog.dismiss()
                                    showCustomToast("❌ Không tạo được file trong Gallery!", "#B71C1C")
                                }
                            }
                            return@launch
                        }

                        // 3. Stream dữ liệu từ mạng vào MediaStore
                        contentResolver.openOutputStream(mediaUri)?.use { out ->
                            val input = conn.inputStream
                            val buf = ByteArray(8 * 1024)
                            var downloaded = 0L
                            var lastUiUpdate = 0L

                            while (true) {
                                if (isCancelled) break
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n

                                // Cập nhật UI mỗi 200ms để tránh spam Main thread
                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdate >= 200) {
                                    lastUiUpdate = now
                                    val pct = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else -1
                                    val dlMb  = "%.1f MB".format(downloaded / 1_048_576.0)
                                    val totMb = if (totalBytes > 0) "/ %.1f MB".format(totalBytes / 1_048_576.0) else ""
                                    withContext(Dispatchers.Main) {
                                        if (pct >= 0) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = pct
                                            tvPercent.text = "$pct%"
                                        } else {
                                            progressBar.isIndeterminate = true
                                        }
                                        tvSize.text = "$dlMb $totMb"
                                    }
                                }
                            }
                        }

                        conn.disconnect()

                        // 4. Nếu user hủy giữa chừng → xóa file dở rồi thoát
                        if (isCancelled) {
                            contentResolver.delete(mediaUri, null, null)
                            return@launch
                        }

                        // 5. Xóa cờ IS_PENDING → file xuất hiện trong Gallery ngay lập tức
                        val update = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        contentResolver.update(mediaUri, update, null, null)
                        savedMediaUri = mediaUri

                        // 6. Cập nhật UI thành công
                        withContext(Dispatchers.Main) {
                            cancelRow.visibility = android.view.View.GONE
                            progressBar.isIndeterminate = false
                            progressBar.progress = 100
                            tvPercent.text = "100%"
                            tvProgTitle.text = "✅  Đã tải xong!"
                            tvSize.text = "Video đã lưu vào Album ảnh (DCIM/TikTok)"

                            // ── Style chung cho 3 nút ─────────────────────────
                            fun makeBtn(label: String): android.widget.Button {
                                return android.widget.Button(this@MainActivity).apply {
                                    text = label
                                    textSize = 11f
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    setTextColor(android.graphics.Color.parseColor("#3b82f6"))
                                    background = android.graphics.drawable.GradientDrawable().apply {
                                        setColor(android.graphics.Color.WHITE)
                                        cornerRadius = 999 * progDp
                                        setStroke((1.5 * progDp).toInt(), android.graphics.Color.parseColor("#3b82f6"))
                                    }
                                    stateListAnimator = null
                                    minHeight = 0
                                    minimumHeight = 0
                                    val pH = (8 * progDp).toInt()
                                    val pV = (5 * progDp).toInt()
                                    setPadding(pH, pV, pH, pV)
                                    layoutParams = android.widget.LinearLayout.LayoutParams(
                                        0, (34 * progDp).toInt(), 1f
                                    ).apply {
                                        marginStart = (4 * progDp).toInt()
                                        marginEnd   = (4 * progDp).toInt()
                                    }
                                }
                            }

                            val btnRow = android.widget.LinearLayout(this@MainActivity).apply {
                                orientation = android.widget.LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { topMargin = (12 * progDp).toInt() }
                            }

                            // Nút Xem ngay — thử nhiều cách, đảm bảo mở được trên mọi máy
                            val btnPlay = makeBtn("▶ Xem ngay").also { btn ->
                                btn.setOnClickListener {
                                    var opened = false

                                    // Cách 1: ACTION_VIEW với MediaStore URI (máy thật, Android 10+)
                                    if (!opened && savedMediaUri != null) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(savedMediaUri, "video/mp4")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            if (intent.resolveActivity(packageManager) != null) {
                                                startActivity(intent)
                                                opened = true
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    // Cách 2: Mở thư mục DCIM/TikTok trong Files/Gallery
                                    if (!opened) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(
                                                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                                    "video/*"
                                                )
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            if (intent.resolveActivity(packageManager) != null) {
                                                startActivity(intent)
                                                opened = true
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    // Cách 3: Mở app Gallery hệ thống
                                    if (!opened) {
                                        try {
                                            val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                                                ?: packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
                                                ?: packageManager.getLaunchIntentForPackage("com.miui.gallery")
                                                ?: packageManager.getLaunchIntentForPackage("com.coloros.gallery3d")
                                            if (intent != null) {
                                                startActivity(intent)
                                                opened = true
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    if (!opened) {
                                        showCustomToast("✅ Video đã lưu vào DCIM/TikTok trong bộ nhớ máy!", "#2E7D32")
                                    }
                                }
                            }

                            // Nút Chia sẻ — dùng MediaStore URI, mọi app đều đọc được
                            val btnShare = makeBtn("↗ Chia sẻ").also { btn ->
                                btn.setOnClickListener {
                                    try {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, savedMediaUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        startActivity(Intent.createChooser(shareIntent, "Chia sẻ video"))
                                    } catch (_: Exception) {
                                        showCustomToast("❌ Không chia sẻ được!", "#B71C1C")
                                    }
                                }
                            }

                            val btnClose = makeBtn("✕").also { btn ->
                                btn.setOnClickListener { progDialog.dismiss() }
                            }

                            btnRow.addView(btnPlay)
                            btnRow.addView(btnShare)
                            btnRow.addView(btnClose)
                            progRoot.addView(btnRow)
                        }

                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Job bị cancel (user bấm Hủy) → không làm gì thêm
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (!isCancelled) {
                                progDialog.dismiss()
                                showCustomToast("❌ Lỗi khi tải video, thử lại sau!", "#B71C1C")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                showCustomToast("❌ Không tải được, thử lại sau!", "#B71C1C")
            }
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    cornerRadius = 20 * dp
                }
            )
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ================= NAV DRAWER =================

    /**
     * Bọc hành động tải TikTok bằng kiểm tra OTP:
     * - Đã xác minh trước đây (bởi bất kỳ OTP dialog nào) → chạy [action] luôn
     * - Chưa xác minh → hiện OtpDialog, xác minh thành công thì lưu + chạy [action]
     */
    private fun requireTiktokOtp(action: () -> Unit) {
        if (TiktokDownloadPermission.isVerified(this)) {
            action()
            return
        }
        OtpDialog.show(this) {
            // markVerified đã được gọi bên trong OtpDialog khi xác minh thành công
            action()
        }
    }

    /**
     * Luôn luôn yêu cầu OTP mỗi lần nhấn — không kiểm tra cache đã xác minh trước đó.
     * Dùng riêng cho "Tải Tiktok" và "Tải Tiktok Plusgin".
     */
    private fun requireTiktokOtpAlways(action: () -> Unit) {
        OtpDialog.show(this) {
            action()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> {
                // startActivity: bấm back → về drawer, onResume tự reload
                startActivity(Intent(this, SubSettingActivity::class.java))
            }
            R.id.update_tiktok -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                requireTiktokOtpAlways { downloadTiktok() }
            }
            R.id.download_tiktok_plusgin -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                requireTiktokOtpAlways { downloadTiktokPlusgin() }
            }
            R.id.update_yumvpn -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                downloadYumVpn()
            }
            R.id.tiktok_downloader -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                showTikTokDownloaderDialog()
            }
            R.id.per_app_proxy_settings -> {
                // OTP hiện trên drawer, sau khi đúng mới launch
                AdminOtpDialog(this, title = "Xác minh vào Per-App Proxy") {
                    startActivity(Intent(this, PerAppProxyActivity::class.java))
                }.show()
            }
            R.id.settings -> {
                // Cần requestActivityLauncher để restart V2Ray nếu settings thay đổi
                AdminOtpDialog(this, title = "Xác minh vào Cài đặt") {
                    requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
                }.show()
            }
            R.id.routing_setting -> {
                // Cần requestActivityLauncher để restart V2Ray nếu routing thay đổi
                AdminOtpDialog(this, title = "Xác minh vào Cài đặt Routing") {
                    requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
                }.show()
            }
            R.id.user_asset_setting -> {
                startActivity(Intent(this, UserAssetActivity::class.java))
            }
            R.id.logcat -> {
                startActivity(Intent(this, LogcatActivity::class.java))
            }
            R.id.about -> {
                startActivity(Intent(this, AboutActivity::class.java))
            }
            R.id.app_expire_setting -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                // Mở thẳng dialog cài hạn — OTP chỉ hỏi khi bấm Lưu bên trong
                AppExpireSettingDialog(this) {
                    // Sau khi admin thay đổi ngày → cập nhật banner + kiểm tra ngay
                    refreshExpireBanner()
                    // Nếu vừa set ngày đã hết hạn → dừng VPN ngay
                    if (AppExpireManager.isExpired(this) &&
                        mainViewModel.isRunning.value == true
                    ) {
                        V2RayServiceManager.stopVService(this)
                        showAppExpiredDialog()
                    }
                }.show()
            }
        }
        return true
    }

    /**
     * Gọi khi sub link thay đổi — cập nhật banner ngay và restart tick
     * để tick dùng expireTs mới.
     */
    private fun refreshExpireBanner() {
        updateExpireBanner()
        startExpireBannerTick()
    }

    /**
     * Hiển thị banner ngày hết hạn subscription ngay dưới toolbar.
     * Lấy sub enabled đầu tiên có expireDate, hiển thị màu theo trạng thái:
     *   - Còn > 7 ngày  → nền xanh lá nhạt, chữ xanh
     *   - Còn ≤ 7 ngày  → nền cam nhạt,    chữ cam đậm
     *   - Đã hết hạn    → nền đỏ nhạt,     chữ đỏ đậm
     * Không có sub nào có expireDate → ẩn banner.
     */
    // ─────────────────────────────────────────────────────────────────
    // THÔNG BÁO CHẠY NGANG TỪ WEB ADMIN
    // ─────────────────────────────────────────────────────────────────

    /**
     * Gọi API lấy thông báo → hiện/ẩn marquee bar ngay dưới tab.
     * Gọi mỗi lần onResume() để luôn cập nhật.
     */
    private fun fetchAnnouncement() {
        announcementJob?.cancel()
        announcementJob = lifecycleScope.launch {
            // Lặp vô tận: fetch ngay lần đầu, sau đó mỗi 1 phút fetch lại
            // → thông báo luôn cập nhật kể cả khi user không tắt đa nhiệm
            // → interval xoay vòng tin nhắn được cài trên web admin
            while (true) {
                when (val result = AnnouncementFetcher.fetchAnnouncement()) {
                    is AnnouncementFetcher.FetchResult.Success -> {
                        binding.tvAnnouncement.apply {
                            text = "  📢  ${result.message}          "
                            try {
                                setBackgroundColor(android.graphics.Color.parseColor(result.color))
                                setTextColor(android.graphics.Color.parseColor(result.textColor))
                            } catch (e: Exception) {
                                setBackgroundColor(android.graphics.Color.parseColor("#E65100"))
                                setTextColor(android.graphics.Color.WHITE)
                            }
                            visibility = android.view.View.VISIBLE
                            isSelected = true
                            post {
                                isSelected = false
                                isSelected = true
                            }
                        }
                    }
                    is AnnouncementFetcher.FetchResult.Hidden -> {
                        binding.tvAnnouncement.visibility = android.view.View.GONE
                    }
                    is AnnouncementFetcher.FetchResult.Error -> {
                        // Lỗi mạng → giữ nguyên trạng thái cũ, không thay đổi UI
                    }
                }
                // App gọi API mỗi 1 phút — đủ để bắt kịp thay đổi từ web admin
                delay(60 * 1000L)
            }
        }
    }

    private fun updateExpireBanner() {
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())

        // Helper: tính chuỗi "còn X ngày / X giờ / X phút / X giây"
        fun formatTimeLeft(expireTs: Long): String {
            val diffMs = expireTs - now
            val diffDays = diffMs / (1000L * 60 * 60 * 24)
            val diffHours = diffMs / (1000L * 60 * 60)
            val diffMinutes = diffMs / (1000L * 60)
            val diffSeconds = diffMs / 1000L
            return when {
                diffDays >= 1 -> "còn lại $diffDays ngày"
                diffHours >= 1 -> "còn lại $diffHours giờ"
                diffMinutes >= 1 -> "còn lại $diffMinutes phút"
                diffSeconds >= 0 -> "còn lại $diffSeconds giây"
                else -> ""
            }
        }

        // ✅ Ưu tiên ngày hết hạn từ AppExpireManager (thiết bị đã cài)
        val deviceExpireTs = AppExpireManager.getExpireTimestamp(this)
        if (deviceExpireTs > 0L) {
            val dateStr = sdf.format(java.util.Date(deviceExpireTs))
            binding.tvToolbarExpire.visibility = android.view.View.VISIBLE
            when {
                now > deviceExpireTs -> {
                    binding.tvToolbarExpire.text = "⏰ ĐÃ HẾT HẠN ($dateStr)"
                    binding.tvToolbarExpire.setTextColor(android.graphics.Color.parseColor("#B71C1C"))
                }
                else -> {
                    binding.tvToolbarExpire.text = "Ngày hết hạn: $dateStr · ${formatTimeLeft(deviceExpireTs)}"
                    // Cài tay trên app → xanh dương
                    binding.tvToolbarExpire.setTextColor(android.graphics.Color.parseColor("#1565C0"))
                }
            }
            binding.tvToolbarExpire.post {
                binding.tvToolbarExpire.isSelected = false
                binding.tvToolbarExpire.isSelected = true
                binding.tvToolbarExpire.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            return
        }

        // Fallback: lấy từ sub link nếu thiết bị chưa cài ngày hết hạn
        val subs = MmkvManager.decodeSubscriptions()
        val activeSub = subs.firstOrNull { it.subscription.enabled && it.subscription.expireDate != null }

        if (activeSub == null) {
            binding.tvToolbarExpire.visibility = android.view.View.GONE
            return
        }

        val expireTs = activeSub.subscription.expireDate!! * 1000L  // giây → ms
        val sdfDate = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        val dateStr = sdfDate.format(java.util.Date(expireTs))

        binding.tvToolbarExpire.visibility = android.view.View.VISIBLE
        when {
            now > expireTs -> {
                binding.tvToolbarExpire.text = "⏰ ĐÃ HẾT HẠN ($dateStr)"
                binding.tvToolbarExpire.setTextColor(android.graphics.Color.parseColor("#B71C1C"))
            }
            else -> {
                binding.tvToolbarExpire.text = "Ngày hết hạn: $dateStr · ${formatTimeLeft(expireTs)}"
                binding.tvToolbarExpire.setTextColor(android.graphics.Color.parseColor("#E65100"))
            }
        }
        binding.tvToolbarExpire.post {
            binding.tvToolbarExpire.isSelected = false
            binding.tvToolbarExpire.isSelected = true
            binding.tvToolbarExpire.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun showCustomToast(message: String, colorHex: String = "#B71C1C") {
        val dp = resources.displayMetrics.density
        val color = android.graphics.Color.parseColor(colorHex)
        val tv = android.widget.TextView(this).apply {
            text = message
            setTextColor(color)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.03f
            setLineSpacing(dp * 2, 1f)
            val padH = (20 * dp).toInt()
            val padV = (12 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 999 * dp
                setStroke(2, color)
            }
        }
        android.widget.Toast(this).apply {
            duration = android.widget.Toast.LENGTH_SHORT
            @Suppress("DEPRECATION")
            view = tv
            setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, (80 * dp).toInt())
        }.show()
    }
}