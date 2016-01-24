package net.orleaf.android.wifistate.core;

import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import net.orleaf.android.wifistate.core.preferences.WifiStatePreferences;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;


public class NetworkStateInfo {
    enum States {
        STATE_DISABLED,
        STATE_WIFI_1,           // no use
        STATE_WIFI_ENABLING,
        STATE_WIFI_ENABLED,
        STATE_WIFI_SCANNING,
        STATE_WIFI_CONNECTING,
        STATE_WIFI_COMPLETED,
        STATE_WIFI_OBTAINING_IPADDR,
        STATE_WIFI_CONNECTED,
        STATE_WIFI_CONNECTED_NO_INTERNET,
        STATE_MOBILE_CONNECTING,
        STATE_MOBILE_CONNECTED
    };

    private final Context mCtx;
    private String mNetworkName;
    private States mState = States.STATE_DISABLED;
    private String mStateDetail = null;

    // Wi-Fi state
    private int mWifiState = 0;
    private boolean mSupplicantConnected = false;
    private SupplicantState mSupplicantState = null;
    private NetworkInfo mWifiNetworkInfo = null;

    // Mobile network state
    private int mDataConnectionState = TelephonyManager.DATA_DISCONNECTED;
    private NetworkInfo mDataNetworkInfo = null;

    /**
     * Constructor
     *
     * @param ctx
     */
    public NetworkStateInfo(Context ctx) {
        mCtx = ctx;
        mState = States.STATE_DISABLED;
        mNetworkName = null;
    }

    /**
     * 状態を更新する
     *
     * @return true:変更あり false:変更なし
     */
    public boolean update() {
        WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
        TelephonyManager tm = (TelephonyManager) mCtx.getSystemService(Context.TELEPHONY_SERVICE);
        Resources res = mCtx.getResources();

        States newState = mState;
        String newStateDetail = mStateDetail;

        // Wi-Fiの状態を取得
        mWifiState = wm.getWifiState();
        WifiInfo wifiInfo = wm.getConnectionInfo();
        mSupplicantState = wifiInfo.getSupplicantState();
        if (mSupplicantState != SupplicantState.DISCONNECTED) {
            mSupplicantConnected = true;
        }
        mWifiNetworkInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        // モバイルネットワークの状態を取得
        mDataConnectionState = tm.getDataState();
        mDataNetworkInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

        if (mWifiState == WifiManager.WIFI_STATE_DISABLING ||
                mWifiState == WifiManager.WIFI_STATE_DISABLED) {
            // clear wifi state
            //wifiNetworkInfo = null;
            //supplicantConnected = false;
            //supplicantState = null;
            newState = States.STATE_DISABLED;
            newStateDetail = res.getString(R.string.state_unavailable);
        } else if (mWifiState == WifiManager.WIFI_STATE_ENABLING) {
            // -> enabled
            newState = States.STATE_WIFI_ENABLING;
            newStateDetail = res.getString(R.string.state_enabling);
        } else if (mWifiState == WifiManager.WIFI_STATE_ENABLED) {
            // enabling -> enabled
            if (mState.compareTo(States.STATE_WIFI_ENABLED) < 0) {
                newState = States.STATE_WIFI_ENABLED;
                newStateDetail = res.getString(R.string.state_enabled);
            }

            if (mWifiNetworkInfo != null && mWifiNetworkInfo.isAvailable() &&
                    mWifiNetworkInfo.getState() == NetworkInfo.State.CONNECTING &&
                    mWifiNetworkInfo.getDetailedState() == NetworkInfo.DetailedState.OBTAINING_IPADDR) {
                newState = States.STATE_WIFI_OBTAINING_IPADDR;
                newStateDetail = res.getString(R.string.state_obtaining_ipaddr);
            } else if (mWifiNetworkInfo != null && mWifiNetworkInfo.isAvailable() &&
                    mWifiNetworkInfo.getState() == NetworkInfo.State.CONNECTED &&
                    mWifiNetworkInfo.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
                if (mDataNetworkInfo.isAvailable()) {
                    newState = States.STATE_WIFI_CONNECTED;
                    newStateDetail = res.getString(R.string.state_connected);
                }
                else
                {
                    newState = States.STATE_WIFI_CONNECTED_NO_INTERNET;
                    newStateDetail = res.getString(R.string.state_connected_no_internet);
                }
            } else if (mSupplicantConnected && mSupplicantState != null) {
                if (mSupplicantState == SupplicantState.SCANNING) {
                    newState = States.STATE_WIFI_SCANNING;
                    newStateDetail = res.getString(R.string.state_scanning);
                } else if (mSupplicantState == SupplicantState.ASSOCIATING) {
                    newState = States.STATE_WIFI_CONNECTING;
                    newStateDetail = res.getString(R.string.state_associating);;
                } else if (mSupplicantState == SupplicantState.ASSOCIATED) {
                    newState = States.STATE_WIFI_CONNECTING;
                    newStateDetail = res.getString(R.string.state_associated);
                } else if (mSupplicantState == SupplicantState.FOUR_WAY_HANDSHAKE ||
                           mSupplicantState == SupplicantState.GROUP_HANDSHAKE) {
                    newState = States.STATE_WIFI_CONNECTING;
                    newStateDetail = res.getString(R.string.state_handshaking);
                } else if (mSupplicantState == SupplicantState.COMPLETED) {
                    newState = States.STATE_WIFI_COMPLETED;
                    newStateDetail = res.getString(R.string.state_handshake_completed);
                } else if (mSupplicantState == SupplicantState.DISCONNECTED) {
                    newState = States.STATE_WIFI_SCANNING;
                    newStateDetail = res.getString(R.string.state_disconnected);
                }
            }
        }

        // モバイルネットワーク状態取得
        if (WifiStatePreferences.getShowDataNetwork(mCtx)) {
            // ・Wi-Fi無効時、または
            // ・スキャン中消去設定が有効でスキャン中
            if (newState == States.STATE_DISABLED ||
                    (newState == States.STATE_WIFI_SCANNING && WifiStatePreferences.getClearOnScanning(mCtx))) {
                if (mDataConnectionState == TelephonyManager.DATA_CONNECTING) {
                    newState = States.STATE_MOBILE_CONNECTING;
                    newStateDetail = res.getString(R.string.state_mobile_connecting);
                } else if (mDataConnectionState == TelephonyManager.DATA_CONNECTED) {
                    newState = States.STATE_MOBILE_CONNECTED;
                    newStateDetail = res.getString(R.string.state_mobile_connected);
                } else {
                    newState = States.STATE_DISABLED;
                    newStateDetail = res.getString(R.string.state_unavailable);
                }
            }
        }

        if (newState == null) {
            // no change
            if (WifiState.DEBUG) Log.d(WifiState.TAG, "State not recognized.");
            return false;
        }
        if (newState == mState && mStateDetail != null && newStateDetail.equals(mStateDetail)) {
            // 状態変更なし
            return false;
        }

        if (WifiState.DEBUG) Log.d(WifiState.TAG, "=>[" + newState + "] " + newStateDetail);
        mState = newState;
        mStateDetail = newStateDetail;

        // ネットワーク名を取得
        mNetworkName = null;
        if (newState.compareTo(States.STATE_WIFI_SCANNING) > 0 && /*スキャン完了済*/
                newState.compareTo(States.STATE_WIFI_CONNECTED) <= 0 &&
                wifiInfo != null) {
            // SSID
            mNetworkName = wifiInfo.getSSID();
        }
        else if (newState.compareTo(States.STATE_MOBILE_CONNECTING) >= 0) {
            // APN
            mNetworkName = mDataNetworkInfo.getExtraInfo();
        }
        // ToDo: what if States.STATE_MOBILE_CONNECTED ?

        return true;
    }

