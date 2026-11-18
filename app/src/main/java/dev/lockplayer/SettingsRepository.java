package dev.lockplayer;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

/** Data layer for settings delivery to System UI via ordered broadcast. */
final class SettingsRepository {
    interface Callback {
        void onDelivered(boolean linked, int wallpaperColor);
    }

    private static final String PREFS = "settings", KEY = "cfg", KEY_DEFAULTS_VER = "defaults_v";
    private static final int DEFAULTS_VER = 2;

    private final Context context;
    private final Handler main;

    SettingsRepository(Context context, Handler main) {
        this.context = context.getApplicationContext();
        this.main = main;
    }

    Config load() {
        String saved = prefs().getString(KEY, null);
        Config c = saved != null ? Config.parse(saved) : Config.read(context.getContentResolver());
        if (prefs().getInt(KEY_DEFAULTS_VER, 1) < DEFAULTS_VER) {
            c.colorSource = Config.COLOR_SYSTEM;
            c.cornerPct = 5;
            c.scalePct = 115;
            c.trackAlphaPct = 100;
            prefs().edit()
                    .putString(KEY, c.serialize())
                    .putInt(KEY_DEFAULTS_VER, DEFAULTS_VER)
                    .apply();
        }
        return c;
    }

    void save(Config cfg, Callback callback) {
        String serialized = cfg.serialize();
        prefs().edit().putString(KEY, serialized).putInt(KEY_DEFAULTS_VER, DEFAULTS_VER).apply();
        deliver(serialized, callback);
    }

    void deliver(String serialized, Callback callback) {
        Intent i = new Intent(ConfigReceiver.ACTION)
                .setPackage("com.android.systemui")
                .putExtra(ConfigReceiver.EXTRA, serialized);
        BroadcastReceiver ack = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent in) {
                Bundle result = getResultExtras(false);
                String echo = result != null ? result.getString(ConfigReceiver.EXTRA_ECHO) : null;
                if (echo != null && !echo.equals(prefs().getString(KEY, echo))) return;
                boolean ok = getResultCode() == Activity.RESULT_OK;
                int wallpaper = ok && result != null ? result.getInt(ConfigReceiver.EXTRA_WALLPAPER, 0) : 0;
                callback.onDelivered(ok, wallpaper);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle opts = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                context.sendOrderedBroadcast(i, null, opts, ack, main, Activity.RESULT_CANCELED, null, null);
            } else {
                context.sendOrderedBroadcast(i, null, ack, main, Activity.RESULT_CANCELED, null, null);
            }
        } catch (Throwable t) {
            callback.onDelivered(false, 0);
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
