package dev.lockplayer;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * LSPosed entry point (libxposed API 102, scope {@code com.android.systemui}).
 *
 * <p>Replaces the stock lock screen player inside System UI's {@code MediaContainerView}
 * (managed by {@code KeyguardMediaController}) with {@link LockPlayerView}, and keeps it visible
 * on the lock screen even while paused until the media app is removed from memory.</p>
 */
public final class HookEntry extends XposedModule {
    private static final String HOOK_TAG = "LockPlayer";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String MEDIA_CONTAINER = "com.android.systemui.statusbar.notification.stack.MediaContainerView";
    private static final String KEYGUARD_MEDIA_CTRL = "com.android.systemui.media.controls.ui.controller.KeyguardMediaController";

    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<ViewGroup, LockPlayerView> CONTAINERS = new WeakHashMap<>();
    private static WeakReference<Object> keyguardMediaCtrlRef = new WeakReference<>(null);
    private static WeakReference<Object> keyguardMediaHostRef = new WeakReference<>(null);
    private static volatile ViewGroup primaryContainer;
    private static volatile boolean mediaHookRegistered;
    private static volatile boolean screenReceiverRegistered;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static Method cachedSetActualHeight;
    private static boolean cachedSetActualHeightTwoArgs;
    private static boolean resolvedSetActualHeight;
    private static Method cachedSetWillBeGone;
    private static boolean resolvedSetWillBeGone;
    private static Method cachedGetViewState;
    private static boolean resolvedGetViewState;
    private static Method cachedKmcRefresh;
    private static boolean resolvedKmcRefresh;
    private static Method cachedSbscGetState;
    private static boolean resolvedSbscGetState;

    static volatile XposedInterface xposed;
    static volatile String moduleApkPath;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
        try {
            if (getModuleApplicationInfo() != null) {
                moduleApkPath = getModuleApplicationInfo().sourceDir;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        ClassLoader cl = classLoader(param);
        markLoaded(cl);
        if (!SYSTEMUI.equals(param.getPackageName())) return;
        try {
            Class<?> containerClass = Class.forName(MEDIA_CONTAINER, false, cl);
            installContainerHooks(containerClass);
        } catch (Throwable t) {
            hookError("find " + MEDIA_CONTAINER, t);
        }
        try {
            Class<?> kmcClass = Class.forName(KEYGUARD_MEDIA_CTRL, false, cl);
            installKeyguardMediaControllerHooks(kmcClass);
        } catch (Throwable t) {
            hookError("find " + KEYGUARD_MEDIA_CTRL, t);
        }
    }

    private static ClassLoader classLoader(PackageReadyParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            if (cl != null) return cl;
        } catch (Throwable ignored) {
        }
        try {
            return param.getDefaultClassLoader();
        } catch (Throwable ignored) {
            return HookEntry.class.getClassLoader();
        }
    }

    private static void markLoaded(ClassLoader loader) {
        if (loader == null) return;
        try {
            Class.forName(ModuleState.class.getName(), true, loader).getField("loaded").setBoolean(null, true);
        } catch (Throwable ignored) {
        }
    }

