package org.adguardian.app.engine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One-shot rollback for a verified ad jump into an explicit shopping-app target. */
public final class JumpGuard {
    private static final Set<String> TAOBAO_FAMILY = new HashSet<>(Arrays.asList(
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.taobao.etao",
            "com.taobao.htao.android",
            "com.taobao.idlefish",
            "com.taobao.movie.android"
    ));
    private static final Set<String> AD_DESTINATIONS = new HashSet<>(Arrays.asList(
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.taobao.etao",
            "com.taobao.htao.android",
            "com.taobao.idlefish",
            "com.taobao.movie.android",
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.ss.android.ugc.aweme",
            "com.smile.gifmaker",
            "com.sankuai.meituan",
            "com.dianping.v1",
            "com.xingin.xhs"
    ));
    private static final Set<String> PROTECTED_SOURCES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.eg.android.AlipayGphone",
            "com.tencent.mm",
            "com.unionpay"
    ));

    private final String selfPackage;
    private String sourcePackage="";
    private boolean pending;

    public JumpGuard(String selfPackage) {
        this.selfPackage=selfPackage==null?"":selfPackage;
    }

    public synchronized boolean arm(String source,boolean jumpEvidence,boolean shakeEvidence) {
        clear();
        if(source==null || source.isEmpty() || source.equals(selfPackage) || protectedSource(source)
                || (!jumpEvidence && !shakeEvidence))return false;
        sourcePackage=source;
        pending=true;
        return true;
    }

    public synchronized void onUserInteraction() {
        clear();
    }

    /** Consumes the evidence on the first distinct package transition, whether accepted or rejected. */
    public synchronized boolean shouldReturn(String source,String target) {
        if(!pending)return false;
        String expected=sourcePackage;
        clear();
        return source!=null && source.equals(expected)
                && target!=null && !target.equals(source)
                && AD_DESTINATIONS.contains(target);
    }

    public static boolean isTaobaoFamily(String packageName) {
        return packageName!=null && TAOBAO_FAMILY.contains(packageName);
    }

    private static boolean protectedSource(String packageName) {
        String lower=packageName.toLowerCase(java.util.Locale.ROOT);
        return PROTECTED_SOURCES.contains(packageName) || lower.contains("permission")
                || lower.contains("installer") || lower.contains("payment")
                || lower.contains("wallet") || lower.contains(".bank");
    }

    public synchronized void clear() {
        pending=false;
        sourcePackage="";
    }
}
