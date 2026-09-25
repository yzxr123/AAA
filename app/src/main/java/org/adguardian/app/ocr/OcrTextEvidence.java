package org.adguardian.app.ocr;

/** Exact visible phrases that can justify jump/shake handling; no body text is retained. */
public final class OcrTextEvidence {
    private OcrTextEvidence() { }

    public static boolean jump(String value) {
        if(value==null || value.isEmpty())return false;
        return value.contains("第三方应用")
                || value.contains("第三方跳转")
                || value.contains("跳转第三方")
                || value.contains("即将跳转")
                || value.contains("打开第三方")
                || value.contains("前往第三方")
                || value.contains("打开其他应用")
                || value.contains("即将打开")
                || value.contains("打开淘宝")
                || value.contains("去淘宝")
                || value.contains("前往淘宝")
                || value.contains("淘宝打开")
                || value.contains("打开天猫")
                || value.contains("去天猫")
                || value.contains("前往天猫")
                || value.contains("打开一淘")
                || value.contains("打开闲鱼");
    }

    public static boolean shake(String value) {
        if(value==null || value.isEmpty())return false;
        return value.contains("摇一摇")
                || value.contains("扭一扭")
                || value.contains("摇动手机")
                || value.contains("扭动手机")
                || value.contains("摇动或点击")
                || value.contains("扭动或点击")
                || value.contains("摇一摇或点击")
                || value.contains("扭一扭或点击");
    }
}
