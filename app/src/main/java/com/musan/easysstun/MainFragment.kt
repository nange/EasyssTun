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
            if (intent?.action == TProxyService.ACTION_SERVICE_STOPPED) {
                Log.d("MainFragment", "Received ACTION_SERVICE_STOPPED. Pending server ID: $pendingServerProfileId")
                if (pendingServerProfileId != null) {
                    pref.setActiveServer(pendingServerProfileId!!)
                    easyssInfo = pref.getEasyssInfo() // Refresh local easyssInfo after changing active server

                    Log.i("MainFragment", "Restarting VPN service with new server: $pendingServerProfileId")
                    startVPNService() // This should now use the new server settings

                    pendingServerProfileId = null
                    isSwitchingServer = false // Reset switching state

                    // Update UI after server switch is complete
                    view?.let {
                        updateServiceStatu(it)
                    }
                } else {
                    // Service stopped for other reasons (e.g. user clicked main stop button)
                    isSwitchingServer = false // Reset if it was somehow true
                    view?.let { updateServiceStatu(it) }
                }
                // Re-enable spinner after processing
                view?.findViewById<Spinner>(R.id.server_spinner)?.isEnabled = true
                Log.d("MainFragment", "Spinner re-enabled in serviceStoppedReceiver.")
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
        requireActivity().registerReceiver(serviceStoppedReceiver, intentFilter)
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
        // Ensure spinner is enabled on resume
        view?.findViewById<Spinner>(R.id.server_spinner)?.isEnabled = true
    }

    private fun setup(view: View) {
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

        // Server Spinner Setup
        val serverSpinner = view.findViewById<Spinner>(R.id.server_spinner)
        val serverProfiles = pref.getServerProfiles()
        val serverNames = serverProfiles.map { it.name }
        val adapter = ArrayAdapter(mContext, android.R.layout.simple_spinner_item, serverNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverSpinner.adapter = adapter
        // Ensure spinner is enabled before setting listener and selection
        serverSpinner.isEnabled = true

        val activeServerProfile = pref.getActiveServerProfile()
        if (activeServerProfile != null) {
            val activeServerPosition = serverProfiles.indexOfFirst { it.id == activeServerProfile.id }
            if (activeServerPosition != -1) {
                serverSpinner.setSelection(activeServerPosition)
            }
        }

        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, viewOfItem: View?, position: Int, id: Long) {
                val selectedProfile = serverProfiles[position]

                // If the selected server is already the active one, do nothing.
                if (pref.getActiveServerProfile()?.id == selectedProfile.id && !isSwitchingServer) {
                     Log.d("MainFragment", "Spinner selected current active server. No change.")
                     // Ensure UI reflects current state if needed, though it should be correct.
                     view?.let { updateServiceStatu(it) }
                     return
                }

                Log.d("MainFragment", "Server selected: ${selectedProfile.name}. Current isServiceEnabled: ${pref.isServiceEnabled}")

                if (pref.isServiceEnabled) {
                    // VPN is running, need to stop, then restart with new server.
                    if (isSwitchingServer) {
                        // Already switching, just update to the latest selection.
                        Log.i("MainFragment", "Server switch already in progress. Updating pending server to: ${selectedProfile.id}")
                        pendingServerProfileId = selectedProfile.id
                        // The existing stop process will continue, and then pick up this latest pendingServerProfileId.
                        return // Don't call stopVPNService again.
                    }
                    
                    Log.i("MainFragment", "Initiating server switch. Setting pending server to: ${selectedProfile.id}")
                    pendingServerProfileId = selectedProfile.id
                    isSwitchingServer = true // Set switching state
                    
                    // For now, just disable the spinner to prevent further changes during switch
                    serverSpinner.isEnabled = false 

                    stopVPNService() // Tell service to stop
                    // Do NOT call pref.setActiveServer() or startVPNService() here.
                    // The broadcast receiver will handle that after service confirms stop.
                } else {
                    // VPN is not running, just change the active server and update UI.
                    Log.i("MainFragment", "VPN not running. Setting active server to: ${selectedProfile.id}")
                    pref.setActiveServer(selectedProfile.id)
                    easyssInfo = pref.getEasyssInfo() // Refresh local easyssInfo
                    isSwitchingServer = false // Ensure this is false
                    // Update UI, including spinner re-enabling if it was disabled by a previous incomplete switch
                    serverSpinner.isEnabled = true
                    view?.let {
                        // Update summary text directly
                        val serviceSummaryTextView = it.findViewById<TextView>(R.id.service_summary)
                        if (easyssInfo.valid) {
                            serviceSummaryTextView.text = easyssInfo.info
                        } else {
                            serviceSummaryTextView.text = getString(R.string.easyss_need_config)
                        }
                        updateServiceStatu(it) // This will show correct "stopped" state with new server info
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
                serverSpinner.isEnabled = true // Re-enable spinner if nothing selected somehow
            }
        }

        var selected_apps = pref.getApps()!!
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
                    true
                }
            }

        view.findViewById<LinearLayout>(R.id.applist).let {
            it.setOnClickListener {
                findNavController().navigate(R.id.action_main_to_applist)
                true
            }
        }

        speed_test_icon = view.findViewById<ImageView>(R.id.speed_test_icon)
        initRotateAnimation()

        view.findViewById<LinearLayout>(R.id.speed_test).let {
            it.setOnClickListener {
                if (!speedTesting){
                    getResponseTimeUsingSocksProxy("https://www.google.com", "127.0.0.1", 2080)
                }

                true
            }
        }
    }

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
            versionPlaceholder.text = gitTag
        }
    }


    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (pref.isServiceEnabled) {
            startVPNService()
        }
    }




    private fun startVPNService() {
        val intent = VpnService.prepare(mContext)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
        }
        try {
            val activeProfile = pref.getActiveServerProfile() // Get the full profile object

            val intent2 = Intent(mContext, TProxyService::class.java)

            if (activeProfile != null) {
                // Ensure Json is available. If Pref.kt's instance is not accessible, create one.
                // For simplicity, creating one here:
                val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                try {
                    val profileJson = json.encodeToString(activeProfile)
                    intent2.putExtra("com.musan.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA", profileJson)
                    // Log.d("MainFragment", "Starting TProxyService with ACTIVE_SERVER_PROFILE_JSON_EXTRA: $profileJson") // Optional debug log
                } catch (e: kotlinx.serialization.SerializationException) {
                    Log.e("MainFragment", "Error serializing active profile", e)
                    // Handle error: perhaps don't start service or start without extra?
                    // For now, if serialization fails, it will proceed without the extra.
                }
            } else {
                // Log.w("MainFragment", "No active profile to send to TProxyService.") // Optional warning
            }

            mContext.startService(intent2.setAction(TProxyService.ACTION_CONNECT))
        } catch (e: Exception) {
            Log.e("MainFragment", "Error starting TProxyService", e)
            // Consider if this should re-throw or just log, depending on desired app behavior
            if (e !is kotlinx.serialization.SerializationException) { // Avoid double throw if caught above
                 throw RuntimeException(e)
            }
        }

    }

    private fun stopVPNService() {
        val intent2 = Intent(mContext, TProxyService::class.java)
        mContext.startService(intent2.setAction(TProxyService.ACTION_DISCONNECT))
    }

    private fun statuVPNService(): Boolean {
        return isServiceRunning(mContext, TProxyService::class.java)
    }

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

        var res = ""
        CoroutineScope(Dispatchers.Default).launch {
            if (!pref.isServiceEnabled){
                res = getString(R.string.service_stopped)
            }else {
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

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = mContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = manager.getRunningServices(Int.MAX_VALUE)
        for (service in services) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

}