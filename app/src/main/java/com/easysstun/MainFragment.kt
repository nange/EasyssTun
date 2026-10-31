package com.easysstun

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.VpnService
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.easysstun.databinding.FragmentMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

class MainFragment : Fragment() {
    companion object {
        private const val TAG = "MainFragment"

        // Process-level guard: the version banner is a startup line, not a
        // per-view one (the home fragment view is re-created on navigation).
        private var versionLogged = false
    }

    private lateinit var mContext: Context
    private lateinit var pref: Pref
    private lateinit var easyssInfo: easyssInfo

    private var _binding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding
        get() = checkNotNull(_binding) { "view binding used outside onCreateView/onDestroyView" }

    private val statsClient = StatsClient()
    private var statsBinder: StatsBinder? = null

    private var statsPollingJob: Job? = null

    // Written by the stats coroutine (IO), read on the main thread when the
    // poller is (re)started.
    @Volatile
    private var statsHadSuccessfulFetch: Boolean = false

    // Set when EasyssVpnService broadcasts ACTION_SERVICE_STARTED (tunnel fully
    // up); cleared on ACTION_SERVICE_STOPPED. Gates the first stats fetch on
    // actual service readiness instead of a fixed blind delay. Written on the
    // main thread, read by the stats coroutine while it waits for the tunnel.
    @Volatile
    private var statsServiceReady: Boolean = false

    private var isStatsExpanded: Boolean = false

    private var pendingServerProfileId: String? = null
    private var isSwitchingServer: Boolean = false
    private var isConnecting: Boolean = false

