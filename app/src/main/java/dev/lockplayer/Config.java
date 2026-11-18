package dev.lockplayer;

import android.content.ContentResolver;
import android.provider.Settings;
import java.util.Locale;

/**
 * Bettermuzlo settings stored as a single {@code Settings.Secure} string ("k=v;k=v").
 */
final class Config {
    static final String KEY = "lockplayer_cfg";
    static final int TIME_REMAINING = 0, TIME_DURATION = 1;
    static final int COLOR_ALBUM = 0, COLOR_SYSTEM = 1, COLOR_CUSTOM = 2;
    static final int COVER_STANDARD = 0, COVER_RETRO = 1, COVER_MATERIAL = 2;
    static final int CONTROLS_CENTER = 0, CONTROLS_LEFT = 1, CONTROLS_RIGHT = 2;

    boolean enabled = true;
    int coverStyle = COVER_STANDARD;
    boolean retroReflection = true;
    boolean retroShadow = true;
    int controlsAlign = CONTROLS_CENTER;
    int colorSource = COLOR_SYSTEM;
    int customColor = 0xFFFFFFFF;
    int timeMode = TIME_REMAINING;
    int scalePct = 115;
    int cornerPct = 5;
    int trackAlphaPct = 100;

    static Config parse(String s) {
        Config c = new Config();
        if (s == null || s.isEmpty()) return c;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "enabled": c.enabled = "1".equals(v) || "true".equalsIgnoreCase(v); break;
                    case "cover": c.coverStyle = clamp(Integer.parseInt(v), 0, 2); break;
                    case "retroReflect": c.retroReflection = "1".equals(v) || "true".equalsIgnoreCase(v); break;
                    case "retroShadow": c.retroShadow = "1".equals(v) || "true".equalsIgnoreCase(v); break;
                    case "controls": c.controlsAlign = clamp(Integer.parseInt(v), 0, 2); break;
                    case "color": c.colorSource = clamp(Integer.parseInt(v), 0, 2); break;
                    case "custom": c.customColor = 0xFF000000 | (int) (Long.parseLong(v, 16) & 0xFFFFFF); break;
                    case "time": c.timeMode = clamp(Integer.parseInt(v), 0, 1); break;
                    case "scale": c.scalePct = clamp(Integer.parseInt(v), 70, 115); break;
                    case "corner": c.cornerPct = clamp(Integer.parseInt(v), 0, 50); break;
                    case "trackAlpha": c.trackAlphaPct = clamp(Integer.parseInt(v), 10, 100); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return c;
    }

    String serialize() {
        return String.format(Locale.ROOT,
                "enabled=%d;cover=%d;retroReflect=%d;retroShadow=%d;controls=%d;color=%d;custom=%06x;time=%d;scale=%d;corner=%d;trackAlpha=%d",
                enabled ? 1 : 0, coverStyle, retroReflection ? 1 : 0, retroShadow ? 1 : 0, controlsAlign,
                colorSource, customColor & 0xFFFFFF,
                timeMode, scalePct, cornerPct, trackAlphaPct);
    }

    static Config read(ContentResolver cr) {
        try {
            return parse(Settings.Secure.getString(cr, KEY));
        } catch (Throwable t) {
            return new Config();
        }
    }

    static Integer parseHex(String text) {
        if (text == null) return null;
        String h = text.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) {
            h = new String(new char[]{h.charAt(0), h.charAt(0), h.charAt(1), h.charAt(1), h.charAt(2), h.charAt(2)});
        }
        if (h.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(h.charAt(i), 16) < 0) return null;
        }
        return 0xFF000000 | Integer.parseInt(h, 16);
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
