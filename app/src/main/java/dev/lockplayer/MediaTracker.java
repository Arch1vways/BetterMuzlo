package dev.lockplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tracks the active media session inside System UI using {@link MediaSessionManager}.
 * Decodes album art asynchronously on a background thread with an LRU cache.
 */
final class MediaTracker {
    interface Listener {
        void onMediaChanged(Snapshot snapshot);
    }

    static final class Snapshot {
        String trackKey = "";
        String title = "";
        String artist = "";
        Bitmap art;
        int artColor;
        boolean playing;
        long durationMs;
        long basePositionMs;
        long updateElapsedMs;
        float speed = 1f;
        boolean hasSession;

        boolean equalsState(Snapshot o) {
            if (o == null) return false;
            return hasSession == o.hasSession
                    && playing == o.playing
                    && durationMs == o.durationMs
                    && basePositionMs == o.basePositionMs
                    && Float.compare(speed, o.speed) == 0
                    && artColor == o.artColor
                    && art == o.art
                    && trackKey.equals(o.trackKey)
                    && title.equals(o.title)
                    && artist.equals(o.artist);
        }

        long currentPositionMs() {
            if (durationMs <= 0) return Math.max(0, basePositionMs);
            if (!playing || updateElapsedMs <= 0) return clamp(basePositionMs, 0, durationMs);
            long dt = Math.max(0, SystemClock.elapsedRealtime() - updateElapsedMs);
            long pos = basePositionMs + Math.round(dt * speed);
            return clamp(pos, 0, durationMs);
        }