    private val serviceStoppedReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(
                            TAG,
                            "serviceStoppedReceiver - Entry. Action: ${intent?.action}"
                    )
                    if (intent?.action == EasyssVpnService.ACTION_SERVICE_STOPPED) {
                        Log.d(
                                TAG,
                                "ACTION_SERVICE_STOPPED received. Pending server ID: $pendingServerProfileId, isSwitchingServer: $isSwitchingServer"
                        )
                        // Service is down: clear the readiness signal so the next
                        // start waits for a fresh ACTION_SERVICE_STARTED again.
                        statsServiceReady = false
                        if (pendingServerProfileId != null) {
                            Log.d(
                                    TAG,
                                    "pendingServerProfileId is NOT null. Attempting to switch."
                            )
                            if (pref.getProfiles().none { it.id == pendingServerProfileId }) {
                                // The pending profile was deleted while the switch was in
                                // flight (e.g. from the profile edit screen). Abandon the
                                // switch instead of activating a stale id.
                                Log.w(
                                        TAG,
                                        "Pending server profile no longer exists. Abandoning switch."
                                )
                                pendingServerProfileId = null
                                isSwitchingServer = false
                                view?.let {
                                    updateServiceStatu(it)
                                }
                            } else {
                                pendingServerProfileId?.let { pref.setActiveServer(it) }
                                Log.d(
                                        TAG,
                                        "pref.setActiveServer called with ID: $pendingServerProfileId"
                                )
                                easyssInfo = pref.getEasyssInfo()
                                Log.d(
                                        TAG,
                                        "pref.getEasyssInfo called. easyssInfo.valid=${easyssInfo.valid}"
                                )

                                Log.i(
                                        TAG,
                                        "Calling startVPNService for server switch. ID: $pendingServerProfileId"
                                )
                                startVPNService(isCalledFromReceiver = true)
                                Log.d(TAG, "Returned from startVPNService call.")

                                pendingServerProfileId = null
                                Log.d(TAG, "pendingServerProfileId reset to null.")
                                isSwitchingServer = false
                                Log.d(TAG, "isSwitchingServer reset to false.")

                                view?.let {
                                    Log.d(
                                            TAG,
                                            "Calling updateServiceStatu (after switch)."
                                    )
                                    updateServiceStatu(it)
                                    Log.d(
                                            TAG,
                                            "Returned from updateServiceStatu (after switch)."
                                    )
                                }
                            }
                        } else {
                            Log.d(
                                    TAG,
                                    "pendingServerProfileId IS null. Service stopped for other reasons or switch already processed/failed."
                            )
                            // Service stopped for other reasons (e.g. user clicked main stop
                            // button, or pendingId was already cleared)
                            if (isSwitchingServer
                            ) { // If it's still true, something went wrong, reset it.
                                Log.w(
                                        TAG,
                                        "isSwitchingServer was true but pendingServerProfileId is null. Resetting isSwitchingServer."
                                )
                                isSwitchingServer = false
                            }
                            view?.let {
                                Log.d(
                                        TAG,
                                        "Calling updateServiceStatu (no pendingId)."
                                )
                                updateServiceStatu(it)
                                Log.d(
                                        TAG,
                                        "Returned from updateServiceStatu (no pendingId)."
                                )
                            }
                        }
                        val spinner = view?.findViewById<Spinner>(R.id.server_spinner)
                        spinner?.isEnabled = true
                        Log.d(
                                TAG,
                                "Spinner explicitly re-enabled. Current state: ${spinner?.isEnabled}"
                        )
                    } else {
                        Log.d(
                                TAG,
                                "Received some other action or null action: ${intent?.action}"
                        )
                    }
                }
            }

    private val serviceStartFailedReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != EasyssVpnService.ACTION_SERVICE_START_FAILED) return
                    val errorMessage = intent.getStringExtra(EasyssVpnService.EXTRA_START_ERROR)
                    Log.e(TAG, "ACTION_SERVICE_START_FAILED received. Error: $errorMessage")
                    if (errorMessage.isNullOrBlank()) return
                    showStartFailedDialog(errorMessage)
                }
            }

    private val serviceStartedReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != EasyssVpnService.ACTION_SERVICE_STARTED) return
                    Log.d(TAG, "ACTION_SERVICE_STARTED received. Leaving connecting state.")
                    isConnecting = false
                    // Tunnel is fully up: release any stats poll waiting for this
                    // signal so the first fetch fires immediately.
                    statsServiceReady = true
                    view?.let { updateServiceStatu(it) }
                }
            }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "VPN permission granted. Restarting start flow.")
            if (pref.isServiceEnabled) {
                startVPNService()
            }
        } else {
            Log.w(
                    TAG,
                    "VPN permission not granted (resultCode=${result.resultCode}). " +
                            "Aborting start and reverting to stopped state."
            )
            pref.isServiceEnabled = false
            isConnecting = false
            view?.let { updateServiceStatu(it) }
        }
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
        easyssInfo = pref.getEasyssInfo()
        statsBinder = StatsBinder(binding, initiallyExpanded = isStatsExpanded) {
            isStatsExpanded = it
        }
        setup(view) // Spinner setup is now in setup()
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
        updateServiceStatu(view)
        bindUpdateCard(view)
        logVersionOnce()

        // Ensure spinner is enabled when view is created
        view.findViewById<Spinner>(R.id.server_spinner)?.isEnabled = true

        // Start stats polling if service is running
        if (pref.isServiceEnabled) {
            startStatsPolling()
        } else {
            statsBinder?.setVisible(false)
        }
    }

    override fun onDestroyView() {
        stopStatsPolling()
        statsBinder = null
        _binding = null
        super.onDestroyView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentFilter = IntentFilter(EasyssVpnService.ACTION_SERVICE_STOPPED)
        ContextCompat.registerReceiver(
                requireActivity(),
                serviceStoppedReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "serviceStoppedReceiver registered.")

        val startFailedFilter = IntentFilter(EasyssVpnService.ACTION_SERVICE_START_FAILED)
        ContextCompat.registerReceiver(
                requireActivity(),
                serviceStartFailedReceiver,
                startFailedFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "serviceStartFailedReceiver registered.")

        val startedFilter = IntentFilter(EasyssVpnService.ACTION_SERVICE_STARTED)
        ContextCompat.registerReceiver(
                requireActivity(),
                serviceStartedReceiver,
                startedFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "serviceStartedReceiver registered.")
    }

    override fun onDestroy() {
        requireActivity().unregisterReceiver(serviceStoppedReceiver)
        Log.d(TAG, "serviceStoppedReceiver unregistered.")
        requireActivity().unregisterReceiver(serviceStartFailedReceiver)
        Log.d(TAG, "serviceStartFailedReceiver unregistered.")
        requireActivity().unregisterReceiver(serviceStartedReceiver)
        Log.d(TAG, "serviceStartedReceiver unregistered.")
        super.onDestroy()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
        pref = Pref(context)
    }

    override fun onResume() {
        super.onResume()
        // Update spinner on resume and ensure it's enabled
        view?.let {
            updateServerSpinner(it)
            it.findViewById<Spinner>(R.id.server_spinner)?.isEnabled = !isSwitchingServer
            updateAppListCard(it)
        }
    }

    private fun updateServerSpinner(view: View) {
        val serverSpinner = view.findViewById<Spinner>(R.id.server_spinner)
        val serverProfiles = pref.getProfiles()
        val serverNames = serverProfiles.map { it.name } // Or it.name if that's the display string
        val adapter = ArrayAdapter(mContext, android.R.layout.simple_spinner_item, serverNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverSpinner.adapter = adapter

        // Re-attach or ensure the listener is set.
        // If the listener logic depends on serverProfiles, it must be correctly scoped or passed.
        // For this refactoring, we assume the existing listener logic in setup() will be part of
        // the spinner setup.
        // The existing onItemSelectedListener is defined below and will be set after this method in
        // setup().
        // However, for onResume, if the adapter is reset, the listener might need to be reset as
        // well.
        // For now, let's keep the listener setup within the setup() method which calls this.
        // If issues arise, the listener setup might need to be part of this method too.
        // Let's re-add the listener here to be safe for onResume calls.
        serverSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            viewOfItem: View?,
                            position: Int,
                            id: Long
                    ) {
                        val selectedProfile =
                                serverProfiles[position] // serverProfiles must be in scope

                        // Guard: never implicitly switch while the VPN is running when no
                        // active profile exists (can only happen in stale states left by
                        // older versions). Silently activating an arbitrary profile here
                        // would race with the running service.
                        if (pref.isServiceEnabled && pref.getActiveProfile() == null) {
                            Log.w(
                                    TAG,
                                    "Spinner selected while VPN running but no active profile. " +
                                            "Ignoring to avoid an implicit server switch."
                            )
                            return
                        }

                        if (pref.getActiveProfile()?.id == selectedProfile.id &&
                                        !isSwitchingServer
                        ) {
                            Log.d(
                                    TAG,
                                    "Spinner selected current active server. No change."
                            )
                            this@MainFragment.view?.let { updateServiceStatu(it) }
                            return
                        }

                        Log.d(
                                TAG,
                                "Server selected: ${selectedProfile.name}. Current isServiceEnabled: ${pref.isServiceEnabled}"
                        )

                        if (pref.isServiceEnabled) {
                            if (isSwitchingServer) {
                                Log.i(
                                        TAG,
                                        "Server switch already in progress. Updating pending server to: ${selectedProfile.id}"
                                )
                                pendingServerProfileId = selectedProfile.id
                                return
                            }

                            Log.i(
                                    TAG,
                                    "Initiating server switch. Setting pending server to: ${selectedProfile.id}"
                            )
                            pendingServerProfileId = selectedProfile.id
                            isSwitchingServer = true
                            serverSpinner.isEnabled = false
                            stopVPNService()
                        } else {
                            Log.i(
                                    TAG,
                                    "VPN not running. Setting active server to: ${selectedProfile.id}"
                            )
                            pref.setActiveServer(selectedProfile.id)
                            easyssInfo = pref.getEasyssInfo()
                            isSwitchingServer = false
                            serverSpinner.isEnabled = true
                            this@MainFragment.view?.let {
                                updateServiceStatu(it)
                            }
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {
                        serverSpinner.isEnabled = true
                    }
                }

        // Set selection AFTER setting the listener, so if it's different from default (0),
        // it doesn't trigger the listener if we don't want it to, OR it triggers it correctly.
        // Actually, we use setSelection(position, false) which might still trigger onItemSelected
        // in some cases
        // but it's meant to suppress animation. To avoid triggering the listener during
        // initialization,
        // we can temporarily disable the listener, but we just set it.
        // Wait, the issue is that when returning to the fragment, the active server might be the
        // pending one,
        // but it's not set as active yet (it's still pending).
        // If we are switching, we should show the pending server in the spinner, or the active one?
        // Let's show the pending server if we are switching, else the active one.
        val activeServerIdToDisplay =
                if (isSwitchingServer && pendingServerProfileId != null) {
                    pendingServerProfileId
                } else {
                    pref.getActiveProfile()?.id
                }

        if (activeServerIdToDisplay != null) {
            val activeServerPosition =
                    serverProfiles.indexOfFirst { it.id == activeServerIdToDisplay }
            if (activeServerPosition != -1) {
                // Temporarily remove listener to avoid triggering switch logic during setup
                val listener = serverSpinner.onItemSelectedListener
                serverSpinner.onItemSelectedListener = null
                serverSpinner.setSelection(activeServerPosition, false)
                serverSpinner.onItemSelectedListener = listener
            }
        }
    }

    private fun setup(view: View) {
        updateServerSpinner(view) // Call the new method to setup spinner

        var service_button = view.findViewById<MaterialButton>(R.id.service_button)
        service_button.let {
            it.setOnClickListener {
                if (isSwitchingServer) {
                    Toast.makeText(
                                    mContext,
                                    "Server switch in progress, please wait.",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                    return@setOnClickListener
                }
                if (pref.isServiceEnabled) {
                    pref.isServiceEnabled = false
                    isConnecting = false
                } else {
                    if (!easyssInfo.valid) {
                        Toast.makeText(
                                        mContext,
                                        getString(R.string.easyss_need_config),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        return@setOnClickListener
                    }
                    pref.isServiceEnabled = true
                    isConnecting = true
                    startVPNService()
                }
                updateServiceStatu(view)
            }
        }

        // The onItemSelectedListener is now set within updateServerSpinner.
        // We might need to ensure serverSpinner variable is accessible or passed if it's used
        // elsewhere in setup
        // For now, assuming it's only used for the listener which is now part of
        // updateServerSpinner.

        val selected_apps = pref.getAppsForMode(pref.getProxyMode())
        val proxyMode = pref.getProxyMode()
        val isProxyOnly = proxyMode == Pref.PROXY_MODE_PROXY_ONLY

        view.findViewById<TextView>(R.id.text1).let {
            it.text = if (isProxyOnly) {
                getString(R.string.proxied_app_list, selected_apps.size.toString())
            } else {
                getString(R.string.skipped_app_list, selected_apps.size.toString())
            }
        }

        view.findViewById<TextView>(android.R.id.text2).let {
            it.text = if (isProxyOnly) {
                getString(R.string.choose_app_to_proxy)
            } else {
                getString(R.string.choose_app_to_bypass)
            }
        }

        view.findViewById<TextView>(R.id.route_desc).let {
            it.text = if (isProxyOnly) {
                getString(R.string.route_desc_proxy_only)
            } else {
                getString(R.string.route_desc)
            }
        }

        if (!easyssInfo.valid) {
            pref.isServiceEnabled = false
        }

        view.findViewById<MaterialButton>(R.id.service_setting).let {
            it.setOnClickListener { findNavController().navigate(R.id.action_main_to_setting) }
        }

        view.findViewById<LinearLayout>(R.id.applist).let {
            it.setOnClickListener { findNavController().navigate(R.id.action_main_to_applist) }
        }

    }

    private fun updateAppListCard(view: View) {
        val proxyMode = pref.getProxyMode()
        val selectedApps = pref.getAppsForMode(proxyMode)
        val isProxyOnly = proxyMode == Pref.PROXY_MODE_PROXY_ONLY

        view.findViewById<TextView>(R.id.text1)?.let {
            it.text = if (isProxyOnly) {
                getString(R.string.proxied_app_list, selectedApps.size.toString())
            } else {
                getString(R.string.skipped_app_list, selectedApps.size.toString())
            }
        }

        view.findViewById<TextView>(android.R.id.text2)?.let {
            it.text = if (isProxyOnly) {
                getString(R.string.choose_app_to_proxy)
            } else {
                getString(R.string.choose_app_to_bypass)
            }
        }

        view.findViewById<TextView>(R.id.route_desc)?.let {
            it.text = if (isProxyOnly) {
                getString(R.string.route_desc_proxy_only)
            } else {
                getString(R.string.route_desc)
            }
        }
    }

    private fun bindUpdateCard(view: View) {
        view.findViewById<UpdateCardView>(R.id.update_card)?.bind(
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

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun startVPNService(isCalledFromReceiver: Boolean = false) {
        Log.d(
                TAG,
                "startVPNService - Entry. isCalledFromReceiver: $isCalledFromReceiver"
        )
        // A server-switch stop sets isServiceEnabled=false (EasyssVpnService.actualFinalizeStop).
        // Mark the service as enabled here so the updateServiceStatu() call right after an
        // auto-restart takes the "running" branch instead of issuing another stop, and so the
        // vpnPermissionLauncher callback still restarts after granting permission mid-switch.
        pref.isServiceEnabled = true
        val intent = VpnService.prepare(mContext)
        if (intent != null) {
            Log.d(
                    TAG,
                    "VpnService.prepare needs permissions. Calling vpnPermissionLauncher."
            )
            vpnPermissionLauncher.launch(intent)
            // If permission is needed, do not proceed to start the service here.
            // onActivityResult will call startVPNService() again.
            return
        }
        Log.d(
                TAG,
                "VpnService.prepare returned null (permissions granted). Proceeding to try block."
        )

        // Permission already granted, proceed to start the service.
        try {
            Log.d(
                    TAG,
                    "startVPNService - Inside try block, attempting to get active profile."
            )
            val activeProfile = pref.getActiveProfile() // Get the full profile object
            val intent2 = Intent(mContext, EasyssVpnService::class.java)

            if (activeProfile != null) {
                try {
                    val profileJson = appJson.encodeToString(activeProfile)
                    intent2.putExtra(
                            "com.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA",
                            profileJson
                    )
                } catch (e: kotlinx.serialization.SerializationException) {
                    Log.e(TAG, "Error serializing active profile", e)
                    // If called from receiver, we must not crash it.
                    if (isCalledFromReceiver) {
                        // Log and allow receiver to clean up UI. Service won't start with this
                        // profile.
                        Toast.makeText(
                                        mContext,
                                        "Error: Could not serialize server profile.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                        return // Prevent service start if serialization fails and in receiver
                        // context
                    }
                    // For non-receiver calls, behavior might differ, but for now, let's be
                    // consistent:
                    // don't proceed if profile can't be sent.
                    return
                }
            } else {
                Log.w(TAG, "No active server profile found to start EasyssVpnService.")
                if (isCalledFromReceiver) {
                    Toast.makeText(
                                    mContext,
                                    "Error: No active server configured.",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                    return // Prevent service start if no profile and in receiver context
                }
                // For non-receiver calls, if no profile, probably shouldn't start.
                return
            }

            // Pass proxy mode and selected apps via Intent to avoid multi-process SharedPreferences issues
            val proxyMode = pref.getProxyMode()
            val selectedApps = ArrayList(pref.getAppsForMode(proxyMode))
            intent2.putExtra(EasyssVpnService.EXTRA_PROXY_MODE, proxyMode)
            intent2.putStringArrayListExtra(EasyssVpnService.EXTRA_SELECTED_APPS, selectedApps)
            Log.d(TAG, "startVPNService: proxyMode=$proxyMode, selectedApps=$selectedApps")

            mContext.startService(intent2.setAction(EasyssVpnService.ACTION_CONNECT))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting EasyssVpnService", e)
            if (isCalledFromReceiver) {
                // IMPORTANT: Do NOT throw RuntimeException if called from serviceStoppedReceiver,
                // as it would crash the receiver and leave the UI in a stuck state.
                Toast.makeText(mContext, "Error starting VPN service.", Toast.LENGTH_LONG).show()
            } else {
                // For other callers of startVPNService (e.g., direct user action,
                // onActivityResult),
                // re-throwing might be acceptable or desired for immediate crash feedback,
                // but for robustness, it's often better to handle gracefully.
                // For now, let's be consistent and show a Toast.
                Toast.makeText(mContext, "Error starting VPN service.", Toast.LENGTH_LONG).show()
                // If strict crashing is desired for non-receiver contexts:
                // if (e !is kotlinx.serialization.SerializationException) { // Already handled
                // above
                //    throw RuntimeException("Non-receiver context: Error starting EasyssVpnService",
                // e)
                // }
            }
        }
    }

    private fun stopVPNService() {
        val intent2 = Intent(mContext, EasyssVpnService::class.java)
        mContext.startService(intent2.setAction(EasyssVpnService.ACTION_DISCONNECT))
    }

    /**
     * Shows the specific native error text returned by the Go side when
     * Mobile.start() failed. Guarded against a finishing/detached activity so
     * the dialog never throws BadTokenException (e.g. when the failure
     * surfaces while the app is backgrounded or being destroyed).
     */
    private fun showStartFailedDialog(errorMessage: String) {
        if (!isAdded || requireActivity().isFinishing) {
            Log.w(TAG, "showStartFailedDialog: fragment not added or activity finishing, skipping dialog. Error: $errorMessage")
            return
        }
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.start_failed_title)
                .setMessage(errorMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    // Removed deprecated service status check helper; rely on Pref state and service lifecycle

    private fun updateServiceStatu(view: View) {
        val service_button = view.findViewById<MaterialButton>(R.id.service_button)
        val service_title = view.findViewById<TextView>(R.id.service_title)

        if (isSwitchingServer) {
            Log.d(
                    TAG,
                    "updateServiceStatu: Server switch in progress, UI set to switching state."
            )
            service_button.text = getString(R.string.service_switching)
            service_button.isEnabled = false
            // Optionally set title or other UI elements
            service_title.text = getString(R.string.service_switching)
            // It might be good to also change the card color or icon here to reflect "switching"
            // For now, just text and button state as per primary requirement.
            return
        } else {
            // Ensure button is generally enabled if not switching, specific logic below will
            // fine-tune this
            service_button.isEnabled = true
            Log.d(
                    TAG,
                    "updateServiceStatu: Not switching server, proceeding with normal UI update."
            )
        }

        // Connecting state: the user pressed Start and the service startup
        // is still in flight (VPN permission pending, native tunnel
        // starting). Show a spinner and keep the button disabled so it
        // cannot be re-tapped. ACTION_SERVICE_STARTED clears this flag; a
        // failed/aborted start (pref.isServiceEnabled == false) falls
        // through to the normal UI update below.
        if (isConnecting && pref.isServiceEnabled) {
            Log.d(
                    TAG,
                    "updateServiceStatu: Connection in progress, button shows spinner and is disabled."
            )
            service_button.text = getString(R.string.service_connecting)
            service_button.isEnabled = false
            service_button.icon = getDrawable(mContext, R.drawable.ic_spinner_anim)
            (service_button.icon as? AnimatedVectorDrawable)?.start()
            service_title.text = getString(R.string.service_connecting)
            return
        }
        isConnecting = false

        // Refresh easyssInfo at the beginning of UI update (if not switching)
        easyssInfo = pref.getEasyssInfo()

        // If config is invalid, ensure service is marked as disabled
        if (!easyssInfo.valid && pref.isServiceEnabled) {
            pref.isServiceEnabled = false
        }

        // References to UI elements
        var service_icon = view.findViewById<ImageView>(R.id.service_icon)
        var service_card = view.findViewById<MaterialCardView>(R.id.service_card)

        // This explicit enabling might conflict if !easyssInfo.valid later.
        // The click listener for service_button handles invalid config by returning, not by
        // disabling button.
        // Let's make sure the button state is explicitly managed in each path.
        // service_button.isEnabled = true; // Default for non-switching, can be overridden below

        when {
            pref.isServiceEnabled -> { // Service is ON
                if (!easyssInfo.valid
                ) { // Should not happen if checks in click listener are effective
                    // And if startVPNService() is robust. But good to have a safeguard.
                    Log.w(
                            TAG,
                            "updateServiceStatu: Service enabled but easyssInfo is invalid! Disabling service."
                    )
                    Toast.makeText(
                                    mContext,
                                    getString(R.string.easyss_need_config),
                                    Toast.LENGTH_LONG
                            )
                            .show()
                    pref.isServiceEnabled = false // Correct the state
                    stopStatsPolling()
                    // Now recursively call to update UI to "stopped" state
                    updateServiceStatu(view)
                    return // Exit current processing
                }

                startVPNService() // This ensures the service is actually started if pref says it
                // should be
                startStatsPolling()
                service_button.text = getString(R.string.service_disable)
                service_button.isEnabled = true // Explicitly enable
                service_card.setCardBackgroundColor(
                        mContext.getColor(R.color.home_card_background_color_active)
                )
                service_icon.setImageDrawable(
                        getDrawable(mContext, R.drawable.ic_launcher_foreground_big)
                )
                service_button.icon = getDrawable(mContext, R.drawable.ic_close_24)
                service_button.setBackgroundColor(mContext.getColor(R.color.button_disable))
                service_title.text = getString(R.string.service_running)
            }
            else -> { // Service is OFF (pref.isServiceEnabled == false)
                stopVPNService() // Ensure service is actually stopped
                stopStatsPolling()
                statsHadSuccessfulFetch = false
                service_button.text = getString(R.string.service_enable)
                service_card.setCardBackgroundColor(
                        mContext.getColor(R.color.home_card_background_color)
                )
                service_icon.setImageDrawable(
                        getDrawable(mContext, R.drawable.ic_launcher_foreground_big)
                )
                service_button.icon = getDrawable(mContext, R.drawable.ic_outline_play_arrow_24)
                service_button.setBackgroundColor(mContext.getColor(R.color.button_enable))
                service_title.text = getString(R.string.service_stopped)

                // If config is invalid, button should be disabled to prevent enabling attempts.
                if (!easyssInfo.valid) {
                    service_button.isEnabled = false
                    // Optionally, provide more visual feedback e.g. change button color
                    Log.d(
                            TAG,
                            "updateServiceStatu: Config invalid and service stopped, service_button disabled."
                    )
                } else {
                    service_button.isEnabled = true
                    Log.d(
                            TAG,
                            "updateServiceStatu: Config valid and service stopped, service_button enabled."
                    )
                }
            }
        }
    }

    // ── Stats polling ───────────────────────────────────────────

    private fun startStatsPolling() {
        if (statsPollingJob?.isActive == true) return
        statsBinder?.setVisible(true)
        statsPollingJob =
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // Gate the first fetch on the service's ACTION_SERVICE_STARTED
                    // broadcast (tunnel fully up) instead of a fixed blind delay, so
                    // stats appear as soon as the endpoint is actually ready. Fall
                    // back to a 2s cushion when no broadcast is expected (e.g.
                    // re-entering this screen while the service is already running);
                    // skip the wait entirely once a fetch has already succeeded.
                    if (!statsHadSuccessfulFetch && !statsServiceReady) {
                        withTimeoutOrNull(2_000) {
                            while (!statsServiceReady) delay(100)
                        }
                    }
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
        val statsUrl = pref.getActiveProfile()?.statsUrl() ?: Profile.defaultStatsUrl()
        val snapshot = statsClient.fetch(statsUrl)
        if (snapshot != null) statsHadSuccessfulFetch = true
        withContext(Dispatchers.Main) {
            if (snapshot != null) statsBinder?.render(snapshot) else statsBinder?.renderUnavailable()
        }
    }
}