    /**
     * 消去可能な状態かどうか
     *
     * @param ctx コンテキスト
     * @param states 状態
     * @return true:消去可能
     */
    public boolean isClearableState() {
        if (WifiStatePreferences.getClearOnDisabled(mCtx) && mState == States.STATE_DISABLED) {
            // ネットワーク無効時は表示しない
            return true;
        }
        if (WifiStatePreferences.getClearOnScanning(mCtx) && isScanning()) {
            // スキャン中は表示しない
            return true;
        }
        if (WifiStatePreferences.getClearOnConnected(mCtx) && isConnected()) {
            // 接続完了時は表示しない
            return true;
        }
        return false;
    }

    /**
     * アイコンを取得
     *
     * @param state 状態
     * @return アイコンのリソースID
     */
    public int getIcon() {
        if (mState == States.STATE_DISABLED) {
            return R.drawable.state_0;
        } else if (mState == States.STATE_WIFI_1) {
            return R.drawable.state_w1;
        } else if (mState == States.STATE_WIFI_ENABLING) {
            return R.drawable.state_w2;
        } else if (mState == States.STATE_WIFI_ENABLED) {
            return R.drawable.state_w3;
        } else if (mState == States.STATE_WIFI_SCANNING) {
            return R.drawable.state_w4;
        } else if (mState == States.STATE_WIFI_CONNECTING) {
            return R.drawable.state_w5;
        } else if (mState == States.STATE_WIFI_COMPLETED) {
            return R.drawable.state_w6;
        } else if (mState == States.STATE_WIFI_OBTAINING_IPADDR) {
            return R.drawable.state_w7;
        } else if (mState == States.STATE_WIFI_CONNECTED) {
            return R.drawable.state_w8;
        } else if (mState == States.STATE_MOBILE_CONNECTING) {
            return R.drawable.state_m4;
        } else if (mState == States.STATE_MOBILE_CONNECTED) {
            return R.drawable.state_m8;
        }
        return 0;
    }

