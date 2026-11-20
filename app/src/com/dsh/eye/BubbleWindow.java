package com.dsh.eye;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * 悬浮球：在别人的 App（视频 / 游戏）上面也能点到的「陪你看屏幕」开关。
 *
 * <p>为什么整颗球都是自绘的：悬浮窗只有 48dp 见方，用 XML 布局 + 多层 drawable 去拼
 * 圆形、描边、呼吸红点，最后还得处理各版本 drawable 的边界裁切；直接在
 * {@link Ball#onDraw} 里按 dp 画反而更短、更可控，也不会引入 res 依赖
 * （这个项目是无 Gradle 的 javac + aapt2 构建，少一个资源就少一类构建坑）。
 *
 * <p>线程约定：{@link #show()}、{@link #hide()}、{@link #setState(int)} 都只允许在主线程调，
 * 内部不再切线程 —— 悬浮窗的 add/remove/update 本来就只能在有 Looper 的线程做，
 * 切来切去只会让「显示到一半被隐藏」这种竞态更难查。日志例外：{@link EyeLog#w}
 * 可以在任意线程调，出错时不用先判断自己在哪个线程。
 */
final class BubbleWindow {

    /** 闲着：球在，没在看。 */
    static final int IDLE = 0;
    /** 正在一起看：球上有个呼吸的红点。 */
    static final int WATCHING = 1;
    /** 等系统授权弹窗：球变灰色。 */
    static final int WAITING = 2;

    interface Cb {
        /** 单击球：开 / 关陪伴。 */
        void onToggle();
        /** 长按球：直接关掉、隐藏。 */
        void onClose();
    }

    private static final String TAG = "bubble";

    /** 球窗口的边长：球本体 45dp，四周各留 1.5dp 给描边和红晕，别让它们被窗口裁掉。 */
    private static final int SIZE_DP = 48;

    private final Context ctx;
    private final Cb cb;
    private final WindowManager wm;
    private final ViewConfiguration vc;
    private final float density;

    private WindowManager.LayoutParams lp;
    private Ball ball;
    private View view;

    /** 当前外观状态。未显示时也要记着，下一次 {@link #show()} 直接用。 */
    private int state = IDLE;

    /**
     * 这一次手势是不是已经变成「拖」。
     *
     * <p>放在外层而不是 touch listener 的匿名类里，是因为长按监听也要读它 ——
     * 拖动超过 touch slop 之后再触发长按，就应该当成拖动而不是「关掉」。
     */
    private boolean dragging;

    /** 长按是否已经真的执行过。用来吞掉长按之后紧跟的那个 ACTION_UP，避免又 toggle 一次。 */
    private boolean longFired;

    BubbleWindow(Context c, Cb cb) {
        // 用 Application Context：悬浮窗可能比 Activity 活得久，拿 Activity 的 Context
        // 去 addView 会在 Activity 销毁后留下一个指向它的窗口。
        Context app = c.getApplicationContext();
        this.ctx = app != null ? app : c;
        this.cb = cb;
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.vc = ViewConfiguration.get(this.ctx);
        this.density = this.ctx.getResources().getDisplayMetrics().density;
    }

    /** 显示（幂等：已显示时再调什么都不做）。 */
    void show() {
        if (view != null) return;

        Ball b = new Ball(ctx);

        // TYPE_PHONE 在 26 之后被系统拦掉，必须换 TYPE_APPLICATION_OVERLAY；
        // 两个常量都是编译期内联的 int，所以老设备上也不会去解析新字段。
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        p.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        p.format = PixelFormat.TRANSLUCENT;
        // 不要 FLAG_NOT_TOUCHABLE：这颗球就是拿来点的。
        // NOT_FOCUSABLE 保证点球不会抢走下面 App 的输入焦点，NOT_TOUCH_MODAL 让球外的
        // 触摸继续传给下层窗口，LAYOUT_IN_SCREEN 让 lp.x/lp.y 直接按整块屏幕算。
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        p.gravity = Gravity.TOP | Gravity.START;
        p.width = dp(SIZE_DP);
        p.height = dp(SIZE_DP);
        p.x = dp(12);
        p.y = dp(160);

        attachTouch(b);

        // 没给「显示在其他应用上层」权限时 addView 会抛 BadTokenException。
        // 这里必须吞掉：悬浮球只是附属功能，不能因为它把整个服务带崩。
        try {
            wm.addView(b, p);
        } catch (Throwable e) {
            EyeLog.w(TAG, "加不上悬浮窗：" + e);
            return;
        }

        lp = p;
        ball = b;
        view = b;
        // 放在 addView 成功之后再设：失败时不会留下一个永远在跑的呼吸动画。
        b.setState(state);
    }

    /** 隐藏并释放（幂等）。 */
    void hide() {
        if (view == null) return;

        // 先把自己标记成「已隐藏」，这样哪怕 onClose / onToggle 里又绕回来调 hide()
        // 也不会对同一个 View 做第二次 removeView。
        View v = view;
        Ball b = ball;
        view = null;
        ball = null;
        dragging = false;
        longFired = false;

        if (b != null) b.stopBreathe();
        try {
            wm.removeView(v);
        } catch (Throwable e) {
            EyeLog.w(TAG, "摘不下悬浮窗：" + e);
        }
    }

    /**
     * 换外观状态。
     *
     * <p>未显示时只记下来 —— 服务完全可能在球还没挂上去之前就切到 WATCHING，
     * 如果这时候直接丢状态，下次 show 出来的就是错的颜色。
     */
    void setState(int s) {
        state = s;
        if (ball != null) ball.setState(s);
    }

    boolean isShown() {
        return view != null;
    }

    int state() {
        return state;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    /**
     * 装触摸：单击 toggle、长按 close、拖动改窗口坐标。
     *
     * <p>关键取舍：OnTouchListener 一律返回 false，把事件继续交给 View 自己的
     * onTouchEvent。因为 View 只有在 onTouchEvent 里才会启动长按计时器；
     * 如果 listener 返回 true 把事件吞了，setOnLongClickListener 就永远不会触发。
     * 代价是按下时 View 会做它自己的按压状态，但这颗球没有 background，看不出来。
     */
    private void attachTouch(final Ball b) {
        b.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // 手势进行到一半被 hide() 摘掉了窗口：后面的事件直接放掉。
                if (view == null || lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 用 raw 坐标：lp.x/lp.y 是整块屏幕的坐标，混用 view 坐标会让球
                        // 在手指下「越拖越偏」（尤其球移动到屏幕边缘之后）。
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragging = false;
                        longFired = false;
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        // 超过系统认定的滑动距离才算拖：否则手指按住时的几像素抖动
                        // 会把「单击」误判成拖动。
                        if (!dragging && Math.hypot(dx, dy) > vc.getScaledTouchSlop()) {
                            dragging = true;
                            // 主动撤掉 View 排着的长按计时器：球是跟着手指走的，
                            // View 自己的「移动超过 slop 就取消长按」用的是 view 坐标，
                            // 窗口跟手之后这个判断会失效，不加这一句拖久了就会被判成长按。
                            b.cancelLongPress();
                        }
                        if (dragging) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            try {
                                wm.updateViewLayout(b, lp);
                            } catch (Throwable ignored) {
                                // 窗口刚好被系统收回时这里会抛，下一帧事件也不会再来，忽略即可。
                            }
                        }
                        return false;

                    case MotionEvent.ACTION_UP:
                        // longFired：长按已经处理过，紧跟的 UP 不是「单击」。
                        if (view != null && !dragging && !longFired) cb.onToggle();
                        dragging = false;
                        return false;

                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return false;

                    default:
                        return false;
                }
            }
        });

        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // 手指已经拖出去了：这次按压的意图是「挪位置」，不是「关掉」。
                // 返回 false 让系统按「没消费长按」处理（此时也不该弹什么上下文菜单，
                // 悬浮窗没有注册菜单）。
                if (dragging) return false;
                longFired = true;
                cb.onClose();
                return true;
            }
        });
    }

    /**
     * 球本体。{@code static} 是因为它只依赖构造时传进来的 Context，不需要摸到外面那个
     * {@link BubbleWindow} 的字段；状态通过 {@link #setState(int)} 推进来。
     */
    static final class Ball extends View {

        private static final int COLOR_IDLE = 0xF21F2937;
        private static final int COLOR_WATCHING = 0xF2E5484D;
        private static final int COLOR_WAITING = 0xF26B7280;
        private static final int COLOR_STROKE = 0x66FFFFFF;
        private static final int COLOR_ICON = 0xFFFFFFFF;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float d;

        private int state = IDLE;
        /** 红点 / 红晕的呼吸透明度，0.35 → 1.0 往返。 */
        private float glow = 1f;
        private ValueAnimator breathe;

        Ball(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;

            // 整颗球压一点透明度，压在视频上不会像一块实心色块。
            setAlpha(0.92f);
            // 让 View 的 onTouchEvent 认为自己是可点的：OnTouchListener 返回 false 之后，
            // 只有 clickable / longClickable 的 View 才会继续收到 MOVE / UP。
            setClickable(true);

            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(COLOR_STROKE);

            solid.setStyle(Paint.Style.FILL);
            solid.setColor(COLOR_ICON);

            icon.setStyle(Paint.Style.STROKE);
            icon.setColor(COLOR_ICON);
            icon.setStrokeWidth(dp(1.7f));
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);

            dot.setStyle(Paint.Style.FILL);
            dot.setColor(0xFFE5484D);
            halo.setStyle(Paint.Style.FILL);
            halo.setColor(0xFFE5484D);
        }

        void setState(int s) {
            if (state == s) return;
            state = s;
            if (s == WATCHING) {
                startBreathe();
            } else {
                stopBreathe();
            }
            invalidate();
        }

        /** 离开 WATCHING 必须停：INFINITE 的动画不会自己结束，留着就是泄漏。 */
        void stopBreathe() {
            if (breathe == null) return;
            breathe.cancel();
            breathe = null;
            glow = 1f;
            invalidate();
        }

        private void startBreathe() {
            if (breathe != null) return;
            glow = 1f;
            breathe = ValueAnimator.ofFloat(0.35f, 1f);
            breathe.setDuration(900);
            breathe.setRepeatMode(ValueAnimator.REVERSE);
            breathe.setRepeatCount(ValueAnimator.INFINITE);
            breathe.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    glow = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            breathe.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            // removeView 之后系统仍可能把已经排队的动画帧走完，这里再兜一次。
            stopBreathe();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = dp(45) / 2f; // 45dp 直径，窗口 48dp，正好留出 1.5dp 余量

            switch (state) {
                case WATCHING:
                    fill.setColor(COLOR_WATCHING);
                    break;
                case WAITING:
                    fill.setColor(COLOR_WAITING);
                    break;
                case IDLE:
                default:
                    fill.setColor(COLOR_IDLE);
                    break;
            }
            cv.drawCircle(cx, cy, r, fill);

            // 描边只给 IDLE：红/灰状态下再加一圈白边会显脏。
            // 半径收 0.75dp，让 1.5dp 的描边外沿刚好落在 45dp 圆上。
            if (state == IDLE) {
                cv.drawCircle(cx, cy, r - dp(0.75f), stroke);
            }

            switch (state) {
                case WATCHING:
                    drawRecord(cv, cx, cy);
                    break;
                case WAITING:
                    drawDots(cv, cx, cy);
                    break;
                case IDLE:
                default:
                    drawEye(cv, cx, cy);
                    break;
            }

            if (state == WATCHING) {
                drawBreatheDot(cv, cx, cy, r);
            }
        }

        /** IDLE：一只眼睛，上下两条弧（瞳孔留白，小尺寸下画出来只会糊成一团）。 */
        private void drawEye(Canvas cv, float cx, float cy) {
            float hw = dp(7.5f);
            float hh = dp(5f);
            path.reset();
            path.moveTo(cx - hw, cy);
            path.quadTo(cx, cy - hh, cx + hw, cy);
            cv.drawPath(path, icon);
            path.reset();
            path.moveTo(cx - hw, cy);
            path.quadTo(cx, cy + hh, cx + hw, cy);
            cv.drawPath(path, icon);
        }

        /** WATCHING：一条竖线 + 一个向右的三角，像录音 / 开始。 */
        private void drawRecord(Canvas cv, float cx, float cy) {
            float top = cy - dp(5f);
            float bottom = cy + dp(5f);
            cv.drawLine(cx - dp(3.5f), top, cx - dp(3.5f), bottom, icon);

            path.reset();
            path.moveTo(cx - dp(0.5f), top);
            path.lineTo(cx + dp(5.5f), cy);
            path.lineTo(cx - dp(0.5f), bottom);
            path.close();
            cv.drawPath(path, solid);
        }

        /** WAITING：三个点，表示「在等系统那边的授权弹窗」。 */
        private void drawDots(Canvas cv, float cx, float cy) {
            float gap = dp(4.5f);
            float rad = dp(1.4f);
            cv.drawCircle(cx - gap, cy, rad, solid);
            cv.drawCircle(cx, cy, rad, solid);
            cv.drawCircle(cx + gap, cy, rad, solid);
        }

        /** WATCHING 右上角：8dp 实心红点 + 3dp 半透明红晕，透明度跟着 animator 呼吸。 */
        private void drawBreatheDot(Canvas cv, float cx, float cy, float r) {
            // 45° 方向压在圆边上，正好是「右上角」又不会跑出窗口。
            float ox = cx + r * 0.7071f;
            float oy = cy - r * 0.7071f;
            dot.setAlpha((int) (255 * glow));
            halo.setAlpha((int) (0x66 * glow));
            cv.drawCircle(ox, oy, dp(4f) + dp(3f), halo);
            cv.drawCircle(ox, oy, dp(4f), dot);
        }

        private int dp(float v) {
            return (int) (v * d + 0.5f);
        }
    }
}
