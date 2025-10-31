package com.musan.easysstun

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.RotateAnimation
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
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL


class MainFragment : Fragment() {
    private lateinit var mContext: Context
    private lateinit var pref: Pref
    private lateinit var easyssInfo: easyssInfo

    private var rotateAnimation: RotateAnimation? = null
    private lateinit var speed_test_icon: ImageView
    private var speedTesting = false

    private var pendingServerProfileId: String? = null
    private var isSwitchingServer: Boolean = false

    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainFragmentReceiver", "serviceStoppedReceiver - Entry. Action: ${intent?.action}")
            if (intent?.action == TProxyService.ACTION_SERVICE_STOPPED) {
                Log.d("MainFragmentReceiver", "ACTION_SERVICE_STOPPED received. Pending server ID: $pendingServerProfileId, isSwitchingServer: $isSwitchingServer")
                if (pendingServerProfileId != null) {
                    Log.d("MainFragmentReceiver", "pendingServerProfileId is NOT null. Attempting to switch.")
                    pref.setActiveServer(pendingServerProfileId!!)
                    Log.d("MainFragmentReceiver", "pref.setActiveServer called with ID: $pendingServerProfileId")
                    easyssInfo = pref.getEasyssInfo()
                    Log.d("MainFragmentReceiver", "pref.getEasyssInfo called. easyssInfo.valid=${easyssInfo.valid}")

                    Log.i("MainFragmentReceiver", "Calling startVPNService for server switch. ID: $pendingServerProfileId")
                    startVPNService(isCalledFromReceiver = true)
                    Log.d("MainFragmentReceiver", "Returned from startVPNService call.")

                    pendingServerProfileId = null
                    Log.d("MainFragmentReceiver", "pendingServerProfileId reset to null.")
                    isSwitchingServer = false
                    Log.d("MainFragmentReceiver", "isSwitchingServer reset to false.")

                    view?.let {
                        Log.d("MainFragmentReceiver", "Calling updateServiceStatu (after switch).")
                        updateServiceStatu(it)
                        Log.d("MainFragmentReceiver", "Returned from updateServiceStatu (after switch).")
                    }
                } else {
                    Log.d("MainFragmentReceiver", "pendingServerProfileId IS null. Service stopped for other reasons or switch already processed/failed.")
                    // Service stopped for other reasons (e.g. user clicked main stop button, or pendingId was already cleared)
                    if (isSwitchingServer) { // If it's still true, something went wrong, reset it.
                        Log.w("MainFragmentReceiver", "isSwitchingServer was true but pendingServerProfileId is null. Resetting isSwitchingServer.")
                        isSwitchingServer = false
                    }
                    view?.let {
                        Log.d("MainFragmentReceiver", "Calling updateServiceStatu (no pendingId).")
                        updateServiceStatu(it)
                        Log.d("MainFragmentReceiver", "Returned from updateServiceStatu (no pendingId).")
                    }
                }
                val spinner = view?.findViewById<Spinner>(R.id.server_spinner)
                spinner?.isEnabled = true
                Log.d("MainFragmentReceiver", "Spinner explicitly re-enabled. Current state: ${spinner?.isEnabled}")
            } else {
                Log.d("MainFragmentReceiver", "Received some other action or null action: ${intent?.action}")
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
        GitTagTask(view, requireContext()).execute()

        val intentFilter = IntentFilter(TProxyService.ACTION_SERVICE_STOPPED)
        ContextCompat.registerReceiver(
            requireActivity(),
            serviceStoppedReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d("MainFragment", "serviceStoppedReceiver registered.")

        // Ensure spinner is enabled when view is created
        view.findViewById<Spinner>(R.id.server_spinner)?.isEnabled = true
    }

    override fun onDestroyView() {
        requireActivity().unregisterReceiver(serviceStoppedReceiver)
        Log.d("MainFragment", "serviceStoppedReceiver unregistered.")
        super.onDestroyView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_tips -> {
                findNavController().navigate(R.id.action_main_to_log)
//                Toast.makeText(mContext, list.shuffled().first(), Toast.LENGTH_SHORT).show()
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

        val activeServerProfile = pref.getActiveServerProfile()
        if (activeServerProfile != null) {
            val activeServerPosition = serverProfiles.indexOfFirst { it.id == activeServerProfile.id }
            if (activeServerPosition != -1) {
                serverSpinner.setSelection(activeServerPosition, false) // set false to avoid triggering onItemSelected
            }
        }

        // Re-attach or ensure the listener is set.
        // If the listener logic depends on serverProfiles, it must be correctly scoped or passed.
        // For this refactoring, we assume the existing listener logic in setup() will be part of the spinner setup.
        // The existing onItemSelectedListener is defined below and will be set after this method in setup().
        // However, for onResume, if the adapter is reset, the listener might need to be reset as well.
        // For now, let's keep the listener setup within the setup() method which calls this.
        // If issues arise, the listener setup might need to be part of this method too.
        // Let's re-add the listener here to be safe for onResume calls.
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, viewOfItem: View?, position: Int, id: Long) {
                val selectedProfile = serverProfiles[position] // serverProfiles must be in scope

                if (pref.getActiveServerProfile()?.id == selectedProfile.id && !isSwitchingServer) {
                    Log.d("MainFragment", "Spinner selected current active server. No change.")
                    this@MainFragment.view?.let { updateServiceStatu(it) }
                    return
                }

                Log.d("MainFragment", "Server selected: ${selectedProfile.name}. Current isServiceEnabled: ${pref.isServiceEnabled}")

                if (pref.isServiceEnabled) {
                    if (isSwitchingServer) {
                        Log.i("MainFragment", "Server switch already in progress. Updating pending server to: ${selectedProfile.id}")
                        pendingServerProfileId = selectedProfile.id
                        return
                    }
                    
                    Log.i("MainFragment", "Initiating server switch. Setting pending server to: ${selectedProfile.id}")
                    pendingServerProfileId = selectedProfile.id
                    isSwitchingServer = true
                    serverSpinner.isEnabled = false
                    stopVPNService()
                } else {
                    Log.i("MainFragment", "VPN not running. Setting active server to: ${selectedProfile.id}")
                    pref.setActiveServer(selectedProfile.id)
                    easyssInfo = pref.getEasyssInfo()
                    isSwitchingServer = false
                    serverSpinner.isEnabled = true
                    this@MainFragment.view?.let {
                        val serviceSummaryTextView = it.findViewById<TextView>(R.id.service_summary)
                        if (easyssInfo.valid) {
                            serviceSummaryTextView.text = easyssInfo.info
                        } else {
                            serviceSummaryTextView.text = getString(R.string.easyss_need_config)
                        }
                        updateServiceStatu(it)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                serverSpinner.isEnabled = true
            }
        }
    }

    private fun setup(view: View) {
        updateServerSpinner(view) // Call the new method to setup spinner

        var service_button =
            view.findViewById<MaterialButton>(R.id.service_button)
        service_button.let {
            it.setOnClickListener {
                if (isSwitchingServer) {
                    Toast.makeText(mContext, "Server switch in progress, please wait.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pref.isServiceEnabled) {
                    pref.isServiceEnabled = false
                } else {
                    if (!easyssInfo.valid) {
                        Toast.makeText(mContext, getString(R.string.easyss_need_config), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    pref.isServiceEnabled = true
                }
                updateServiceStatu(view)
            }
        }

        // The onItemSelectedListener is now set within updateServerSpinner.
        // We might need to ensure serverSpinner variable is accessible or passed if it's used elsewhere in setup
        // For now, assuming it's only used for the listener which is now part of updateServerSpinner.

        val selected_apps = pref.getApps()
        view.findViewById<TextView>(R.id.text1).let {
            it.text = getString(R.string.skipped_app_list, selected_apps.size.toString())
        }

        if (easyssInfo.valid){
            view.findViewById<TextView>(R.id.service_summary).let {
                it.text = easyssInfo.info
            }
        }else{
            pref.isServiceEnabled = false
        }

        view.findViewById<MaterialButton>(R.id.service_setting)
            .let {
                it.setOnClickListener {
                    findNavController().navigate(R.id.action_main_to_setting)
                }
            }

        view.findViewById<LinearLayout>(R.id.applist).let {
            it.setOnClickListener {
                findNavController().navigate(R.id.action_main_to_applist)
            }
        }

        speed_test_icon = view.findViewById<ImageView>(R.id.speed_test_icon)
        initRotateAnimation()

        view.findViewById<LinearLayout>(R.id.speed_test).let {
            it.setOnClickListener {
                if (!speedTesting){
                    getResponseTimeUsingSocksProxy("https://www.google.com", "127.0.0.1", 2080)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private inner class GitTagTask(private val rootView: View, private val context: Context) : AsyncTask<Void, Void, String>() {

        override fun doInBackground(vararg params: Void): String {
            val libraryPath = context.applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
            val command = listOf(libraryPath, "--version")
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var gitTag = "Easyss"
            while (reader.readLine().also { line = it } != null) {
                // 在输出中查找包含 "Git tag:" 的行
                if (line!!.startsWith("Git tag:")) {
                    gitTag += ": " + line!!.substringAfter(":").trim()
                    break
                }
            }

            process.waitFor()
            return gitTag
        }

        override fun onPostExecute(gitTag: String) {
            // 将 Git tag 设置到 TextView 上
            val versionPlaceholder = rootView.findViewById<TextView>(R.id.version_placeholder)
            val appVersion = BuildConfig.VERSION_NAME
            versionPlaceholder.text = "$gitTag | EasyssTun: v$appVersion"
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
        Log.d("MainFragmentSVC", "startVPNService - Entry. isCalledFromReceiver: $isCalledFromReceiver")
        val intent = VpnService.prepare(mContext)
        if (intent != null) {
            Log.d("MainFragmentSVC", "VpnService.prepare needs permissions. Calling startActivityForResult.")
            startActivityForResult(intent, 0)
            // If permission is needed, do not proceed to start the service here.
            // onActivityResult will call startVPNService() again.
            return
        }
        Log.d("MainFragmentSVC", "VpnService.prepare returned null (permissions granted). Proceeding to try block.")

        // Permission already granted, proceed to start the service.
        try {
            Log.d("MainFragmentSVC", "startVPNService - Inside try block, attempting to get active profile.")
            val activeProfile = pref.getActiveServerProfile() // Get the full profile object
            val intent2 = Intent(mContext, TProxyService::class.java)

            if (activeProfile != null) {
                val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                try {
                    val profileJson = json.encodeToString(activeProfile)
                    intent2.putExtra("com.musan.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA", profileJson)
                } catch (e: kotlinx.serialization.SerializationException) {
                    Log.e("MainFragment", "Error serializing active profile", e)
                    // If called from receiver, we must not crash it.
                    if (isCalledFromReceiver) {
                        // Log and allow receiver to clean up UI. Service won't start with this profile.
                        Toast.makeText(mContext, "Error: Could not serialize server profile.", Toast.LENGTH_LONG).show()
                        return // Prevent service start if serialization fails and in receiver context
                    }
                    // For non-receiver calls, behavior might differ, but for now, let's be consistent:
                    // don't proceed if profile can't be sent.
                    return
                }
            } else {
                Log.w("MainFragment", "No active server profile found to start TProxyService.")
                if (isCalledFromReceiver) {
                    Toast.makeText(mContext, "Error: No active server configured.", Toast.LENGTH_LONG).show()
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
                // For other callers of startVPNService (e.g., direct user action, onActivityResult),
                // re-throwing might be acceptable or desired for immediate crash feedback,
                // but for robustness, it's often better to handle gracefully.
                // For now, let's be consistent and show a Toast.
                Toast.makeText(mContext, "Error starting VPN service.", Toast.LENGTH_LONG).show()
                // If strict crashing is desired for non-receiver contexts:
                // if (e !is kotlinx.serialization.SerializationException) { // Already handled above
                //    throw RuntimeException("Non-receiver context: Error starting TProxyService", e)
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
            Log.d("MainFragment", "updateServiceStatu: Server switch in progress, UI set to switching state.")
            service_button.text = "Switching..."
            service_button.isEnabled = false
            // Optionally set title or other UI elements
            service_title.text = "Switching..." 
            // It might be good to also change the card color or icon here to reflect "switching"
            // For now, just text and button state as per primary requirement.
            return
        } else {
            // Ensure button is generally enabled if not switching, specific logic below will fine-tune this
            service_button.isEnabled = true 
            Log.d("MainFragment", "updateServiceStatu: Not switching server, proceeding with normal UI update.")
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
        // The click listener for service_button handles invalid config by returning, not by disabling button.
        // Let's make sure the button state is explicitly managed in each path.
        // service_button.isEnabled = true; // Default for non-switching, can be overridden below

        when {
            pref.isServiceEnabled -> { // Service is ON
                if(!easyssInfo.valid) { // Should not happen if checks in click listener are effective
                                      // And if startVPNService() is robust. But good to have a safeguard.
                    Log.w("MainFragment", "updateServiceStatu: Service enabled but easyssInfo is invalid! Disabling service.")
                    Toast.makeText(mContext, getString(R.string.easyss_need_config), Toast.LENGTH_LONG).show()
                    pref.isServiceEnabled = false // Correct the state
                    // Now recursively call to update UI to "stopped" state
                    updateServiceStatu(view)
                    return // Exit current processing
                }

                startVPNService() // This ensures the service is actually started if pref says it should be
                service_button.text = getString(R.string.service_disable)
                service_button.isEnabled = true // Explicitly enable
                service_card.setCardBackgroundColor(mContext.getColor(R.color.home_card_background_color_active))
                service_icon.setImageDrawable(getDrawable(mContext, R.drawable.ic_launcher_foreground_big))
                service_button.icon = getDrawable(mContext, R.drawable.ic_close_24)
                service_button.setBackgroundColor(mContext.getColor(R.color.button_disable))
                service_title.text = getString(R.string.service_running)
            }
            else -> { // Service is OFF (pref.isServiceEnabled == false)
                stopVPNService() // Ensure service is actually stopped
                service_button.text = getString(R.string.service_enable)
                service_card.setCardBackgroundColor(mContext.getColor(R.color.home_card_background_color))
                service_icon.setImageDrawable(getDrawable(mContext, R.drawable.ic_launcher_foreground_big))
                service_button.icon = getDrawable(mContext, R.drawable.ic_outline_play_arrow_24)
                service_button.setBackgroundColor(mContext.getColor(R.color.button_enable))
                service_title.text = getString(R.string.service_stopped)

                // If config is invalid, button should be disabled to prevent enabling attempts.
                if (!easyssInfo.valid) {
                    service_button.isEnabled = false
                    // Optionally, provide more visual feedback e.g. change button color
                    Log.d("MainFragment", "updateServiceStatu: Config invalid and service stopped, service_button disabled.")
                } else {
                    service_button.isEnabled = true
                    Log.d("MainFragment", "updateServiceStatu: Config valid and service stopped, service_button enabled.")
                }
            }
        }
    }

    fun getResponseTimeUsingSocksProxy(
        urlString: String,
        socksProxyHost: String,
        socksProxyPort: Int
    ) {
        speed_test_icon.startAnimation(rotateAnimation)
        speedTesting = true

        CoroutineScope(Dispatchers.Default).launch {
            var res: String
            if (!pref.isServiceEnabled){
                res = getString(R.string.service_stopped)
            } else {
                val url = URL(urlString)
                val proxy =
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))

                try {
                    val startTime = System.currentTimeMillis()
                    (url.openConnection(proxy) as? HttpURLConnection)?.run {
                        requestMethod = "GET"
                        connectTimeout = 3000 // 3 seconds timeout
                        readTimeout = 3000    // 3 seconds read timeout

                        // Force connection and get response code
                        try {
                            inputStream.close() // We just want to connect and get headers
                        } catch (e: Exception) {
                            // Ignore exceptions from closing stream if any, we care about connect time
                        }
                        // val responseCode = responseCode // Not strictly needed if we only measure connect time
                        disconnect()
                    }
                    val endTime = System.currentTimeMillis()
                    val responseTime = endTime - startTime
                    res = "$responseTime ms"
                } catch (e: Exception) {
                    Log.w("SpeedTest", "Error during speed test: ${e.message}")
                    res = getString(R.string.delay_test_fail)
                }
            }

            withContext(Dispatchers.Main) {
                speedTesting = false
                speed_test_icon.clearAnimation()
                // Ensure view is still valid if fragment is detached quickly
                view?.findViewById<TextView>(R.id.speed_result)?.text = getString(R.string.delay_test_result, res)
                if (isAdded) { // Check if fragment is currently added to its activity
                    Toast.makeText(mContext, getString(R.string.delay_test_result, res), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun initRotateAnimation() {
        rotateAnimation = RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        ).apply {
            duration = 1000 // 旋转一周的时间，单位毫秒
            repeatCount = Animation.INFINITE // 无限循环
        }
    }

    
}