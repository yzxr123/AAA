package org.adguardian.app.engine;

public enum AdType {
    STARTUP("startup", "开屏广告"),
    POPUP("popup", "弹窗广告"),
    FLOATING("floating", "悬浮广告"),
    CARD("card", "卡片广告"),
    FEED("feed", "信息流广告"),
    SCROLL("scroll", "滑动广告"),
    JUMP("jump", "广告跳转"),
    SHAKE("shake", "摇一摇广告");

    private final String preferenceKey;
    private final String displayName;

    AdType(String preferenceKey, String displayName) {
        this.preferenceKey = preferenceKey;
        this.displayName = displayName;
    }

    public String preferenceKey() {
        return preferenceKey;
    }

    public String displayName() {
        return displayName;
    }
}
