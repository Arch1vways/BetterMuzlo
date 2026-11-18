package dev.lockplayer;

import android.annotation.SuppressLint;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Computes and caches the average colour of the lock-screen / home wallpaper for System colour mode. */
final class WallpaperAverage {
    private static final int SAMPLE = 64;
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lockplayer-wallpaper");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile int cached;
    private static volatile long cachedAt;
    private static volatile int generation;
    private static boolean loading;

    private WallpaperAverage() { }

    static int cached() {
        return cached;
    }

    static int get(Context context, Runnable onChanged) {
        int value = cached;
        boolean stale = value == 0 || SystemClock.elapsedRealtime() - cachedAt > MAX_AGE_MS;
        if (stale) refresh(context, onChanged);
        return value;
    }

    static void invalidate(Context context, Runnable onChanged) {
        generation++;
        cachedAt = 0;
        refresh(context, onChanged);
    }

    private static void refresh(Context context, Runnable onChanged) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final int gen;
        synchronized (WallpaperAverage.class) {
            if (loading) return;
            loading = true;
            gen = generation;
        }
        IO.execute(() -> {
            int value = 0;
            try {
                value = compute(app);
            } catch (Throwable ignored) {
            }
            final int result = value;
            synchronized (WallpaperAverage.class) {
                loading = false;
            }
            MAIN.post(() -> {
                boolean changed = result != 0 && result != cached;
                if (result != 0) {
                    cached = result;
                    cachedAt = SystemClock.elapsedRealtime();
                }
                if (gen != generation) {
                    refresh(app, onChanged);
                } else if (changed && onChanged != null) {
                    onChanged.run();
                }
            });
        });
    }

    static int compute(Context context) {
        WallpaperManager wm = WallpaperManager.getInstance(context);
        Bitmap bitmap = decode(wm, WallpaperManager.FLAG_LOCK);
        if (bitmap == null) bitmap = decode(wm, WallpaperManager.FLAG_SYSTEM);
        if (bitmap == null) bitmap = render(wm);
        if (bitmap != null) {
            try {
                int w = bitmap.getWidth(), h = bitmap.getHeight();
                int[] px = new int[w * h];
                bitmap.getPixels(px, 0, w, 0, 0, w, h);
                int avg = MaterialYou.averageColor(px);
                if (avg != 0) return avg;
            } finally {
                bitmap.recycle();
            }
        }
        return fromWallpaperColors(wm);
    }

    static int fromWallpaperColors(Context context) {
        try {
            return fromWallpaperColors(WallpaperManager.getInstance(context));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int fromWallpaperColors(WallpaperManager wm) {
        for (int which : new int[]{WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM}) {
            try {
                WallpaperColors colors = wm.getWallpaperColors(which);
                if (colors == null || colors.getPrimaryColor() == null) continue;
                int p = colors.getPrimaryColor().toArgb();
                int[] mix = colors.getSecondaryColor() == null ? new int[]{p}
                        : colors.getTertiaryColor() == null
                        ? new int[]{p, p, colors.getSecondaryColor().toArgb()}
                        : new int[]{p, p, p, colors.getSecondaryColor().toArgb(), colors.getTertiaryColor().toArgb()};
                return MaterialYou.averageColor(mix);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    @SuppressLint("MissingPermission")
    private static Bitmap decode(WallpaperManager wm, int which) {
        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(which)) {
            if (pfd == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / SAMPLE);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            android.system.Os.lseek(pfd.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET);
            Bitmap full = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
            return scaleDown(full);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static Bitmap render(WallpaperManager wm) {
        try {
            Drawable d = wm.getDrawable();
            if (d == null) return null;
            Bitmap b = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, SAMPLE, SAMPLE);
            d.draw(new Canvas(b));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap full) {
        if (full == null) return null;
        if (full.getWidth() <= SAMPLE * 2 && full.getHeight() <= SAMPLE * 2) return full;
        Bitmap small = Bitmap.createScaledBitmap(full, SAMPLE, SAMPLE, true);
        if (small != full) full.recycle();
        return small;
    }
}
