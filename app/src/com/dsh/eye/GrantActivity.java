package com.dsh.eye;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * 只干一件事的透明页面：换 `MediaProjection` 的授权。
 *
 * ## 为什么要单独一个 Activity
 *
 * 系统那个「开始录制或投射」弹窗只能从 **Activity** 里 `startActivityForResult` 拉起
 * （Service 直接 startActivity 拿不到 `onActivityResult`）。而授权发生在两个场景：
 * 用户点设置页那一行、用户点悬浮球 —— 两个都可能在没有现成 Activity 的时候发生。
 *
 * 所以这里起一个无界面 Activity：`onCreate` 拉起系统弹窗，`onActivityResult` 把
 * `resultCode + data` 交给 {@link EyeService#grantScreen}，然后立刻 `finish()`。
 * 用户从头到尾只看到系统那一个弹窗，看不到这个页面。
 */
public class GrantActivity extends Activity {

    private static final int REQ_GRANT = 0x5C01;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            MediaProjectionManager m = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_GRANT);
        } catch (Throwable e) {
            EyeLog.w("screen", "拉不起授权弹窗：" + e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_GRANT) {
            EyeService svc = EyeService.peek();
            if (svc != null) svc.grantScreen(res, data);
            else EyeLog.w("screen", "授权回来了但服务不在");
            finish();
        }
    }
}
