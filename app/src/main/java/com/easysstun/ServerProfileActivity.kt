package com.easysstun

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class ServerProfileActivity : AppCompatActivity() {

    private lateinit var pref: Pref
    private var currentProfile: Profile? = null
    private var profileId: String? = null

    // Declare UI elements
    private lateinit var profileName: EditText
    private lateinit var profileServer: EditText
    private lateinit var profileServerPort: EditText
    private lateinit var profilePassword: EditText
    private lateinit var profileSocksPort: EditText
    private lateinit var profileEncryption: Spinner
    private lateinit var profileProxyRule: Spinner
    private lateinit var profileOutbound: Spinner
    private lateinit var profileServerNameIndication: EditText
    private lateinit var profileCustomCa: EditText
    private lateinit var profileDirectFile: EditText
    private lateinit var profileProxyFile: EditText
    private lateinit var profileLogLevel: Spinner
    private lateinit var profileEnableQuic: Spinner
    private lateinit var profileIpv6Rule: Spinner
    private lateinit var saveProfileButton: Button
    private lateinit var deleteProfileButton: Button // Added delete button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_profile)

        pref = Pref(this)
        profileId = intent.getStringExtra("profileId")

        // Initialize UI elements
        profileName = findViewById(R.id.profile_name)
        profileServer = findViewById(R.id.profile_server)
        profileServerPort = findViewById(R.id.profile_server_port)
        profilePassword = findViewById(R.id.profile_password)
        profileSocksPort = findViewById(R.id.profile_socks_port)
        profileEncryption = findViewById(R.id.profile_encryption)
        profileProxyRule = findViewById(R.id.profile_proxy_rule)
        profileOutbound = findViewById(R.id.profile_outbound)
        profileServerNameIndication = findViewById(R.id.profile_server_name_indication)
        profileCustomCa = findViewById(R.id.profile_custom_ca)
        profileDirectFile = findViewById(R.id.profile_direct_file)
        profileProxyFile = findViewById(R.id.profile_proxy_file)
        profileLogLevel = findViewById(R.id.profile_log_level)
        profileEnableQuic = findViewById(R.id.profile_enable_quic)
        profileIpv6Rule = findViewById(R.id.profile_ipv6_rule)
        saveProfileButton = findViewById(R.id.save_profile_button)
        deleteProfileButton = findViewById(R.id.delete_profile_button) // Initialize delete button

        // Populate Spinners
        val encryptionAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_encryption_list, android.R.layout.simple_spinner_item
        )
        encryptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileEncryption.adapter = encryptionAdapter

        val proxyRuleAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_proxyrule_list, android.R.layout.simple_spinner_item
        )
        proxyRuleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileProxyRule.adapter = proxyRuleAdapter

        val outboundAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_outbound_list, android.R.layout.simple_spinner_item
        )
        outboundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileOutbound.adapter = outboundAdapter

        val logLevelAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_loglevel_list, android.R.layout.simple_spinner_item
        )
        logLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileLogLevel.adapter = logLevelAdapter

        val enableQuicAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_enable_quic_list, android.R.layout.simple_spinner_item
        )
        enableQuicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileEnableQuic.adapter = enableQuicAdapter

        val ipv6RuleAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_ipv6_rule_list, android.R.layout.simple_spinner_item
        )
        ipv6RuleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileIpv6Rule.adapter = ipv6RuleAdapter


        if (profileId != null) {
            currentProfile = pref.getProfiles().find { it.id == profileId }
            currentProfile?.let {
                profileName.setText(it.name)
                profileServer.setText(it.server)
                profileServerPort.setText(it.serverPort)
                profilePassword.setText(it.password)
                profileSocksPort.setText(it.socksPort)
                // Set spinner selections
                setSpinnerSelection(profileEncryption, it.encryption, R.array.easyss_encryption_list)
                setSpinnerSelection(profileProxyRule, it.proxyRule, R.array.easyss_proxyrule_list_value, R.array.easyss_proxyrule_list_value)
                setSpinnerSelection(profileOutbound, it.outbound, R.array.easyss_outbound_list_value, R.array.easyss_outbound_list_value)
                setSpinnerSelection(profileLogLevel, it.logLevel, R.array.easyss_loglevel_list_value, R.array.easyss_loglevel_list_value)
                setSpinnerSelection(profileEnableQuic, it.enableQuic, R.array.easyss_enable_quic_list_value, R.array.easyss_enable_quic_list_value)
                setSpinnerSelection(profileIpv6Rule, it.ipv6Rule, R.array.easyss_ipv6_rule_value, R.array.easyss_ipv6_rule_value)

                profileServerNameIndication.setText(it.serverNameIndication)
                profileCustomCa.setText(it.customCa)
                profileDirectFile.setText(it.directFile)
                profileProxyFile.setText(it.proxyFile)

                // Show delete button if editing an existing profile
                deleteProfileButton.visibility = View.VISIBLE
            }
        }

        saveProfileButton.setOnClickListener {
            saveProfile()
        }

        deleteProfileButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun showDeleteConfirmationDialog() {
        val isActive = profileId != null && profileId == pref.getActiveProfile()?.id
        val message = if (isActive && pref.isServiceEnabled) {
            // Deleting the active profile while the VPN is running will disconnect it.
            getString(R.string.delete_confirm_message_active)
        } else {
            getString(R.string.delete_confirm_message)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.delete_confirm_positive)) { _, _ ->
                deleteProfileAndFinish()
            }
            .setNegativeButton(getString(R.string.delete_confirm_negative), null)
            .show()
    }

    private fun deleteProfileAndFinish() {
        profileId?.let {
            val wasActive = pref.getActiveProfile()?.id == it
            val wasRunning = pref.isServiceEnabled
            pref.deleteProfile(it)
            Toast.makeText(this, getString(R.string.delete_success), Toast.LENGTH_SHORT).show()
            if (wasActive && wasRunning) {
                // The VPN was running with the profile being deleted. Disconnect it
                // explicitly (same signal as the home screen stop button) instead of
                // letting it keep running with a config that no longer exists.
                val intent = Intent(this, TProxyService::class.java)
                    .setAction(TProxyService.ACTION_DISCONNECT)
                startService(intent)
            }
            finish()
        }
    }

    private fun setSpinnerSelection(spinner: Spinner, value: String?, valuesArrayResId: Int, entriesArrayResId: Int? = null) {
        value?.let {
            if (spinner.adapter == null) {
                val entries = resources.getStringArray(entriesArrayResId ?: valuesArrayResId)
                val aa = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    entries
                )
                aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = aa
            }

            val values = resources.getStringArray(valuesArrayResId)
            val position = values.indexOf(it)
            if (position >= 0) {
                spinner.setSelection(position)
            }
        }
    }


    private fun saveProfile() {
        val name = profileName.text.toString()
        val server = profileServer.text.toString()
        val serverPort = profileServerPort.text.toString()
        val password = profilePassword.text.toString()
        val socksPort = profileSocksPort.text.toString()
        val encryption = profileEncryption.selectedItem.toString() // May need to get from values array if entries are different
        val proxyRule = getSpinnerValue(profileProxyRule, R.array.easyss_proxyrule_list_value)
        val outbound = getSpinnerValue(profileOutbound, R.array.easyss_outbound_list_value)
        val serverNameIndication = profileServerNameIndication.text.toString()
        val customCa = profileCustomCa.text.toString()
        val directFile = profileDirectFile.text.toString()
        val proxyFile = profileProxyFile.text.toString()
        val logLevel = getSpinnerValue(profileLogLevel, R.array.easyss_loglevel_list_value)
        val enableQuic = getSpinnerValue(profileEnableQuic, R.array.easyss_enable_quic_list_value)
        val ipv6Rule = getSpinnerValue(profileIpv6Rule, R.array.easyss_ipv6_rule_value)


        if (server.isBlank() || serverPort.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Server, Port, and Password cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val profileToSave = Profile(
            id = profileId ?: UUID.randomUUID().toString(),
            name = name.ifBlank { "$server:$serverPort" }, // Default name to server:port if blank
            server = server,
            serverPort = serverPort,
            password = password,
            encryption = encryption,
            proxyRule = proxyRule,
            outbound = outbound,
            logLevel = logLevel,
            enableQuic = enableQuic,
            ipv6Rule = ipv6Rule,
            serverNameIndication = serverNameIndication,
            customCa = customCa,
            directFile = directFile,
            proxyFile = proxyFile,
            socksPort = socksPort
        )

        if (profileId == null) { // A new profile is being added
            pref.addProfile(profileToSave)
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
        } else { // An existing profile is being updated
            pref.updateProfile(profileToSave)
            Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()

            // Check if the updated profile is the active one
            if (profileToSave.id == pref.getActiveProfile()?.id) {
                val intent = android.content.Intent(Pref.PREFS_UPDATED).apply {
                    // Ensure the broadcast targets only this app's non-exported receiver
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }
        finish()
    }

    // Helper to get value from spinner if entryValues are used
    private fun getSpinnerValue(spinner: Spinner, valuesArrayResId: Int): String {
        val values = resources.getStringArray(valuesArrayResId)
        return values[spinner.selectedItemPosition]
    }
}
