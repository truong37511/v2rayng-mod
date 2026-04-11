package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.*
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.*

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()

    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
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

        setupToolbar(binding.toolbar, false, "Yum VPN")

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)
        binding.fab.setOnClickListener { handleFabAction() }

        // ── Thanh kiểm tra kết nối ────────────────────────────────────────
        updateConnectionTestBar(false)
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                binding.tvTestState.text = getString(R.string.connection_test_testing)
            }
            mainViewModel.testCurrentServerRealPing()
        }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        invalidateOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        tabMediator?.detach()
        tabMediator = null
        mainViewModel.reloadServerList()
        setupGroupTab()
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
            }
            wasRunning = isRunning
        }

        // Observe kết quả kiểm tra kết nối → hiện lên thanh dưới cùng
        mainViewModel.updateTestResultAction.observe(this) { result ->
            if (!result.isNullOrEmpty()) {
                binding.tvTestState.text = result
            }
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    /**
     * Cập nhật text thanh trạng thái kết nối phía dưới màn hình.
     * - VPN bật: "Đã kết nối, nhấn để kiểm tra kết nối mạng!"
     * - VPN tắt: "VPN đang tắt, nhấn nút màu đỏ để bật."
     */
    private fun updateConnectionTestBar(isRunning: Boolean) {
        binding.tvTestState.text = if (isRunning) {
            getString(R.string.connection_connected)
        } else {
            getString(R.string.connection_not_connected)
        }
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
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else {
            val intent = VpnService.prepare(this)
            if (intent == null) startV2Ray()
            else requestVpnPermission.launch(intent)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    /**
     * Đổi màu FAB theo trạng thái VPN:
     * - Đang bật  → nền xanh dương đậm + icon stop
     * - Đang tắt  → nền đỏ + icon play
     */
    private fun applyRunningState(isRunning: Boolean) {
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
    }

    // ================= IMPORT =================

    fun importConfigViaSub() {
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
        OtpUpdateDialog(this, onImportSuccess = {
            tabMediator?.detach()
            tabMediator = null
            mainViewModel.subscriptionIdChanged("")
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
        OtpShopDialog(this, onImportSuccess = {
            tabMediator?.detach()
            tabMediator = null
            mainViewModel.subscriptionIdChanged("")
            setupGroupTab()
            binding.viewPager.setCurrentItem(0, false)
            selectFirstServerIfNeeded()
        }).show()
    }

    /**
     * Mở OtpYearDialog (Admin 1 năm - nhận sub link gói đăng ký 1 năm).
     * onResume() sẽ tự reload server list sau khi dialog dismiss.
     */
    private fun importYearOtp() {
        OtpYearDialog(this, onImportSuccess = {
            tabMediator?.detach()
            tabMediator = null
            mainViewModel.subscriptionIdChanged("")
            setupGroupTab()
            binding.viewPager.setCurrentItem(0, false)
            selectFirstServerIfNeeded()
        }).show()
    }

    // ================= MENU =================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        try {
            val method = menu.javaClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.java)
            method.isAccessible = true
            method.invoke(menu, true)
        } catch (e: Exception) {
            // ignore
        }

        val color = ContextCompat.getColor(this, R.color.blue)

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val title = android.text.SpannableString(item.title)
            title.setSpan(
                android.text.style.ForegroundColorSpan(color),
                0, title.length,
                android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
            )
            item.title = title

            val sub = item.subMenu
            if (sub != null) {
                for (j in 0 until sub.size()) {
                    val subItem = sub.getItem(j)
                    val subTitle = android.text.SpannableString(subItem.title)
                    subTitle.setSpan(
                        android.text.style.ForegroundColorSpan(color),
                        0, subTitle.length,
                        android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
                    )
                    subItem.title = subTitle
                }
            }
        }

        return super.onPrepareOptionsMenu(menu)
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

    // ================= NAV DRAWER =================

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> {
                requestActivityLauncher.launch(
                    Intent(this, SubSettingActivity::class.java)
                )
            }
            R.id.per_app_proxy_settings -> {
                requestActivityLauncher.launch(
                    Intent(this, PerAppProxyActivity::class.java)
                )
            }
            R.id.settings -> {
                OtpDialog.show(this) {
                    requestActivityLauncher.launch(
                        Intent(this, SettingsActivity::class.java)
                    )
                }
            }
            R.id.routing_setting -> {
                requestActivityLauncher.launch(
                    Intent(this, RoutingSettingActivity::class.java)
                )
            }
            R.id.user_asset_setting -> {
                requestActivityLauncher.launch(
                    Intent(this, UserAssetActivity::class.java)
                )
            }
            R.id.logcat -> {
                startActivity(Intent(this, LogcatActivity::class.java))
            }
            R.id.about -> {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}