        private static long clamp(long v, long lo, long hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ART_EXEC = Executors.newSingleThreadExecutor();
    private static final ArrayList<WeakReference<Listener>> LISTENERS = new ArrayList<>();
    private static final Map<String, Bitmap> ART_CACHE = new LinkedHashMap<>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Entry<String, Bitmap> eldest) {
            return size() > 20;
        }
    };
    private static final Map<String, Integer> COLOR_CACHE = new LinkedHashMap<>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Entry<String, Integer> eldest) {
            return size() > 24;
        }
    };

    private static boolean initialized;
    private static Context appContext;
    private static MediaSessionManager msm;
    private static MediaController currentController;
    private static String lastTrackKey = "";
    private static Bitmap currentArt;
    private static int currentArtColor;
    private static int requestSeq = 0;
    private static Snapshot latest = new Snapshot();

    private static final MediaController.Callback CONTROLLER_CB = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            refreshFromController();
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            refreshFromController();
        }

        @Override public void onSessionDestroyed() {
            pickActiveController();
        }
    };

    private MediaTracker() { }

    static synchronized void ensure(Context ctx) {
        if (initialized) return;
        initialized = true;
        appContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            msm = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                msm.addOnActiveSessionsChangedListener(controllers -> pickFromList(controllers), null, MAIN);
                pickActiveController();
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized void addListener(Listener l) {
        LISTENERS.add(new WeakReference<>(l));
        l.onMediaChanged(latest);
    }

    static Snapshot getSnapshot() {
        return latest;
    }

    static void togglePlayPause() {
        MediaController c = currentController;
        if (c == null) {
            pickActiveController();
            c = currentController;
        }
        if (c == null) {
            dispatchGlobalMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return;
        }
        try {
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING
                    || st.getState() == PlaybackState.STATE_BUFFERING);
            if (playing) c.getTransportControls().pause();
            else c.getTransportControls().play();
        } catch (Throwable ignored) {
            dispatchGlobalMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    static void skipNext() {
        MediaController c = currentController;
        if (c == null) {
            pickActiveController();
            c = currentController;
        }
        if (c == null) {
            dispatchGlobalMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            return;
        }
        try {
            c.getTransportControls().skipToNext();
            PlaybackState st = c.getPlaybackState();
            long actions = st != null ? st.getActions() : 0L;
            if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) == 0) {
                long now = SystemClock.uptimeMillis();
                c.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
                c.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
            }
        } catch (Throwable ignored) {
            dispatchGlobalMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        }
    }

    static void skipPrev() {
        MediaController c = currentController;
        if (c == null) {
            pickActiveController();
            c = currentController;
        }
        if (c == null) {
            dispatchGlobalMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return;
        }
        try {
            c.getTransportControls().skipToPrevious();
            PlaybackState st = c.getPlaybackState();
            long actions = st != null ? st.getActions() : 0L;
            if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0) {
                long now = SystemClock.uptimeMillis();
                c.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0));
                c.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0));
            }
        } catch (Throwable ignored) {
            dispatchGlobalMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    private static void dispatchGlobalMediaKey(int keyCode) {
        if (appContext == null) return;
        try {
            android.media.AudioManager am = appContext.getSystemService(android.media.AudioManager.class);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable ignored) {
        }
    }

    static void seekTo(long positionMs) {
        MediaController c = currentController;
        if (c == null) return;
        try {
            c.getTransportControls().seekTo(Math.max(0, positionMs));
            Snapshot next = copySnapshot(latest);
            next.basePositionMs = Math.max(0, positionMs);
            next.updateElapsedMs = SystemClock.elapsedRealtime();
            latest = next;
            notifyListeners();
        } catch (Throwable ignored) {
        }
    }

    static void refreshNow() {
        final MediaSessionManager mgr = msm;
        if (mgr == null) return;
        ART_EXEC.execute(() -> {
            try {
                final List<MediaController> sessions = mgr.getActiveSessions(null);
                MAIN.post(() -> pickFromList(sessions));
            } catch (Throwable ignored) {
            }
        });
    }

    private static void pickActiveController() {
        if (msm == null) return;
        try {
            pickFromList(msm.getActiveSessions(null));
        } catch (Throwable ignored) {
        }
    }

    private static void pickFromList(List<MediaController> list) {
        MediaController chosen = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null && currentController != null) {
                for (MediaController c : list) {
                    if (c.getSessionToken().equals(currentController.getSessionToken()) && c.getMetadata() != null) {
                        chosen = c;
                        break;
                    }
                }
            }
            if (chosen == null && !list.isEmpty()) {
                for (MediaController c : list) {
                    if (c.getMetadata() != null) {
                        chosen = c;
                        break;
                    }
                }
                if (chosen == null) chosen = list.get(0);
            }
        }
        if (currentController != chosen) {
            if (currentController != null) {
                try {
                    currentController.unregisterCallback(CONTROLLER_CB);
                } catch (Throwable ignored) {
                }
            }
            currentController = chosen;
            if (chosen != null) {
                try {
                    chosen.registerCallback(CONTROLLER_CB, MAIN);
                } catch (Throwable ignored) {
                }
            }
        }
        refreshFromController();
    }

    private static void refreshFromController() {
        MediaController c = currentController;
        final Snapshot s = new Snapshot();
        if (c == null) {
            boolean hadSession = latest != null && latest.hasSession;
            currentArt = null;
            currentArtColor = 0;
            lastTrackKey = "";
            latest = s;
            if (hadSession) notifyListeners();
            return;
        }
        s.hasSession = true;

        MediaMetadata md = null;
        try {
            md = c.getMetadata();
        } catch (Throwable ignored) {
        }
        PlaybackState st = null;
        try {
            st = c.getPlaybackState();
        } catch (Throwable ignored) {
        }

        if (st != null) {
            int state = st.getState();
            float spd = st.getPlaybackSpeed();
            s.playing = state == PlaybackState.STATE_PLAYING && spd > 0f;
            s.basePositionMs = Math.max(0, st.getPosition());
            s.updateElapsedMs = st.getLastPositionUpdateTime();
            s.speed = spd > 0f ? spd : 0f;
        }

        String artUri = null;
        Bitmap inlineBmp = null;
        if (md != null) {
            String title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null || title.isEmpty()) title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null || artist.isEmpty()) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null || artist.isEmpty()) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            s.title = title != null ? title : "";
            s.artist = artist != null ? artist : "";
            s.durationMs = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));

            artUri = firstUri(md);
            inlineBmp = firstBitmap(md);
        }

        final String trackKey = c.getPackageName() + "|" + s.title + "|" + s.artist + "|" + s.durationMs + "|" + (artUri != null ? artUri : "");
        s.trackKey = trackKey;
        boolean trackChanged = !trackKey.equals(lastTrackKey);
        if (trackChanged) {
            lastTrackKey = trackKey;
        }

        Bitmap cached = ART_CACHE.get(trackKey);
        Integer cachedColor = COLOR_CACHE.get(trackKey);
        if (cached != null && !cached.isRecycled()) {
            currentArt = cached;
            currentArtColor = cachedColor != null ? cachedColor : 0;
            s.art = cached;
            s.artColor = currentArtColor;
        } else if (!trackChanged && currentArt != null && !currentArt.isRecycled() && inlineBmp == null) {
            s.art = currentArt;
            s.artColor = currentArtColor;
        } else if (trackChanged) {
            currentArt = null;
            currentArtColor = 0;
        }

        boolean stateChanged = !s.equalsState(latest);
        latest = s;
        if (stateChanged) {
            notifyListeners();
        }

        final boolean needArt = (cached == null || cached.isRecycled()) && (inlineBmp != null || artUri != null);
        if (!needArt) return;

        final int seq = ++requestSeq;
        final Bitmap finalInlineBmp = inlineBmp;
        final String finalArtUri = artUri;

        ART_EXEC.execute(() -> {
            if (seq != requestSeq) return;
            Bitmap resolved = null;
            if (finalInlineBmp != null && !finalInlineBmp.isRecycled()) {
                resolved = toSquareSoftwareBitmap(finalInlineBmp, 448);
            }
            if (resolved == null && finalArtUri != null) {
                Bitmap decoded = loadUri(finalArtUri, 448);
                if (decoded != null) resolved = toSquareSoftwareBitmap(decoded, 448);
            }
            if (resolved == null || seq != requestSeq) return;

            final Bitmap finalResolved = resolved;
            final int dominant = MaterialYou.dominantColor(resolved);
            finalResolved.prepareToDraw();
            MAIN.post(() -> {
                ART_CACHE.put(trackKey, finalResolved);
                COLOR_CACHE.put(trackKey, dominant);
                if (seq != requestSeq && !trackKey.equals(lastTrackKey)) return;
                currentArt = finalResolved;
                currentArtColor = dominant;
                Snapshot updated = copySnapshot(latest);
                updated.art = finalResolved;
                updated.artColor = dominant;
                latest = updated;
                notifyListeners();
            });
        });
    }

    private static String firstUri(MediaMetadata md) {
        String[] uriKeys = {
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        };
        for (String key : uriKeys) {
            String u = md.getString(key);
            if (u != null && !u.isEmpty()) return u;
        }
        return null;
    }

    private static Bitmap firstBitmap(MediaMetadata md) {
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return bmp;
    }

    private static Bitmap loadUri(String u, int targetSize) {
        if (appContext == null || u == null || u.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(u);
            String scheme = uri.getScheme();
            if (!"content".equals(scheme) && !"file".equals(scheme) && !"android.resource".equals(scheme)) return null;
            ImageDecoder.Source src = ImageDecoder.createSource(appContext.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSize(targetSize, targetSize);
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap toSquareSoftwareBitmap(Bitmap src, int size) {
        try {
            Bitmap sw = src.getConfig() == Bitmap.Config.HARDWARE
                    ? src.copy(Bitmap.Config.ARGB_8888, false)
                    : src;
            if (sw == null || sw.isRecycled()) return null;
            int w = sw.getWidth();
            int h = sw.getHeight();
            if (w <= 0 || h <= 0) return null;
            int crop = Math.min(w, h);
            int left = (w - crop) / 2;
            int top = (h - crop) / 2;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(sw, new Rect(left, top, left + crop, top + crop), new Rect(0, 0, size, size), p);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Snapshot copySnapshot(Snapshot s) {
        Snapshot out = new Snapshot();
        out.trackKey = s.trackKey;
        out.title = s.title;
        out.artist = s.artist;
        out.art = s.art;
        out.artColor = s.artColor;
        out.playing = s.playing;
        out.durationMs = s.durationMs;
        out.basePositionMs = s.basePositionMs;
        out.updateElapsedMs = s.updateElapsedMs;
        out.speed = s.speed;
        out.hasSession = s.hasSession;
        return out;
    }

    private static synchronized void notifyListeners() {
        Snapshot s = latest;
        Iterator<WeakReference<Listener>> it = LISTENERS.iterator();
        while (it.hasNext()) {
            Listener l = it.next().get();
            if (l == null) it.remove();
            else l.onMediaChanged(s);
        }
    }
}
