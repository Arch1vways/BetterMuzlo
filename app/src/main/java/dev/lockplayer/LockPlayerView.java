package dev.lockplayer;

import android.animation.ValueAnimator;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Custom lock screen media player matching {@code setup.svg}:
 * single centered rounded album art (tap to pause/play), large title & artist,
 * full-width progress bar with timestamps, and animated right-aligned controls.
 * Supports White, Album, System and Custom colours and suppresses OriginOS dark-mode inversion.
 */
final class LockPlayerView extends View implements ConfigStore.Listener, MediaTracker.Listener {
    private static final float REF_W = 2809f;
    private static final float REF_H = 2320f;

    private static final float COVER_SIZE = 1290f;
    private static final float GAP_COVER_TITLE = 150f;
    private static final float TITLE_SIZE = 228f;
    private static final float GAP_TITLE_ARTIST = 72f;
    private static final float ARTIST_SIZE = 94f;
    private static final float TIME_SIZE = 64f;
    private static final float GAP_TIME_BAR = 50f;
    private static final float BAR_HEIGHT = 35f;
    private static final float GAP_BAR_CONTROLS = 148f;
    private static final float CTRL_HEIGHT = 154f;

    private static final int HIT_NONE = 0, HIT_COVER = 1, HIT_SEEK = 2, HIT_PREV = 3, HIT_PLAY = 4, HIT_NEXT = 5;

    private static final Method VIEW_SET_NIGHT_MODE = findIntMethod(View.class, "setNightMode");
    private static final Method VIEW_SET_ALLOW_NIGHT = findBoolMethod(View.class, "setAllowNightMode");
    private static final Method VIEW_SET_DARK_MODE = findBoolMethod(View.class, "setDarkMode");
    private static final Method CANVAS_SET_NIGHT_MODE = findIntMethod(Canvas.class, "setNightMode");

    private final boolean demoMode;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Config cfg = new Config();
    private MediaTracker.Snapshot media = new MediaTracker.Snapshot();

    private boolean demoPlaying = true;
    private long demoPositionMs = 142_000L;
    private final long demoDurationMs = 384_000L;
    private Bitmap demoCover;
    private int demoSystemColor;

    private int currentColor = Color.WHITE;
    private int targetColor = Color.WHITE;
    private ValueAnimator colorAnim;

    // Cached 1px solid Shader bypasses ROM dark-mode Paint color inversion completely
    private int cachedShaderColor;
    private LinearGradient cachedShader;
    private int cachedSubShaderColor;
    private LinearGradient cachedSubShader;

    private WallpaperManager wallpaperManager;
    private final Runnable onWallpaperAverage = () -> post(() -> {
        updateTargetColor(true);
        invalidate();
    });
    private final WallpaperManager.OnColorsChangedListener wallpaperListener =
            (colors, which) -> onWallpaperChanged();

