package net.orleaf.android.wifistate.core;

import java.util.Set;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

import net.orleaf.android.wifistate.core.WifiStateControlService;
import net.orleaf.android.wifistate.core.preferences.WifiStatePreferences;

public class WifiStateReceiver extends BroadcastReceiver {
    private static final int NOTIFICATIONID_ICON = 1;
    private static final String ACTION_CLEAR_NOTIFICATION = "net.orleaf.android.wifistate.CLEAR_NOTIFICATION";

    public static final String ACTION_REACHABILITY = "net.orleaf.android.wifistate.ACTION_RECHABLILITY";
    public static final String EXTRA_REACHABLE = "reachable";
    public static final String EXTRA_COUNT_OK = "ok";
    public static final String EXTRA_COUNT_NG = "ng";
    public static final String EXTRA_COUNT_TOTAL_OK = "total_ok";
    public static final String EXTRA_COUNT_TOTAL_NG = "total_ng";
    public static final String EXTRA_COUNT_TOTAL = "total";

    public static final String ACTION_PING_FAIL = "net.orleaf.android.wifistate.PING_FAIL";
    public static final String EXTRA_FAIL = "fail";

    private static NetworkStateInfo mNetworkStateInfo = null;
    private static PhoneStateListener mPhoneStateListener = null;
    private static boolean mReachable;

    /**
     * ネットワーク状態の変化によって通知アイコンを切り替える
     */
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (WifiState.DEBUG) logIntent(intent);

        if (!WifiStatePreferences.getEnabled(ctx)) {
            return;
        }

        // ネットワーク状態を取得
        if (mNetworkStateInfo == null) {
            mNetworkStateInfo = new NetworkStateInfo(ctx);
        }
        if (mPhoneStateListener == null && WifiStatePreferences.getShowDataNetwork(ctx)) {
            // 3G接続状態の監視を開始
            mPhoneStateListener = new PhoneStateListener() {
                @Override
                public void onDataConnectionStateChanged(int state) {
                    super.onDataConnectionStateChanged(state);
                    if (mNetworkStateInfo != null) {
                        updateState(ctx);
                    }
                }
            };
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
        }

