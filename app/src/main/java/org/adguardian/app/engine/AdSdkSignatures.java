package org.adguardian.app.engine;

import java.util.Locale;

public final class AdSdkSignatures {
    private static final String PANGLE_FULLSCREEN =
            "com.bytedance.sdk.openadsdk.core.component.reward.activity.TTFullScreenVideoActivity";
    private static final String PANGLE_FULLSCREEN_LANDSCAPE =
            "com.bytedance.sdk.openadsdk.core.component.reward.activity.TTFullScreenVideoLandscapeActivity";
    private static final String[] RESOURCE_MARKERS = {
            "tt_splash",
            "pangle",
            "openadsdk",
            "pangolin",
            "ksad",
            "kwad",
            "adkwai",
            "gdt",
            "tencent_ad",
            "jad_",
            "jad-",
            "sigmob",
            "mobads",
            "baidu_mobads",
            "mbridge",
            "mintegral",
            "anythink",
            "topon",
            "tradplus",
            "applovin",
            "unityads",
            "unity_ads",
            "vungle",
            "ironsource",
            "levelplay",
            "google_mobile_ads",
            "admob",
            "inmobi",
            "adcolony",
            "chartboost",
            "tapjoy",
            "fyber",
            "heytap",
            "oppo_ads",
            "huawei_ads",
            "hw_ads"
    };

    private static final String[] ACTIVITY_MARKERS = {
            "com.bytedance.sdk.openadsdk",
            "com.byted.pangle",
            "com.qq.e.ads",
            "com.qq.e.comm",
            "com.kwad.sdk",
            "com.ksad",
            "com.baidu.mobads",
            "com.baidu.mobad",
            "com.sigmob",
            "com.mbridge.msdk",
            "com.mintegral.msdk",
            "com.anythink",
            "com.applovin",
            "com.unity3d.services.ads",
            "com.vungle",
            "com.ironsource",
            "com.google.android.gms.ads",
            "com.inmobi",
            "com.adcolony",
            "com.chartboost",
            "com.tapjoy",
            "com.tradplus.ads",
            "com.huawei.openalliance.ad",
            "com.heytap.msp.mobad"
    };

    private static final String[] REWARD_MARKERS = {
            "reward",
            "rewarded",
            "rewardvideo",
            "reward_video",
            "incentive"
    };

    private static final String[] SDK_NAMES = {
            "pangle|tt_splash|openadsdk|pangolin|com.bytedance.sdk.openadsdk|com.byted.pangle",
            "kuaishou|ksad|kwad|adkwai|com.kwad.sdk|com.ksad|com.kwai",
            "gdt|gdt|tencent_ad|com.qq.e.ads|com.qq.e.comm",
            "jad|jad_|jad-|jrad.jd.com",
            "baidu|mobads|baidu_mobads|com.baidu.mobads|com.baidu.mobad",
            "sigmob|sigmob|com.sigmob",
            "mintegral|mbridge|mintegral|com.mbridge.msdk|com.mintegral.msdk",
            "topon|anythink|topon|com.anythink",
            "tradplus|tradplus",
            "applovin|applovin|com.applovin",
            "unity|unityads|unity_ads|com.unity3d.services.ads",
            "vungle|vungle|com.vungle",
            "levelplay|ironsource|levelplay|com.ironsource",
            "googleads|google_mobile_ads|admob|com.google.android.gms.ads",
            "inmobi|inmobi|com.inmobi",
            "adcolony|adcolony|com.adcolony",
            "chartboost|chartboost|com.chartboost",
            "tapjoy|tapjoy|com.tapjoy",
            "tradplus|tradplus|com.tradplus.ads",
            "huaweiads|huawei_ads|hw_ads|com.huawei.openalliance.ad",
            "heytapads|heytap|oppo_ads|com.heytap.msp.mobad"
    };

    private AdSdkSignatures() {
    }

    public static boolean containsResourceMarker(String value) {
        String lower = lower(value);
        if (lower.isEmpty()) {
            return false;
        }
        for (String marker : RESOURCE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAdSdkActivity(String className) {
        String lower = lower(className);
        if (lower.isEmpty()) {
            return false;
        }
        for (String marker : ACTIVITY_MARKERS) {
            if (lower.startsWith(marker + ".")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRewardedActivity(String className) {
        String lower = lower(className);
        // Pangle places ordinary fullscreen classes in a package segment named reward.
        // Only these APK-confirmed ordinary classes are excepted, not arbitrary reward activities.
        if(PANGLE_FULLSCREEN.equals(className) || PANGLE_FULLSCREEN_LANDSCAPE.equals(className))return false;
        for (String marker : REWARD_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public static String sdkName(String resourceId, String className) {
        String haystack = lower(resourceId) + ' ' + lower(className);
        for (String row : SDK_NAMES) {
            String[] parts = row.split("\\|");
            for (int index = 1; index < parts.length; index++) {
                if (haystack.contains(parts[index])) {
                    return parts[0];
                }
            }
        }
        return "unknown";
    }

    public static int resourceMarkerCount() {
        return RESOURCE_MARKERS.length;
    }

    public static int activityMarkerCount() {
        return ACTIVITY_MARKERS.length;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
