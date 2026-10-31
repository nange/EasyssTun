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
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
        private const val TAG = "MainFragment"

        // Process-level guard: the version banner is a startup line, not a
        // per-view one (the home fragment view is re-created on navigation).
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

    private var statsPollingJob: Job? = null

    private var isStatsExpanded: Boolean = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // The controller is the only place that knows whether the tunnel is
        // still wanted; it reverts to Idle (and clears the persisted intent)
        // when the user declines.
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

        // The VPN state is process-wide (VpnController), so collecting it here
        // survives rotation and re-entry without any fragment-side bookkeeping:
        // the first emission already renders the correct state. Collection is
        // paused while the screen is stopped so a failure dialog never pops up
        // in the background; onResume() renders the current state on return.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { renderState(it) }
            }
        }
    }

    override fun onDestroyView() {
        stopStatsPolling()
        statsBinder = null
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
        // The server list and the per-app routing can change while this screen
        // is in the background (profile edit/delete, app list, proxy mode), so
        // refresh the configuration-dependent parts and re-render the current
        // state.
        updateServerSpinner()
        updateAppListCard()
        renderState(controller.state.value)
        // Recovers a dropped permission result, and turns the persisted
        // "user wants the VPN" intent back into a connection when this process
        // has no tunnel left (force-stop / kill without a service restart).
        val resumed = controller.resume()
        if (resumed is ConnectRequest.NeedsPermission) {
            vpnPermissionLauncher.launch(resumed.intent)
        }
    }

    // ── Server spinner ──────────────────────────────────────────

    private fun updateServerSpinner() {
        val binding = _binding ?: return
        val serverProfiles = profiles.all()
        val adapter = ArrayAdapter(
            mContext,
            android.R.layout.simple_spinner_item,
            serverProfiles.map { it.name }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.serverSpinner.adapter = adapter
        binding.serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                viewOfItem: View?,
                position: Int,
                id: Long
            ) {
                serverProfiles.getOrNull(position)?.let { onServerSelected(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // Restore the active server without triggering the listener: selecting a
        // spinner entry programmatically must not be mistaken for a user switch.
        val activeId = profiles.activeId() ?: return
        val activePosition = serverProfiles.indexOfFirst { it.id == activeId }
        if (activePosition != -1) {
            val listener = binding.serverSpinner.onItemSelectedListener
            binding.serverSpinner.onItemSelectedListener = null
            binding.serverSpinner.setSelection(activePosition, false)
            binding.serverSpinner.onItemSelectedListener = listener
        }
    }

    private fun onServerSelected(profile: Profile) {
        val state = controller.state.value
        if (profile.id == profiles.activeId() && state !is VpnState.Switching) {
            Log.d(TAG, "Spinner selected the active server. No change.")
            return
        }
        // The controller is the only writer of the active profile: it either
        // selects it (tunnel down) or runs the stop/start switch (tunnel up).
        controller.switchTo(profile.id)
        if (!state.isEngaged) {
            // A plain selection emits no state change, so refresh the
            // configuration-dependent UI here.
            renderState(controller.state.value)
        }
    }

    // ── Connect / disconnect ────────────────────────────────────

    private fun setup() {
        updateServerSpinner()
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

    // ── Rendering (pure: never issues VPN commands) ─────────────

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
                // The failure is explained exactly once. Only consume it when the
                // dialog can actually be shown, so a failure that arrives while
                // the screen is finishing is still reported on the next visit.
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
            // Connecting/switching can be cancelled: the service may be slow or
            // never report back, and a disabled button would leave the user with
            // no way out. Stopping is already the way out.
            is VpnState.Connecting -> renderBusy(R.string.service_connecting, cancellable = true)
            is VpnState.Switching -> renderBusy(R.string.service_switching, cancellable = true)
            VpnState.Stopping -> renderBusy(R.string.service_stopping, cancellable = false)
            is VpnState.Running -> renderRunning()
        }
    }

    private fun renderStopped() {
        binding.serviceButton.text = getString(R.string.service_enable)
        // The active profile can be deleted while the tunnel keeps running, so
        // "is anything configured" is re-read on every render.
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

    /**
     * Logs the app/libeasyss version banner once per process. The banner used to
     * be rendered below the statistics card; the home update card now shows the
     * app version, while the libeasyss build stays visible in the log viewer.
     */
    private fun logVersionOnce() {
        if (versionLogged) return
        versionLogged = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            logVersionBanner()
        }
    }

    /**
     * Shows the specific error text of the last failed start (native error from
     * the Go side, or a localised precondition message). Guarded against a
     * finishing/detached activity so the dialog never throws BadTokenException
     * (e.g. when the failure surfaces while the app is backgrounded).
     */
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

    // ── Stats polling ───────────────────────────────────────────

    private fun startStatsPolling() {
        if (statsPollingJob?.isActive == true) return
        statsBinder?.setVisible(true)
        statsPollingJob =
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // The state is only Running after waitForSocksReady() and
                    // TProxyStartService succeeded, so the endpoint is already
                    // serving; a failed fetch simply retries on the next tick.
                    fetchAndUpdateStats()
                    AppState.isForeground.collectLatest { isForeground ->
                        val interval = if (isForeground) 2_000L else 30_000L
                        Log.i(TAG, "Stats polling: using ${interval / 1000}s interval (foreground=$isForeground)")
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
        // Hide stats card when service is stopped
        statsBinder?.setVisible(false)
    }

    private suspend fun fetchAndUpdateStats() {
        // Stats endpoint port follows the active profile's SOCKS port + 1000
        // (e.g. default 4080 -> 5080). Recompute each fetch so a server switch
        // that restarts the service with a new profile is picked up.
        val statsUrl = profiles.active()?.statsUrl() ?: Profile.defaultStatsUrl()
        val snapshot = statsClient.fetch(statsUrl)
        withContext(Dispatchers.Main) {
            if (snapshot != null) statsBinder?.render(snapshot) else statsBinder?.renderUnavailable()
        }
    }
}