    private void onWallpaperChanged() {
        if (demoMode) onWallpaperAverage.run();
        else WallpaperAverage.invalidate(getContext(), onWallpaperAverage);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint reflMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathToolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Path coverClipPath = new Path();
    private final android.graphics.Rect cropSrcRect = new android.graphics.Rect();

    // Retro Cover Flow carousel state (mtoc HorizontalAlbumBrowser port)
    private final Camera coverCamera = new Camera();
    private final Matrix coverCamMatrix = new Matrix();
    private final RectF retroItemRect = new RectF();
    private final RectF retroShadowRect = new RectF();
    private final RectF retroReflRect = new RectF();
    private static Bitmap noArtworkBmp;
    private static Bitmap noArtworkCardBmp;
    private static Bitmap noArtworkReflBmp;
    private static Bitmap retroShadowBmp;
    private static boolean noArtworkTried;
    private final PowerManager powerManager;
    private Bitmap cachedCenterReflArt;
    private Bitmap cachedCenterReflBmp;
    private Bitmap[] demoCovers;
    private int demoTrackIndex = 0;
    private float carouselPos = 0f;
    private ValueAnimator carouselAnim;
    private int lastFlipDir = 1;
    private Bitmap retroOutgoingArt;
    private Bitmap retroFadeOldArt;
    private Bitmap displayedArt;
    private boolean pendingMediaSync = false;
    private float retroArtFade = 1f;
    private ValueAnimator retroFadeAnim;
    private final java.util.Map<Bitmap, Bitmap> reflCache = new java.util.LinkedHashMap<Bitmap, Bitmap>(20, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Bitmap, Bitmap> eldest) {
            return size() > 16;
        }
    };
    private final java.util.concurrent.ExecutorService reflExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RetroRefl");
        t.setDaemon(true);
        return t;
    });
    private boolean draggingCarousel = false;
    private float carouselDragStartX = 0f;
    private float carouselStepPx = 220f;
    private float lastCarouselCovSize = 300f;
    private float lastCarouselCovCx = 0f;
    private float lastCarouselCovCy = 0f;

    private final RectF coverRect = new RectF();
    private final RectF barRect = new RectF();
    private final RectF barFillRect = new RectF();
    private final RectF seekHitRect = new RectF();
    private final RectF prevHitRect = new RectF();
    private final RectF playHitRect = new RectF();
    private final RectF nextHitRect = new RectF();
    private final RectF pauseBarRect = new RectF();
    private final Path notePath = new Path();
    private final Path triRawLeft = new Path();
    private final Path triRawRight = new Path();
    private final Path triRoundLeft = new Path();
    private final Path triRoundRight = new Path();
    private final Path triGapMask = new Path();
    private final Path nextArrowPath = new Path();
    private final Path playTriPath = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private final android.util.SparseArray<LinearGradient> m3Shaders = new android.util.SparseArray<>();
    private int cachedM3Seed;
    private MaterialYou.Scheme cachedM3Scheme;
    private final Path materialWavePath = new Path();
    private final Paint materialWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float materialWaveAmp = 0f;
    private float m3HandlePress = 0f;
    private long lastMaterialFrameMs = 0L;

    private float cachedArrowUnit = -1f;

    private Typeface fontBold;
    private Typeface fontMedium;

    private int activeHit = HIT_NONE;
    private boolean seeking;
    private float seekFraction;
    private float downX, downY;
    private float touchSlop = 24f;

    private float centerCoverScale = 1f;
    private ValueAnimator coverPressAnim;

    private String lastMarqueeTitle = "";
    private long marqueeStartMs = 0L;

    private final float[] btnScale = {1f, 1f, 1f};
    private final float[] btnGlow = {0f, 0f, 0f};
    private final ValueAnimator[] btnAnims = new ValueAnimator[3];
    private final ValueAnimator[] glowAnims = new ValueAnimator[3];

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean playing = demoMode ? demoPlaying : media.playing;
            if (demoMode && demoPlaying) {
                demoPositionMs += 500L;
                if (demoPositionMs > demoDurationMs) demoPositionMs = 0L;
            }
            if (isAttachedToWindow() && isShown() && playing && !isHostSleeping()) {
                invalidate();
                handler.postDelayed(this, 500L);
            }
        }
    };

    LockPlayerView(Context context, boolean demoMode) {
        super(context);
        this.demoMode = demoMode;
        this.powerManager = context.getSystemService(PowerManager.class);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setFocusable(true);
        setSoundEffectsEnabled(false);
        disableNightInversion(this);

        fontBold = loadFont(context, 700, Typeface.DEFAULT_BOLD);
        fontMedium = loadFont(context, 500, Typeface.DEFAULT);

        titlePaint.setTypeface(fontBold);
        titlePaint.setTextAlign(Paint.Align.CENTER);

        artistPaint.setTypeface(fontMedium);
        artistPaint.setTextAlign(Paint.Align.CENTER);

        timePaint.setTypeface(fontMedium);
        timePaint.setFontFeatureSettings("tnum");

        reflMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        if (demoMode) {
            initDemoCover();
        } else {
            cfg = ConfigStore.get();
            ConfigStore.addListener(this);
            MediaTracker.addListener(this);
        }
        ensureNoArtwork(context);
        updateTargetColor(false);
    }

    private static void ensureNoArtwork(Context context) {
        if (noArtworkBmp != null && !noArtworkBmp.isRecycled()) return;
        if (noArtworkTried) return;
        noArtworkTried = true;
        Bitmap decoded = decodeApkNoArtwork();
        if (decoded == null && context != null) {
            decoded = decodeAssetNoArtwork(context);
        }
        if (decoded == null && context != null) {
            try {
                Context modCtx = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
                decoded = decodeAssetNoArtwork(modCtx);
            } catch (Throwable ignored) {
            }
        }
        if (decoded != null) {
            noArtworkBmp = decoded;
            if (noArtworkReflBmp == null || noArtworkReflBmp.isRecycled()) {
                noArtworkReflBmp = buildReflectionBitmap(decoded);
            }
        }
    }

    private static Bitmap decodeApkNoArtwork() {
        String apk = HookEntry.moduleApkPath;
        if (apk == null || apk.isEmpty()) return null;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/noartwork.png");
            if (entry == null) return null;
            try (java.io.InputStream is = zip.getInputStream(entry)) {
                return decodeStreamSquare(is);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decodeAssetNoArtwork(Context ctx) {
        try (java.io.InputStream is = ctx.getAssets().open("noartwork.png")) {
            return decodeStreamSquare(is);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decodeStreamSquare(java.io.InputStream is) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap raw = BitmapFactory.decodeStream(is, null, opts);
        if (raw == null) return null;
        if (raw.getWidth() > 320 || raw.getHeight() > 320) {
            Bitmap scaled = Bitmap.createScaledBitmap(raw, 320, 320, true);
            if (scaled != raw) raw.recycle();
            return scaled;
        }
        return raw;
    }

    static void disableNightInversion(View v) {
        if (v == null) return;
        try {
            v.setForceDarkAllowed(false);
        } catch (Throwable ignored) {
        }
        invokeSafe(VIEW_SET_NIGHT_MODE, v, 0);
        invokeSafe(VIEW_SET_ALLOW_NIGHT, v, false);
        invokeSafe(VIEW_SET_DARK_MODE, v, false);
    }

    private static void disableCanvasNightInversion(Canvas c) {
        if (c == null) return;
        invokeSafe(CANVAS_SET_NIGHT_MODE, c, 0);
    }

    private static Method findIntMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name, int.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findBoolMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name, boolean.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeSafe(Method m, Object target, Object arg) {
        if (m == null || target == null) return;
        try {
            m.invoke(target, arg);
        } catch (Throwable ignored) {
        }
    }

    private void initDemoCover() {
        int[][] palettes = {
                {0xFF3E1E68, 0xFFB4558C, 0xFFF3904F},
                {0xFF0F3D5E, 0xFF2872EA, 0xFF6CD4FF},
                {0xFF1F4037, 0xFF42A88B, 0xFFF2D06B},
                {0xFF4A192C, 0xFFC94B65, 0xFFFFB5A7}
        };
        demoCovers = new Bitmap[palettes.length];
        for (int i = 0; i < palettes.length; i++) {
            Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 192, 192, palettes[i][0], palettes[i][1], Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 192, 192, p);
            p.setShader(null);
            p.setColor(palettes[i][2]);
            p.setAlpha(165);
            c.drawCircle(i % 2 == 0 ? 132 : 60, 60, 56, p);
            p.setColor(Color.WHITE);
            p.setAlpha(48);
            c.drawCircle(i % 2 == 0 ? 56 : 132, 136, 44, p);
            demoCovers[i] = b;
        }
        demoCover = demoCovers[0];
    }

    private static Typeface loadFont(Context c, int weight, Typeface fallback) {
        try {
            return new Typeface.Builder(c.getAssets(), "fonts/Montserrat.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .setWeight(weight)
                    .build();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    void setDemoConfig(Config value) {
        this.cfg = value;
        updateTargetColor(false);
        requestLayout();
        invalidate();
    }

    void setDemoSystemColor(int color) {
        if (demoSystemColor == color) return;
        demoSystemColor = color;
        if (cfg.colorSource == Config.COLOR_SYSTEM) {
            updateTargetColor(false);
            invalidate();
        }
    }

    @Override public void onConfigChanged(Config next) {
        this.cfg = next;
        post(() -> {
            updateTargetColor(true);
            scheduleTicker();
            requestLayout();
            invalidate();
        });
    }

    @Override public void onMediaChanged(MediaTracker.Snapshot snapshot) {
        this.media = snapshot;
        if (snapshot != null && snapshot.art != null && !snapshot.art.isRecycled()) {
            prewarmReflection(snapshot.art);
        }
        post(() -> {
            if (carouselAnim != null && carouselAnim.isRunning()) {
                pendingMediaSync = true;
                return;
            }
            syncDisplayedMedia(true);
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        disableNightInversion(this);
        if (!demoMode) {
            cfg = ConfigStore.get();
            media = MediaTracker.getSnapshot();
            displayedArt = (media.art != null && !media.art.isRecycled()) ? media.art : null;
            if (displayedArt != null) prewarmReflection(displayedArt);
        }
        try {
            wallpaperManager = WallpaperManager.getInstance(getContext());
            wallpaperManager.addOnColorsChangedListener(wallpaperListener, handler);
        } catch (Throwable ignored) {
            wallpaperManager = null;
        }
        updateTargetColor(false);
        scheduleTicker();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        if (wallpaperManager != null) {
            try {
                wallpaperManager.removeOnColorsChangedListener(wallpaperListener);
            } catch (Throwable ignored) {
            }
            wallpaperManager = null;
        }
        cancelAllAnimations();
        super.onDetachedFromWindow();
    }

    @Override protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        disableNightInversion(this);
        updateTargetColor(false);
        invalidate();
    }

    private boolean isHostSleeping() {
        if (!demoMode && powerManager != null && !powerManager.isInteractive()) {
            return true;
        }
        return !demoMode && getParent() instanceof View && ((View) getParent()).getAlpha() < 0.02f;
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            disableNightInversion(this);
            scheduleTicker();
        } else {
            handler.removeCallbacks(ticker);
        }
    }

    private void cancelAllAnimations() {
        if (retroFadeAnim != null) retroFadeAnim.cancel();
        if (colorAnim != null) colorAnim.cancel();
        if (coverPressAnim != null) coverPressAnim.cancel();
        if (carouselAnim != null) carouselAnim.cancel();
        for (int i = 0; i < 3; i++) {
            if (btnAnims[i] != null) btnAnims[i].cancel();
            if (glowAnims[i] != null) glowAnims[i].cancel();
        }
    }

    private void updateTargetColor(boolean animate) {
        int next = resolveTargetColor();
        if (next == targetColor && currentColor == targetColor) return;
        targetColor = next;
        if (!animate || !isLaidOut()) {
            if (colorAnim != null) colorAnim.cancel();
            currentColor = targetColor;
            invalidate();
            return;
        }
        if (colorAnim != null) colorAnim.cancel();
        final int start = currentColor;
        colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(260);
        colorAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        colorAnim.addUpdateListener(a -> {
            currentColor = MaterialYou.lerp(start, targetColor, (float) a.getAnimatedValue());
            invalidate();
        });
        colorAnim.start();
    }

    private int resolveTargetColor() {
        switch (cfg.colorSource) {
            case Config.COLOR_SYSTEM:
                return MaterialYou.forLockScreen(systemColor());
            case Config.COLOR_CUSTOM:
                return 0xFF000000 | cfg.customColor;
            case Config.COLOR_ALBUM:
            default:
                if (demoMode) return MaterialYou.forLockScreen(0xFFB4558C);
                if (media.artColor != 0) return MaterialYou.forLockScreen(media.artColor);
                return MaterialYou.forLockScreen(systemColor());
        }
    }

    private int systemColor() {
        Context c = getContext();
        int avg = demoMode ? demoSystemColor : WallpaperAverage.get(c, onWallpaperAverage);
        if (avg == 0) avg = WallpaperAverage.cached();
        if (avg == 0) avg = WallpaperAverage.fromWallpaperColors(c);
        return avg != 0 ? avg : 0xFFA6A6A6;
    }

    private LinearGradient solidShaderFor(int color) {
        if (cachedShader == null || cachedShaderColor != color) {
            cachedShaderColor = color;
            cachedShader = new LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP);
        }
        return cachedShader;
    }

    private LinearGradient subShaderFor(int color) {
        if (cachedSubShader == null || cachedSubShaderColor != color) {
            cachedSubShaderColor = color;
            cachedSubShader = new LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP);
        }
        return cachedSubShader;
    }

    private LinearGradient m3ShaderFor(int color) {
        LinearGradient g = m3Shaders.get(color);
        if (g == null) {
            if (m3Shaders.size() > 32) m3Shaders.clear();
            g = new LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP);
            m3Shaders.put(color, g);
        }
        return g;
    }

    private MaterialYou.Scheme m3SchemeFor(int seed) {
        if (cachedM3Scheme == null || cachedM3Seed != seed) {
            cachedM3Seed = seed;
            cachedM3Scheme = MaterialYou.fromSeed(seed, true);
        }
        return cachedM3Scheme;
    }

    void onWakeResumed() {
        if (!isAttachedToWindow()) return;
        scheduleTicker();
        if (media.playing) invalidate();
    }

    private void scheduleTicker() {
        handler.removeCallbacks(ticker);
        boolean playing = demoMode ? demoPlaying : media.playing;
        if (playing && isAttachedToWindow() && !isHostSleeping()) {
            handler.postDelayed(ticker, 500L);
        }
    }

    private void syncDisplayedMedia(boolean animate) {
        pendingMediaSync = false;
        Bitmap nextArt = (media != null && media.art != null && !media.art.isRecycled()) ? media.art : null;
        if (nextArt != displayedArt) {
            if (animate) {
                startArtCrossfade(displayedArt, nextArt);
            } else {
                retroArtFade = 1f;
                retroFadeOldArt = null;
            }
            displayedArt = nextArt;
        }
        updateTargetColor(animate);
        scheduleTicker();
        invalidate();
    }

    private void startArtCrossfade(Bitmap fromArt, Bitmap toArt) {
        if (retroFadeAnim != null) retroFadeAnim.cancel();
        retroFadeOldArt = (fromArt != null && !fromArt.isRecycled()) ? fromArt : noArtworkBmp;
        if (toArt == null && retroFadeOldArt == noArtworkBmp) {
            retroArtFade = 1f;
            return;
        }
        retroArtFade = 0f;
        retroFadeAnim = ValueAnimator.ofFloat(0f, 1f);
        retroFadeAnim.setDuration(220L);
        retroFadeAnim.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));
        retroFadeAnim.addUpdateListener(a -> {
            retroArtFade = (float) a.getAnimatedValue();
            if (retroArtFade >= 0.999f) {
                retroArtFade = 1f;
                retroFadeOldArt = null;
            }
            invalidate();
        });
        retroFadeAnim.start();
    }

    private void triggerRetroFlip(int dir) {
        if (cfg.coverStyle != Config.COVER_RETRO) return;
        lastFlipDir = dir >= 0 ? 1 : -1;
        Bitmap cur = demoMode ? demoCover : displayedArt;
        if (cur != null && !cur.isRecycled()) {
            retroOutgoingArt = cur;
        }
        if (!demoMode) {
            // Decouple carousel slide from track loading: slide noArtworkBmp in, then load/fade art after slide
            if (retroFadeAnim != null) retroFadeAnim.cancel();
            retroArtFade = 1f;
            retroFadeOldArt = null;
            displayedArt = null;
            pendingMediaSync = true;
        }
        if (lastFlipDir > 0) {
            // Next track: new album slides in from right (+1 -> 0), old goes left (0 -> -1)
            animateCarouselFrom(carouselPos - 1f);
        } else {
            // Prev track: new album slides in from left (-1 -> 0), old goes right (0 -> +1)
            animateCarouselFrom(carouselPos + 1f);
        }
    }

    private void animateCarouselFrom(float startPos) {
        if (carouselAnim != null) carouselAnim.cancel();
        carouselPos = startPos;
        carouselAnim = ValueAnimator.ofFloat(startPos, 0f);
        carouselAnim.setDuration(360L);
        carouselAnim.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));
        carouselAnim.addUpdateListener(a -> {
            carouselPos = (float) a.getAnimatedValue();
            if (Math.abs(carouselPos) < 0.001f) {
                carouselPos = 0f;
                retroOutgoingArt = null;
            }
            invalidate();
        });
        carouselAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                carouselPos = 0f;
                retroOutgoingArt = null;
                if (!demoMode && pendingMediaSync) {
                    syncDisplayedMedia(true);
                } else {
                    invalidate();
                }
            }
        });
        carouselAnim.start();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (w <= 0) w = Math.round(340f * getResources().getDisplayMetrics().density);
        float padH = demoMode ? dp(16) : dp(4);
        float availW = Math.max(1f, w - padH * 2f);
        float scale = cfg.scalePct / 100f;
        float u = (availW / REF_W) * scale;
        float padV = dp(8);
        int h = Math.round(REF_H * u + padV * 2);
        int maxH = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST
                ? MeasureSpec.getSize(heightMeasureSpec) : Integer.MAX_VALUE;
        if (h > maxH && maxH > 0) h = maxH;
        setMeasuredDimension(w, Math.max(1, h));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        disableCanvasNightInversion(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float d = getResources().getDisplayMetrics().density;
        float scale = cfg.scalePct / 100f;
        float padH = demoMode ? dp(16) : dp(4);
        float padV = dp(6);
        float availW = Math.max(1f, w - padH * 2f);
        float availH = Math.max(1f, h - padV * 2f);

        float u = Math.min((availW / REF_W) * scale, availH / REF_H);
        float totalContentH = REF_H * u;
        float topY = Math.max(padV, (h - totalContentH) / 2f);

        int baseFg = currentColor;
        float uiAlpha = Math.max(0.10f, Math.min(1f, cfg.trackAlphaPct / 100f));
        boolean isMaterial = cfg.coverStyle == Config.COVER_MATERIAL;
        MaterialYou.Scheme m3 = isMaterial ? m3SchemeFor(baseFg) : null;
        int fg = MaterialYou.withAlpha(isMaterial ? m3.primary : baseFg, Math.round(Color.alpha(baseFg) * uiAlpha));
        int fgTitle = isMaterial ? MaterialYou.withAlpha(m3.onSurface, Math.round(255f * uiAlpha)) : fg;
        int fgSub = MaterialYou.withAlpha(isMaterial ? m3.onSurfaceVariant : baseFg, Math.round(235f * uiAlpha));
        int fgTrackBg = isMaterial
                ? MaterialYou.withAlpha(m3.secondaryContainer, Math.round(170f * uiAlpha))
                : MaterialYou.withAlpha(baseFg, Math.round(96f * uiAlpha));
        LinearGradient fgShader = solidShaderFor(fg);
        LinearGradient titleShader = isMaterial ? m3ShaderFor(fgTitle) : fgShader;

        // 1. Single centered square cover art or mtoc-style Cover Flow carousel (not affected by player opacity)
        float baseCovSize = COVER_SIZE * u;
        float drawCovSize = baseCovSize * centerCoverScale;
        float covCx = w / 2f;
        float covCy = topY + baseCovSize / 2f;
        Bitmap art = demoMode ? demoCover : displayedArt;
        if (cfg.coverStyle == Config.COVER_RETRO) {
            float retroCovSize = cfg.retroReflection ? baseCovSize * 0.76f : baseCovSize * 0.82f;
            float retroCy = cfg.retroReflection ? topY + retroCovSize * 0.5f + dp(4) : covCy;
            float drawRetroSize = retroCovSize * centerCoverScale;
            coverRect.set(covCx - drawRetroSize / 2f, retroCy - drawRetroSize / 2f,
                    covCx + drawRetroSize / 2f, retroCy + drawRetroSize / 2f);
            drawRetroCarousel(canvas, covCx, retroCy, retroCovSize, availW, art, baseFg, d);
        } else {
            coverRect.set(covCx - drawCovSize / 2f, covCy - drawCovSize / 2f,
                    covCx + drawCovSize / 2f, covCy + drawCovSize / 2f);
            drawCoverCard(canvas, coverRect, art, baseFg, d);
        }

        // 2. Title & Artist (painted with solid Shader to defeat dark-mode color inversion)
        String rawTitle = demoMode ? "TrackName" : (media.title.isEmpty() ? "No media" : media.title);
        String rawArtist = demoMode ? "Artist" : media.artist;

        titlePaint.setColor(fgTitle);
        titlePaint.setShader(titleShader);
        titlePaint.setTextSize(TITLE_SIZE * u);
        artistPaint.setColor(fgSub);
        artistPaint.setShader(subShaderFor(fgSub));
        artistPaint.setTextSize(ARTIST_SIZE * u);

        float titleTop = topY + baseCovSize + GAP_COVER_TITLE * u;
        Paint.FontMetrics tfm = titlePaint.getFontMetrics();
        float titleBaseline = titleTop - tfm.ascent;
        float maxTitleW = availW * 0.94f;
        float titleW = titlePaint.measureText(rawTitle);
        if (!rawTitle.equals(lastMarqueeTitle)) {
            lastMarqueeTitle = rawTitle;
            marqueeStartMs = SystemClock.uptimeMillis();
        }
        if (titleW <= maxTitleW) {
            titlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(rawTitle, w / 2f, titleBaseline, titlePaint);
        } else {
            long elapsed = Math.max(0L, SystemClock.uptimeMillis() - marqueeStartMs);
            float gap = Math.max(dp(48), maxTitleW * 0.24f);
            float loopW = titleW + gap;
            float speedPx = Math.max(dp(34), maxTitleW * 0.14f);
            float offset = elapsed <= 1200L ? 0f : (((elapsed - 1200L) / 1000f) * speedPx) % loopW;

            float clipLeft = (w - maxTitleW) / 2f;
            float clipRight = clipLeft + maxTitleW;
            canvas.save();
            canvas.clipRect(clipLeft, titleBaseline + tfm.ascent - 2f, clipRight, titleBaseline + tfm.descent + 4f);
            titlePaint.setTextAlign(Paint.Align.LEFT);
            float drawX = clipLeft - offset;
            canvas.drawText(rawTitle, drawX, titleBaseline, titlePaint);
            if (drawX + loopW < clipRight) {
                canvas.drawText(rawTitle, drawX + loopW, titleBaseline, titlePaint);
            }
            canvas.restore();
            titlePaint.setTextAlign(Paint.Align.CENTER);
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE && !isHostSleeping()) {
                postInvalidateOnAnimation();
            }
        }

        float artistTop = titleBaseline + tfm.descent + GAP_TITLE_ARTIST * u;
        Paint.FontMetrics afm = artistPaint.getFontMetrics();
        float artistBaseline = artistTop - afm.ascent;
        if (!rawArtist.isEmpty()) {
            CharSequence ellArtist = TextUtils.ellipsize(rawArtist, artistPaint, availW * 0.88f, TextUtils.TruncateAt.END);
            canvas.drawText(ellArtist, 0, ellArtist.length(), w / 2f, artistBaseline, artistPaint);
        }

        // 3. Progress timestamps + full-width progress bar
        long duration = demoMode ? demoDurationMs : media.durationMs;
        long pos = seeking ? Math.round(seekFraction * duration) : (demoMode ? demoPositionMs : media.currentPositionMs());
        float frac = duration > 0 ? Math.max(0f, Math.min(1f, (float) pos / duration)) : 0f;

        int fgTime = isMaterial ? fgSub : fg;
        timePaint.setTextSize(TIME_SIZE * u);
        timePaint.setColor(fgTime);
        timePaint.setShader(isMaterial ? subShaderFor(fgSub) : fgShader);
        Paint.FontMetrics tm = timePaint.getFontMetrics();
        float timeBaseline = Math.max(artistBaseline + tm.descent + 28f * u,
                topY + (1905f / REF_H) * totalContentH);

        String leftTime = formatTime(pos);
        String rightTime = cfg.timeMode == Config.TIME_REMAINING && duration > 0
                ? "-" + formatTime(Math.max(0L, duration - pos))
                : formatTime(duration);

        float barLeft = padH;
        float barRight = w - padH;
        timePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(leftTime, barLeft, timeBaseline, timePaint);

        timePaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(rightTime, barRight, timeBaseline, timePaint);

        boolean isPlaying = demoMode ? demoPlaying : media.playing;
        float barTop = timeBaseline + GAP_TIME_BAR * u;
        float barH = isMaterial ? Math.max(d * 10f, BAR_HEIGHT * 2.2f * u) : Math.max(d * 2.8f, BAR_HEIGHT * u);
        float barR = barH / 2f;
        barRect.set(barLeft, barTop, barRight, barTop + barH);

        if (isMaterial) {
            drawMaterialWavyProgress(canvas, barLeft, barRight, barTop, barH, frac, isPlaying, fg, fgShader, fgTrackBg, d, u);
        } else {
            paint.setColorFilter(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(subShaderFor(fgTrackBg));
            paint.setColor(fgTrackBg);
            canvas.drawRoundRect(barRect, barR, barR, paint);

            if (frac > 0f) {
                float fillEnd = barLeft + (barRight - barLeft) * frac;
                if (fillEnd - barLeft < barH) fillEnd = Math.min(barRight, barLeft + barH);
                barFillRect.set(barLeft, barTop, fillEnd, barTop + barH);
                paint.setShader(fgShader);
                paint.setColor(fg);
                canvas.drawRoundRect(barFillRect, barR, barR, paint);
            }
        }

        // 4. Animated controls (Center by default, or Left / Right)
        float ctrlTop = barTop + barH + GAP_BAR_CONTROLS * u;
        float ctrlH = CTRL_HEIGHT * u;
        float ctrlCy = ctrlTop + ctrlH / 2f;

        float splitY = (barTop + barH + ctrlTop) / 2f;
        float seekTop = Math.max(artistBaseline + dp(2), barTop - dp(16));
        float seekBottom = Math.min(barTop + barH + dp(14), splitY - dp(1));
        seekHitRect.set(barLeft, seekTop, barRight, Math.max(barTop + barH, seekBottom));

        float unitCtrl = ctrlH / 154f;
        float nextW = 221f * unitCtrl;
        float spacing = 330f * unitCtrl;
        float prevCx, playCx, nextCx;
        if (cfg.controlsAlign == Config.CONTROLS_LEFT) {
            prevCx = barLeft + nextW / 2f + 10f * unitCtrl;
            playCx = prevCx + spacing;
            nextCx = playCx + spacing;
        } else if (cfg.controlsAlign == Config.CONTROLS_RIGHT) {
            nextCx = barRight - nextW / 2f - 10f * unitCtrl;
            playCx = nextCx - spacing;
            prevCx = playCx - spacing;
        } else {
            playCx = (barLeft + barRight) / 2f;
            prevCx = playCx - spacing;
            nextCx = playCx + spacing;
        }

        float hitHalfW = spacing * 0.48f;
        float hitHalfH = Math.max(dp(22), ctrlH * 0.80f);
        float btnHitTop = Math.max(splitY + dp(1), ctrlCy - hitHalfH);
        float btnHitBot = Math.min(h, ctrlCy + hitHalfH);
        prevHitRect.set(Math.min(barLeft, prevCx - hitHalfW - dp(10)), btnHitTop, prevCx + hitHalfW, btnHitBot);
        if (cfg.controlsAlign != Config.CONTROLS_LEFT) {
            prevHitRect.left = prevCx - hitHalfW - dp(10);
        }
        playHitRect.set(playCx - hitHalfW, btnHitTop, playCx + hitHalfW, btnHitBot);
        nextHitRect.set(nextCx - hitHalfW, btnHitTop,
                cfg.controlsAlign == Config.CONTROLS_RIGHT ? barRight + dp(12) : nextCx + hitHalfW + dp(10),
                btnHitBot);

        drawControlGlow(canvas, prevCx, ctrlCy, ctrlH * 0.85f, btnGlow[0], fg);
        drawControlGlow(canvas, playCx, ctrlCy, ctrlH * 0.85f, btnGlow[1], fg);
        drawControlGlow(canvas, nextCx, ctrlCy, ctrlH * 0.85f, btnGlow[2], fg);

        ensureArrowPaths(unitCtrl);
        drawDoubleArrow(canvas, prevCx, ctrlCy, true, btnScale[0], fgShader, fg);
        drawPlayPause(canvas, playCx, ctrlCy, unitCtrl, isPlaying, btnScale[1], fgShader, fg);
        drawDoubleArrow(canvas, nextCx, ctrlCy, false, btnScale[2], fgShader, fg);
        paint.setShader(null);
    }

    /**
     * Sung-inspired Material 3 Expressive SeekBar (SeekBar.qml):
     * - Active wavy line (3dp stroke, 28dp wavelength, 1 cycle/sec, amplitude morphing 3dp <-> 1dp on defaultEffects)
     * - Vertical pill handle (4dp wide, narrowing to 2dp while seeking) with 6dp gaps on both sides
     * - Rounded inactive track with 2dp inner corners facing the handle + 4dp stop indicator dot at the end.
     */
    private void drawMaterialWavyProgress(Canvas canvas, float barLeft, float barRight, float barTop, float barH,
                                          float frac, boolean isPlaying, int fg, LinearGradient fgShader,
                                          int fgTrackBg, float d, float u) {
        long nowMs = SystemClock.uptimeMillis();
        float dt = lastMaterialFrameMs == 0L ? 0.016f : Math.min(0.08f, Math.max(0f, (nowMs - lastMaterialFrameMs) / 1000f));
        lastMaterialFrameMs = nowMs;

        // Critically damped M3 defaultEffects transitions (~166ms settle)
        materialWaveAmp = stepToward(materialWaveAmp, isPlaying ? 1f : 0f, dt / 0.17f);
        m3HandlePress = stepToward(m3HandlePress, seeking ? 1f : 0f, dt / 0.10f);

        float barCy = barTop + barH * 0.5f;
        float strokeW = Math.max(d * 2.8f, BAR_HEIGHT * 0.72f * u);
        float trackH = Math.max(d * 6.5f, barH * 0.54f);
        float handleW = Math.max(d * 2.0f, strokeW * (1.15f - 0.52f * m3HandlePress));
        float handleH = Math.max(barH * 1.12f, strokeW * 5.2f);
        float gap = Math.max(d * 5.5f, strokeW * 1.65f);
        float stopSize = Math.max(d * 3.5f, strokeW * 1.05f);

        float thumbCenter = barLeft + handleW * 0.5f + Math.max(0f, barRight - barLeft - handleW) * frac;

        // 1. Inactive track (after handle + gap)
        float inactiveStart = thumbCenter + handleW * 0.5f + gap;
        if (barRight - inactiveStart > d * 2f) {
            float halfT = trackH * 0.5f;
            float outerR = halfT;
            float innerR = Math.min(halfT, Math.max(d * 1.5f, trackH * 0.22f));
            barFillRect.set(inactiveStart, barCy - halfT, barRight, barCy + halfT);
            materialWavePath.reset();
            materialWavePath.addRoundRect(barFillRect,
                    new float[]{innerR, innerR, outerR, outerR, outerR, outerR, innerR, innerR},
                    Path.Direction.CW);
            paint.setColorFilter(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(subShaderFor(fgTrackBg));
            paint.setColor(fgTrackBg);
            canvas.drawPath(materialWavePath, paint);
        }

        // 2. Stop indicator dot at the end of the track
        float stopCx = barRight - gap * 0.65f - stopSize * 0.5f;
        if (inactiveStart < stopCx - stopSize * 0.5f) {
            paint.setColorFilter(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(fgShader);
            paint.setColor(fg);
            canvas.drawCircle(stopCx, barCy, stopSize * 0.5f, paint);
        }

        // 3. Active wavy line (before handle - gap)
        float waveEnd = thumbCenter - handleW * 0.5f - gap;
        if (waveEnd > barLeft + d) {
            float len = waveEnd - barLeft;
            float amp = strokeW * (0.28f + 0.72f * materialWaveAmp);
            float wavelength = Math.max(d * 24f, strokeW * 7.2f);
            float phase = ((nowMs % 1000L) / 1000f) * (float) (2.0 * Math.PI);
            float taperLen = Math.min(len * 0.30f, strokeW * 1.4f);
            float sampleStep = Math.max(2f, d * 1.5f);

            materialWavePaint.setColorFilter(null);
            materialWavePaint.setStyle(Paint.Style.STROKE);
            materialWavePaint.setStrokeWidth(strokeW);
            materialWavePaint.setStrokeCap(Paint.Cap.ROUND);
            materialWavePaint.setStrokeJoin(Paint.Join.ROUND);
            materialWavePaint.setShader(fgShader);
            materialWavePaint.setColor(fg);

            materialWavePath.reset();
            materialWavePath.moveTo(barLeft, barCy);
            for (float x = sampleStep; x < len; x += sampleStep) {
                float env = 1f;
                if (x < taperLen) env = x / taperLen;
                else if (len - x < taperLen) env = (len - x) / taperLen;
                float y = barCy + amp * env * (float) Math.sin((x / wavelength) * (2.0 * Math.PI) - phase);
                materialWavePath.lineTo(barLeft + x, y);
            }
            materialWavePath.lineTo(waveEnd, barCy);
            canvas.drawPath(materialWavePath, materialWavePaint);
        }

        // 4. Vertical pill handle (SeekBar.qml seekHandle)
        barFillRect.set(thumbCenter - handleW * 0.5f, barCy - handleH * 0.5f,
                thumbCenter + handleW * 0.5f, barCy + handleH * 0.5f);
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(fgShader);
        paint.setColor(fg);
        canvas.drawRoundRect(barFillRect, handleW * 0.5f, handleW * 0.5f, paint);

        boolean animActive = isPlaying
                || (materialWaveAmp > 0.01f && materialWaveAmp < 0.99f)
                || (m3HandlePress > 0.01f && m3HandlePress < 0.99f);
        if (animActive && isAttachedToWindow() && getWindowVisibility() == VISIBLE && !isHostSleeping()) {
            postInvalidateOnAnimation();
        }
    }

    private static float stepToward(float cur, float target, float maxStep) {
        if (Math.abs(cur - target) <= maxStep) return target;
        return cur < target ? cur + maxStep : cur - maxStep;
    }

    private void drawCoverCard(Canvas canvas, RectF rect, Bitmap art, int fg, float d) {
        float cardSize = rect.width();
        float cornerR = cfg.coverStyle == Config.COVER_MATERIAL
                ? cardSize * 0.16f
                : Math.min(cardSize * 0.499f, cardSize * (cfg.cornerPct / 100f));

        drawCoverCardWithRadius(canvas, rect, art, fg, d, cornerR, 255);
    }

    private void drawCoverCardWithRadius(Canvas canvas, RectF rect, Bitmap art, int fg, float d,
                                         float cornerR, int alpha) {
        float cardSize = rect.width();
        if (art != null && !art.isRecycled() && art.getWidth() > 0 && art.getHeight() > 0) {
            if (art == noArtworkBmp && cornerR <= d * 2.5f) {
                Bitmap baked = getOrBuildNoArtworkCard(fg, d);
                if (baked != null) {
                    coverPaint.setShader(null);
                    coverPaint.setColorFilter(null);
                    coverPaint.setAlpha(alpha);
                    canvas.drawBitmap(baked, null, rect, coverPaint);
                    return;
                }
            }
            int bw = art.getWidth();
            int bh = art.getHeight();
            int crop = Math.min(bw, bh);
            int left = (bw - crop) / 2;
            int top = (bh - crop) / 2;
            cropSrcRect.set(left, top, left + crop, top + crop);

            // Fast path for tiny retro corner radius: skip expensive HWUI clipPath on 3D-rotated Canvas
            if (cornerR <= d * 2.5f) {
                coverPaint.setShader(null);
                coverPaint.setColorFilter(null);
                coverPaint.setAlpha(alpha);
                canvas.drawBitmap(art, cropSrcRect, rect, coverPaint);
            } else {
                int save = canvas.save();
                coverClipPath.reset();
                coverClipPath.addRoundRect(rect, cornerR, cornerR, Path.Direction.CW);
                canvas.clipPath(coverClipPath);
                coverPaint.setShader(null);
                coverPaint.setColorFilter(null);
                coverPaint.setAlpha(alpha);
                canvas.drawBitmap(art, cropSrcRect, rect, coverPaint);
                canvas.restoreToCount(save);
            }
        } else {
            paint.setColorFilter(null);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(MaterialYou.withAlpha(0xFF2B2B2B, alpha));
            canvas.drawRoundRect(rect, cornerR, cornerR, paint);
            drawFallbackNote(canvas, rect.centerX(), rect.centerY(), cardSize * 0.24f, fg);
        }

        paint.setColorFilter(null);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, d * 0.6f));
        paint.setColor(MaterialYou.withAlpha(fg, Math.round(28f * (alpha / 255f))));
        canvas.drawRoundRect(rect, cornerR, cornerR, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private Bitmap getOrBuildNoArtworkCard(int fg, float d) {
        if (noArtworkCardBmp != null && !noArtworkCardBmp.isRecycled()) return noArtworkCardBmp;
        if (noArtworkBmp == null || noArtworkBmp.isRecycled()) return null;
        int sz = 320;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        RectF r = new RectF(0, 0, sz, sz);
        float cr = Math.max(2f, d * 1.5f * (sz / 420f));
        Path p = new Path();
        p.addRoundRect(r, cr, cr, Path.Direction.CW);
        c.save();
        c.clipPath(p);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(noArtworkBmp, null, r, bp);
        c.restore();
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeWidth(1.5f);
        sp.setColor(MaterialYou.withAlpha(fg, 28));
        c.drawRoundRect(r, cr, cr, sp);
        out.prepareToDraw();
        noArtworkCardBmp = out;
        return out;
    }

    /**
     * Port of mtoc HorizontalAlbumBrowser Cover Flow carousel + optional reflection & shadow.
     * Matches mtoc proportions: uniform 0.29w slice progression per slot on both left and right,
     * 65 deg Y-axis 3D tilt (outer edge taller, inner edge receding), scale 0.77, and optional
     * reflection & hairline shadow. Side slots always render noartwork.png until active.
     */
    private void drawRetroCarousel(Canvas canvas, float cx, float cy, float covSize, float availW,
                                   Bitmap currentArt, int fg, float d) {
        lastCarouselCovSize = covSize;
        lastCarouselCovCx = cx;
        lastCarouselCovCy = cy;
        carouselStepPx = Math.max(dp(84), covSize * 0.525f);

        float reflH = cfg.retroReflection ? covSize * 0.30f : 0f;
        float shadowPad = cfg.retroShadow ? dp(14) : dp(6);
        float clipTop = cy - covSize * 0.70f - shadowPad;
        float clipBottom = cy + covSize * 0.50f + reflH + shadowPad;
        float halfSpan = Math.max(availW * 0.52f, covSize * 1.65f);

        int clipSave = canvas.save();
        canvas.clipRect(cx - halfSpan, clipTop, cx + halfSpan, clipBottom);

        // Sort slots [-3..+3] by descending |slot - carouselPos| so center draws on top; keep max 2 visible per side at rest
        int[] order = {-3, -2, -1, 0, 1, 2, 3};
        for (int i = 0; i < order.length - 1; i++) {
            for (int j = i + 1; j < order.length; j++) {
                float di = Math.abs(order[i] - carouselPos);
                float dj = Math.abs(order[j] - carouselPos);
                if (di < dj) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                }
            }
        }

        for (int slot : order) {
            float rel = slot - carouselPos;
            if (Math.abs(rel) > 2.35f) continue;
            Bitmap slotBmp = retroSlotBitmap(slot, currentArt);
            Bitmap fadeFrom = null;
            float fadeAlpha = 1f;
            if (slot == 0 && retroArtFade < 0.999f) {
                fadeFrom = retroFadeOldArt;
                fadeAlpha = retroArtFade;
            } else if (slot == -lastFlipDir && Math.abs(carouselPos) > 0.001f
                    && retroOutgoingArt != null && !retroOutgoingArt.isRecycled()) {
                // Outgoing old cover fades out into noArtworkBmp as it slides off to the side (rel: 0 -> +-1)
                float outFade = Math.max(0f, Math.min(1f, 1f - Math.abs(rel)));
                if (outFade > 0.01f) {
                    fadeFrom = noArtworkBmp;
                    slotBmp = retroOutgoingArt;
                    fadeAlpha = outFade;
                }
            }
            drawCarouselSlot(canvas, cx, cy, covSize, rel, slotBmp, fadeFrom, fadeAlpha, fg, d);
        }

        canvas.restoreToCount(clipSave);
    }

    private Bitmap retroSlotBitmap(int slot, Bitmap currentArt) {
        ensureNoArtwork(getContext());
        if (slot == 0) {
            if (currentArt != null && !currentArt.isRecycled()) return currentArt;
            return noArtworkBmp;
        }
        return noArtworkBmp;
    }

    private void drawCarouselSlot(Canvas canvas, float cx, float cy, float covSize, float rel,
                                  Bitmap art, Bitmap fadeFromArt, float fadeAlpha, int fg, float d) {
        float absRel = Math.abs(rel);
        float sign = rel >= 0f ? 1f : -1f;

        // Equal slice progression (~0.29w per album) after shifting clear of center edge (+/-0.315w)
        float initialGap;
        if (absRel < 0.35f) {
            initialGap = covSize * 0.315f * (absRel / 0.35f);
        } else {
            initialGap = covSize * 0.315f;
        }
        float itemCx = cx + sign * (absRel * covSize * 0.29f + initialGap);

        // Y-axis rotation: 0 near center -> -sign * 65 deg by slot +-1 (outer edge forward/taller, inner edge receding like mtoc)
        float angleY;
        if (absRel < 0.16f) {
            angleY = 0f;
        } else if (absRel < 0.90f) {
            float progress = (absRel - 0.16f) / 0.74f;
            angleY = -sign * progress * 65f;
        } else {
            angleY = -sign * 65f;
        }

        // Uniform 0.77 side height (outer edge ~0.85h, inner receding edge ~0.70h)
        float scale;
        if (absRel < 0.08f) {
            scale = 1.0f;
        } else if (absRel < 0.90f) {
            float t = (absRel - 0.08f) / 0.82f;
            scale = 1.0f - 0.23f * t;
        } else {
            scale = 0.77f;
        }
        if (absRel < 0.5f) {
            float pressBlend = 1f - absRel * 2f;
            scale *= 1f - (1f - centerCoverScale) * pressBlend;
        }

        // Only 2 side covers per side at rest (fade out cleanly beyond |rel| > 2.02 during swipes)
        int alpha = absRel <= 2.02f ? 255
                : Math.max(0, Math.round(255f * (1f - (absRel - 2.02f) / 0.33f)));
        if (alpha <= 0) return;

        int save = canvas.save();
        if (Math.abs(angleY) > 0.3f) {
            coverCamera.save();
            coverCamera.setLocation(0f, 0f, -Math.max(16f, covSize * 3.8f / 72f));
            coverCamera.rotateY(angleY);
            coverCamera.getMatrix(coverCamMatrix);
            coverCamera.restore();
            coverCamMatrix.preTranslate(-itemCx, -cy);
            coverCamMatrix.postTranslate(itemCx, cy);
            canvas.concat(coverCamMatrix);
        }
        canvas.scale(scale, scale, itemCx, cy);

        float half = covSize / 2f;
        retroItemRect.set(itemCx - half, cy - half, itemCx + half, cy + half);
        float cornerR = Math.max(1f, dp(1.5f));

        if (cfg.retroShadow) {
            drawCoverShadow(canvas, retroItemRect, cornerR, alpha, d);
        }
        if (fadeFromArt != null && !fadeFromArt.isRecycled() && fadeAlpha < 0.999f) {
            float clampedFade = Math.max(0f, Math.min(1f, fadeAlpha));
            int botReflAlpha = Math.round(alpha * (1f - clampedFade));
            if (cfg.retroReflection && botReflAlpha > 0) {
                drawCoverReflection(canvas, retroItemRect, fadeFromArt, fg, cornerR, botReflAlpha, d);
            }
            drawCoverCardWithRadius(canvas, retroItemRect, fadeFromArt, fg, d, cornerR, alpha);
            int topAlpha = Math.round(alpha * clampedFade);
            if (topAlpha > 0) {
                if (cfg.retroReflection) {
                    drawCoverReflection(canvas, retroItemRect, art, fg, cornerR, topAlpha, d);
                }
                drawCoverCardWithRadius(canvas, retroItemRect, art, fg, d, cornerR, topAlpha);
            }
        } else {
            if (cfg.retroReflection) {
                drawCoverReflection(canvas, retroItemRect, art, fg, cornerR, alpha, d);
            }
            drawCoverCardWithRadius(canvas, retroItemRect, art, fg, d, cornerR, alpha);
        }
        canvas.restoreToCount(save);
    }

    private void drawCoverShadow(Canvas canvas, RectF rect, float cornerR, int alpha, float d) {
        Bitmap sb = getOrBuildShadowBitmap();
        float pad = Math.max(2f, rect.width() * 0.045f);
        float dy = Math.max(1f, d * 0.6f);
        retroShadowRect.set(rect.left - pad, rect.top - pad * 0.45f + dy,
                rect.right + pad, rect.bottom + pad + dy);
        coverPaint.setShader(null);
        coverPaint.setColorFilter(null);
        coverPaint.setAlpha(alpha);
        canvas.drawBitmap(sb, null, retroShadowRect, coverPaint);
    }

    private static Bitmap getOrBuildShadowBitmap() {
        if (retroShadowBmp != null && !retroShadowBmp.isRecycled()) return retroShadowBmp;
        int sz = 160;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.FILL);
        float pad = sz * 0.045f;
        float[] spreads = {pad * 0.95f, pad * 0.40f};
        int[] alphas = {16, 26};
        for (int i = 0; i < spreads.length; i++) {
            float inset = pad - spreads[i];
            sp.setColor(Color.argb(alphas[i], 0, 0, 0));
            c.drawRoundRect(new RectF(inset, inset, sz - inset, sz - inset), 6f, 6f, sp);
        }
        out.prepareToDraw();
        retroShadowBmp = out;
        return out;
    }

    private void drawCoverReflection(Canvas canvas, RectF rect, Bitmap art, int fg,
                                     float cornerR, int alpha, float d) {
        float covSize = rect.width();
        float reflH = covSize * 0.30f;
        float gap = Math.max(1f, d * 0.8f);
        float reflTop = rect.bottom + gap;
        retroReflRect.set(rect.left, reflTop, rect.right, reflTop + reflH);

        Bitmap cachedRefl = getOrBuildReflectionBitmap(art);
        if (cachedRefl != null && !cachedRefl.isRecycled()) {
            coverPaint.setShader(null);
            coverPaint.setColorFilter(null);
            coverPaint.setAlpha(alpha);
            canvas.drawBitmap(cachedRefl, null, retroReflRect, coverPaint);
            return;
        }

        paint.setColorFilter(null);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MaterialYou.withAlpha(0xFF2B2B2B, Math.round(alpha * 0.32f)));
        canvas.drawRect(retroReflRect, paint);
    }

    private void prewarmReflection(Bitmap art) {
        if (art == null || art.isRecycled()) return;
        synchronized (reflCache) {
            Bitmap b = reflCache.get(art);
            if (b != null && !b.isRecycled()) return;
        }
        reflExec.execute(() -> {
            if (art.isRecycled()) return;
            Bitmap built = buildReflectionBitmap(art);
            if (built != null) {
                synchronized (reflCache) {
                    reflCache.put(art, built);
                }
                postInvalidate();
            }
        });
    }

    private Bitmap getOrBuildReflectionBitmap(Bitmap art) {
        if (art == null || art.isRecycled() || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return null;
        }
        if (art == noArtworkBmp) {
            if (noArtworkReflBmp == null || noArtworkReflBmp.isRecycled()) {
                noArtworkReflBmp = buildReflectionBitmap(noArtworkBmp);
            }
            return noArtworkReflBmp;
        }
        synchronized (reflCache) {
            Bitmap cached = reflCache.get(art);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        Bitmap built = buildReflectionBitmap(art);
        if (built != null) {
            synchronized (reflCache) {
                reflCache.put(art, built);
            }
        }
        return built;
    }

    private static Bitmap buildReflectionBitmap(Bitmap src) {
        int bw = src.getWidth(), bh = src.getHeight();
        int crop = Math.min(bw, bh);
        int left = (bw - crop) / 2;
        int top = (bh - crop) / 2;
        int outW = 240;
        int outH = 72; // 0.30 * 240
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int srcTop = top + Math.round(crop * 0.70f);
        android.graphics.Rect sRect = new android.graphics.Rect(left, srcTop, left + crop, top + crop);
        RectF dRect = new RectF(0f, 0f, outW, outH);
        c.save();
        c.scale(1f, -1f, 0f, outH / 2f);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, sRect, dRect, bp);
        c.restore();

        LinearGradient fade = new LinearGradient(
                0f, 0f, 0f, outH,
                new int[]{0x70FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        maskPaint.setShader(fade);
        c.drawRect(0f, 0f, outW, outH, maskPaint);
        out.prepareToDraw();
        return out;
    }

    private void drawFallbackNote(Canvas canvas, float cx, float cy, float size, int fg) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MaterialYou.withAlpha(fg, 90));
        notePath.reset();
        float r = size * 0.22f;
        float stemW = size * 0.14f;
        float top = cy - size * 0.45f;
        float bot = cy + size * 0.28f;
        notePath.addCircle(cx - size * 0.16f, bot, r, Path.Direction.CW);
        notePath.addRect(cx - size * 0.16f + r - stemW, top, cx - size * 0.16f + r, bot, Path.Direction.CW);
        notePath.addRect(cx - size * 0.16f + r - stemW, top, cx + size * 0.34f, top + size * 0.18f, Path.Direction.CW);
        canvas.drawPath(notePath, paint);
    }

    private void drawControlGlow(Canvas canvas, float cx, float cy, float radius, float glow, int fg) {
        if (glow <= 0.01f) return;
        int c = MaterialYou.withAlpha(fg, Math.round(56f * glow));
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(subShaderFor(c));
        paint.setColor(c);
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private void ensureArrowPaths(float s) {
        if (Math.abs(cachedArrowUnit - s) < 0.001f) return;
        cachedArrowUnit = s;

        float halfH = 74f * s;
        float triW = 122f * s;
        float cornerRadius = 26f * s;

        buildTriangle(triRawLeft, -108f * s, -halfH, triW, halfH * 2f);
        buildTriangle(triRawRight, -16f * s, -halfH, triW, halfH * 2f);

        pathToolPaint.setStyle(Paint.Style.FILL);
        pathToolPaint.setPathEffect(new CornerPathEffect(cornerRadius));
        triRoundLeft.reset();
        triRoundRight.reset();
        pathToolPaint.getFillPath(triRawLeft, triRoundLeft);
        pathToolPaint.getFillPath(triRawRight, triRoundRight);

        pathToolPaint.setStyle(Paint.Style.STROKE);
        pathToolPaint.setStrokeWidth(38f * s);
        pathToolPaint.setStrokeCap(Paint.Cap.ROUND);
        pathToolPaint.setStrokeJoin(Paint.Join.ROUND);
        pathToolPaint.setPathEffect(null);
        triGapMask.reset();
        pathToolPaint.getFillPath(triRoundLeft, triGapMask);

        nextArrowPath.set(triRoundRight);
        nextArrowPath.op(triGapMask, Path.Op.DIFFERENCE);
        nextArrowPath.op(triRoundLeft, Path.Op.UNION);

        float pW = 132f * s;
        float pH = 150f * s;
        buildTriangle(triRawRight, -pW * 0.42f, -pH / 2f, pW, pH);
        pathToolPaint.setStyle(Paint.Style.FILL);
        pathToolPaint.setPathEffect(new CornerPathEffect(cornerRadius));
        playTriPath.reset();
        pathToolPaint.getFillPath(triRawRight, playTriPath);
        pathToolPaint.setPathEffect(null);
    }

    private void drawDoubleArrow(Canvas canvas, float cx, float cy, boolean flipLeft, float scale,
                                 LinearGradient shader, int fg) {
        int save = canvas.save();
        canvas.translate(cx, cy);
        if (flipLeft) canvas.scale(-scale, scale);
        else canvas.scale(scale, scale);

        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
        paint.setColor(fg);
        canvas.drawPath(nextArrowPath, paint);
        canvas.restoreToCount(save);
    }

    private static void buildTriangle(Path p, float left, float top, float w, float h) {
        p.reset();
        p.moveTo(left, top);
        p.lineTo(left + w, top + h / 2f);
        p.lineTo(left, top + h);
        p.close();
    }

    private void drawPlayPause(Canvas canvas, float cx, float cy, float s, boolean playing, float scale,
                               LinearGradient shader, int fg) {
        int save = canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
        paint.setColor(fg);
        if (playing) {
            float barW = 38f * s;
            float barH = 154f * s;
            float gap = 38f * s;
            float rx = 19f * s;
            pauseBarRect.set(-gap / 2f - barW, -barH / 2f, -gap / 2f, barH / 2f);
            canvas.drawRoundRect(pauseBarRect, rx, rx, paint);
            pauseBarRect.set(gap / 2f, -barH / 2f, gap / 2f + barW, barH / 2f);
            canvas.drawRoundRect(pauseBarRect, rx, rx, paint);
        } else {
            canvas.drawPath(playTriPath, paint);
        }
        canvas.restoreToCount(save);
    }

    private boolean isInsideCoverHit(float x, float y, float slop) {
        return containsSloppy(coverRect, x, y, slop);
    }

    private boolean isInsideCarouselBand(float x, float y) {
        if (cfg.coverStyle != Config.COVER_RETRO) return false;
        float half = lastCarouselCovSize * 0.5f;
        float top = lastCarouselCovCy - half - dp(8);
        float bottom = lastCarouselCovCy + half + dp(8);
        return y >= top && y <= bottom && x >= 0f && x <= getWidth();
    }

    boolean canHandleTouchAt(float x, float y) {
        return prevHitRect.contains(x, y)
                || playHitRect.contains(x, y)
                || nextHitRect.contains(x, y)
                || seekHitRect.contains(x, y)
                || isInsideCoverHit(x, y, 0f)
                || isInsideCarouselBand(x, y);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (canHandleTouchAt(event.getX(), event.getY())) {
                disallowParentIntercept(true);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeHit = HIT_NONE;
                draggingCarousel = false;
                downX = x;
                downY = y;
                if (prevHitRect.contains(x, y)) {
                    activeHit = HIT_PREV;
                    animateButton(0, true);
                    disallowParentIntercept(true);
                    return true;
                } else if (playHitRect.contains(x, y)) {
                    activeHit = HIT_PLAY;
                    animateButton(1, true);
                    disallowParentIntercept(true);
                    return true;
                } else if (nextHitRect.contains(x, y)) {
                    activeHit = HIT_NEXT;
                    animateButton(2, true);
                    disallowParentIntercept(true);
                    return true;
                } else if (seekHitRect.contains(x, y)) {
                    activeHit = HIT_SEEK;
                    seeking = true;
                    updateSeek(x);
                    disallowParentIntercept(true);
                    return true;
                } else if (isInsideCoverHit(x, y, 0f) || isInsideCarouselBand(x, y)) {
                    activeHit = HIT_COVER;
                    if (isInsideCoverHit(x, y, 0f)) animateCenterCover(true);
                    carouselDragStartX = x;
                    if (cfg.coverStyle == Config.COVER_RETRO) {
                        disallowParentIntercept(true);
                    }
                    return true;
                }
                return super.onTouchEvent(event);

            case MotionEvent.ACTION_MOVE:
                if (activeHit == HIT_SEEK) {
                    updateSeek(x);
                    return true;
                }
                if (activeHit == HIT_COVER) {
                    float dx = x - downX;
                    float dy = y - downY;
                    if (cfg.coverStyle == Config.COVER_RETRO) {
                        if (!draggingCarousel && Math.abs(dx) > touchSlop && Math.abs(dx) >= Math.abs(dy)) {
                            draggingCarousel = true;
                            animateCenterCover(false);
                            if (carouselAnim != null) carouselAnim.cancel();
                            carouselDragStartX = x;
                        }
                        if (draggingCarousel) {
                            float step = Math.max(1f, carouselStepPx);
                            carouselPos = Math.max(-1.15f, Math.min(1.15f, (carouselDragStartX - x) / step));
                            invalidate();
                            return true;
                        }
                    }
                    float coverSlop = Math.max(touchSlop * 2f, dp(18));
                    if (Math.hypot(dx, dy) > coverSlop) {
                        animateCenterCover(false);
                        activeHit = HIT_NONE;
                        return false;
                    }
                    return true;
                }
                if (activeHit == HIT_PREV || activeHit == HIT_PLAY || activeHit == HIT_NEXT) {
                    return true;
                }
                return super.onTouchEvent(event);

            case MotionEvent.ACTION_UP:
                if (activeHit == HIT_SEEK) {
                    updateSeek(x);
                    commitSeek();
                    seeking = false;
                    activeHit = HIT_NONE;
                    invalidate();
                    return true;
                }
                if (activeHit == HIT_COVER) {
                    animateCenterCover(false);
                    if (draggingCarousel) {
                        draggingCarousel = false;
                        if (carouselPos > 0.28f) {
                            skipTrackAction(1);
                        } else if (carouselPos < -0.28f) {
                            skipTrackAction(-1);
                        } else {
                            animateCarouselFrom(carouselPos);
                        }
                        activeHit = HIT_NONE;
                        return true;
                    }
                    float coverSlop = Math.max(touchSlop * 2f, dp(18));
                    if (Math.hypot(x - downX, y - downY) <= coverSlop) {
                        if (isInsideCoverHit(x, y, dp(10))) {
                            togglePlayPauseAction();
                        } else if (isInsideCarouselBand(x, y)) {
                            skipTrackAction(x < lastCarouselCovCx ? -1 : 1);
                        }
                    }
                    activeHit = HIT_NONE;
                    return true;
                }
                if (activeHit != HIT_NONE) {
                    performActionForHit(x, y);
                }
                releaseButtons();
                activeHit = HIT_NONE;
                return true;

            case MotionEvent.ACTION_CANCEL:
                seeking = false;
                if (draggingCarousel) {
                    draggingCarousel = false;
                    animateCarouselFrom(carouselPos);
                }
                animateCenterCover(false);
                releaseButtons();
                activeHit = HIT_NONE;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void togglePlayPauseAction() {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (demoMode) {
            demoPlaying = !demoPlaying;
            scheduleTicker();
            invalidate();
        } else {
            MediaTracker.togglePlayPause();
        }
    }

    private void animateCenterCover(boolean pressed) {
        float target = pressed ? 0.94f : 1f;
        if (centerCoverScale == target) return;
        if (coverPressAnim != null) coverPressAnim.cancel();
        coverPressAnim = ValueAnimator.ofFloat(centerCoverScale, target);
        coverPressAnim.setDuration(pressed ? 90 : 200);
        coverPressAnim.setInterpolator(pressed ? new DecelerateInterpolator(1.5f) : new OvershootInterpolator(1.8f));
        coverPressAnim.addUpdateListener(a -> {
            centerCoverScale = (float) a.getAnimatedValue();
            invalidate();
        });
        coverPressAnim.start();
    }

    private void animateButton(int index, boolean pressed) {
        if (btnAnims[index] != null) btnAnims[index].cancel();
        float targetScale = pressed ? 0.78f : 1f;
        btnAnims[index] = ValueAnimator.ofFloat(btnScale[index], targetScale);
        btnAnims[index].setDuration(pressed ? 90 : 240);
        btnAnims[index].setInterpolator(pressed ? new DecelerateInterpolator(1.6f) : new OvershootInterpolator(2.2f));
        btnAnims[index].addUpdateListener(a -> {
            btnScale[index] = (float) a.getAnimatedValue();
            invalidate();
        });
        btnAnims[index].start();

        if (glowAnims[index] != null) glowAnims[index].cancel();
        float targetGlow = pressed ? 1f : 0f;
        glowAnims[index] = ValueAnimator.ofFloat(btnGlow[index], targetGlow);
        glowAnims[index].setDuration(pressed ? 90 : 260);
        glowAnims[index].setInterpolator(new DecelerateInterpolator(1.5f));
        glowAnims[index].addUpdateListener(a -> {
            btnGlow[index] = (float) a.getAnimatedValue();
            invalidate();
        });
        glowAnims[index].start();
    }

    private void releaseButtons() {
        for (int i = 0; i < 3; i++) {
            if (btnScale[i] != 1f || btnGlow[i] > 0f) animateButton(i, false);
        }
    }

    private void updateSeek(float x) {
        float w = barRect.width();
        if (w <= 0f) return;
        seekFraction = Math.max(0f, Math.min(1f, (x - barRect.left) / w));
        invalidate();
    }

    private void commitSeek() {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (demoMode) {
            demoPositionMs = Math.round(seekFraction * demoDurationMs);
        } else if (media.durationMs > 0) {
            MediaTracker.seekTo(Math.round(seekFraction * media.durationMs));
        }
    }

    private static boolean containsSloppy(RectF r, float x, float y, float slop) {
        return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop;
    }

    private void skipTrackAction(int dir) {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (cfg.coverStyle == Config.COVER_RETRO) {
            triggerRetroFlip(dir);
        }
        if (demoMode) {
            demoPositionMs = 0L;
            if (demoCovers != null && demoCovers.length > 0) {
                demoTrackIndex = ((demoTrackIndex + dir) % demoCovers.length + demoCovers.length) % demoCovers.length;
                demoCover = demoCovers[demoTrackIndex];
            }
            invalidate();
        } else if (dir < 0) {
            MediaTracker.skipPrev();
        } else {
            MediaTracker.skipNext();
        }
    }

    private void performActionForHit(float x, float y) {
        float slop = dp(24);
        if (activeHit == HIT_PREV && containsSloppy(prevHitRect, x, y, slop)) {
            skipTrackAction(-1);
        } else if (activeHit == HIT_PLAY && containsSloppy(playHitRect, x, y, slop)) {
            togglePlayPauseAction();
        } else if (activeHit == HIT_NEXT && containsSloppy(nextHitRect, x, y, slop)) {
            skipTrackAction(1);
        }
    }

    private static String formatTime(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        long min = sec / 60L;
        long rem = sec % 60L;
        return String.format(Locale.ROOT, "%d:%02d", min, rem);
    }

    private void disallowParentIntercept(boolean disallow) {
        android.view.ViewParent p = getParent();
        for (int i = 0; i < 4 && p != null; i++) {
            p.requestDisallowInterceptTouchEvent(disallow);
            p = p.getParent();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
