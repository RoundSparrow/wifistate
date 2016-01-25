package net.orleaf.android.wifistate.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import net.orleaf.android.wifistate.core.preferences.WifiStatePreferences;

/**
 * ネットワーク疎通監視サービス
 */
public class WifiStatePingService extends Service {
    public static final String EXTRA_TARGET = "target";

    private static final boolean TESTMODE = false;

    private static ComponentName mService;

    private BroadcastReceiver mScreenReceiver;

    private String mTarget;         // 疎通監視先ホスト
    private Thread mThread = null;  // 処理スレッド

    public PingStatus pingStatus = new PingStatus();


    @Override
    public void onCreate() {
        super.onCreate();

        // 画面ON/OFF監視
        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiState.DEBUG) Log.d(WifiState.TAG, "received intent: " + intent.getAction());
                if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                    // 画面ONで開始
                    startPing();
                } if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                    // 画面OFFで停止
                    stopThread();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);

        // 監視先ホスト取得
        String target = WifiStatePreferences.getPingTarget(this);
        if (target != null && target.length() > 0) {
            mTarget = target;
        } else {
            // 監視先未設定の場合は指定されたホスト(ゲートウェイ)を使用する
            mTarget = intent.getStringExtra(EXTRA_TARGET);
        }

        // 画面ONなら監視開始
        boolean screenOn = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            screenOn = (Boolean) PowerManager.class.getMethod("isScreenOn").invoke(pm);
        } catch (Exception e) {
            // 分からない場合はONとみなす。
        }
        if (screenOn) {
            startPing();
        }
    }

    /**
     * 監視開始
     */
    private void startPing() {
        if (WifiState.DEBUG) Log.d(WifiState.TAG, "Target: " + mTarget);
        if (mTarget != null) {
            pingStatus.mReachable = true;
            pingStatus.mNumPing = 0;
            pingStatus.mNumOk = 0;
            pingStatus.mNumNg = 0;
            pingStatus.mNumFail = 0;
            startThread();
        } else {
            // ターゲット未設定の場合は停止
            stopThread();
        }
    }

    /**
     * 監視スレッド開始
     */
    private void startThread() {
        if (mThread == null) {
            mThread = new PingThread();
            mThread.start();
        }
        // スレッド起動済みの場合は何もしない。
    }

    /**
     * 監視スレッド停止
     */
    private void stopThread() {
        mThread = null;
    }

    /**
     * ネットワーク疎通監視結果通知
     */
    private void notifyReachability(boolean reachable) {
        Intent intent = new Intent(this, WifiStateReceiver.class);
        intent.setAction(WifiStateReceiver.ACTION_REACHABILITY);
        intent.putExtra(WifiStateReceiver.EXTRA_REACHABLE, reachable);
        intent.putExtra(WifiStateReceiver.EXTRA_COUNT_OK, pingStatus.mNumOk);
        intent.putExtra(WifiStateReceiver.EXTRA_COUNT_NG, pingStatus.mNumNg);
        intent.putExtra(WifiStateReceiver.EXTRA_COUNT_TOTAL, pingStatus.mNumPing);
        sendBroadcast(intent);
    }

    /**
     * ネットワーク疎通監視失敗通知
     *
     * @param fail 連続失敗回数
     */
    private void notifyFail(int fail) {
        Intent intent = new Intent(this, WifiStateReceiver.class);
        intent.setAction(WifiStateReceiver.ACTION_PING_FAIL);
        intent.putExtra(WifiStateReceiver.EXTRA_FAIL, fail);
        sendBroadcast(intent);
    }

    public void onDestroy() {
        stopThread();

        // 画面ON/OFF監視停止
        if (mScreenReceiver != null) {
            unregisterReceiver(mScreenReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * ネットワーク疎通監視処理スレッド
     */
    private class PingThread extends Thread {
        @Override
        public void run() {
            if (WifiState.DEBUG) Log.d(WifiState.TAG, "Thread started. (" + getId() + ")");
            while (this == mThread) {
                boolean reachable = false;
                int ntry = WifiStatePreferences.getPingRetry(WifiStatePingService.this) + 1;
                int timeout = WifiStatePreferences.getPingTimeout(WifiStatePingService.this);

                if (WifiState.DEBUG) Log.d(WifiState.TAG, "Pinging: " + mTarget + " try " + ntry + " timeout " + timeout);

                int count;
                for (count = 0; count < ntry; count++) {
                    pingStatus.mNumPing++;
                    if (TESTMODE) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        reachable = !pingStatus.mReachable;
                    } else {
                        reachable = ping(mTarget, timeout);
                    }
                    if (reachable) {
                        pingStatus.mNumOk++;
                    } else {
                        pingStatus.mNumNg++;

                    }
                    // only update Android Notification if it's a change in reachability
                    // ToDo: Make this an Advanced setting for user
                    //if (reachable != pingStatus.mReachable) {
                        notifyReachability(reachable);
                    //}
                    pingStatus.mReachable = reachable;

                    // pull new target to try
                    // If it is a multi list, hostListOn should have been ++ from previous lookup
                    if (WifiStatePreferences.hostListOn > 0)
                        mTarget = WifiStatePreferences.getPingTarget(getApplicationContext());

                    if (reachable) {
                        break;
                    }
                }
                if (count == ntry) {
                    // リトライオーバー
                    pingStatus.mNumFail++;
                    notifyFail(pingStatus.mNumFail);
                } else {
                    // 成功したら失敗回数をリセット
                    pingStatus.mNumFail = 0;
                }
                try {
                    Thread.sleep(WifiStatePreferences.getPingInterval(WifiStatePingService.this) * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (WifiState.DEBUG) Log.d(WifiState.TAG, "Thread stoppped. (" + getId() + ")");
        }

        /**
         * ping
         */
        private boolean ping(String target, int timeout) {
            boolean result = false;

            if (false) {
                // 事前にアドレスを取得する
                InetAddress inetAddress = null;
                try {
                    inetAddress = InetAddress.getByName(target);
                } catch (UnknownHostException e) {
                    Log.e(WifiState.TAG, "name resolution failed: " + target);
                    e.printStackTrace();
                }
                target = inetAddress.getHostAddress();
            }
            if (target != null) {
                try {
                    // BUG ToDo: the timeout is not used when doing the DNS lookup, the app default of www.google.com can take
                    //  55 seconds on tested device (Android 5.0 Blu Energy 2)
                    //  suggested target is 8.8.8.8 - and research how to force ping to timeout DNS lookup
                    String[] cmdLine = new String[] { "ping", "-c", "1", "-w", "" + timeout, target };
                    long pingStartWhen = System.currentTimeMillis();
                    Process process = Runtime.getRuntime().exec(cmdLine);
                    process.waitFor();
                    //String out = readAll(process.getInputStream());
                    //String err = readAll(process.getErrorStream());
                    //process.destroy();
                    if (process.exitValue() == 0) {
                        if (WifiState.DEBUG) Log.d(WifiState.TAG, "ping success: " + target);
                        result = true;
                    } else {
                        Log.e(WifiState.TAG, "ping failed: " + target + " process took " + (System.currentTimeMillis() - pingStartWhen));
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return result;
        }

        /**
         * コマンド実行結果を取得する
         */
        @SuppressWarnings("unused")
        private String readAll(InputStream stream) throws IOException {
            String line;
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream), 1024);
            StringBuffer msg = new StringBuffer();
            while ((line = reader.readLine()) != null) {
                if (WifiState.DEBUG) Log.v(WifiState.TAG, " |" + line);
                msg.append(line + "\n");
            }
            return msg.toString().trim();
        }
    }


    /**
     * ネットワーク疎通監視サービス開始
     *
     * @param ctx
     * @param target 監視先ホスト
     */
    public static boolean startService(Context ctx, String target) {
        boolean result;

        Intent intent = new Intent(ctx, WifiStatePingService.class);
        intent.putExtra(EXTRA_TARGET, target);
        mService = ctx.startService(intent);
        if (mService == null) {
            Log.e(WifiState.TAG, "WifiStatePingService could not start!");
            result = false;
        } else {
            Log.d(WifiState.TAG, "Service started: " + mService);
            result = true;
        }
        return result;
    }

    /**
     * ネットワーク疎通監視サービス停止
     */
    public static void stopService(Context ctx) {
        if (mService != null) {
            Intent i = new Intent();
            i.setComponent(mService);
            boolean res = ctx.stopService(i);
            if (res == false) {
                Log.e(WifiState.TAG, "WifiStatePingService could not stop!");
            } else {
                Log.d(WifiState.TAG, "Service stopped: " + mService);
                mService = null;
            }
        }
    }

}
