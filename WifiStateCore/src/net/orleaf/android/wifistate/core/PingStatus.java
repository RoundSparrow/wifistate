package net.orleaf.android.wifistate.core;

/**
 * Created by Stephen A Gutknecht on 2016-01-24.
 */
public class PingStatus {
    public boolean mReachable;     // 直前の到達性
    public int mNumPing;           // ping実行回数(累計)
    public int mNumOk;             // ping成功回数(累計)
    public int mNumNg;             // ping失敗回数(累計)
    public int mNumFail;           // ping連続失敗回数
}
