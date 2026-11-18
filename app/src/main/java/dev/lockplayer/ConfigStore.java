package dev.lockplayer;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

/** Process-wide settings store inside System UI. */
final class ConfigStore {
    interface Listener { void onConfigChanged(Config cfg); }

    private static volatile Config current = new Config();
    private static boolean registered;
    private static final ArrayList<WeakReference<Listener>> LISTENERS = new ArrayList<>();

    private ConfigStore() { }

    static Config get() {
        return current;
    }

    static synchronized void addListener(Listener l) {
        LISTENERS.add(new WeakReference<>(l));
    }

    static void update(Context context, Config c) {
        current = c;
        try {
            Settings.Secure.putString(context.getContentResolver(), Config.KEY, c.serialize());
        } catch (Throwable ignored) {
        }
        notifyListeners();
    }

    private static synchronized void notifyListeners() {
        Config c = current;
        Iterator<WeakReference<Listener>> it = LISTENERS.iterator();
        while (it.hasNext()) {
            Listener l = it.next().get();
            if (l == null) it.remove();
            else l.onConfigChanged(c);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    static synchronized void ensure(Context context) {
        if (registered) return;
        registered = true;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final ContentResolver cr = app.getContentResolver();
        current = Config.read(cr);
        try {
            cr.registerContentObserver(Settings.Secure.getUriFor(Config.KEY), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange) {
                            Config c = Config.read(cr);
                            if (!c.serialize().equals(current.serialize())) {
                                current = c;
                                notifyListeners();
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            IntentFilter filter = new IntentFilter(ConfigReceiver.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(new ConfigReceiver(), filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(new ConfigReceiver(), filter);
            }
        } catch (Throwable ignored) {
        }
    }
}
