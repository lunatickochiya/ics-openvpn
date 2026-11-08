/*
 * Copyright (c) 2026 Kizuna contributors
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import java.io.ByteArrayInputStream
import java.util.HashSet

/**
 * Raw configuration editor for WireGuard profiles, including routing and per-app options.
 */
class WireGuardConfigActivity : BaseActivity() {
    private lateinit var mProfile: VpnProfile
    private lateinit var mEditor: EditText
    private lateinit var mRoutingMode: Spinner
    private lateinit var mCustomRoutes: EditText
    private lateinit var mAppsSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uuid = intent.getStringExtra("$packageName.profileUUID")
        val profile = if (uuid != null) ProfileManager.get(this, uuid) else null
        if (profile == null || !profile.isWireguard) {
            Toast.makeText(this, R.string.wireguard_profile_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        mProfile = profile
        if (mProfile.mAllowedAppsVpn == null)
            mProfile.mAllowedAppsVpn = HashSet()

        setTitle(R.string.wireguard_config_title)
        val rootView = layoutInflater.inflate(R.layout.wireguard_config, null)
        setUpEdgeEdgeInsetsListener(rootView)
        setContentView(rootView)

        mEditor = findViewById(R.id.wireguard_config_text)
        mEditor.setText(profile.mWireguardConfig)

        mCustomRoutes = findViewById(R.id.wireguard_custom_routes)
        mCustomRoutes.setText(profile.mWireguardCustomRoutes)

        mRoutingMode = findViewById(R.id.wireguard_routing_mode)
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.wireguard_routing_modes,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mRoutingMode.adapter = adapter
        mRoutingMode.setSelection(profile.mWireguardRoutingMode.coerceIn(0, 2))
        mRoutingMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCustomRoutesState(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        mAppsSummary = findViewById(R.id.wireguard_apps_summary)
        findViewById<Button>(R.id.wireguard_select_apps).setOnClickListener {
            val intent = Intent(this, WireGuardAppsActivity::class.java)
            intent.putExtra("$packageName.profileUUID", mProfile.getUUIDString())
            startActivity(intent)
        }

        findViewById<Button>(R.id.wireguard_help).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.wireguard_config_title)
                .setMessage(R.string.wireguard_help)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        findViewById<Button>(R.id.wireguard_config_save).setOnClickListener { save() }
        updateCustomRoutesState(mRoutingMode.selectedItemPosition)
    }

    override fun onResume() {
        super.onResume()
        if (this::mAppsSummary.isInitialized)
            updateAppsSummary()
    }

    private fun updateCustomRoutesState(position: Int) {
        if (!this::mCustomRoutes.isInitialized)
            return
        val custom = position == 2
        mCustomRoutes.isEnabled = custom
        mCustomRoutes.alpha = if (custom) 1f else 0.5f
    }

    private fun updateAppsSummary() {
        val count = mProfile.mAllowedAppsVpn?.size ?: 0
        mAppsSummary.text = if (mProfile.mAllowedAppsVpnAreDisallowed)
            getString(R.string.wireguard_apps_all)
        else
            getString(R.string.wireguard_apps_selected, count)
    }

    private fun save() {
        val text = mEditor.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.wireguard_config_missing, Toast.LENGTH_LONG).show()
            return
        }

        try {
            Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.wireguard_config_invalid, e.localizedMessage ?: e.toString()),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val routingMode = mRoutingMode.selectedItemPosition.coerceIn(0, 2)
        val routes = mCustomRoutes.text.toString().trim()
        if (routingMode == 2) {
            if (routes.isEmpty()) {
                Toast.makeText(this, R.string.wireguard_routes_missing, Toast.LENGTH_LONG).show()
                return
            }
            for (route in routes.split(Regex("[,\\s]+"))) {
                if (route.isEmpty())
                    continue
                try {
                    InetNetwork.parse(route)
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        getString(R.string.wireguard_routes_invalid, route),
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }
        }

        if (!mProfile.mAllowedAppsVpnAreDisallowed && mProfile.mAllowedAppsVpn.isNullOrEmpty()) {
            Toast.makeText(this, R.string.wireguard_no_apps_selected, Toast.LENGTH_LONG).show()
            return
        }

        mProfile.mWireguardConfig = text
        mProfile.mWireguardRoutingMode = routingMode
        mProfile.mWireguardCustomRoutes = routes
        mProfile.addChangeLogEntry("WireGuard configuration edited")
        ProfileManager.saveProfile(this, mProfile)

        val result = Intent()
        result.putExtra(VpnProfile.EXTRA_PROFILEUUID, mProfile.getUUIDString())
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
