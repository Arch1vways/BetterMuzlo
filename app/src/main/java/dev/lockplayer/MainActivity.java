package dev.lockplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/**
 * Settings screen styled in the shared Material 3 language used across the companion modules:
 * Montserrat typography, rounded tonal cards, PixelSwitch, PixelSegmentedControl and PixelSliderThumbDrawable.
 */
public class MainActivity extends Activity {
    private static final int LINK_CHECKING = 0, LINK_OK = 1, LINK_FAIL = 2;
    private static final int W_MEDIUM = 500, W_SEMIBOLD = 600, W_BOLD = 700;
    private static final float WINDOW_TITLE_SP = 18f, DIALOG_TITLE_SP = 24f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable commit = this::save;
    private final SparseArray<Typeface> fonts = new SparseArray<>();

    private MaterialYou.Scheme sc;
    private boolean dark;
    private int surfaceHigh, surfaceHighest;
    private Config cfg;
    private TextView statusTitle, statusIcon;
    private LinearLayout statusCard;
    private View appearancePanel, customPanel, retroCard, controlsCard, shapeCard, cornerRow;
    private EditText hexInput;
    private View hexPreview;
    private Dialog colorPicker;
    private boolean hexEditing;
    private SettingsRepository repo;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dark = MaterialYou.isNight(this);
        sc = MaterialYou.fromSeed(0xFFB4558C, dark);
        surfaceHigh = MaterialYou.lerp(sc.surfaceContainer, sc.onSurface, dark ? 0.05f : 0.035f);
        surfaceHighest = MaterialYou.lerp(sc.surfaceContainer, sc.onSurface, dark ? 0.10f : 0.07f);

        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsController bars = getWindow().getDecorView().getWindowInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(dark ? 0 : light, light);
        }
        repo = new SettingsRepository(this, ui);
        cfg = repo.load();
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        repo.deliver(cfg.serialize(), this::onDelivered);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(commit);
        save();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (colorPicker != null && colorPicker.isShowing()) colorPicker.dismiss();
        colorPicker = null;
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(sc.surface);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int available = Math.max(0, v.getWidth() - b.left - b.right);
            int horizontal = Math.max(dp(available >= dp(600) ? 24 : 16), Math.max(0, (available - dp(840)) / 2));
            root.setPadding(b.left + horizontal, b.top + dp(8), b.right + horizontal, b.bottom + dp(40));
            return WindowInsets.CONSUMED;
        });

        buildStatus(root);
        buildGeneral(root);
        buildRetro(root);
        buildControls(root);
        buildAppearance(root);
        buildReset(root);
        refreshVisibility();
        refreshStatus(LINK_CHECKING);
        PullDownPromo.install(this, scroll, root);
    }

    private void buildStatus(LinearLayout root) {
        statusCard = card(sc.secondaryContainer);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setMinimumHeight(dp(96));

        statusIcon = text("", 24, sc.onPrimary, W_BOLD);
        statusIcon.setGravity(Gravity.CENTER);
        statusIcon.setIncludeFontPadding(false);
        statusIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusCard.addView(statusIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        statusTitle = text("", 18, sc.onSecondaryContainer, W_SEMIBOLD);
        texts.addView(statusTitle);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(16);
        statusCard.addView(texts, tlp);
        root.addView(statusCard);
    }

    private void buildGeneral(LinearLayout root) {
        LinearLayout card = card(sc.surfaceContainer);
        card.addView(sectionTitle(getString(R.string.section_general)));
        card.addView(switchRow(getString(R.string.enable_player), cfg.enabled, on -> {
            cfg.enabled = on;
            refreshVisibility();
        }));

        LinearLayout styleWrap = new LinearLayout(this);
        styleWrap.setOrientation(LinearLayout.VERTICAL);
        appearancePanel = styleWrap;
        styleWrap.addView(label(getString(R.string.cover_style)));
        styleWrap.addView(segmented(
                new String[]{
                        getString(R.string.cover_standard),
                        getString(R.string.cover_retro),
                        getString(R.string.cover_material)
                },
                cfg.coverStyle,
                i -> {
                    cfg.coverStyle = i;
                    refreshVisibility();
                    changed();
                }));
        card.addView(styleWrap);
        root.addView(card);
    }

    private void buildRetro(LinearLayout root) {
        LinearLayout card = card(sc.surfaceContainer);
        retroCard = card;
        card.addView(sectionTitle(getString(R.string.section_retro)));
        card.addView(switchRow(getString(R.string.retro_reflection), cfg.retroReflection, on -> cfg.retroReflection = on));
        card.addView(spaced(switchRow(getString(R.string.retro_shadow), cfg.retroShadow, on -> cfg.retroShadow = on), 8));
        root.addView(card);
    }

    private void buildControls(LinearLayout root) {
        LinearLayout card = card(sc.surfaceContainer);
        controlsCard = card;

        TextView alignLabel = label(getString(R.string.controls_align));
        alignLabel.setPadding(dp(4), dp(4), dp(4), dp(8));
        card.addView(alignLabel);
        int alignSegIdx = cfg.controlsAlign == Config.CONTROLS_LEFT ? 0
                : (cfg.controlsAlign == Config.CONTROLS_RIGHT ? 2 : 1);
        int[] alignOrder = {Config.CONTROLS_LEFT, Config.CONTROLS_CENTER, Config.CONTROLS_RIGHT};
        card.addView(segmented(
                new String[]{
                        getString(R.string.align_left),
                        getString(R.string.align_center),
                        getString(R.string.align_right)
                },
                alignSegIdx,
                i -> {
                    cfg.controlsAlign = alignOrder[i];
                    changed();
                }));

        card.addView(label(getString(R.string.time_format)));
        card.addView(segmented(
                new String[]{getString(R.string.time_remaining), getString(R.string.time_duration)},
                cfg.timeMode,
                i -> {
                    cfg.timeMode = i;
                    changed();
                }));
        root.addView(card);
    }

    private void buildAppearance(LinearLayout root) {
        LinearLayout card = card(sc.surfaceContainer);
        shapeCard = card;

        TextView colorLabel = label(getString(R.string.color));
        colorLabel.setPadding(dp(4), dp(4), dp(4), dp(8));
        card.addView(colorLabel);
        int[] colorOrder = {Config.COLOR_SYSTEM, Config.COLOR_ALBUM, Config.COLOR_CUSTOM};
        int colorSegIdx = cfg.colorSource == Config.COLOR_SYSTEM ? 0
                : (cfg.colorSource == Config.COLOR_ALBUM ? 1 : 2);
        card.addView(segmented(
                new String[]{
                        getString(R.string.color_system),
                        getString(R.string.color_album),
                        getString(R.string.color_custom)
                },
                colorSegIdx,
                i -> {
                    cfg.colorSource = colorOrder[i];
                    refreshVisibility();
                    changed();
                }));

        customPanel = hexField();
        card.addView(customPanel);

        card.addView(spaced(sliderRow(getString(R.string.scale), 70, 115, cfg.scalePct, v -> cfg.scalePct = v), 14));
        cornerRow = spaced(sliderRow(getString(R.string.corner_radius), 0, 50, cfg.cornerPct, v -> cfg.cornerPct = v), 8);
        card.addView(cornerRow);
        card.addView(spaced(sliderRow(getString(R.string.track_opacity), 10, 100, cfg.trackAlphaPct, v -> cfg.trackAlphaPct = v), 8));
        root.addView(card);
    }

    private View spaced(View view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        view.setLayoutParams(params);
        return view;
    }

    private void buildReset(LinearLayout root) {
        TextView b = outlinedErrorButton(getString(R.string.reset), this::confirmReset);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.topMargin = dp(16);
        root.addView(b);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset_confirm, (d, w) -> {
                    ui.removeCallbacks(commit);
                    cfg = new Config();
                    save();
                    buildUi();
                    refreshStatus(LINK_CHECKING);
                    repo.deliver(cfg.serialize(), this::onDelivered);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changed() {
        ui.removeCallbacks(commit);
        ui.postDelayed(commit, 150);
    }

    private void save() {
        repo.save(cfg, this::onDelivered);
    }

    private void onDelivered(boolean linked, int wallpaperColor) {
        refreshStatus(linked ? LINK_OK : LINK_FAIL);
    }

    private void refreshStatus(int state) {
        statusCard.setVisibility(state == LINK_CHECKING ? View.INVISIBLE : View.VISIBLE);
        if (state == LINK_CHECKING) return;
        boolean fail = state == LINK_FAIL;
        int bg = fail ? (dark ? 0xFF93000A : 0xFFFFDAD6) : sc.secondaryContainer;
        int fg = fail ? (dark ? 0xFFFFDAD6 : 0xFF410002) : sc.onSecondaryContainer;
        int iconBg = fail ? (dark ? 0xFFFFB4AB : 0xFFBA1A1A) : sc.primary;
        int iconFg = fail ? (dark ? 0xFF690005 : Color.WHITE) : sc.onPrimary;
        statusCard.setBackground(round(bg, dp(24)));
        statusTitle.setTextColor(fg);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(iconBg);
        statusIcon.setBackground(dot);
        statusIcon.setTextColor(iconFg);
        statusIcon.setText(fail ? "!" : "\u2713");
        statusTitle.setText(fail ? R.string.status_fail : R.string.status_ok);
    }

    private void refreshVisibility() {
        customPanel.setVisibility(cfg.colorSource == Config.COLOR_CUSTOM ? View.VISIBLE : View.GONE);
        if (retroCard != null) {
            retroCard.setVisibility(cfg.coverStyle == Config.COVER_RETRO ? View.VISIBLE : View.GONE);
        }
        if (cornerRow != null) {
            cornerRow.setVisibility(cfg.coverStyle == Config.COVER_STANDARD ? View.VISIBLE : View.GONE);
        }
        setSectionEnabled(appearancePanel, cfg.enabled, false);
        setSectionEnabled(retroCard, cfg.enabled, true);
        setSectionEnabled(controlsCard, cfg.enabled, true);
        setSectionEnabled(shapeCard, cfg.enabled, true);
    }

    private void setSectionEnabled(View view, boolean enabled, boolean move) {
        if (view == null) return;
        float alpha = enabled ? 1f : 0.38f;
        float shift = !enabled && move ? dp(6) : 0f;
        float scale = !enabled && !move ? 0.985f : 1f;
        view.animate().cancel();
        if (view.isLaidOut()) {
            view.animate().alpha(alpha).translationY(shift).scaleX(scale).scaleY(scale).setDuration(240).start();
        } else {
            view.setAlpha(alpha);
            view.setTranslationY(shift);
            view.setScaleX(scale);
            view.setScaleY(scale);
        }
        setChildrenEnabled(view, enabled);
    }

    private void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setChildrenEnabled(group.getChildAt(i), enabled);
        }
    }

    // ------------------------------------------------------------------ Material 3 building blocks

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private Typeface font(int weight) {
        Typeface t = fonts.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(getAssets(), "fonts/Montserrat.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .setWeight(weight)
                    .build();
        } catch (Throwable ignored) {
            t = null;
        }
        if (t == null) t = Typeface.create(Typeface.DEFAULT, weight, false);
        fonts.put(weight, t);
        return t;
    }

    private TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, WINDOW_TITLE_SP, sc.primary, W_SEMIBOLD);
        t.setPadding(dp(4), 0, dp(4), dp(8));
        t.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        t.setMaxLines(2);
        t.setAccessibilityHeading(true);
        return t;
    }

    private TextView dialogTitle(String s) {
        TextView t = text(s, DIALOG_TITLE_SP, sc.onSurface, W_SEMIBOLD);
        t.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        t.setMaxLines(2);
        t.setAccessibilityHeading(true);
        t.setPadding(0, 0, 0, dp(16));
        return t;
    }

    private static void announceAs(View view, String className, boolean checkable) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(className);
                info.setCheckable(checkable);
                if (checkable) info.setChecked(host.isSelected());
            }
        });
    }

    private TextView label(String s) {
        TextView t = text(s, 16, sc.primary, W_BOLD);
        t.setPadding(dp(4), dp(16), dp(4), dp(8));
        return t;
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(16));
        l.setBackground(round(color, dp(24)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        l.setLayoutParams(lp);
        return l;
    }

    private RippleDrawable ripple(Drawable background, int radius, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(MaterialYou.withAlpha(rippleColor, 40)),
                background, round(Color.WHITE, radius));
    }

    private TextView button(String label, Runnable action) {
        TextView b = text(label, 16, sc.onPrimary, W_MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(24), dp(10), dp(24), dp(10));
        b.setMinHeight(dp(48));
        b.setBackground(ripple(round(sc.primary, dp(100)), dp(100), sc.onPrimary));
        b.setOnClickListener(v -> action.run());
        pressMotion(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void pressMotion(View view) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            }
            return false;
        });
    }

    private TextView outlinedErrorButton(String label, Runnable action) {
        TextView b = button(label, action);
        int error = dark ? 0xFFFFB4AB : 0xFFBA1A1A;
        b.setTextColor(error);
        GradientDrawable outline = round(Color.TRANSPARENT, dp(100));
        outline.setStroke(dp(1), MaterialYou.withAlpha(error, 0x88));
        b.setBackground(ripple(outline, dp(100), error));
        return b;
    }

    private View switchRow(String title, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        row.setBackground(ripple(round(surfaceHigh, dp(10)), dp(10), sc.primary));
        row.addView(text(title, 18, sc.onSurface, W_MEDIUM),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        PixelSwitch sw = new PixelSwitch(this);
        sw.setColors(sc);
        sw.setChecked(checked, false);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener(on -> {
            onChange.accept(on);
            changed();
        });
        row.setOnClickListener(v -> sw.performClick());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(48));
        lp.leftMargin = dp(12);
        row.addView(sw, lp);
        return row;
    }

    private View segmented(String[] labels, int selected, IntConsumer onSelect) {
        PixelSegmentedControl row = new PixelSegmentedControl(this);
        row.setColors(sc);
        row.setBaselineAligned(false);
        final TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = text(labels[i], 14, sc.onSurface, W_MEDIUM);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(48));
            t.setPadding(dp(4), 0, dp(4), 0);
            t.setLetterSpacing(-0.01f);
            t.setIncludeFontPadding(false);
            t.setMaxLines(1);
            t.setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, TypedValue.COMPLEX_UNIT_SP);
            announceAs(t, "android.widget.RadioButton", true);
            t.setOnClickListener(v -> {
                row.select(index, true);
                paintSegmentLabels(items, index);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                onSelect.accept(index);
            });
            items[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        row.select(selected, false);
        paintSegmentLabels(items, selected);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (r - l <= 0) return;
            float smallest = Float.MAX_VALUE;
            for (TextView item : items) smallest = Math.min(smallest, item.getTextSize());
            if (smallest < Float.MAX_VALUE && smallest > 0f) {
                for (TextView item : items) {
                    item.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
                    item.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallest);
                }
            }
        });
        return row;
    }

    private void paintSegmentLabels(TextView[] items, int selected) {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == selected;
            items[i].setBackground(ripple(round(Color.TRANSPARENT, dp(100)), dp(100),
                    on ? sc.onSecondaryContainer : sc.primary));
            items[i].setTextColor(on ? sc.onSecondaryContainer : sc.onSurface);
            items[i].setTypeface(font(on ? W_BOLD : W_MEDIUM));
            items[i].setSelected(on);
        }
    }

    private View sliderRow(String title, int min, int max, int value, IntConsumer onChange) {
        return sliderRow(title, min, max, value, true, true, onChange);
    }

    private View sliderRow(String title, int min, int max, int value, boolean percent,
            boolean commitChange, IntConsumer onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(8));
        box.setBackground(round(surfaceHigh, dp(10)));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 18, sc.onSurface, W_MEDIUM);
        TextView val = text(percent ? getString(R.string.value_percent, value) : String.valueOf(value),
                14, sc.primary, W_BOLD);
        val.setPadding(dp(8), 0, 0, 0);
        val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(val);
        box.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setProgressDrawable(sliderTrack());
        bar.setThumb(new PixelSliderThumbDrawable(sc.primary, getResources().getDisplayMetrics().density));
        bar.setSplitTrack(false);
        bar.setBackground(null);
        bar.setMinHeight(dp(6));
        bar.setMaxHeight(dp(6));
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setThumbOffset(dp(20));
        bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress;
                val.setText(percent ? getString(R.string.value_percent, v) : String.valueOf(v));
                if (fromUser) {
                    onChange.accept(v);
                    if (commitChange) changed();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        blp.topMargin = dp(2);
        box.addView(bar, blp);
        return box;
    }

    private Drawable sliderTrack() {
        GradientDrawable bg = round(surfaceHighest, dp(3));
        GradientDrawable fg = round(sc.primary, dp(3));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                bg, new ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    /** Filled pill text field for a hex colour, with a live colour dot. No outline. */
    private View hexField() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(20), 0);
        row.setBackground(round(surfaceHigh, dp(12)));
        row.setMinimumHeight(dp(64));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);

        hexPreview = new View(this);
        hexPreview.setContentDescription(getString(R.string.open_picker));
        announceAs(hexPreview, "android.widget.Button", false);
        hexPreview.setOnClickListener(v -> showColorPicker());
        row.addView(hexPreview, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView hash = text("#", 20, sc.onSurfaceVariant, W_BOLD);
        hash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = dp(14);
        row.addView(hash, hlp);

        hexInput = new EditText(this);
        hexInput.setBackground(null);
        hexInput.setPadding(dp(4), 0, 0, 0);
        hexInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        hexInput.setTypeface(font(W_BOLD));
        hexInput.setTextColor(sc.onSurface);
        hexInput.setHintTextColor(MaterialYou.withAlpha(sc.onSurfaceVariant, 0x99));
        hexInput.setHint("RRGGBB");
        hexInput.setHighlightColor(MaterialYou.withAlpha(sc.primary, 0x55));
        hexInput.setTextCursorDrawable(cursor());
        hexInput.setSingleLine(true);
        hexInput.setLetterSpacing(0.04f);
        hexInput.setContentDescription(getString(R.string.hex_description));
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        hexInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hexInput.setFilters(new InputFilter[]{hexOnly(), new InputFilter.LengthFilter(7)});
        hexInput.setText(Config.hex(cfg.customColor).substring(1));
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (hexEditing) return;
                String digits = e.toString().replace("#", "");
                Integer color = digits.length() == 6 ? Config.parseHex(digits) : null;
                if (color != null) applyCustomColor(color, false);
            }
        });
        hexInput.setOnEditorActionListener((v, action, event) -> {
            Integer color = Config.parseHex(hexInput.getText().toString());
            if (color != null) applyCustomColor(color, true);
            else setHexText(cfg.customColor);
            hexInput.clearFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(hexInput.getWindowToken(), 0);
            return true;
        });
        row.addView(hexInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        paintHexPreview();
        return row;
    }

    /** Hex digits and '#' only; letters are upper-cased as they are typed. */
    private static InputFilter hexOnly() {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder out = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                char ch = source.charAt(i);
                if (Character.digit(ch, 16) >= 0) out.append(Character.toUpperCase(ch));
                else if (ch == '#' && dstart == 0 && i == start) out.append(ch);
            }
            return out.length() == end - start
                    && out.toString().contentEquals(source.subSequence(start, end)) ? null : out;
        };
    }

    private Drawable cursor() {
        GradientDrawable g = round(sc.primary, dp(1));
        g.setSize(dp(2), dp(24));
        return g;
    }

    /** Single place that changes the custom colour, so the field and System UI stay in sync. */
    private void applyCustomColor(int color, boolean updateText) {
        cfg.customColor = 0xFF000000 | color;
        if (updateText) setHexText(color);
        paintHexPreview();
        changed();
    }

    private void setHexText(int color) {
        hexEditing = true;
        hexInput.setText(Config.hex(color).substring(1));
        hexInput.setSelection(hexInput.length());
        hexEditing = false;
    }

    private void paintHexPreview() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF000000 | cfg.customColor);
        hexPreview.setBackground(ripple(g, dp(24), Color.WHITE));
    }

    private static final int[] RGB_NAMES = {R.string.rgb_red, R.string.rgb_green, R.string.rgb_blue};

    /** The SeekBar inside a slider row built above. */
    private static SeekBar seekBarIn(View v) {
        if (v instanceof SeekBar) return (SeekBar) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SeekBar s = seekBarIn(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    /** Colour picker dialog opened from the colour dot: HSV wheel, brightness + RGB sliders. */
    private void showColorPicker() {
        if (colorPicker != null && colorPicker.isShowing()) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(20));
        box.setBackground(round(sc.surfaceContainer, dp(32)));

        box.addView(dialogTitle(getString(R.string.picker_title)));

        ColorWheelView wheel = new ColorWheelView(this);
        wheel.setColor(0xFF000000 | cfg.customColor);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(wheel, wlp);

        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setPadding(dp(8), dp(8), dp(20), dp(8));
        result.setBackground(round(surfaceHigh, dp(100)));
        View dot = new View(this);
        result.addView(dot, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView hexText = text("", 18, sc.onSurface, W_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(14);
        result.addView(hexText, tlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        rlp.topMargin = dp(12);
        box.addView(result, rlp);

        View brightness = sliderRow(getString(R.string.brightness), 0, 100,
                Math.round(wheel.getValue() * 100), true, false, v -> wheel.setValue(v / 100f));
        box.addView(spaced(brightness, 8));

        int[] picked = {wheel.getColor()};
        SeekBar brightBar = seekBarIn(brightness);
        Runnable[] repaint = new Runnable[1];
        SeekBar[] rgbBars = new SeekBar[3];
        for (int k = 0; k < 3; k++) {
            final int shift = 16 - 8 * k;
            View row = sliderRow(getString(RGB_NAMES[k]), 0, 255, (picked[0] >> shift) & 0xFF,
                    false, false, v -> {
                        int c = 0xFF000000 | (picked[0] & ~(0xFF << shift)) | (v << shift);
                        wheel.setColor(c);
                        picked[0] = c;
                        if (brightBar != null) brightBar.setProgress(Math.round(wheel.getValue() * 100));
                        if (repaint[0] != null) repaint[0].run();
                    });
            rgbBars[k] = seekBarIn(row);
            box.addView(spaced(row, 8));
        }
        Runnable paint = () -> {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(picked[0]);
            dot.setBackground(g);
            hexText.setText(Config.hex(picked[0]));
        };
        repaint[0] = paint;
        wheel.setListener(color -> {
            picked[0] = color;
            paint.run();
            for (int k = 0; k < 3; k++) {
                if (rgbBars[k] != null) rgbBars[k].setProgress((color >> (16 - 8 * k)) & 0xFF);
            }
        });
        paint.run();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(getString(R.string.cancel), dialog::dismiss);
        cancel.setBackground(ripple(round(sc.secondaryContainer, dp(100)), dp(100), sc.onSecondaryContainer));
        cancel.setTextColor(sc.onSecondaryContainer);
        TextView done = button(getString(R.string.done), () -> {
            applyCustomColor(picked[0], true);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = dp(12);
        actions.addView(cancel, clp);
        actions.addView(done, dlp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        box.addView(actions, alp);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(box);
        dialog.setContentView(scroller);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> colorPicker = null);
        colorPicker = dialog;
        dialog.show();
    }
}