    private static void installKeyguardMediaControllerHooks(Class<?> kmcClass) {
        synchronized (INSTALLED) {
            if (!INSTALLED.add(kmcClass)) return;
        }
        try {
            interceptConstructors(kmcClass, chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self != null) {
                    keyguardMediaCtrlRef = new WeakReference<>(self);
                    hookKeyguardMediaHost(self);
                }
                return res;
            });
        } catch (Throwable ignored) {
        }
        try {
            interceptMethods(kmcClass, "onMediaHostVisibilityChanged", chain -> {
                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof Boolean && !(Boolean) args.get(0)) {
                    Object self = chain.getThisObject();
                    if (ConfigStore.get().enabled && MediaTracker.getSnapshot().hasSession && isKeyguardState(self)) {
                        return chain.proceed(new Object[]{Boolean.TRUE});
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
        try {
            interceptMethods(kmcClass, "refreshMediaPosition", chain -> {
                Object self = chain.getThisObject();
                if (self != null && ConfigStore.get().enabled
                        && MediaTracker.getSnapshot().hasSession && isKeyguardState(self)) {
                    forceMediaHostStateVisible(self);
                }
                Object res = chain.proceed();
                if (self != null) {
                    keyguardMediaCtrlRef = new WeakReference<>(self);
                    hookKeyguardMediaHost(self);
                    keepAliveIfPausedOnKeyguard(self);
                }
                return res;
            });
        } catch (Throwable t) {
            hookError("hook refreshMediaPosition", t);
        }
        try {
            interceptMethods(kmcClass, "setVisibility$1", chain -> {
                Object self = chain.getThisObject();
                List<Object> args = chain.getArgs();
                if (self != null && args.size() >= 2 && args.get(1) instanceof Integer
                        && (Integer) args.get(1) != View.VISIBLE
                        && ConfigStore.get().enabled && MediaTracker.getSnapshot().hasSession
                        && isKeyguardState(self)) {
                    forceMediaHostStateVisible(self);
                    return chain.proceed(new Object[]{args.get(0), View.VISIBLE});
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
        for (String extraMethod : new String[]{"updateVisibility", "onStateChanged", "onDozingChanged", "setVisible"}) {
            try {
                interceptMethods(kmcClass, extraMethod, chain -> {
                    Object res = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) {
                        keyguardMediaCtrlRef = new WeakReference<>(self);
                        keepAliveIfPausedOnKeyguard(self);
                    }
                    return res;
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookKeyguardMediaHost(Object kmc) {
        try {
            Object host = readField(kmc, "mediaHost");
            if (host == null) return;
            keyguardMediaHostRef = new WeakReference<>(host);
            Class<?> hostClass = host.getClass();
            synchronized (INSTALLED) {
                if (!INSTALLED.add(hostClass)) return;
            }
            interceptMethods(hostClass, "getVisible", chain -> {
                Object self = chain.getThisObject();
                if (self != null && self == keyguardMediaHostRef.get()
                        && ConfigStore.get().enabled
                        && MediaTracker.getSnapshot().hasSession) {
                    Object ctrl = keyguardMediaCtrlRef.get();
                    if (ctrl != null && isKeyguardState(ctrl)) {
                        return Boolean.TRUE;
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static void forceMediaHostStateVisible(Object kmc) {
        try {
            Object host = readField(kmc, "mediaHost");
            if (host == null) return;
            Object state = readField(host, "state");
            if (state != null) {
                setFieldBoolean(state, "visible", true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forceContainerShouldBeVisible(ViewGroup container) {
        if (container == null) return;
        try {
            if (!resolvedGetViewState) {
                resolvedGetViewState = true;
                for (Class<?> c = container.getClass(); c != null && c != FrameLayout.class; c = c.getSuperclass()) {
                    try {
                        Method m = c.getDeclaredMethod("getViewState");
                        m.setAccessible(true);
                        cachedGetViewState = m;
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }
            Object vs = cachedGetViewState != null ? cachedGetViewState.invoke(container) : null;
            if (vs != null) {
                setFieldBoolean(vs, "shouldBeVisible", true);
                setFieldBoolean(vs, "hidden", false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void keepAliveIfPausedOnKeyguard(Object kmc) {
        if (!ConfigStore.get().enabled || !MediaTracker.getSnapshot().hasSession) return;
        if (!isKeyguardState(kmc)) return;
        try {
            forceMediaHostStateVisible(kmc);
            Object containerObj = readField(kmc, "singlePaneContainer");
            if (!(containerObj instanceof ViewGroup)) return;
            ViewGroup container = (ViewGroup) containerObj;
            ensurePlayer(container);
            forceContainerShouldBeVisible(container);
            boolean kmcVisible = readFieldBoolean(kmc, "visible", true);
            if (!kmcVisible || container.getVisibility() != View.VISIBLE) {
                setFieldBoolean(kmc, "visible", true);
                clearWillBeGone(container);
                if (container.getVisibility() != View.VISIBLE) {
                    container.setVisibility(View.VISIBLE);
                }
                notifyVisibilityListener(kmc, true);
                container.requestLayout();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void notifyVisibilityListener(Object kmc, boolean visible) {
        try {
            Object listener = readField(kmc, "visibilityChangedListener");
            if (listener != null) {
                Method invoke = listener.getClass().getMethod("invoke", Object.class);
                invoke.invoke(listener, Boolean.valueOf(visible));
            }
        } catch (Throwable ignored) {
        }
    }

    private static Context kmcContext(Object kmc) {
        if (kmc == null) return null;
        try {
            Object c = readField(kmc, "singlePaneContainer");
            if (c instanceof View) return ((View) c).getContext();
            Object ctx = readField(kmc, "context");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isKeyguardState(Object kmc) {
        if (kmc != null) {
            try {
                Object sbsc = readField(kmc, "statusBarStateController");
                if (sbsc != null) {
                    if (!resolvedSbscGetState) {
                        resolvedSbscGetState = true;
                        try {
                            Method m = sbsc.getClass().getMethod("getState");
                            m.setAccessible(true);
                            cachedSbscGetState = m;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (cachedSbscGetState != null) {
                        int state = (Integer) cachedSbscGetState.invoke(sbsc);
                        // 1 = KEYGUARD, 2 = SHADE_LOCKED, 0 = SHADE
                        if (state == 1 || state == 2) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            Context ctx = kmcContext(kmc);
            if (ctx != null) {
                try {
                    KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
                    if (km != null && km.isKeyguardLocked()) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean shouldKeepContainerVisible(ViewGroup container) {
        if (!ConfigStore.get().enabled || !MediaTracker.getSnapshot().hasSession) return false;
        Object kmc = keyguardMediaCtrlRef.get();
        if (kmc != null && isKeyguardState(kmc)) return true;
        Context ctx = container.getContext();
        if (ctx != null) {
            try {
                KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
                if (km != null && km.isKeyguardLocked()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void installContainerHooks(Class<?> containerClass) {
        synchronized (INSTALLED) {
            if (!INSTALLED.add(containerClass)) return;
        }
        ConfigStore.addListener(cfg -> {
            synchronized (CONTAINERS) {
                for (ViewGroup container : new ArrayList<>(CONTAINERS.keySet())) {
                    if (container != null) container.post(() -> syncContainer(container));
                }
            }
        });

        Hooker onLifecycle = chain -> {
            Object res = chain.proceed();
            Object self = chain.getThisObject();
            if (self instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) self;
                vg.post(() -> attachAndSync(vg));
            }
            return res;
        };
        try {
            interceptConstructors(containerClass, onLifecycle);
        } catch (Throwable ignored) {
        }
        for (String method : new String[]{"onFinishInflate", "onAttachedToWindow", "onViewAdded"}) {
            try {
                interceptMethods(containerClass, method, onLifecycle);
            } catch (Throwable ignored) {
            }
        }

        try {
            interceptMethods(containerClass, "onMeasure", chain -> {
                Object self = chain.getThisObject();
                if (self instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) self;
                    LockPlayerView player = ensurePlayer(vg);
                    if (player != null && ConfigStore.get().enabled) {
                        hideStockChildren(vg, player);
                        List<Object> args = chain.getArgs();
                        if (args.size() >= 2 && args.get(0) instanceof Integer && args.get(1) instanceof Integer) {
                            int wSpec = (Integer) args.get(0);
                            player.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                            int w = View.MeasureSpec.getSize(wSpec);
                            int h = player.getMeasuredHeight();
                            Object res = chain.proceed();
                            forceMeasuredDimension(vg, w > 0 ? w : vg.getMeasuredWidth(), h);
                            return res;
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            hookError("hook onMeasure", t);
        }

        try {
            interceptMethods(containerClass, "onLayout", chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) self;
                    LockPlayerView player = CONTAINERS.get(vg);
                    if (player != null && ConfigStore.get().enabled) {
                        hideStockChildren(vg, player);
                        if (player.getVisibility() != View.VISIBLE) player.setVisibility(View.VISIBLE);
                        int w = vg.getWidth(), h = vg.getHeight();
                        if (player.getLeft() != 0 || player.getTop() != 0
                                || player.getRight() != w || player.getBottom() != h) {
                            player.layout(0, 0, w, h);
                        }
                        syncActualHeight(vg, h);
                    }
                }
                return res;
            });
        } catch (Throwable ignored) {
        }

        hookHeightMethodUpwards(containerClass, "getIntrinsicHeight");
        hookHeightMethodUpwards(containerClass, "getActualHeight");
        hookContainerSetVisibility(containerClass);
        hookContainerSetKeyguardVisibility(containerClass);
        hookWillBeGone(containerClass);
    }

    private static void hookContainerSetKeyguardVisibility(Class<?> containerClass) {
        try {
            interceptMethods(containerClass, "setKeyguardVisibility", chain -> {
                Object self = chain.getThisObject();
                List<Object> args = chain.getArgs();
                if (containerClass.isInstance(self) && !args.isEmpty()
                        && args.get(0) instanceof Boolean && !(Boolean) args.get(0)
                        && shouldKeepContainerVisible((ViewGroup) self)) {
                    clearWillBeGone((ViewGroup) self);
                    forceContainerShouldBeVisible((ViewGroup) self);
                    return chain.proceed(new Object[]{Boolean.TRUE});
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookWillBeGone(Class<?> containerClass) {
        hookFirstDeclaredUpwards(containerClass, "setWillBeGone", chain -> {
            Object self = chain.getThisObject();
            List<Object> args = chain.getArgs();
            if (containerClass.isInstance(self) && !args.isEmpty()
                    && args.get(0) instanceof Boolean && (Boolean) args.get(0)
                    && shouldKeepContainerVisible((ViewGroup) self)) {
                return chain.proceed(new Object[]{Boolean.FALSE});
            }
            return chain.proceed();
        });
        hookFirstDeclaredUpwards(containerClass, "willBeGone", chain -> {
            Object self = chain.getThisObject();
            if (containerClass.isInstance(self) && shouldKeepContainerVisible((ViewGroup) self)) {
                return Boolean.FALSE;
            }
            return chain.proceed();
        });
    }

    private static void clearWillBeGone(ViewGroup vg) {
        if (vg == null) return;
        if (!resolvedSetWillBeGone) {
            resolvedSetWillBeGone = true;
            for (Class<?> c = vg.getClass(); c != null && c != FrameLayout.class && c != ViewGroup.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("setWillBeGone", boolean.class);
                    m.setAccessible(true);
                    cachedSetWillBeGone = m;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (cachedSetWillBeGone != null) {
            try {
                cachedSetWillBeGone.invoke(vg, false);
                return;
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> c = vg.getClass(); c != null && c != FrameLayout.class && c != ViewGroup.class; c = c.getSuperclass()) {
            for (String field : new String[]{"willBeGone", "mWillBeGone"}) {
                try {
                    Field f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    f.setBoolean(vg, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void hookContainerSetVisibility(Class<?> containerClass) {
        Method target = null;
        for (Class<?> c = containerClass; c != null && c != FrameLayout.class && c != ViewGroup.class && c != View.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("setVisibility") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class && !Modifier.isAbstract(m.getModifiers())) {
                    target = m;
                    break;
                }
            }
            if (target != null) break;
        }
        if (target == null) return;
        try {
            intercept(target, chain -> {
                Object self = chain.getThisObject();
                if (!containerClass.isInstance(self)) {
                    return chain.proceed();
                }
                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof Integer) {
                    int vis = (Integer) args.get(0);
                    ViewGroup vg = (ViewGroup) self;
                    if (vis != View.VISIBLE && shouldKeepContainerVisible(vg)) {
                        clearWillBeGone(vg);
                        return chain.proceed(new Object[]{View.VISIBLE});
                    }
                    if (vis == View.VISIBLE && shouldKeepContainerVisible(vg)) {
                        clearWillBeGone(vg);
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookFirstDeclaredUpwards(Class<?> start, String methodName, Hooker hooker) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && !Modifier.isAbstract(m.getModifiers())) {
                    found = true;
                    break;
                }
            }
            if (found) {
                try {
                    interceptMethods(c, methodName, hooker);
                } catch (Throwable ignored) {
                }
                return;
            }
        }
    }

    private static void hookHeightMethodUpwards(Class<?> containerClass, String methodName) {
        for (Class<?> c = containerClass; c != null && c != ViewGroup.class && c != View.class; c = c.getSuperclass()) {
            Method found = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 0 && !Modifier.isAbstract(m.getModifiers())) {
                    found = m;
                    break;
                }
            }
            if (found != null) {
                try {
                    intercept(found, chain -> {
                        Object self = chain.getThisObject();
                        if (containerClass.isInstance(self) && ConfigStore.get().enabled) {
                            ViewGroup vg = (ViewGroup) self;
                            if (vg.getVisibility() == View.VISIBLE) {
                                LockPlayerView player = CONTAINERS.get(vg);
                                if (player != null && player.getMeasuredHeight() > 0) {
                                    return player.getMeasuredHeight();
                                }
                            }
                        }
                        return chain.proceed();
                    });
                } catch (Throwable ignored) {
                }
                return;
            }
        }
    }

    private static void syncActualHeight(ViewGroup vg, int h) {
        if (h <= 0) return;
        if (!resolvedSetActualHeight) {
            resolvedSetActualHeight = true;
            for (Class<?> c = vg.getClass(); c != null && c != FrameLayout.class && c != ViewGroup.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("setActualHeight", int.class, boolean.class);
                    m.setAccessible(true);
                    cachedSetActualHeight = m;
                    cachedSetActualHeightTwoArgs = true;
                    break;
                } catch (Throwable ignored) {
                }
                try {
                    Method m = c.getDeclaredMethod("setActualHeight", int.class);
                    m.setAccessible(true);
                    cachedSetActualHeight = m;
                    cachedSetActualHeightTwoArgs = false;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (cachedSetActualHeight != null) {
            try {
                if (cachedSetActualHeightTwoArgs) cachedSetActualHeight.invoke(vg, h, false);
                else cachedSetActualHeight.invoke(vg, h);
            } catch (Throwable ignored) {
            }
        }
    }

    private static LockPlayerView ensurePlayer(ViewGroup container) {
        primaryContainer = container;
        LockPlayerView fast;
        synchronized (CONTAINERS) {
            fast = CONTAINERS.get(container);
        }
        if (fast != null && fast.getParent() == container) {
            return fast;
        }
        Context ctx = container.getContext();
        if (ctx != null) {
            ConfigStore.ensure(ctx);
            MediaTracker.ensure(ctx);
            ensureMediaListenerRegistered();
            ensureScreenReceiver(ctx);
        }
        installTouchForwarder(container);
        synchronized (CONTAINERS) {
            LockPlayerView player = CONTAINERS.get(container);
            if (player == null && ctx != null) {
                player = new LockPlayerView(ctx, false);
                CONTAINERS.put(container, player);
            }
            if (player != null && player.getParent() != container) {
                if (player.getParent() instanceof ViewGroup) {
                    ((ViewGroup) player.getParent()).removeView(player);
                }
                container.addView(player, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return player;
        }
    }

    private static volatile boolean touchForwarderInstalled;
    private static LockPlayerView touchForwardPlayer;
    private static final int[] TMP_PARENT_LOC = new int[2];
    private static final int[] TMP_PLAYER_LOC = new int[2];

    /**
     * Forwards touch gestures hitting an interactive region of {@link LockPlayerView} directly
     * from the parent {@code NotificationStackScrollLayout}. Vivo's stack drawing order / view
     * hierarchy otherwise skips child dispatch to {@code MediaContainerView}.
     */
    private static void installTouchForwarder(ViewGroup container) {
        if (touchForwarderInstalled || container == null || container.getParent() == null) return;
        touchForwarderInstalled = true;
        Class<?> parentCls = container.getParent().getClass();
        hookFirstDeclaredUpwards(parentCls, "onInterceptTouchEvent", chain -> {
            List<Object> args = chain.getArgs();
            if (!args.isEmpty() && args.get(0) instanceof MotionEvent) {
                MotionEvent ev = (MotionEvent) args.get(0);
                if (touchForwardPlayer != null) return Boolean.FALSE;
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                        && chain.getThisObject() instanceof ViewGroup
                        && findHitPlayer((ViewGroup) chain.getThisObject(), ev) != null) {
                    return Boolean.FALSE;
                }
            }
            return chain.proceed();
        });
        hookFirstDeclaredUpwards(parentCls, "dispatchTouchEvent", chain -> {
            List<Object> args = chain.getArgs();
            if (args.isEmpty() || !(args.get(0) instanceof MotionEvent)
                    || !(chain.getThisObject() instanceof ViewGroup)) {
                return chain.proceed();
            }
            ViewGroup parent = (ViewGroup) chain.getThisObject();
            MotionEvent ev = (MotionEvent) args.get(0);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touchForwardPlayer = null;
                LockPlayerView target = findHitPlayer(parent, ev);
                if (target != null && dispatchToPlayer(parent, target, ev)) {
                    touchForwardPlayer = target;
                    disallowInterceptUpwards(parent);
                    return Boolean.TRUE;
                }
                return chain.proceed();
            }
            LockPlayerView active = touchForwardPlayer;
            if (active != null) {
                if (!active.isShown()) {
                    sendSyntheticCancel(parent, active, ev);
                    touchForwardPlayer = null;
                    return chain.proceed();
                }
                boolean handled = dispatchToPlayer(parent, active, ev);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touchForwardPlayer = null;
                    return Boolean.valueOf(handled);
                }
                if (!handled && action == MotionEvent.ACTION_MOVE) {
                    sendSyntheticCancel(parent, active, ev);
                    touchForwardPlayer = null;
                    return Boolean.FALSE;
                }
                return Boolean.valueOf(handled);
            }
            return chain.proceed();
        });
    }

    private static LockPlayerView findHitPlayer(ViewGroup parent, MotionEvent ev) {
        if (!ConfigStore.get().enabled) return null;
        parent.getLocationOnScreen(TMP_PARENT_LOC);
        List<ViewGroup> containers;
        synchronized (CONTAINERS) {
            containers = new ArrayList<>(CONTAINERS.keySet());
        }
        for (ViewGroup vg : containers) {
            if (vg == null || vg.getParent() != parent) continue;
            if (vg.getVisibility() != View.VISIBLE || vg.getAlpha() < 0.05f) continue;
            if (vg.getWidth() <= 0 || vg.getHeight() <= 0) continue;
            LockPlayerView player = CONTAINERS.get(vg);
            if (player == null || player.getVisibility() != View.VISIBLE) continue;
            if (player.getWidth() <= 0 || player.getHeight() <= 0) continue;
            player.getLocationOnScreen(TMP_PLAYER_LOC);
            float localX = ev.getX() + TMP_PARENT_LOC[0] - TMP_PLAYER_LOC[0];
            float localY = ev.getY() + TMP_PARENT_LOC[1] - TMP_PLAYER_LOC[1];
            if (localX >= 0f && localX <= player.getWidth()
                    && localY >= 0f && localY <= player.getHeight()
                    && player.canHandleTouchAt(localX, localY)) {
                return player;
            }
        }
        return null;
    }

    private static boolean dispatchToPlayer(ViewGroup parent, LockPlayerView player, MotionEvent ev) {
        parent.getLocationOnScreen(TMP_PARENT_LOC);
        player.getLocationOnScreen(TMP_PLAYER_LOC);
        MotionEvent copy = MotionEvent.obtain(ev);
        copy.offsetLocation(TMP_PARENT_LOC[0] - TMP_PLAYER_LOC[0], TMP_PARENT_LOC[1] - TMP_PLAYER_LOC[1]);
        boolean handled = player.dispatchTouchEvent(copy);
        copy.recycle();
        return handled;
    }

    private static void sendSyntheticCancel(ViewGroup parent, LockPlayerView player, MotionEvent ev) {
        parent.getLocationOnScreen(TMP_PARENT_LOC);
        player.getLocationOnScreen(TMP_PLAYER_LOC);
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        cancel.offsetLocation(TMP_PARENT_LOC[0] - TMP_PLAYER_LOC[0], TMP_PARENT_LOC[1] - TMP_PLAYER_LOC[1]);
        player.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private static void disallowInterceptUpwards(ViewParent p) {
        for (int i = 0; i < 4 && p != null; i++) {
            p.requestDisallowInterceptTouchEvent(true);
            p = p.getParent();
        }
    }

    private static void ensureScreenReceiver(Context ctx) {
        if (screenReceiverRegistered) return;
        screenReceiverRegistered = true;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            // Only wake needs a lightweight deferred check; AOD entry does zero work.
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    resyncOnWake();
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable WAKE_SYNC_PASS = () -> {
        if (!ConfigStore.get().enabled || !MediaTracker.getSnapshot().hasSession) return;
        boolean needsSync = false;
        synchronized (CONTAINERS) {
            for (ViewGroup vg : CONTAINERS.keySet()) {
                if (vg == null) continue;
                LockPlayerView player = CONTAINERS.get(vg);
                if (player != null) player.onWakeResumed();
                if (vg.getVisibility() != View.VISIBLE && shouldKeepContainerVisible(vg)) {
                    needsSync = true;
                }
            }
        }
        if (!needsSync) return;
        Object kmc = keyguardMediaCtrlRef.get();
        if (kmc != null) {
            refreshKeyguardController(kmc);
            keepAliveIfPausedOnKeyguard(kmc);
        }
        synchronized (CONTAINERS) {
            for (ViewGroup vg : new ArrayList<>(CONTAINERS.keySet())) {
                if (vg != null && vg.getVisibility() != View.VISIBLE && shouldKeepContainerVisible(vg)) {
                    syncContainer(vg);
                }
            }
        }
    };

    private static void resyncOnWake() {
        if (!ConfigStore.get().enabled) return;
        if (!MediaTracker.getSnapshot().hasSession) {
            MediaTracker.refreshNow();
        }
        MAIN_HANDLER.removeCallbacks(WAKE_SYNC_PASS);
        MAIN_HANDLER.postDelayed(WAKE_SYNC_PASS, 50L);
    }

    private static void ensureMediaListenerRegistered() {
        if (mediaHookRegistered) return;
        mediaHookRegistered = true;
        MediaTracker.addListener(snapshot -> {
            Object kmc = keyguardMediaCtrlRef.get();
            if (kmc != null) {
                try {
                    Object c = readField(kmc, "singlePaneContainer");
                    if (c instanceof View) {
                        ((View) c).post(() -> refreshKeyguardController(kmc));
                    }
                } catch (Throwable ignored) {
                }
            }
            synchronized (CONTAINERS) {
                for (ViewGroup vg : new ArrayList<>(CONTAINERS.keySet())) {
                    if (vg == null) continue;
                    vg.post(() -> {
                        if (shouldKeepContainerVisible(vg)) {
                            clearWillBeGone(vg);
                            if (vg.getVisibility() != View.VISIBLE) vg.setVisibility(View.VISIBLE);
                        } else if (!MediaTracker.getSnapshot().hasSession && vg.getVisibility() == View.VISIBLE) {
                            vg.setVisibility(View.GONE);
                        }
                    });
                }
            }
        });
    }

    private static void refreshKeyguardController(Object kmc) {
        try {
            if (!resolvedKmcRefresh) {
                resolvedKmcRefresh = true;
                Method m = kmc.getClass().getDeclaredMethod("refreshMediaPosition", String.class);
                m.setAccessible(true);
                cachedKmcRefresh = m;
            }
            if (cachedKmcRefresh != null) {
                cachedKmcRefresh.invoke(kmc, "LockPlayer:sessionChanged");
                return;
            }
        } catch (Throwable ignored) {
        }
        keepAliveIfPausedOnKeyguard(kmc);
    }

    private static void attachAndSync(ViewGroup container) {
        try {
            ensurePlayer(container);
            syncContainer(container);
        } catch (Throwable t) {
            hookError("attachAndSync", t);
        }
    }

    private static void syncContainer(ViewGroup container) {
        LockPlayerView player = CONTAINERS.get(container);
        if (player == null) return;
        boolean enabled = ConfigStore.get().enabled;
        if (enabled) {
            container.setClipChildren(false);
            container.setClipToPadding(false);
            LockPlayerView.disableNightInversion(container);
            LockPlayerView.disableNightInversion(player);
            hideStockChildren(container, player);
            if (player.getVisibility() != View.VISIBLE) player.setVisibility(View.VISIBLE);
            if (shouldKeepContainerVisible(container)) {
                clearWillBeGone(container);
                if (container.getVisibility() != View.VISIBLE) container.setVisibility(View.VISIBLE);
            }
        } else {
            restoreStockChildren(container, player);
            if (player.getVisibility() != View.GONE) player.setVisibility(View.GONE);
        }
        container.requestLayout();
        container.invalidate();
    }

    private static void hideStockChildren(ViewGroup container, LockPlayerView ours) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == null || child == ours) continue;
            if (child.getVisibility() != View.GONE) child.setVisibility(View.GONE);
        }
        if (ours != null && container.indexOfChild(ours) != container.getChildCount() - 1) {
            ours.bringToFront();
        }
    }

    private static void restoreStockChildren(ViewGroup container, LockPlayerView ours) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == null || child == ours) continue;
            if (child.getVisibility() == View.GONE) child.setVisibility(View.VISIBLE);
        }
    }

    private static Object readField(Object instance, String name) throws Exception {
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(instance);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean readFieldBoolean(Object instance, String name, boolean fallback) {
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(instance);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private static void setFieldBoolean(Object instance, String name, boolean value) {
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(instance, value);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static final Method SET_MEASURED_DIMENSION = resolveSetMeasuredDimension();

    private static Method resolveSetMeasuredDimension() {
        try {
            Method m = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void forceMeasuredDimension(View v, int w, int h) {
        if (SET_MEASURED_DIMENSION == null) return;
        try {
            SET_MEASURED_DIMENSION.invoke(v, w, h);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
    }

    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    static List<HookHandle> interceptConstructors(Class<?> type, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) targets.add(c);
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) {
            if (t != null) x.log(Log.ERROR, HOOK_TAG, what, t);
            else x.log(Log.ERROR, HOOK_TAG, what);
        }
        if (t != null) Log.e(HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what);
    }
}
