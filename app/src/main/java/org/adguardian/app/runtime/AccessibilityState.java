package org.adguardian.app.runtime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import org.adguardian.app.settings.PreferenceStore;

public final class AccessibilityState {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static Object owner;
    private static boolean ready;
    private static boolean failed;

    private AccessibilityState() { }

    public static boolean containsComponent(String enabled, String pkg, String serviceName) {
        if (enabled == null || pkg == null || serviceName == null) return false;
        ComponentName expected = new ComponentName(pkg, serviceName);
        for (String part : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(part.trim());
            if (expected.equals(component)) return true;
        }
        return false;
    }

    public static final String SERVICE_NAME = "org.adguardian.app.service.AdAccessibilityService";
    private static final String LAST_GRANT = "accessibility_last_verified_grant";

    public static Snapshot read(Context context) {
        boolean known = false, authorized = false;
        String evidence = "系统授权信息暂时不可用", other = "";
        String enabled = null;
        try {
            enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (RuntimeException unavailable) { }
        if (enabled != null) {
            known = true;
            authorized = containsComponent(enabled, context.getPackageName(), SERVICE_NAME);
            evidence = "系统已保存的辅助功能开关";
            for (String value : enabled.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(value.trim());
                if (c != null && !c.getPackageName().equals(context.getPackageName())
                        && SERVICE_NAME.equals(c.getClassName())) other = c.getPackageName();
            }
        } else {
            try {
                if("0".equals(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED))) {
                    known=true;authorized=false;evidence="系统辅助功能总开关已关闭";
                }
            } catch(RuntimeException unavailable) { }
            try {
                if (!known && listedByManager(context)) {
                    known = true; authorized = true; evidence = "系统服务管理器";
                }
            } catch (RuntimeException unavailable) { }
            synchronized (AccessibilityState.class) {
                // A real framework connection is positive evidence, an empty runtime list is not revocation.
                if (!known && owner != null) {
                    known = true; authorized = true; evidence = "系统已经连接辅助功能服务";
                }
            }
        }
        // A retained component entry cannot override an explicitly disabled system master switch.
        try {
            if("0".equals(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED))) {
                known=true;authorized=false;evidence="系统辅助功能总开关已关闭";
            }
        } catch(RuntimeException unavailable) { }
        boolean previous = false;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("service_state", Context.MODE_PRIVATE);
            previous = prefs.getBoolean(LAST_GRANT, false);
            if (known && previous != authorized) prefs.edit().putBoolean(LAST_GRANT, authorized).apply();
            if (known) previous = authorized;
        } catch (RuntimeException unavailable) { }
        synchronized (AccessibilityState.class) {
            return new Snapshot(known, authorized, owner != null, ready, failed,
                    PreferenceStore.isMasterEnabled(context), previous, evidence, other);
        }
    }

    private static boolean listedByManager(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> list = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (list == null) return false;
        for (AccessibilityServiceInfo info : list) {
            if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
            if (service.name == null || service.packageName == null) continue;
            String name = service.name.startsWith(".") ? service.packageName + service.name : service.name;
            if (context.getPackageName().equals(service.packageName) && SERVICE_NAME.equals(name)) return true;
        }
        return false;
    }

    public static synchronized void connected(Object source) {
        if (owner == source) return;
        owner = source; ready = false; failed = false; changed();
    }
    public static synchronized void preparing(Object source) {
        if(owner!=source)return;ready=false;failed=false;changed();
    }
    public static synchronized void initialized(Object source, boolean ok) {
        if (owner != source) return;
        ready = ok; failed = !ok; changed();
    }
    public static synchronized void disconnected(Object source) {
        if (owner != source) return;
        owner = null; ready = false; failed = false; changed();
    }
    public static void changed() {
        for (Runnable listener : LISTENERS) MAIN.post(listener);
    }

    public static final class Snapshot {
        public final boolean known, authorized, connected, ready, failed, enabled, previouslyAuthorized;
        public final String evidence, otherAuthorizedPackage;
        Snapshot(boolean known, boolean authorized, boolean connected, boolean ready, boolean failed, boolean enabled, boolean previous, String evidence, String other) {
            this.known=known;this.authorized=authorized;this.connected=connected;
            this.ready=ready;this.failed=failed;this.enabled=enabled;
            previouslyAuthorized=previous;this.evidence=evidence;otherAuthorizedPackage=other;
        }
        public boolean running() { return authorized && connected && ready && enabled; }
        public String permissionText() {
            if (!known) return "辅助功能 请前往系统设置查看";
            return authorized ? "辅助功能权限 已允许" : "辅助功能权限 未允许";
        }
        public String runtimeText() {
            if (!enabled) return "广告保护 已暂停";
            if (!known) return "广告保护 请确认辅助功能设置";
            if (!authorized) return "广告保护 请开启本应用的辅助功能";
            if (!connected) return "广告保护 未启动 请关闭后重新开启辅助功能";
            if (failed) return "广告保护 暂不可用 请重新打开应用";
            if (!ready) return "广告保护 正在启动";
            return "广告保护 正在运行";
        }
    }

    public static final class Watch implements AutoCloseable {
        private final Context context;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable callback;
        private final Runnable notify;
        private final ContentObserver observer;
        private boolean closed;
        private boolean registered;
        public Watch(Context context, Runnable callback) {
            this.context=context; this.callback=callback;
            notify=() -> { if(!closed)this.callback.run(); };
            observer=new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) { notify.run(); }
            };
            LISTENERS.add(notify);
            try {
                context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),false,observer);
                registered=true;
                context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_ENABLED),false,observer);
            } catch(RuntimeException unavailable) { }

        }
        @Override public void close() {
            closed=true;LISTENERS.remove(notify);
            if(registered) {
                try {context.getContentResolver().unregisterContentObserver(observer);}catch(RuntimeException unavailable) { }
            }
            handler.removeCallbacksAndMessages(null);
        }
    }
}
