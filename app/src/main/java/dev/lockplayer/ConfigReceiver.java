package dev.lockplayer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** Runs inside System UI to receive settings updates and reply with RESULT_OK + wallpaper average. */
final class ConfigReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.lockplayer.action.CONFIG";
    static final String EXTRA = "cfg";
    static final String EXTRA_ECHO = "cfg_echo";
    static final String EXTRA_WALLPAPER = "wallpaper_avg";
    private static final String APP = BuildConfig.APPLICATION_ID;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 34 && !APP.equals(getSentFromPackage())) return;
        String raw = intent.getStringExtra(EXTRA);
        if (raw == null || raw.length() > 4096) return;
        ConfigStore.update(context, Config.parse(raw));
        if (isOrderedBroadcast()) {
            Bundle result = getResultExtras(true);
            result.putString(EXTRA_ECHO, raw);
            int avg = WallpaperAverage.get(context, null);
            if (avg != 0) result.putInt(EXTRA_WALLPAPER, avg);
            setResultCode(Activity.RESULT_OK);
        }
    }
}
