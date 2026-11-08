/*
 * Copyright (c) 2026 OutHome contributors
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.wireguard.config.Config
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import java.io.ByteArrayInputStream

/**
 * Simple raw configuration editor for WireGuard profiles.
 */
class WireGuardConfigActivity : BaseActivity() {
    private lateinit var mProfile: VpnProfile
    private lateinit var mEditor: EditText

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

        setTitle(R.string.wireguard_config_title)
        val rootView = layoutInflater.inflate(R.layout.wireguard_config, null)
        setUpEdgeEdgeInsetsListener(rootView)
        setContentView(rootView)

        mEditor = findViewById(R.id.wireguard_config_text)
        mEditor.setText(profile.mWireguardConfig)

        findViewById<Button>(R.id.wireguard_config_save).setOnClickListener { save() }
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

        mProfile.mWireguardConfig = text
        mProfile.addChangeLogEntry("WireGuard configuration edited")
        ProfileManager.saveProfile(this, mProfile)

        val result = Intent()
        result.putExtra(VpnProfile.EXTRA_PROFILEUUID, mProfile.getUUIDString())
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
