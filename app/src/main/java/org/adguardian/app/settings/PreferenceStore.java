package org.adguardian.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import org.adguardian.app.engine.AdType;

public final class PreferenceStore {
    private static final String FILE_NAME = "qunideguanggao_settings";
    private static final String MASTER_KEY = "master";
    private static final String LTT_KEY = "engine.ltt";
    private static final String GKD_KEY = "engine.gkd";
    private static final String OCR_KEY = "engine.ocr";
    private static final String KEEP_ALIVE_KEY = "runtime.keep_alive";
    private static final String TASK_REMOVED_KEY = "runtime.task_removed";
    private static final String TYPE_PREFIX = "type.";

    private PreferenceStore() {
    }

    public static boolean isMasterEnabled(Context context) {
        return preferences(context).getBoolean(MASTER_KEY, true);
    }

    public static void setMasterEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(MASTER_KEY, enabled).apply();
    }

    public static boolean isLiTiaotiaoEnabled(Context context) {
        return preferences(context).getBoolean(LTT_KEY, true);
    }

    public static void setLiTiaotiaoEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(LTT_KEY, enabled).apply();
    }

    public static boolean isGkdEnabled(Context context) {
        return preferences(context).getBoolean(GKD_KEY, true);
    }

    public static void setGkdEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(GKD_KEY, enabled).apply();
    }

    public static boolean isOcrEnabled(Context context) {
        return preferences(context).getBoolean(OCR_KEY, true);
    }

    public static void setOcrEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(OCR_KEY, enabled).apply();
    }

    public static boolean isKeepAliveEnabled(Context context) {
        return preferences(context).getBoolean(KEEP_ALIVE_KEY, true);
    }

    public static void setKeepAliveEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEEP_ALIVE_KEY, enabled).apply();
    }

    public static void markTaskRemoved(Context context) {
        preferences(context).edit().putBoolean(TASK_REMOVED_KEY,true).apply();
    }

    public static boolean isTaskRemoved(Context context) {
        return preferences(context).getBoolean(TASK_REMOVED_KEY,false);
    }

    public static boolean consumeTaskRemoved(Context context) {
        SharedPreferences values=preferences(context);
        if(!values.getBoolean(TASK_REMOVED_KEY,false))return false;
        values.edit().putBoolean(TASK_REMOVED_KEY,false).apply();
        return true;
    }

    public static boolean isSdkEnabled(Context context) { return preferences(context).getBoolean("engine.sdk",true); }
    public static void setSdkEnabled(Context context,boolean enabled) { preferences(context).edit().putBoolean("engine.sdk",enabled).apply(); }
    public static boolean isTypeEnabled(Context context, AdType type) {
        return preferences(context).getBoolean(TYPE_PREFIX + type.preferenceKey(), true);
    }

    public static void setTypeEnabled(Context context, AdType type, boolean enabled) {
        preferences(context).edit().putBoolean(TYPE_PREFIX + type.preferenceKey(), enabled).apply();
    }

    public static void enableAllAds(Context context) {
        SharedPreferences.Editor editor = preferences(context).edit();
        editor.putBoolean(MASTER_KEY, true);
        editor.putBoolean(LTT_KEY, true);
        editor.putBoolean(GKD_KEY, true);
        editor.putBoolean(OCR_KEY, true);
        editor.putBoolean(KEEP_ALIVE_KEY, true);
        editor.putBoolean("engine.sdk",true);
        for (AdType type : AdType.values()) {
            editor.putBoolean(TYPE_PREFIX + type.preferenceKey(), true);
        }
        editor.apply();
    }

    public static boolean areAllAdsEnabled(Context context) {
        if (!isMasterEnabled(context)
                || !isLiTiaotiaoEnabled(context)
                || !isGkdEnabled(context)
                || !isOcrEnabled(context)
                || !isKeepAliveEnabled(context) || !isSdkEnabled(context)) {
            return false;
        }
        for (AdType type : AdType.values()) {
            if (!isTypeEnabled(context, type)) {
                return false;
            }
        }
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }
}
