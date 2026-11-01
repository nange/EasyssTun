package com.easysstun

import android.app.Activity
import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.easysstun.databinding.FragmentMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainFragment : Fragment() {
    companion object {
        private const val TAG = LogTags.MAIN

        // 进程级标记：版本横幅是启动行而非每视图一次；首页视图在导航后会被重建。
        private var versionLogged = false
    }

    private lateinit var mContext: Context
    private lateinit var pref: Pref
    private lateinit var profiles: ProfileStore
    private lateinit var controller: VpnController

    private var _binding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding
        get() = checkNotNull(_binding) { "view binding used outside onCreateView/onDestroyView" }

    private val statsClient = StatsClient()
    private var statsBinder: StatsBinder? = null
    private var serverSpinner: ServerSpinnerController? = null

    private var statsPollingJob: Job? = null

    private var isStatsExpanded: Boolean = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 是否仍需要隧道由控制器判定：用户拒绝时它回到 Idle 并清掉持久化意图。
        controller.onPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statsBinder = StatsBinder(binding, initiallyExpanded = isStatsExpanded) {
            isStatsExpanded = it
        }
        serverSpinner = ServerSpinnerController(
            mContext,
            binding.serverSpinner,
            profiles,
        ) { onServerSelected(it) }
        setup()
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_tips -> {
                        findNavController().navigate(R.id.action_main_to_log)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner)
        bindUpdateCard()
        logVersionOnce()

        // 状态是进程级的：首次发射即渲染正确状态，旋转/重入无需 Fragment 记账。
        // 只在 STARTED 收集：屏幕停止时暂停，失败弹框不会在后台弹出。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { renderState(it) }
            }
        }
    }

    override fun onDestroyView() {
        stopStatsPolling()
        statsBinder = null
        serverSpinner = null
        _binding = null
        super.onDestroyView()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
        pref = Pref(context)
        profiles = ProfileStore.get(context)
        controller = VpnController.get(context)
    }

    override fun onResume() {
        super.onResume()
        // 后台期间服务器列表与分应用路由都可能变（增删配置档、分应用、代理模式），
        // 所以重刷配置相关部分并重渲染当前状态。
        serverSpinner?.refresh()
        updateAppListCard()
        renderState(controller.state.value)
        // 补发丢失的授权结果，并在本进程已无隧道时把持久化意图还原成连接（强杀后服务未重启）。
        val resumed = controller.resume()
        if (resumed is ConnectRequest.NeedsPermission) {
            vpnPermissionLauncher.launch(resumed.intent)
        }
    }

    // ── 服务器下拉框 ──────────────────────────────────────────

    private fun onServerSelected(profile: Profile) {
        val state = controller.state.value
        if (profile.id == profiles.activeId() && state !is VpnState.Switching) {
            return
        }
        // 当前配置档的唯一写入方是控制器：隧道关闭时只改选中，开启时走停/启切换。
        controller.switchTo(profile.id)
        if (!state.isEngaged) {
            // 纯选择不产生状态迁移，配置相关 UI 只能在这里手动刷新。
            renderState(controller.state.value)
        }
    }

    // ── 连接 / 断开 ────────────────────────────────────

    private fun setup() {
        serverSpinner?.refresh()
        binding.serviceButton.setOnClickListener { onServiceButtonClicked() }
        binding.serviceSetting.setOnClickListener {
            findNavController().navigate(R.id.action_main_to_setting)
        }
        binding.applist.setOnClickListener {
            findNavController().navigate(R.id.action_main_to_applist)
        }
        updateAppListCard()
    }

    private fun onServiceButtonClicked() {
        val state = controller.state.value
        if (state.isEngaged || state is VpnState.Stopping) {
            controller.disconnect()
            return
        }
        if (profiles.active() == null) {
            Toast.makeText(mContext, getString(R.string.easyss_need_config), Toast.LENGTH_SHORT).show()
            return
        }
        when (val request = controller.connect()) {
            is ConnectRequest.NeedsPermission -> vpnPermissionLauncher.launch(request.intent)
            ConnectRequest.NoProfile -> Toast.makeText(
                mContext,
                getString(R.string.easyss_need_config),
                Toast.LENGTH_SHORT
            ).show()
            ConnectRequest.Started, ConnectRequest.AlreadyActive -> Unit
        }
    }

    // ── 渲染（纯渲染，不下发 VPN 命令） ─────────────

    private fun renderState(state: VpnState) {
        if (_binding == null) return
        binding.serverSpinner.isEnabled = when (state) {
            is VpnState.Idle, is VpnState.Failed, is VpnState.Running -> true
            is VpnState.Connecting, is VpnState.Switching, VpnState.Stopping -> false
        }
        when (state) {
            is VpnState.Idle -> renderStopped()
            is VpnState.Failed -> {
                renderStopped()
                // 失败只解释一次：只在确实能弹框时消费，屏幕结束时到达的失败留到下次可见再报。
                if (isAdded && !requireActivity().isFinishing) {
                    val message = controller.consumeFailure()
                    if (message != null) {
                        val profileName = state.profileId?.let { id ->
                            profiles.find(id)?.name
                        }
                        showStartFailedDialog(message, profileName)
                    }
                }
            }
            // Connecting/Switching 可取消：服务可能很慢或永不上报，禁用按钮会让用户无路可退。
            // Stopping 本身就是出路，故不可取消。
            is VpnState.Connecting -> renderBusy(R.string.service_connecting, cancellable = true)
            is VpnState.Switching -> renderBusy(R.string.service_switching, cancellable = true)
            VpnState.Stopping -> renderBusy(R.string.service_stopping, cancellable = false)
            is VpnState.Running -> renderRunning()
        }
    }

    private fun renderStopped() {
        binding.serviceButton.text = getString(R.string.service_enable)
        // 当前配置档可能在隧道运行期间被删，故每次渲染都重新读一次是否已配置。
        binding.serviceButton.isEnabled = profiles.active() != null
        binding.serviceButton.icon = getDrawable(
            mContext, R.drawable.ic_outline_play_arrow_24
        )
        binding.serviceButton.setBackgroundColor(mContext.getColor(R.color.button_enable))
        binding.serviceTitle.text = getString(R.string.service_stopped)
        binding.serviceCard.setCardBackgroundColor(
            mContext.getColor(R.color.home_card_background_color)
        )
        binding.serviceIcon.setImageDrawable(
            getDrawable(mContext, R.drawable.ic_launcher_foreground_big)
        )
        stopStatsPolling()
    }

    private fun renderBusy(titleRes: Int, cancellable: Boolean) {
        binding.serviceButton.text = getString(
            if (cancellable) R.string.service_cancel else titleRes
        )
        binding.serviceButton.isEnabled = cancellable
        val icon = getDrawable(
            mContext,
            if (cancellable) R.drawable.ic_close_24 else R.drawable.ic_spinner_anim
        )
        binding.serviceButton.icon = icon
        if (!cancellable) (icon as? AnimatedVectorDrawable)?.start()
        binding.serviceButton.setBackgroundColor(mContext.getColor(R.color.button_disable))
        binding.serviceTitle.text = getString(titleRes)
        stopStatsPolling()
    }

    private fun renderRunning() {
        binding.serviceButton.text = getString(R.string.service_disable)
        binding.serviceButton.isEnabled = true
        binding.serviceButton.icon = getDrawable(
            mContext, R.drawable.ic_close_24
        )
        binding.serviceButton.setBackgroundColor(mContext.getColor(R.color.button_disable))
        binding.serviceTitle.text = getString(R.string.service_running)
        binding.serviceCard.setCardBackgroundColor(
            mContext.getColor(R.color.home_card_background_color_active)
        )
        binding.serviceIcon.setImageDrawable(
            getDrawable(mContext, R.drawable.ic_launcher_foreground_big)
        )
        startStatsPolling()
    }

    private fun updateAppListCard() {
        if (_binding == null) return
        val proxyMode = pref.proxyMode
        val selectedApps = AppSelectionStore(pref).selected(proxyMode)
        val isProxyOnly = proxyMode == Pref.PROXY_MODE_PROXY_ONLY

        binding.text1.text = if (isProxyOnly) {
            getString(R.string.proxied_app_list, selectedApps.size.toString())
        } else {
            getString(R.string.skipped_app_list, selectedApps.size.toString())
        }
        binding.text2.text = if (isProxyOnly) {
            getString(R.string.choose_app_to_proxy)
        } else {
            getString(R.string.choose_app_to_bypass)
        }
        binding.routeDesc.text = if (isProxyOnly) {
            getString(R.string.route_desc_proxy_only)
        } else {
            getString(R.string.route_desc)
        }
    }

    private fun bindUpdateCard() {
        binding.updateCard.bind(
                (requireActivity().application as EasyssTunApplication).updateManager,
                pref,
                viewLifecycleOwner
        )
    }

    /** 每个进程只记一次版本横幅：首页更新卡片显示 App 版本，libeasyss 构建版本只在日志里看。 */
    private fun logVersionOnce() {
        if (versionLogged) return
        versionLogged = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            logVersionBanner()
        }
    }

    /** 展示上次启动失败的具体文案（native 错误或前置条件）；已防 isAdded/isFinishing，避免后台弹框抛 BadTokenException。 */
    private fun showStartFailedDialog(errorMessage: String, profileName: String?) {
        if (!isAdded || requireActivity().isFinishing) {
            Log.w(TAG, "showStartFailedDialog: fragment not added or activity finishing, skipping dialog. Error: $errorMessage")
            return
        }
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(
                    if (profileName != null) {
                        getString(R.string.start_failed_title_named, profileName)
                    } else {
                        getString(R.string.start_failed_title)
                    }
                )
                .setMessage(errorMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    // ── 统计轮询 ───────────────────────────────────────────

    private fun startStatsPolling() {
        if (statsPollingJob?.isActive == true) return
        statsBinder?.setVisible(true)
        statsPollingJob =
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // Running 意味着 waitForSocksReady() 与 TProxyStartService 均已成功，端点必然在服务；
                    // 拉取失败等下一拍重试即可。
                    fetchAndUpdateStats()
                    AppState.isForeground.collectLatest { isForeground ->
                        val interval = if (isForeground) 2_000L else 30_000L
                        Log.d(TAG, "Stats polling: using ${interval / 1000}s interval (foreground=$isForeground)")
                        while (isActive) {
                            delay(interval)
                            fetchAndUpdateStats()
                        }
                    }
                }
    }

    private fun stopStatsPolling() {
        statsPollingJob?.cancel()
        statsPollingJob = null
        statsBinder?.setVisible(false)
    }

    private suspend fun fetchAndUpdateStats() {
        // stats 端点端口 = 当前配置档 SOCKS 端口 + 1000（默认 4080 → 5080）；
        // 每次拉取都重算，切换服务器重启服务后能立刻跟上。
        val statsUrl = profiles.active()?.statsUrl() ?: Profile.defaultStatsUrl()
        val snapshot = statsClient.fetch(statsUrl)
        withContext(Dispatchers.Main) {
            if (snapshot != null) statsBinder?.render(snapshot) else statsBinder?.renderUnavailable()
        }
    }
}
