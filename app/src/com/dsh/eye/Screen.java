package com.dsh.eye;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
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
 * ## 横屏与转屏
 *
 * 虚拟屏是启动那一刻按 `DisplayMetrics` 建的，**不会自己跟着转**：

 * 转屏/分屏/改分辨率时**不重建**（Android 14 上换虚拟屏会被系统判成投射结束，
 * 打断陪伴还得重新授权）：保持原虚拟屏。横屏时由 {@link #upright()} 把画面**转 90°
 * 立起来铺满竖屏画布**（先裁掉 letterbox 黑边），这样宫格里每格都是有效画面。
 * 想要"横屏也清楚"，重开一次「一起看屏幕」，那时按当前方向建：
 *
 * - 横屏输出抬到 1280 宽（竖屏仍是 720）—— 横屏本来就矮，压到 324 高就全糊了。
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
        /**
         * 系统把投射收回了（用户点了「停止共享」/ 权限被改 / 系统认为不该继续）。
         * 实现方要把"正在看"的状态也收掉，否则界面显示在看、实际早就没帧了。
         */
        default void onScreenStopped() { }
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
    /** 调用方给的宽度口径（竖屏用；横屏会自动抬到 1280，见 {@link #sizeFor}）。 */
    private int maxW = 720;
    /** 应用 Context：旋转后要重新读 `DisplayMetrics`。 */
    private Context appCtx;
    /** 盯着屏幕尺寸变化（转屏 / 分屏 / 改分辨率）—— 变了就得重建虚拟屏。 */
    private DisplayManager displayMgr;
    private DisplayManager.DisplayListener displayListener;

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
        this.maxW = maxW <= 0 ? 720 : maxW;
        this.appCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            MediaProjectionManager m = (MediaProjectionManager)
                c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            if (projection == null) {
                EyeLog.w(TAG, "getMediaProjection 返回 null");
                android.util.Log.w("SopScreen", "getMediaProjection 返回 null");
                return false;
            }
            thread = new HandlerThread("sop-screen");
            thread.start();
            handler = new Handler(thread.getLooper());

            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int[] wh = sizeFor(dm.widthPixels, dm.heightPixels, this.maxW);
            outW = wh[0];
            outH = wh[1];
            outDpi = dm.densityDpi;

            openDisplay();
            projection.registerCallback(projectionCb, handler);
            registerDisplayWatcher();
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

    /**
     * 输出尺寸：竖屏按调用方给的宽（默认 720），**横屏抬到 1280**。
     *
     * <p>为什么横屏要特殊对待：竖屏 1080×2400 缩到 720 宽还剩 1600 高；横屏 2400×1080
     * 按同样口径就只剩 324 高 —— 视频字幕、血条、网页正文在这个高度上全部糊成一团。
     * 横屏本来就是"看细节"的场景（看片、打游戏），所以给到 1280 宽（1280×576），
     * 拼进宫格后每格像素密度高 3 倍左右。
     */
    private static int[] sizeFor(int sw, int sh, int maxW) {
        int safeW = Math.max(1, sw);
        int safeH = Math.max(1, sh);
        int w;
        if (safeW >= safeH) w = Math.min(Math.max(maxW, 1280), safeW);       // 横屏：至少 1280
        else w = Math.min(Math.min(maxW <= 0 ? 720 : maxW, 720), safeW);     // 竖屏：硬顶 720
        w &= ~1;                                                         // 偶数：编码器更省事
        int h = Math.max(2, Math.round((float) safeH * w / safeW)) & ~1;
        return new int[] { w, h };
    }

    /** 建 ImageReader + VirtualDisplay（首次和「转屏重建」共用）。 */
    private void openDisplay() {
        reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(listener, handler);
        display = projection.createVirtualDisplay("sop-screen", outW, outH, outDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.getSurface(), null, handler);
    }

    /**
     * 盯着「屏幕尺寸/方向变了」。
     *
     * <p>虚拟屏是启动那一刻按 {@code DisplayMetrics} 建的，之后不会自己跟着转 ——
     * 用户横过来看视频时，画面会被塞进竖版画布（压扁或加黑边）。所以这里挂一个
     * DisplayListener，尺寸一变就用**同一个 `MediaProjection`** 重建 reader + display
     * （重建不需要重新授权，只有 `stop()` 之后才要）。
     */
    private void registerDisplayWatcher() {
        if (appCtx == null || handler == null) return;
        try {
            displayMgr = (DisplayManager) appCtx.getSystemService(Context.DISPLAY_SERVICE);
            if (displayMgr == null) return;
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) { }
                @Override public void onDisplayRemoved(int displayId) { }
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId != android.view.Display.DEFAULT_DISPLAY) return;
                    final Handler h = handler;
                    if (h == null) return;
                    h.post(new Runnable() {
                        @Override public void run() { rebuildForSize(); }
                    });
                }
            };
            displayMgr.registerDisplayListener(displayListener, handler);
        } catch (Throwable e) {
            EyeLog.w(TAG, "注册屏幕尺寸监听失败：" + e);
        }
    }

    /**
     * 尺寸/方向真的变了 —— **只记一笔，不动采集**。
     *
     * <p>Android 14 上同一个 token 换不出第二套虚拟屏（先释放/先建都实测会 `onStop`），
     * 硬要重建就等于把陪伴打断、还得让用户重新授权。所以这里选择**保持原虚拟屏**：
     * 横屏时系统会把横屏画面按比例缩到画布中间（上下黑边），字会小一点，但陪伴不断、
     * 不用重新点授权。
     *
     * <p>用户想要"横屏也清楚"时，重开一次「一起看屏幕」即可 —— 那时会按当前方向建
     * （横屏 1280×576）。
     */
    private void rebuildForSize() {
        if (!running || appCtx == null) return;
        DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        int[] wh = sizeFor(dm.widthPixels, dm.heightPixels, maxW);
        if (wh[0] == outW && wh[1] == outH) return;
        EyeLog.w(TAG, "屏幕变成 " + wh[0] + "x" + wh[1] + "，保持原虚拟屏 " + outW + "x" + outH
            + "（重建会中断陪伴；想更清楚就重开一次「一起看屏幕」）");
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
        try {
            if (displayMgr != null && displayListener != null) {
                displayMgr.unregisterDisplayListener(displayListener);
            }
        } catch (Throwable ignored) { }
        displayMgr = null;
        displayListener = null;
        appCtx = null;
        // token 停了就作废（Android 14 起不能再用），别让 hasGrant() 说谎
        grantData = null;
        grantResult = 0;
    }

    private final MediaProjection.Callback projectionCb = new MediaProjection.Callback() {
        @Override public void onStop() {
            // 系统自己收回了（用户点了「停止共享」/ 另一个 App 抢了 / 转屏重建被拒）：安静地停。
            EyeLog.w(TAG, "系统停止投射");
            stop();
            Sink s = sink;
            if (s != null) s.onScreenStopped();
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
                // 横屏：把画面转 90° 立起来铺满竖屏画布（见 upright()）
                if (landscape()) bmp = upright(bmp, outW, outH);
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
    /** 现在物理方向是不是横的（决定要不要把画面立起来）。 */
    private boolean landscape() {
        Context c = appCtx;
        if (c == null) return false;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    /**
     * **横屏 → 立起来**：把横屏画面转 90° 铺满竖屏画布。
     *
     * <p>为什么需要这一步：虚拟屏固定是竖屏比例（720×1472）。横屏内容塞进去时，
     * 系统要么把它缩成中间一条（上下大片黑边），要么直接拉扁 —— 两种在宫格里都浪费画幅。
     * 转 90° 之后内容能铺满整块画布，宫格的每一格都是有效画面。
     *
     * <p>代价说清楚：**像素没有变多**。横屏内容在竖屏画布里本来就只有 ~720×324，
     * 转过来放大到 720×1472 只是"铺满"，不是"变清晰"。要真的看清字幕，只有重开一次
     * 「一起看屏幕」按横屏 1280×576 建。
     *
     * <p>处理顺序：先裁掉上下黑边（如果系统是居中缩放）→ 旋转 90° → 居中裁切铺满。
     */
    private Bitmap upright(Bitmap src, int cw, int ch) {
        int w = src.getWidth();
        int h = src.getHeight();
        int top = 0;
        int bottom = h - 1;
        for (int y = 0; y < h; y += 4) {
            if (!rowDark(src, y)) { top = y; break; }
        }
        for (int y = h - 1; y >= 0; y -= 4) {
            if (!rowDark(src, y)) { bottom = y; break; }
        }
        int contentH = Math.max(2, bottom - top + 1);
        Bitmap content = contentH < h * 92 / 100
            ? Bitmap.createBitmap(src, 0, top, w, contentH)   // 有黑边：裁出来
            : src;                                            // 没黑边（被拉扁）：先按原样
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        try {
            Canvas cv = new Canvas(out);
            Matrix m = new Matrix();
            m.postRotate(90);
            // 旋转后尺寸是 (contentH, w)；居中裁切铺满 cw×ch
            float scale = Math.max((float) cw / contentH, (float) ch / w);
            m.postScale(scale, scale);
            m.postTranslate((cw - contentH * scale) / 2f, (ch - w * scale) / 2f);
            cv.drawBitmap(content, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        } finally {
            if (content != src) content.recycle();
            // 原帧已经画进 out 了：显式回收，横屏时 5fps 每帧 4MB，靠 GC 兜容易抖
            try { src.recycle(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 这一行是不是"整行都接近黑"（用来找 letterbox 黑边）。 */
    private static boolean rowDark(Bitmap b, int y) {
        int w = b.getWidth();
        int step = Math.max(1, w / 24);
        for (int x = 0; x < w; x += step) {
            int p = b.getPixel(x, y);
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int bl = p & 0xFF;
            if (r + g + bl > 36) return false;
        }
        return true;
    }

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
