/*
 * Copyright (c) 2026 OutHome contributors
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.wireguard;

import android.content.Context;
import android.util.Log;

import com.wireguard.android.backend.BackendException;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * Owns the userspace WireGuard backend and the currently running tunnel.
 * <p>
 * All WireGuard tunnels run in the application process. This makes the status handling with the
 * UI simple and avoids the OpenVPN service process, which is only able to run OpenVPN tunnels.
 */
public final class WireGuardManager {
    private static final String TAG = "WireGuardManager";
    /** WireGuard interface names are limited to 15 characters */
    public static final String TUNNEL_NAME = "outhome";

    private static volatile GoBackend backend;
    private static volatile Tunnel tunnel;
    private static volatile String activeProfileUuid;

    private WireGuardManager() {
    }

    /**
     * Cheap check used while importing configuration files.
     */
    public static boolean looksLikeConfig(String text) {
        if (text == null)
            return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("[interface]") && lower.contains("[peer]");
    }

    public static boolean isConnected() {
        if (backend == null || tunnel == null)
            return false;
        try {
            return backend.getState(tunnel) == Tunnel.State.UP;
        } catch (Exception e) {
            Log.w(TAG, "Unable to query WireGuard state", e);
            return false;
        }
    }

    public static boolean isConnected(VpnProfile profile) {
        return isConnected() && activeProfileUuid != null
                && activeProfileUuid.equals(profile.getUUIDString());
    }

    public static String getActiveProfileUuid() {
        return activeProfileUuid;
    }

    /**
     * Parses the profile's wg-quick configuration and brings the tunnel up.
     *
     * @throws Exception if the configuration is invalid or the backend fails to start the tunnel
     */
    public static synchronized void connect(Context context, VpnProfile profile) throws Exception {
        if (profile == null || !profile.isWireguard())
            throw new IllegalArgumentException("Not a WireGuard profile");

        if (profile.mWireguardConfig == null || profile.mWireguardConfig.trim().isEmpty())
            throw new IllegalArgumentException(context.getString(R.string.wireguard_config_missing));

        Config config = Config.parse(new ByteArrayInputStream(
                profile.mWireguardConfig.getBytes(StandardCharsets.UTF_8)));

        if (backend == null)
            backend = new GoBackend(context.getApplicationContext());

        if (tunnel != null) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
            } catch (Exception e) {
                Log.w(TAG, "Unable to stop previous WireGuard tunnel", e);
            }
            tunnel = null;
            activeProfileUuid = null;
        }

        final String profileName = profile.getName();
        tunnel = new Tunnel() {
            @Override
            public String getName() {
                return TUNNEL_NAME;
            }

            @Override
            public void onStateChange(State newState) {
                Log.i(TAG, "WireGuard tunnel '" + profileName + "' is now " + newState);
            }
        };

        activeProfileUuid = profile.getUUIDString();
        try {
            backend.setState(tunnel, Tunnel.State.UP, config);
        } catch (Exception e) {
            tunnel = null;
            activeProfileUuid = null;
            throw e;
        }

        Context appContext = context.getApplicationContext();
        ProfileManager.setConnectedVpnProfile(appContext, profile);
        VpnStatus.setConnectedVPNProfile(profile.getUUIDString());
        VpnStatus.updateStateString("CONNECTED", profile.getName(),
                R.string.state_connected, ConnectionStatus.LEVEL_CONNECTED);
        VpnStatus.logInfo("WireGuard tunnel '" + profile.getName() + "' established");
    }

    public static synchronized void disconnect(Context context) {
        if (backend != null && tunnel != null) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
            } catch (Exception e) {
                VpnStatus.logException("WireGuard", e);
            }
        }
        tunnel = null;
        activeProfileUuid = null;

        Context appContext = context.getApplicationContext();
        ProfileManager.setConnectedVpnProfileDisconnected(appContext);
        VpnStatus.setConnectedVPNProfile(null);
        VpnStatus.updateStateString("DISCONNECTED", "",
                R.string.state_disconnected, ConnectionStatus.LEVEL_NOTCONNECTED);
    }

    /**
     * @return a user-facing description for backend failures
     */
    public static String describeError(Context context, Exception e) {
        if (e instanceof BackendException) {
            BackendException be = (BackendException) e;
            BackendException.Reason reason = be.getReason();
            switch (reason) {
                case VPN_NOT_AUTHORIZED:
                    return context.getString(R.string.wireguard_not_authorized);
                case DNS_RESOLUTION_FAILURE:
                    return context.getString(R.string.wireguard_dns_error,
                            be.getFormat().length > 0 ? be.getFormat()[0] : "");
                case TUN_CREATION_ERROR:
                    return context.getString(R.string.wireguard_tun_error);
                case TUNNEL_MISSING_CONFIG:
                    return context.getString(R.string.wireguard_config_missing);
                default:
                    return reason.name();
            }
        }
        String message = e.getMessage();
        return message != null ? message : e.toString();
    }
}
