package com.musan.easysstun

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.musan.easysstun.databinding.ActivityServerProfileBinding
import java.util.*

class ServerProfileActivity : AppCompatActivity() {

    private lateinit var pref: Pref
    private var currentProfile: ServerProfile? = null
    private var profileId: String? = null
    private lateinit var binding: ActivityServerProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pref = Pref(this)
        profileId = intent.getStringExtra("profileId")

        // Populate Spinners
        val encryptionAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_encryption_list, android.R.layout.simple_spinner_item
        )
        encryptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.profileEncryption.adapter = encryptionAdapter

        val proxyRuleAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_proxyrule_list, android.R.layout.simple_spinner_item
        )
        proxyRuleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.profileProxyRule.adapter = proxyRuleAdapter

        val outboundAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_outbound_list, android.R.layout.simple_spinner_item
        )
        outboundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.profileOutbound.adapter = outboundAdapter

        val logLevelAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_loglevel_list, android.R.layout.simple_spinner_item
        )
        logLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.profileLogLevel.adapter = logLevelAdapter

        val disableQuicAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_disable_quic_list, android.R.layout.simple_spinner_item
        )
        disableQuicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.profileDisableQuic.adapter = disableQuicAdapter

        val ipv6RuleAdapter = ArrayAdapter.createFromResource(
            this, R.array.easyss_ipv6_rule_list, android.R.layout.simple_spinner_item
        )
        ipv6RuleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.profileIpv6Rule.adapter = ipv6RuleAdapter


        if (profileId != null) {
            currentProfile = pref.getServerProfiles().find { it.id == profileId }
            currentProfile?.let {
                binding.profileName.setText(it.name)
                binding.profileServer.setText(it.server)
                binding.profileServerPort.setText(it.serverPort)
                binding.profilePassword.setText(it.password)
                // Set spinner selections
                setSpinnerSelection(binding.profileEncryption, it.encryption, R.array.easyss_encryption_list)
                setSpinnerSelection(binding.profileProxyRule, it.proxyRule, R.array.easyss_proxyrule_list_value, R.array.easyss_proxyrule_list_value)
                setSpinnerSelection(binding.profileOutbound, it.outbound, R.array.easyss_outbound_list_value, R.array.easyss_outbound_list_value)
                setSpinnerSelection(binding.profileLogLevel, it.logLevel, R.array.easyss_loglevel_list_value, R.array.easyss_loglevel_list_value)
                setSpinnerSelection(binding.profileDisableQuic, it.disableQuic, R.array.easyss_disable_quic_list_value, R.array.easyss_disable_quic_list_value)
                setSpinnerSelection(binding.profileIpv6Rule, it.ipv6Rule, R.array.easyss_ipv6_rule_value, R.array.easyss_ipv6_rule_value)

                binding.profileServerNameIndication.setText(it.serverNameIndication)
                binding.profileCustomCa.setText(it.customCa)
            }
        }

        binding.saveProfileButton.setOnClickListener {
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
        val name = binding.profileName.text.toString()
        val server = binding.profileServer.text.toString()
        val serverPort = binding.profileServerPort.text.toString()
        val password = binding.profilePassword.text.toString()
        val encryption = binding.profileEncryption.selectedItem.toString() // May need to get from values array if entries are different
        val proxyRule = getSpinnerValue(binding.profileProxyRule, R.array.easyss_proxyrule_list_value)
        val outbound = getSpinnerValue(binding.profileOutbound, R.array.easyss_outbound_list_value)
        val serverNameIndication = binding.profileServerNameIndication.text.toString()
        val customCa = binding.profileCustomCa.text.toString()
        val logLevel = getSpinnerValue(binding.profileLogLevel, R.array.easyss_loglevel_list_value)
        val disableQuic = getSpinnerValue(binding.profileDisableQuic, R.array.easyss_disable_quic_list_value)
        val ipv6Rule = getSpinnerValue(binding.profileIpv6Rule, R.array.easyss_ipv6_rule_value)


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
