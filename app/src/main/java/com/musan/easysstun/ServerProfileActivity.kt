package com.musan.easysstun

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class ServerProfileActivity : AppCompatActivity() {

    private lateinit var pref: Pref
    private var currentProfile: ServerProfile? = null
    private var profileId: String? = null

    // Declare UI elements
    private lateinit var profileName: EditText
    private lateinit var profileServer: EditText
    private lateinit var profileServerPort: EditText
    private lateinit var profilePassword: EditText
    private lateinit var profileEncryption: Spinner
    private lateinit var profileProxyRule: Spinner
    private lateinit var profileOutbound: Spinner
    private lateinit var profileServerNameIndication: EditText
    private lateinit var profileCustomCa: EditText
    private lateinit var profileLogLevel: Spinner
    private lateinit var profileDisableQuic: Spinner
    private lateinit var profileIpv6Rule: Spinner
    private lateinit var saveProfileButton: Button

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
        profileEncryption = findViewById(R.id.profile_encryption)
        profileProxyRule = findViewById(R.id.profile_proxy_rule)
        profileOutbound = findViewById(R.id.profile_outbound)
        profileServerNameIndication = findViewById(R.id.profile_server_name_indication)
        profileCustomCa = findViewById(R.id.profile_custom_ca)
        profileLogLevel = findViewById(R.id.profile_log_level)
        profileDisableQuic = findViewById(R.id.profile_disable_quic)
        profileIpv6Rule = findViewById(R.id.profile_ipv6_rule)
        saveProfileButton = findViewById(R.id.save_profile_button)

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

        val disableQuicAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_disable_quic_list, android.R.layout.simple_spinner_item
        )
        disableQuicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileDisableQuic.adapter = disableQuicAdapter

        val ipv6RuleAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_ipv6_rule_list, android.R.layout.simple_spinner_item
        )
        ipv6RuleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileIpv6Rule.adapter = ipv6RuleAdapter


        if (profileId != null) {
            currentProfile = pref.getServerProfiles().find { it.id == profileId }
            currentProfile?.let {
                profileName.setText(it.name)
                profileServer.setText(it.server)
                profileServerPort.setText(it.serverPort)
                profilePassword.setText(it.password)
                // Set spinner selections
                setSpinnerSelection(profileEncryption, it.encryption, R.array.easyss_encryption_list)
                setSpinnerSelection(profileProxyRule, it.proxyRule, R.array.easyss_proxyrule_list_value, R.array.easyss_proxyrule_list_value)
                setSpinnerSelection(profileOutbound, it.outbound, R.array.easyss_outbound_list_value, R.array.easyss_outbound_list_value)
                setSpinnerSelection(profileLogLevel, it.logLevel, R.array.easyss_loglevel_list_value, R.array.easyss_loglevel_list_value)
                setSpinnerSelection(profileDisableQuic, it.disableQuic, R.array.easyss_disable_quic_list_value, R.array.easyss_disable_quic_list_value)
                setSpinnerSelection(profileIpv6Rule, it.ipv6Rule, R.array.easyss_ipv6_rule_value, R.array.easyss_ipv6_rule_value)

                profileServerNameIndication.setText(it.serverNameIndication)
                profileCustomCa.setText(it.customCa)
            }
        }

        saveProfileButton.setOnClickListener {
            saveProfile()
        }
    }

    private fun setSpinnerSelection(spinner: Spinner, value: String?, valuesArrayResId: Int, entriesArrayResId: Int? = null) {
        value?.let {
            val adapter = spinner.adapter as ArrayAdapter<String>? ?: ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                resources.getStringArray(entriesArrayResId ?: valuesArrayResId)
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = it
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
        val encryption = profileEncryption.selectedItem.toString() // May need to get from values array if entries are different
        val proxyRule = getSpinnerValue(profileProxyRule, R.array.easyss_proxyrule_list_value)
        val outbound = getSpinnerValue(profileOutbound, R.array.easyss_outbound_list_value)
        val serverNameIndication = profileServerNameIndication.text.toString()
        val customCa = profileCustomCa.text.toString()
        val logLevel = getSpinnerValue(profileLogLevel, R.array.easyss_loglevel_list_value)
        val disableQuic = getSpinnerValue(profileDisableQuic, R.array.easyss_disable_quic_list_value)
        val ipv6Rule = getSpinnerValue(profileIpv6Rule, R.array.easyss_ipv6_rule_value)


        if (server.isBlank() || serverPort.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Server, Port, and Password cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val profileToSave = ServerProfile(
            id = profileId ?: UUID.randomUUID().toString(),
            name = name.ifBlank { server }, // Default name to server address if blank
            server = server,
            serverPort = serverPort,
            password = password,
            encryption = encryption,
            proxyRule = proxyRule,
            outbound = outbound,
            logLevel = logLevel,
            disableQuic = disableQuic,
            ipv6Rule = ipv6Rule,
            serverNameIndication = serverNameIndication,
            customCa = customCa
        )

        if (profileId == null) { // A new profile is being added
            pref.addServerProfile(profileToSave)
            pref.setActiveServer(profileToSave.id) // Ensure this line is added
            Toast.makeText(this, "Profile saved and set as active", Toast.LENGTH_SHORT).show() // Update this line
        } else { // An existing profile is being updated
            pref.updateServerProfile(profileToSave)
            Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()

            // Check if the updated profile is the active one
            if (profileToSave.id == pref.getActiveServerProfile()?.id) {
                val intent = android.content.Intent("prefs_updated")
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