    /**
     * ネットワークの状態を取得
     */
    public States getState() {
        return mState;
    }

    /**
     * スキャン中かどうか
     */
    public boolean isScanning() {
        return (mState.equals(States.STATE_WIFI_SCANNING));
    }

    /**
     * ネットワークに接続中かどうか
     */
    public boolean isConnected() {
        if (mState.equals(States.STATE_WIFI_SCANNING))
        {
           // WiFi can be scanning at the same time as Mobile connected
            return isMobileConnected();
        }
        else {
            return (mState.equals(States.STATE_MOBILE_CONNECTED) || mState.equals(States.STATE_WIFI_CONNECTED));
        }
    }


    public boolean isMobileConnected()
    {
        return (mDataConnectionState == TelephonyManager.DATA_CONNECTED);
    }

    /**
     * Wi-Fiに接続中かどうか
     */
    public boolean isWifiConnected() {
        return (mState.equals(States.STATE_WIFI_CONNECTED));
    }

    /**
     * ネットワーク名
     */
    public String getNetworkName() {
        return mNetworkName;
    }

    /**
     * メッセージ
     */
    public String getStateMessage() {
        // Version 1.4 showed:
        String message = mStateDetail;
        if (mNetworkName != null) {
            message += " (" + mNetworkName + ")";
        }

        // NOTE: Github GPL source only had version 1.4
        // The Google Play Store has version 1.5 with this comment "- Show SSID and IP address on status bar."
        // Version 1.5 change
        switch (getState())
        {
            case STATE_WIFI_CONNECTED:
                message = "IP:" + getLocalIpAddress();
                break;
            case STATE_MOBILE_CONNECTED:
                message = "IP:" + getMobileLocalIpAddress();
                break;
            case STATE_WIFI_SCANNING:
                if (isMobileConnected())
                    message = "WiFi Scanning, Data IP:" + getMobileLocalIpAddress();
                else
                    message = "WiFi Scanning, Data disconnected";
                break;
        }

        return message;
    }

    public String getNetworkMessage() {
        if (isWifiConnected())
        {
            return "Wi-Fi: " + getNetworkName();
        }
        else
        {
            switch (getState()) {
                case STATE_DISABLED:
                // Behavior of 1.5 is to show the App title when not connected
                    return mCtx.getResources().getString(R.string.app_name);
                case STATE_MOBILE_CONNECTED:
                    return "Mobile Data: " + mNetworkName;
                case STATE_WIFI_SCANNING:
                    // WiFi can be scanning at same time as mobile connected
                    if (isMobileConnected())
                        return "Wi-Fi scanning, Mobile connected";
                    else
                        return "Wi-Fi scanning";
                default:
                    return "??? " + mState;
            }
        }
    }

    /**
     * ネットワークの状態を取得
     */
    public String getDetail() {
        return mStateDetail;
    }


    private boolean checkWifiOnAndConnected() {
        WifiManager wifiMgr = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
        if (wifiMgr.isWifiEnabled()) { // WiFi adapter is ON
            WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
            if( wifiInfo.getNetworkId() == -1 ){
                return false; // Not connected to an access-Point
            }
            return true;      // Connected to an Access Point
        } else {
            return false; // WiFi adapter is OFF
        }
    }

    /**
     * IPアドレスを取得
     */
    public String getLocalIpAddress() {
        WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);

        if (mState.equals(States.STATE_WIFI_CONNECTED)) {
            // ToDo: test on Static IP address device to be sure this isn't literally only DHCP
            DhcpInfo dhcpInfo = wm.getDhcpInfo();
            if (dhcpInfo.ipAddress != 0) {
                return int2IpAddress(dhcpInfo.ipAddress);
            }
        }
        else
        {
            if (mState.equals(States.STATE_MOBILE_CONNECTED)) {
                return getMobileLocalIpAddress();
            }
        }

        return null;
    }


    public String getMobileLocalIpAddress()
    {
        try {
            // ToDo: How does this behave on Ethernet + Mobile + WiFi devices?
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        return inetAddress.getHostAddress().toString();
                    }
                }
            }
            return null;
        } catch (SocketException ex) {
            return null;
        }
    }

    /**
     * ゲートウェイアドレスを取得
     */
    public String getGatewayIpAddress() {
        WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);

        if (mState.equals(States.STATE_WIFI_CONNECTED)) {
            DhcpInfo dhcpInfo = wm.getDhcpInfo();
            if (dhcpInfo.gateway != 0) {
                return int2IpAddress(dhcpInfo.gateway);
            }
        }
        return null;
    }

    /**
     * intをIPアドレス文字列に変換
     */
    private String int2IpAddress(int ip) {
        return
            ( ip        & 0xff) + "." +
            ((ip >>  8) & 0xff) + "." +
            ((ip >> 16) & 0xff) + "." +
            ((ip >> 24) & 0xff);
    }

}
