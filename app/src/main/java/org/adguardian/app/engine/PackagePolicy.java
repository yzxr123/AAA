package org.adguardian.app.engine;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PackagePolicy {
    private static final Set<String> CORE_SYSTEM_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui"
    ));

    private final Context context;
    private final Set<String> homePackages = new HashSet<>();

    public PackagePolicy(Context context) {
        this.context = context.getApplicationContext();
        discoverHomePackages();
    }

    public boolean allow(String packageName) {
        return "allowed".equals(reason(packageName));
    }

    public String reason(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "empty-package";
        if (packageName.equals(context.getPackageName())) return "self";
        if (CORE_SYSTEM_PACKAGES.contains(packageName)) return "core-system";
        if (homePackages.contains(packageName)) return "home";
        return "allowed";
    }

    public boolean isHomePackage(String packageName) {
        return packageName != null && homePackages.contains(packageName);
    }

    private void discoverHomePackages() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        for (ResolveInfo resolveInfo : homes) {
            if (resolveInfo.activityInfo != null && resolveInfo.activityInfo.packageName != null) {
                homePackages.add(resolveInfo.activityInfo.packageName);
            }
        }
    }
}
