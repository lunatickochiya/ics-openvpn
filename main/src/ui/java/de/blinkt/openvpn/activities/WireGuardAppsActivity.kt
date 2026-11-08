/*
 * Copyright (c) 2026 Kizuna contributors
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.activities

import android.os.Bundle
import android.widget.Toast
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.fragments.Settings_Allowed_Apps
import java.util.HashSet

/**
 * Hosts the existing per-application selector for a WireGuard profile.
 */
class WireGuardAppsActivity : BaseActivity() {
    private var mProfileUUID: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mProfileUUID = intent.getStringExtra("$packageName.profileUUID")
        val profile = mProfileUUID?.let { ProfileManager.get(this, it) }
        if (profile == null || !profile.isWireguard) {
            Toast.makeText(this, R.string.wireguard_profile_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (profile.mAllowedAppsVpn == null)
            profile.mAllowedAppsVpn = HashSet()

        setTitle(R.string.wireguard_apps_title)
        setContentView(R.layout.wireguard_apps)

        val fragment = Settings_Allowed_Apps()
        fragment.arguments = Bundle().apply {
            putString("$packageName.profileUUID", mProfileUUID)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.wireguard_apps_container, fragment)
            .commit()
    }

    override fun onPause() {
        super.onPause()
        val profile = mProfileUUID?.let { ProfileManager.get(this, it) }
        if (profile != null)
            ProfileManager.saveProfile(this, profile)
    }
}