        if (intent.getAction() != null) {
            if (intent.getAction().equals("android.intent.action.PACKAGE_REPLACED"/*Intent.ACTION_PACKAGE_REPLACED*/)) {
                if (intent.getData() == null ||
                    !intent.getData().equals(Uri.fromParts("package", ctx.getPackageName(), null))) {
                    return;
                }
            } else if (intent.getAction().equals(ACTION_REACHABILITY)) {
                android.util.Log.v("WifiState", "PING_REPORT");
                /*
                 * ネットワーク疎通監視結果通知 (ping毎に通知)
                 */
                if (mNetworkStateInfo.isConnected()) {  // 接続中のみ
                    boolean reachable = intent.getBooleanExtra(EXTRA_REACHABLE, true);
                    // Only if there is a change in reachable state
                    //if (reachable != mReachable) {
                        mReachable = reachable;
                        Integer notificaitonColor = null;
                        String message = mNetworkStateInfo.getStateMessage();
                        if (WifiState.DEBUG) {
                            int ok = intent.getIntExtra("ok", 0);
                            int total = intent.getIntExtra("total", 0);
                            message += " ping:" + ok + "/" + total;
                            if (reachable)
                            {
                                if (ok % 2 == 0) {
                                    notificaitonColor = Color.parseColor("#BFFC93");  // very light green, nearly white
                                    notificaitonColor = Color.parseColor("#3C8F00");  // very dark green, nearly black
                                    message += " ♡";
                                }
                                else {
                                    notificaitonColor = null;   // black
                                    message += " ♥";
                                }
                            }
                            else
                            {
                                message += " !!";
                                notificaitonColor = Color.parseColor("#FFC0CB");
                            }
                        }
                        if (mReachable) {
                            showNotificationIcon(ctx, mNetworkStateInfo.getIcon(), message, mNetworkStateInfo.getNetworkMessage(), notificaitonColor);
                        } else {
                            showNotificationIcon(ctx, R.drawable.state_warn, message, mNetworkStateInfo.getNetworkMessage(), notificaitonColor);
                        }
                    //}
                }
                return;
            } else if (intent.getAction().equals(ACTION_PING_FAIL)) {
                /*
                 * ネットワーク疎通監視失敗 (指定回数連続失敗時)
                 */
                if (WifiStatePreferences.getPingDisableWifiOnFail(ctx)) {
                    if (mNetworkStateInfo.isWifiConnected()) {
                        int wait = WifiStatePreferences.getPingDisableWifiPeriod(ctx);
                        WifiStateControlService.startService(ctx,
                                WifiStateControlService.ACTION_WIFI_REENABLE, wait);
                    }
                }
                return;
            } else if (intent.getAction().equals(ACTION_CLEAR_NOTIFICATION)) {
                /*
                 * 通知アイコン消去タイマ満了
                 */
                // 状態が変わっているかもしれないので再度チェックして消去可能なら消去
                if (mNetworkStateInfo.isClearableState()) {
                    clearNotification(ctx);
                    return;
                }
            } else if (intent.getAction().equals("android.net.conn.CONNECTIVITY_CHANGE"))
            {
                String outPile0 = "";
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    for (String key: extras.keySet()) {
                        outPile0 += " [" + key + "]: " + extras.get(key);
                    }
                }
                else {
                    outPile0 = "no Extras";
                }

                android.util.Log.i("WiFiState", "intent CONNECTIVITY_CHANGE " + outPile0);
            } else if (intent.getAction().equals("android.net.wifi.STATE_CHANGE"))
            {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if(info != null) {
                    if(info.isConnected()) {
                        // Do your work.
                        android.util.Log.i("WiFiState", "intent android.net.wifi.STATE_CHANGE " + info.getDetailedState() + " " + info.getState());
                        ;
                    }
                }
            }
        }

        updateState(ctx);
    }

    /**
     * ネットワーク状態情報の更新、および通知アイコンの反映
     */
    private void updateState(Context ctx) {
        if (mNetworkStateInfo.update()) {
            // 状態が変化したら通知アイコンを更新
            showNotificationIcon(ctx, mNetworkStateInfo.getIcon(), mNetworkStateInfo.getStateMessage(), mNetworkStateInfo.getNetworkMessage(), null);
            if (!WifiState.isLiteVersion(ctx)) {
                if (WifiStatePreferences.getPing(ctx) &&
                        (mNetworkStateInfo.getState().equals(NetworkStateInfo.States.STATE_WIFI_CONNECTED) ||
                                (mNetworkStateInfo.getState().equals(NetworkStateInfo.States.STATE_MOBILE_CONNECTED) &&
                                        WifiStatePreferences.getPingOnMobile(ctx) == true))) {
                    // ネットワーク疎通監視サービス開始
                    mReachable = true;
                    WifiStatePingService.startService(ctx, mNetworkStateInfo.getGatewayIpAddress());
                } else {
                    // ネットワーク疎通監視サービス停止
                    WifiStatePingService.stopService(ctx);
                }
            }
            if (mNetworkStateInfo.isClearableState()) {
                // 3秒後に消去するタイマ
                long next = SystemClock.elapsedRealtime() + 3000;
                Intent clearIntent = new Intent(ctx, WifiStateReceiver.class).setAction(ACTION_CLEAR_NOTIFICATION);
                AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, next, PendingIntent.getBroadcast(ctx, 0, clearIntent, 0));
            }
        }
    }

    /**
     * 状態をクリア
     *
     * @param ctx
     */
    public static void disable(Context ctx) {
        if (mPhoneStateListener != null) {
            // 3G接続状態の監視を停止
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mPhoneStateListener = null;
        }
        // ネットワーク状態情報を破棄
        mNetworkStateInfo = null;
    }

    /**
     * ステータスバーに通知アイコンをすべて表示 (テスト用)
     */
    public static void testNotificationIcon(Context ctx) {
        int[] icons = {
            R.drawable.state_w1,
            R.drawable.state_w2,
            R.drawable.state_w3,
            R.drawable.state_w4,
            R.drawable.state_w5,
            R.drawable.state_w6,
            R.drawable.state_w7,
            R.drawable.state_w8,
            R.drawable.state_m4,
            R.drawable.state_m8,
        };
        for (int i = 0; i < icons.length; i++) {
            NotificationManager notificationManager = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification notification = new Notification(icons[i],
                    ctx.getResources().getString(R.string.app_name), System.currentTimeMillis());
            Intent intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
            PendingIntent contentIntent = PendingIntent.getActivity(ctx, 0, intent, Intent.FLAG_ACTIVITY_NEW_TASK);
            //notification.setLatestEventInfo(ctx, ctx.getResources().getString(R.string.app_name),
            //        "State" + i, contentIntent);
            //notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notificationManager.notify(i, notification);
        }
    }

    /**
     * ステータスバーに通知アイコンを表示
     *
     * @param ctx
     * @param iconRes 表示するアイコンのリソースID
     * @param message 表示するメッセージ
     */
    public static void showNotificationIcon(Context ctx, int iconRes, String message, String title, Integer color) {
        NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent intent = new Intent(ctx, WifiStateLaunchReceiver.class);
        PendingIntent contentIntent = PendingIntent.getBroadcast(ctx, 0, intent, 0);

        if (color == null)
        {
            color = Notification.COLOR_DEFAULT;
            color = Color.BLACK;
        }

        Spannable wordtoSpan = new SpannableString(message);
        wordtoSpan.setSpan(new ForegroundColorSpan(color), 0, message.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        Notification notification = new Notification.Builder(ctx)
                .setContentTitle(title)
                .setContentText(wordtoSpan)
                .setSmallIcon(iconRes)
                // .setLargeIcon(aBitmap)
                .setContentIntent(contentIntent)
                .setColor(color)
                .build(); // available from API level 11 and onwards

        notification.flags = 0;

        if (!WifiStatePreferences.getClearable(ctx)) {
            notification.flags |= (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR);
        }
        notificationManager.notify(NOTIFICATIONID_ICON, notification);
    }

    /**
     * ステータスバーの通知アイコンを消去
     */
    public static void clearNotification(Context ctx) {
        NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATIONID_ICON);
        disable(ctx);
    }

    /**
     * インテントのログ採取
     */
    private static void logIntent(Intent intent) {
        Log.d(WifiState.TAG, "received intent: " + intent.getAction());

        Bundle extras = intent.getExtras();
        if (extras != null) {
            Set<String> keySet = extras.keySet();
            if (keySet != null) {
                Object[] keys = keySet.toArray();
                for (int i = 0; i < keys.length; i++) {
                    Object o = extras.get((String)keys[i]);
                    if (o != null) {
                        String className = "???";
                        className = o.getClass().getName();
                        Log.d(WifiState.TAG, "  " + (String)keys[i] + " = (" + className + ") " + o.toString());
                    }
                }
            }
        }
    }

    /**
     * ネットワーク状態の通知を開始/再開
     * 設定を変更した場合などに、明示的に表示を更新したいときに呼ぶ。
     */
    public static void startNotification(Context ctx) {
        if (WifiStatePreferences.getEnabled(ctx)) {
            // 空インテントを投げて強制的に更新
            Intent intent = new Intent().setClass(ctx, WifiStateReceiver.class);
            ctx.sendBroadcast(intent);
        } else {
            // 無効に設定された場合は消去
            clearNotification(ctx);
        }
        disable(ctx);
    }

}
