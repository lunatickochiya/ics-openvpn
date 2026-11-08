/*
 * Copyright (c) 2026 Kizuna contributors
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.wireguard;

import android.content.Context;
import android.util.Log;

import com.wireguard.android.backend.BackendException;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Interface;
import com.wireguard.config.ParseException;
import com.wireguard.config.Peer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    public static final String TUNNEL_NAME = "kizuna";

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
        config = applyProfileOptions(context, profile, config);

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

    /**
     * Applies the per-profile route and application selections on top of the imported
     * wg-quick configuration.
     */
    private static Config applyProfileOptions(Context context, VpnProfile profile, Config config)
            throws BadConfigException, IllegalArgumentException {
        Interface iface = config.getInterface();
        Interface.Builder ib = new Interface.Builder()
                .setKeyPair(iface.getKeyPair())
                .addAddresses(iface.getAddresses())
                .addDnsServers(iface.getDnsServers())
                .addDnsSearchDomains(iface.getDnsSearchDomains());

        if (iface.getListenPort().isPresent())
            ib.setListenPort(iface.getListenPort().get());
        if (iface.getMtu().isPresent())
            ib.setMtu(iface.getMtu().get());

        // Per-application selection. If the user did not configure a selection, keep
        // IncludedApplications/ExcludedApplications from the imported configuration.
        boolean appFilter = !profile.mAllowedAppsVpnAreDisallowed
                || (profile.mAllowedAppsVpn != null && !profile.mAllowedAppsVpn.isEmpty());
        if (appFilter) {
            if (profile.mAllowedAppsVpnAreDisallowed) {
                ib.excludeApplications(profile.mAllowedAppsVpn);
            } else {
                if (profile.mAllowedAppsVpn == null || profile.mAllowedAppsVpn.isEmpty())
                    throw new IllegalArgumentException(
                            context.getString(R.string.wireguard_no_apps_selected));
                ib.includeApplications(profile.mAllowedAppsVpn);
            }
        } else {
            ib.includeApplications(iface.getIncludedApplications());
            ib.excludeApplications(iface.getExcludedApplications());
        }

        List<InetNetwork> customRoutes = profile.mWireguardRoutingMode == 2
                ? parseRoutes(context, profile.mWireguardCustomRoutes)
                : new ArrayList<>();

        Config.Builder cb = new Config.Builder().setInterface(ib.build());
        for (Peer peer : config.getPeers()) {
            Peer.Builder pb = new Peer.Builder().setPublicKey(peer.getPublicKey());

            Set<InetNetwork> allowedIps;
            if (profile.mWireguardRoutingMode == 2) {
                if (customRoutes.isEmpty())
                    throw new IllegalArgumentException(
                            context.getString(R.string.wireguard_routes_missing));
                allowedIps = new LinkedHashSet<>(customRoutes);
            } else if (profile.mWireguardRoutingMode == 1) {
                allowedIps = new LinkedHashSet<>();
                for (InetNetwork network : peer.getAllowedIps()) {
                    if (network.getMask() != 0)
                        allowedIps.add(network);
                }
            } else {
                allowedIps = peer.getAllowedIps();
            }
            pb.addAllowedIps(allowedIps);

            if (peer.getEndpoint().isPresent())
                pb.setEndpoint(peer.getEndpoint().get());
            if (peer.getPersistentKeepalive().isPresent())
                pb.setPersistentKeepalive(peer.getPersistentKeepalive().get());
            if (peer.getPreSharedKey().isPresent())
                pb.setPreSharedKey(peer.getPreSharedKey().get());
            cb.addPeer(pb.build());
        }
        return cb.build();
    }

    private static List<InetNetwork> parseRoutes(Context context, String routes)
            throws IllegalArgumentException {
        List<InetNetwork> parsed = new ArrayList<>();
        if (routes == null)
            return parsed;
        for (String route : routes.split("[,\\s]+")) {
            if (route.isEmpty())
                continue;
            try {
                parsed.add(InetNetwork.parse(route));
            } catch (ParseException e) {
                throw new IllegalArgumentException(
                        context.getString(R.string.wireguard_routes_invalid, route), e);
            }
        }
        return parsed;
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
