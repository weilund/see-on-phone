package com.dsh.eye;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 屏幕帧源：把「手机屏幕」变成和相机一样的东西 —— 一串 JPEG（默认 5fps）。
 *
 * ## 为什么是「帧源」而不是「截一张图」
 *
 * 相机那条链路（{@link EyeService#onJpeg} → {@link EyeService#sampleCallFrame} →
 * {@link CallCapture} 5fps → {@link Pipeline} 挑帧 → {@link CallStream} 封箱）已经是
 * 完整的「连续看 + 低频说一句」。屏幕只要**产出同样形状的 JPEG**，后面一个字都不用改。
 *
 * 所以这里不生产「一张截图」，而是持续产帧。单次看图（`look_screen`）复用最新那一帧；
 * 陪伴模式（悬浮球）复用同一个回调。
 *
 * ## 为什么缩到 720 宽
 *
 * 原生 1080×2400 每帧编码 JPEG 要 ~40ms，5fps 就吃掉 20% 单核；缩到 720 宽后
 * 编码 ~15ms，而且模型看宫格也够（一格只占两百多像素）。原生分辨率只在
 * 「真要看清楚一行小字」时才需要 —— 那是 P2 的局部放大，不是这里的默认。
 *
 * ## 授权只做一次
 *
 * **`MediaProjection` 的 token 是一次性的**（Android 14 起明确如此，API 34 实测会抛
 * `SecurityException: Media projections require a foreground service of type
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`，而 `stop()` 之后再 `getMediaProjection`
 * 也不认旧 token）。所以这里的设计是：
 *
 * - `stop()` 之后必须重新走一次系统弹窗，`resultCode + data` 不能复用到下一段；
 * - 因此**能不停就不停**：单次看图的采集会保留一段闲置时间（见 `idleMs()`），
 *   由调用方在长期不用时回收。
 */
final class Screen {

    /** 帧的去处。回调都在采集线程上，实现方不要在主线程上做重活。 */
    interface Sink {
        /** 一帧屏幕 JPEG（已缩到 {@code outW} 宽）。 */
        void onScreenJpeg(byte[] jpg);
        /** 这一刻屏幕是黑的（`FLAG_SECURE` 界面 / 锁屏）：别把它当正常画面发出去。 */
        void onScreenBlocked();
    }

    private static final String TAG = "screen";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private Sink sink;

    /** 最近一帧（`look_screen` / `/screen.jpg` 直接取它，不必等下一帧）。 */
    private volatile byte[] latest;
    private volatile long latestAt;
    private volatile boolean running;
    /** 授权凭证：系统弹窗给的那一对，`start()` 每次都拿它换 projection。 */
    private int grantResult;
    private Intent grantData;
    /** 上次有人取帧的时间（闲置回收用，见 `idleMs()`）。 */
    private volatile long lastUseAt;

    private int fps = 5;
    private long lastAt = -1;
    private int outW;
    private int outH;
    private int outDpi;

    /** 授权 Intent（系统那个「开始录制或投射」弹窗）。 */
    static Intent intent(Context c) {
        MediaProjectionManager m = (MediaProjectionManager)
            c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return m.createScreenCaptureIntent();
    }

    /** 现在能不能直接取帧。= 采集活着（token 不能跨 `stop()` 复用，见类注释）。 */
    boolean hasGrant() { return running; }

    /** 记一次使用：调用方每次取帧都 touch 一下，`idleMs()` 才不会涨。 */
    void touch() { lastUseAt = System.currentTimeMillis(); }

    /** 距上次使用多久（毫秒）。陪伴模式下调用方不看它 —— 那是一直在用的。 */
    long idleMs() { return System.currentTimeMillis() - lastUseAt; }

    boolean isRunning() { return running; }

    /** 最新一帧（可能为 null —— 刚开、或者屏幕一直没变到能出帧）。 */
    byte[] latest() { return latest; }

    long latestAt() { return latestAt; }

    /**
     * 开始产帧。
     *
     * @param maxW 输出宽度上限；≤0 用 720
     * @return true = 已经在产帧；false = 授权无效 / 系统拒绝（调用方提示重新授权）
     */
    boolean start(Context c, int resultCode, Intent data, Sink sink, int fps, int maxW) {
        stop();
        this.sink = sink;
        this.fps = Math.max(1, fps);
        try {
            MediaProjectionManager m = (MediaProjectionManager)
                c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            if (projection == null) {
                EyeLog.w(TAG, "getMediaProjection 返回 null");
                android.util.Log.w("SopScreen", "getMediaProjection 返回 null");
                return false;
            }
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int sw = Math.max(1, dm.widthPixels);
            int sh = Math.max(1, dm.heightPixels);
            outW = Math.min(maxW <= 0 ? 720 : maxW, sw);
            outW &= ~1;                                  // 偶数：编码器与部分 GPU 都更省事
            outH = Math.max(2, Math.round((float) sh * outW / sw)) & ~1;
            outDpi = dm.densityDpi;

            thread = new HandlerThread("sop-screen");
            thread.start();
            handler = new Handler(thread.getLooper());

            reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(listener, handler);
            display = projection.createVirtualDisplay("sop-screen", outW, outH, outDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
            projection.registerCallback(projectionCb, handler);
            running = true;
            this.grantResult = resultCode;
            this.grantData = data;     // 只有真起来了才记：失败留着会骗人
            touch();
            android.util.Log.w("SopScreen", "开始产帧 " + outW + "x" + outH + " @" + fps + "fps");
            EyeLog.w(TAG, "开始产帧 " + outW + "x" + outH + " @" + fps + "fps");
            return true;
        } catch (Throwable e) {
            EyeLog.w(TAG, "启动失败：" + e);
            android.util.Log.w("SopScreen", "启动失败：" + e, e);
            stop();
            return false;
        }
    }

    /** 停：释放虚拟屏与读帧器，**不动授权**（下次 `start()` 还能用同一对凭证）。 */
    void stop() {
        running = false;
        try {
            if (display != null) display.release();
        } catch (Throwable ignored) { }
        display = null;
        try {
            if (reader != null) {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
            }
        } catch (Throwable ignored) { }
        reader = null;
        try {
            if (projection != null) {
                projection.unregisterCallback(projectionCb);
                projection.stop();
            }
        } catch (Throwable ignored) { }
        projection = null;
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) { }
        }
        thread = null;
        handler = null;
        latest = null;
        latestAt = 0;
        lastAt = -1;
        // token 停了就作废（Android 14 起不能再用），别让 hasGrant() 说谎
        grantData = null;
        grantResult = 0;
    }

    private final MediaProjection.Callback projectionCb = new MediaProjection.Callback() {
        @Override public void onStop() {
            // 系统自己收回了（用户点了「停止共享」/ 另一个 App 抢了）：安静地停。
            EyeLog.w(TAG, "系统停止投射");
            stop();
        }
    };

    /**
     * 每来一帧走这里。**在采集线程上**：只做解码 + 节流 + 编码。
     *
     * `acquireLatestImage()` 这里不用 `acquireNextImage`：屏幕静止时帧会堆，
     * 拿最旧的只会让我们多编码几张一样的，模型那边还得多付 token。
     */
    private final ImageReader.OnImageAvailableListener listener = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null || !running) return;
                long now = SystemClock.elapsedRealtime();
                if (lastAt >= 0 && now - lastAt < 1000L / fps) return;   // 节流到 fps
                lastAt = now;
                Bitmap bmp = toBitmap(img);
                if (bmp == null) return;
                boolean blank = looksBlank(bmp);
                if (blank) {
                    Sink s = sink;
                    if (s != null) s.onScreenBlocked();
                    return;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
                byte[] jpg = out.toByteArray();
                latest = jpg;
                latestAt = now;
                Sink s = sink;
                if (s != null) s.onScreenJpeg(jpg);
            } catch (Throwable e) {
                EyeLog.w(TAG, "取帧失败：" + e);
            } finally {
                if (img != null) {
                    try { img.close(); } catch (Throwable ignored) { }
                }
            }
        }
    };

    /** `Image` → `Bitmap`。有些设备的 `rowStride` 比宽度大，先按 stride 建再裁。 */
    private static Bitmap toBitmap(Image img) {
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int ps = Math.max(1, p.getPixelStride());
            int rs = Math.max(ps, p.getRowStride());
            int strideW = rs / ps;
            int h = img.getHeight();
            if (strideW <= 0 || h <= 0) return null;
            Bitmap raw = Bitmap.createBitmap(strideW, h, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(buf);
            if (strideW == img.getWidth()) return raw;
            Bitmap cut = Bitmap.createBitmap(raw, 0, 0, img.getWidth(), h);
            raw.recycle();
            return cut;
        } catch (Throwable e) {
            EyeLog.w(TAG, "解码屏幕帧失败：" + e);
            return null;
        }
    }

    /**
     * 是不是全黑（银行 App / 密码键盘 / 锁屏这类 `FLAG_SECURE` 界面投出来就是黑的）。
     *
     * 只看 9 个采样点亮度：够快（几微秒），也够准 —— 正常画面不可能 9 个点都 < 8。
     */
    private static boolean looksBlank(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            if (w < 3 || h < 3) return false;
            int[] pts = new int[9];
            int i = 0;
            for (int gy = 1; gy <= 3; gy++) {
                for (int gx = 1; gx <= 3; gx++) {
                    pts[i++] = b.getPixel(w * gx / 4, h * gy / 4);
                }
            }
            for (int c : pts) {
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, bl = c & 0xff;
                if (r + g + bl > 24) return false;      // 有一个点不是黑的：当正常画面
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    int width() { return outW; }

    int height() { return outH; }

    int grantResult() { return grantResult; }

    Intent grantData() { return grantData; }
}
