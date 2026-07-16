package com.easysstun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

class MainFragment : Fragment() {
    private lateinit var mContext: Context
    private lateinit var pref: Pref
    private lateinit var easyssInfo: easyssInfo

    private var statsPollingJob: Job? = null

    private var pendingServerProfileId: String? = null
    private var isSwitchingServer: Boolean = false

    private val serviceStoppedReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(
                            "MainFragmentReceiver",
                            "serviceStoppedReceiver - Entry. Action: ${intent?.action}"
                    )
                    if (intent?.action == TProxyService.ACTION_SERVICE_STOPPED) {
                        Log.d(
                                "MainFragmentReceiver",
                                "ACTION_SERVICE_STOPPED received. Pending server ID: $pendingServerProfileId, isSwitchingServer: $isSwitchingServer"
                        )
                        if (pendingServerProfileId != null) {
                            Log.d(
                                    "MainFragmentReceiver",
                                    "pendingServerProfileId is NOT null. Attempting to switch."
                            )
                            pref.setActiveServer(pendingServerProfileId!!)
                            Log.d(
                                    "MainFragmentReceiver",
                                    "pref.setActiveServer called with ID: $pendingServerProfileId"
                            )
                            easyssInfo = pref.getEasyssInfo()
                            Log.d(
                                    "MainFragmentReceiver",
                                    "pref.getEasyssInfo called. easyssInfo.valid=${easyssInfo.valid}"
                            )

                            Log.i(
                                    "MainFragmentReceiver",
                                    "Calling startVPNService for server switch. ID: $pendingServerProfileId"
                            )
                            startVPNService(isCalledFromReceiver = true)
                            Log.d("MainFragmentReceiver", "Returned from startVPNService call.")

                            pendingServerProfileId = null
                            Log.d("MainFragmentReceiver", "pendingServerProfileId reset to null.")
                            isSwitchingServer = false
                            Log.d("MainFragmentReceiver", "isSwitchingServer reset to false.")

                            view?.let {
                                Log.d(
                                        "MainFragmentReceiver",
                                        "Calling updateServiceStatu (after switch)."
                                )
                                updateServiceStatu(it)
                                Log.d(
                                        "MainFragmentReceiver",
                                        "Returned from updateServiceStatu (after switch)."
                                )
                            }
                        } else {
                            Log.d(
                                    "MainFragmentReceiver",
                                    "pendingServerProfileId IS null. Service stopped for other reasons or switch already processed/failed."
                            )
                            // Service stopped for other reasons (e.g. user clicked main stop
                            // button, or pendingId was already cleared)
                            if (isSwitchingServer
                            ) { // If it's still true, something went wrong, reset it.
                                Log.w(
                                        "MainFragmentReceiver",
                                        "isSwitchingServer was true but pendingServerProfileId is null. Resetting isSwitchingServer."
                                )
                                isSwitchingServer = false
                            }
                            view?.let {
                                Log.d(
                                        "MainFragmentReceiver",
                                        "Calling updateServiceStatu (no pendingId)."
                                )
                                updateServiceStatu(it)
                                Log.d(
                                        "MainFragmentReceiver",
                                        "Returned from updateServiceStatu (no pendingId)."
                                )
                            }
                        }
                        val spinner = view?.findViewById<Spinner>(R.id.server_spinner)
                        spinner?.isEnabled = true
                        Log.d(
                                "MainFragmentReceiver",
                                "Spinner explicitly re-enabled. Current state: ${spinner?.isEnabled}"
                        )
                    } else {
                        Log.d(
                                "MainFragmentReceiver",
                                "Received some other action or null action: ${intent?.action}"
                        )
                    }
                }
            }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)
        setHasOptionsMenu(true)
        // easyssInfo will be initialized in onViewCreated after view is created
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        easyssInfo = pref.getEasyssInfo()
        setup(view) // Spinner setup is now in setup()
        updateServiceStatu(view)
        updateVersionInfo(view)

        // Ensure spinner is enabled when view is created
        view.findViewById<Spinner>(R.id.server_spinner)?.isEnabled = true

        // Start stats polling if service is running
        if (pref.isServiceEnabled) {
            startStatsPolling()
        } else {
            view.findViewById<LinearLayout>(R.id.stats_grid)?.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStatsPolling()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentFilter = IntentFilter(TProxyService.ACTION_SERVICE_STOPPED)
        ContextCompat.registerReceiver(
                requireActivity(),
                serviceStoppedReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d("MainFragment", "serviceStoppedReceiver registered.")
    }

    override fun onDestroy() {
        requireActivity().unregisterReceiver(serviceStoppedReceiver)
        Log.d("MainFragment", "serviceStoppedReceiver unregistered.")
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_tips -> {
                findNavController().navigate(R.id.action_main_to_log)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
        }
    }

    private fun updateServerSpinner(view: View) {
        val serverSpinner = view.findViewById<Spinner>(R.id.server_spinner)
        val serverProfiles = pref.getServerProfiles()
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

                        if (pref.getActiveServerProfile()?.id == selectedProfile.id &&
                                        !isSwitchingServer
                        ) {
                            Log.d(
                                    "MainFragment",
                                    "Spinner selected current active server. No change."
                            )
                            this@MainFragment.view?.let { updateServiceStatu(it) }
                            return
                        }

                        Log.d(
                                "MainFragment",
                                "Server selected: ${selectedProfile.name}. Current isServiceEnabled: ${pref.isServiceEnabled}"
                        )

                        if (pref.isServiceEnabled) {
                            if (isSwitchingServer) {
                                Log.i(
                                        "MainFragment",
                                        "Server switch already in progress. Updating pending server to: ${selectedProfile.id}"
                                )
                                pendingServerProfileId = selectedProfile.id
                                return
                            }

                            Log.i(
                                    "MainFragment",
                                    "Initiating server switch. Setting pending server to: ${selectedProfile.id}"
                            )
                            pendingServerProfileId = selectedProfile.id
                            isSwitchingServer = true
                            serverSpinner.isEnabled = false
                            stopVPNService()
                        } else {
                            Log.i(
                                    "MainFragment",
                                    "VPN not running. Setting active server to: ${selectedProfile.id}"
                            )
                            pref.setActiveServer(selectedProfile.id)
                            easyssInfo = pref.getEasyssInfo()
                            isSwitchingServer = false
                            serverSpinner.isEnabled = true
                            this@MainFragment.view?.let {
                                val serviceSummaryTextView =
                                        it.findViewById<TextView>(R.id.service_summary)
                                if (easyssInfo.valid) {
                                    serviceSummaryTextView.text = easyssInfo.info
                                } else {
                                    serviceSummaryTextView.text =
                                            getString(R.string.easyss_need_config)
                                }
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
                    pref.getActiveServerProfile()?.id
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
                }
                updateServiceStatu(view)
            }
        }

        // The onItemSelectedListener is now set within updateServerSpinner.
        // We might need to ensure serverSpinner variable is accessible or passed if it's used
        // elsewhere in setup
        // For now, assuming it's only used for the listener which is now part of
        // updateServerSpinner.

        val selected_apps = pref.getApps()
        view.findViewById<TextView>(R.id.text1).let {
            it.text = getString(R.string.skipped_app_list, selected_apps.size.toString())
        }

        if (easyssInfo.valid) {
            view.findViewById<TextView>(R.id.service_summary).let { it.text = easyssInfo.info }
        } else {
            pref.isServiceEnabled = false
        }

        view.findViewById<MaterialButton>(R.id.service_setting).let {
            it.setOnClickListener { findNavController().navigate(R.id.action_main_to_setting) }
        }

        view.findViewById<LinearLayout>(R.id.applist).let {
            it.setOnClickListener { findNavController().navigate(R.id.action_main_to_applist) }
        }

        // Stats card — tap to manually refresh
        view.findViewById<MaterialCardView>(R.id.stats_card).setOnClickListener {
            if (pref.isServiceEnabled) {
                // Brief loading feedback
                val updatingText = getString(R.string.stats_updating)
                view.findViewById<TextView>(R.id.stats_avg_rtt_ms)?.text = updatingText
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { fetchAndUpdateStats() }
            }
        }
    }

    private fun updateVersionInfo(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val gitTag = withContext(Dispatchers.IO) { fetchGitTag(requireContext()) }
            val versionPlaceholder = view.findViewById<TextView>(R.id.version_placeholder)
            val appVersion = BuildConfig.VERSION_NAME
            versionPlaceholder.text = "$gitTag | EasyssTun: v$appVersion"
        }
    }

    private fun fetchGitTag(context: Context): String {
        val libraryPath = context.applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
        val command = listOf(libraryPath, "--version")
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(true)

        return try {
            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var gitTag = "Easyss"
            while (true) {
                val currentLine = reader.readLine() ?: break
                if (currentLine.startsWith("Git tag:")) {
                    gitTag += ": " + currentLine.substringAfter(":").trim()
                    break
                }
            }
            process.waitFor()
            gitTag
        } catch (e: Exception) {
            Log.e("MainFragment", "Error fetching Git tag", e)
            "Easyss"
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (pref.isServiceEnabled) {
            startVPNService()
        }
    }

    @Suppress("DEPRECATION")
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun startVPNService(isCalledFromReceiver: Boolean = false) {
        Log.d(
                "MainFragmentSVC",
                "startVPNService - Entry. isCalledFromReceiver: $isCalledFromReceiver"
        )
        val intent = VpnService.prepare(mContext)
        if (intent != null) {
            Log.d(
                    "MainFragmentSVC",
                    "VpnService.prepare needs permissions. Calling startActivityForResult."
            )
            startActivityForResult(intent, 0)
            // If permission is needed, do not proceed to start the service here.
            // onActivityResult will call startVPNService() again.
            return
        }
        Log.d(
                "MainFragmentSVC",
                "VpnService.prepare returned null (permissions granted). Proceeding to try block."
        )

        // Permission already granted, proceed to start the service.
        try {
            Log.d(
                    "MainFragmentSVC",
                    "startVPNService - Inside try block, attempting to get active profile."
            )
            val activeProfile = pref.getActiveServerProfile() // Get the full profile object
            val intent2 = Intent(mContext, TProxyService::class.java)

            if (activeProfile != null) {
                val json = Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
                try {
                    val profileJson = json.encodeToString(activeProfile)
                    intent2.putExtra(
                            "com.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA",
                            profileJson
                    )
                } catch (e: kotlinx.serialization.SerializationException) {
                    Log.e("MainFragment", "Error serializing active profile", e)
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
                Log.w("MainFragment", "No active server profile found to start TProxyService.")
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

            mContext.startService(intent2.setAction(TProxyService.ACTION_CONNECT))
        } catch (e: Exception) {
            Log.e("MainFragment", "Error starting TProxyService", e)
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
                //    throw RuntimeException("Non-receiver context: Error starting TProxyService",
                // e)
                // }
            }
        }
    }

    private fun stopVPNService() {
        val intent2 = Intent(mContext, TProxyService::class.java)
        mContext.startService(intent2.setAction(TProxyService.ACTION_DISCONNECT))
    }

    // Removed deprecated service status check helper; rely on Pref state and service lifecycle

    private fun updateServiceStatu(view: View) {
        val service_button = view.findViewById<MaterialButton>(R.id.service_button)
        val service_title = view.findViewById<TextView>(R.id.service_title)

        if (isSwitchingServer) {
            Log.d(
                    "MainFragment",
                    "updateServiceStatu: Server switch in progress, UI set to switching state."
            )
            service_button.text = "Switching..."
            service_button.isEnabled = false
            // Optionally set title or other UI elements
            service_title.text = "Switching..."
            // It might be good to also change the card color or icon here to reflect "switching"
            // For now, just text and button state as per primary requirement.
            return
        } else {
            // Ensure button is generally enabled if not switching, specific logic below will
            // fine-tune this
            service_button.isEnabled = true
            Log.d(
                    "MainFragment",
                    "updateServiceStatu: Not switching server, proceeding with normal UI update."
            )
        }

        // Refresh easyssInfo at the beginning of UI update (if not switching)
        easyssInfo = pref.getEasyssInfo()

        // Update service summary based on current easyssInfo
        val serviceSummaryTextView = view.findViewById<TextView>(R.id.service_summary)
        if (easyssInfo.valid) {
            serviceSummaryTextView.text = easyssInfo.info
        } else {
            serviceSummaryTextView.text = getString(R.string.easyss_need_config)
            // If config is invalid, ensure service is marked as disabled
            if (pref.isServiceEnabled) { // only if it was previously enabled
                pref.isServiceEnabled = false
            }
        }

        // References to UI elements already obtained if isSwitchingServer is false,
        // or obtained at the start of this else block for the first time.
        // No, they are obtained at the top of updateServiceStatu now.
        // var service_button = view.findViewById<MaterialButton>(R.id.service_button)
        // var service_title = view.findViewById<TextView>(R.id.service_title)
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
                            "MainFragment",
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
                            "MainFragment",
                            "updateServiceStatu: Config invalid and service stopped, service_button disabled."
                    )
                } else {
                    service_button.isEnabled = true
                    Log.d(
                            "MainFragment",
                            "updateServiceStatu: Config valid and service stopped, service_button enabled."
                    )
                }
            }
        }
    }

    // ── Stats polling ───────────────────────────────────────────

    private fun startStatsPolling() {
        if (statsPollingJob?.isActive == true) return
        view?.findViewById<LinearLayout>(R.id.stats_grid)?.visibility = View.VISIBLE
        statsPollingJob =
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // Fetch immediately on service start for quick display
                    delay(1_000) // brief wait for stats endpoint to be ready
                    fetchAndUpdateStats()
                    while (isActive) {
                        delay(60_000) // poll every 1 minute
                        fetchAndUpdateStats()
                    }
                }
    }

    private fun stopStatsPolling() {
        statsPollingJob?.cancel()
        statsPollingJob = null
        // Hide stats card when service is stopped
        view?.findViewById<LinearLayout>(R.id.stats_grid)?.visibility = View.GONE
    }

    private suspend fun fetchAndUpdateStats() {
        try {
            val url = URL("http://127.0.0.1:3080/stats")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"

            val status = conn.responseCode
            if (status != 200) {
                Log.w("MainFragment", "Stats endpoint returned HTTP $status")
                withContext(Dispatchers.Main) { updateStatsUnavailable() }
                return
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            withContext(Dispatchers.Main) { updateStatsDisplay(json) }
        } catch (e: Exception) {
            Log.w("MainFragment", "Failed to fetch stats: ${e.message}")
            withContext(Dispatchers.Main) { updateStatsUnavailable() }
        }
    }

    private fun updateStatsUnavailable() {
        val v = view ?: return
        val txt = getString(R.string.stats_unavailable)
        v.findViewById<TextView>(R.id.stats_avg_rtt_ms)?.text = txt
        v.findViewById<TextView>(R.id.stats_conns)?.text = ""
        v.findViewById<TextView>(R.id.stats_bytes_sent)?.text = ""
        v.findViewById<TextView>(R.id.stats_bytes_recv)?.text = ""
        v.findViewById<TextView>(R.id.stats_streams_opened)?.text = ""
        v.findViewById<TextView>(R.id.stats_streams_closed)?.text = ""
        v.findViewById<TextView>(R.id.stats_uptime)?.text = ""
        v.findViewById<TextView>(R.id.stats_active_streams)?.text = ""
    }

    private fun updateStatsDisplay(json: JSONObject) {
        val v = view ?: return
        // Ensure card is visible on successful data
        v.findViewById<LinearLayout>(R.id.stats_grid)?.visibility = View.VISIBLE
        val avgRttMs = json.optDouble("avg_rtt_ms", 0.0)
        val conns = json.optInt("conns", 0)
        val sent = json.optLong("bytes_sent", 0)
        val recv = json.optLong("bytes_recv", 0)
        val opened = json.optLong("total_streams_opened", 0)
        val closed = json.optLong("total_streams_closed", 0)
        val uptime = json.optDouble("uptime_seconds", 0.0)
        val active = json.optInt("active_streams", 0)

        v.findViewById<TextView>(R.id.stats_avg_rtt_ms)?.text =
                "${getString(R.string.stats_avg_rtt_ms)}: ${String.format("%.1f ms", avgRttMs)}"
        v.findViewById<TextView>(R.id.stats_conns)?.text =
                "${getString(R.string.stats_conns)}: $conns"
        v.findViewById<TextView>(R.id.stats_bytes_sent)?.text =
                "${getString(R.string.stats_bytes_sent)}: ${formatBytes(sent)}"
        v.findViewById<TextView>(R.id.stats_bytes_recv)?.text =
                "${getString(R.string.stats_bytes_recv)}: ${formatBytes(recv)}"
        v.findViewById<TextView>(R.id.stats_streams_opened)?.text =
                "${getString(R.string.stats_streams_opened)}: $opened"
        v.findViewById<TextView>(R.id.stats_streams_closed)?.text =
                "${getString(R.string.stats_streams_closed)}: $closed"
        v.findViewById<TextView>(R.id.stats_uptime)?.text =
                "${getString(R.string.stats_uptime)}: ${formatDuration(uptime)}"
        v.findViewById<TextView>(R.id.stats_active_streams)?.text =
                "${getString(R.string.stats_active_streams)}: $active"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    private fun formatDuration(seconds: Double): String {
        val totalSecs = seconds.toLong()
        val d = totalSecs / 86400
        val h = (totalSecs % 86400) / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (h > 0) append("${h}h ")
            if (m > 0) append("${m}m ")
            append("${s}s")
        }
    }
}
