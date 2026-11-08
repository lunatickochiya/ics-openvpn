/*
 * Copyright (c) 2026 OutHome contributors
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.wireguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * Foreground service that keeps a WireGuard tunnel alive and shows its status notification.
 * <p>
 * The actual VPN interface is created by the WireGuard tunnel library's own {@code VpnService}.
 * This service only anchors the process and gives the user a place to disconnect the tunnel.
 */
public class WireGuardService extends Service {
    public static final String ACTION_CONNECT = "de.blinkt.openvpn.wireguard.action.CONNECT";
    public static final String ACTION_DISCONNECT = "de.blinkt.openvpn.wireguard.action.DISCONNECT";
    public static final String EXTRA_PROFILE_UUID = "de.blinkt.openvpn.wireguard.extra.PROFILE_UUID";

    private static final String TAG = "WireGuardService";
    private static final String CHANNEL_ID = "wireguard_status";
    private static final int NOTIFICATION_ID = 4006;

    private ExecutorService executor;

    public static void start(Context context, VpnProfile profile) {
        Intent intent = new Intent(context, WireGuardService.class);
        intent.setAction(ACTION_CONNECT);
        intent.putExtra(EXTRA_PROFILE_UUID, profile.getUUIDString());
        startServiceCompat(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, WireGuardService.class);
        intent.setAction(ACTION_DISCONNECT);
        startServiceCompat(context, intent);
    }

    /**
     * Tears the tunnel down immediately and removes the foreground service.
     * Use this when another VPN implementation is about to be started.
     */
    public static void stopNow(Context context) {
        context.stopService(new Intent(context, WireGuardService.class));
        WireGuardManager.disconnect(context);
    }

    private static void startServiceCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent);
        else
            context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Service was restarted by the system, recover the last connected profile
            VpnProfile last = ProfileManager.getLastConnectedProfile(this);
            if (last != null && last.isWireguard())
                return connect(last);
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action))
            return disconnect();
        if (ACTION_CONNECT.equals(action)) {
            String uuid = intent.getStringExtra(EXTRA_PROFILE_UUID);
            VpnProfile profile = uuid == null ? null : ProfileManager.get(this, uuid);
            if (profile == null || !profile.isWireguard()) {
                VpnStatus.logError(getString(R.string.wireguard_profile_not_found));
                stopSelf();
                return START_NOT_STICKY;
            }
            ProfileManager.updateLRU(this, profile);
            return connect(profile);
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private int connect(VpnProfile profile) {
        startForeground(NOTIFICATION_ID, buildNotification(
                getString(R.string.wireguard_connecting, profile.getName()), false));
        executor.execute(() -> {
            try {
                WireGuardManager.connect(this, profile);
                updateNotification(getString(R.string.wireguard_connected, profile.getName()));
            } catch (Exception e) {
                VpnStatus.logException("WireGuard", e);
                String error = WireGuardManager.describeError(this, e);
                VpnStatus.setConnectedVPNProfile(null);
                VpnStatus.updateStateString("DISCONNECTED", error,
                        R.string.state_disconnected, ConnectionStatus.LEVEL_NOTCONNECTED);
                ProfileManager.setConnectedVpnProfileDisconnected(this);
                stopForegroundCompat();
                stopSelf();
            }
        });
        return START_STICKY;
    }

    private int disconnect() {
        startForeground(NOTIFICATION_ID, buildNotification(
                getString(R.string.wireguard_disconnecting), true));
        executor.execute(() -> {
            WireGuardManager.disconnect(this);
            stopForegroundCompat();
            stopSelf();
        });
        return START_NOT_STICKY;
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null)
            manager.notify(NOTIFICATION_ID, buildNotification(text, false));
    }

    private Notification buildNotification(String text, boolean disconnecting) {
        PendingIntent contentIntent = null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            contentIntent = PendingIntent.getActivity(this, 0, launch, pendingFlags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle(getString(R.string.app))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent);

        if (!disconnecting) {
            Intent stopIntent = new Intent(this, WireGuardService.class);
            stopIntent.setAction(ACTION_DISCONNECT);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent stopPendingIntent =
                    PendingIntent.getService(this, 1, stopIntent, pendingFlags);
            builder.addAction(0, getString(R.string.wireguard_disconnect), stopPendingIntent);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.wireguard_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.wireguard_notification_channel_description));
            channel.setShowBadge(false);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null)
                manager.createNotificationChannel(channel);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE);
        else
            stopForeground(true);
    }

    @Override
    public void onDestroy() {
        if (WireGuardManager.isConnected())
            WireGuardManager.disconnect(this);
        if (executor != null)
            executor.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
