package org.adguardian.app.engine;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes only complete close/skip labels, not arbitrary substrings. Callers still need a page guard. */
public final class AdCloseText {
    private static final Pattern COUNT = Pattern.compile("(?i)^\\d{1,2}\\s*(?:s|秒)$");
    private static final Pattern PREFIX = Pattern.compile("(?i)^\\d{1,2}\\s*(?:s|秒)\\s*[|·:：\\-]?\\s*");
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\s*[|·:：\\-]?\\s*\\d{1,2}\\s*(?:s|秒)?$");
    private AdCloseText() { }
    public static String normalize(String raw) {
        if (raw == null) return "";
        if (raw.length() > 80) return raw.trim();
        String original = raw.trim();
        String text = Normalizer.normalize(original, Normalizer.Form.NFKC)
                .replace('\u00a0',' ').replace('\u200b',' ').trim();
        text = PREFIX.matcher(text).replaceFirst("");
        text = text.replaceAll("^[|·:：\\-]\\s*", "");
        text = SUFFIX.matcher(text).replaceFirst("");
        text = text.replaceAll("\\s*[|·:：\\-]$", "").trim();
        if (COUNT.matcher(text).matches()) return original;
        String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (lower.equals("skip") || lower.equals("skip ad") || lower.equals("close") || lower.equals("close ad")) return lower;
        if (text.equals("跳过") || text.equals("跳过广告") || text.equals("跳過") || text.equals("跳過廣告")
                || text.equals("关闭") || text.equals("关闭广告") || text.equals("關閉") || text.equals("關閉廣告")
                || text.equals("×") || text.equals("✕") || lower.equals("x")) return lower.equals("x") ? "x" : text;
        return original;
    }
    public static boolean isClose(String raw) {
        String s = normalize(raw);
        return s.equals("跳过") || s.equals("跳过广告") || s.equals("跳過") || s.equals("跳過廣告")
                || s.equals("关闭") || s.equals("关闭广告") || s.equals("關閉") || s.equals("關閉廣告")
                || s.equals("skip") || s.equals("skip ad") || s.equals("close") || s.equals("close ad")
                || s.equals("×") || s.equals("✕") || s.equals("x");
    }
}
