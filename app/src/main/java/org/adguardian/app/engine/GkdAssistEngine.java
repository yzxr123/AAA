package org.adguardian.app.engine;

import android.accessibilityservice.AccessibilityService;
import org.adguardian.app.runtime.RootAccess;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.adguardian.app.settings.PreferenceStore;

import java.util.Locale;

public final class GkdAssistEngine {
    private static final long ACTION_GUARD_MS = 420L;
    private static final long FEED_SCROLL_GUARD_MS = 1_100L;

    private final AccessibilityService service;
    private final Handler worker;
    private final ActionVerifier verifier;
    private volatile boolean gesturePending;
    private long gestureGeneration;
    private String packageName = "";
    private long lastAutomatedActionUptime;
    private long lastFeedScrollUptime;

    public GkdAssistEngine(AccessibilityService service) {
        this(service,new Handler(Looper.getMainLooper()),new ActionVerifier(service,new Handler(Looper.getMainLooper()),
                () -> rootPackage(service),()->{}));
    }
    public GkdAssistEngine(AccessibilityService service,Handler worker,ActionVerifier verifier) {
        this.service=service;this.worker=worker;this.verifier=verifier;
    }

    private static String rootPackage(AccessibilityService service) {
        AccessibilityNodeInfo root=RootAccess.read(service);
        try {return root==null?"":NodeTarget.string(root.getPackageName());}
        finally {if(root!=null)root.recycle();}
    }
    public void onPackageEntered(String currentPackage) {
        packageName = currentPackage == null ? "" : currentPackage;
        lastFeedScrollUptime = 0L;
    }

    public long lastAutomatedActionUptime() {
        return lastAutomatedActionUptime;
    }

    public boolean handle(
            String currentPackage,
            String activityName,
            int eventType,
            AccessibilityNodeIndex index
    ) {
        if (!PreferenceStore.isGkdEnabled(service) || index == null) return false;
        if (currentPackage == null || currentPackage.isEmpty()) return false;
        if (!currentPackage.equals(packageName)) onPackageEntered(currentPackage);
        long now = SystemClock.uptimeMillis();
        if (now - lastAutomatedActionUptime < ACTION_GUARD_MS) return false;

        boolean startupEnabled = PreferenceStore.isTypeEnabled(service, AdType.STARTUP);
        boolean closeEnabled = PreferenceStore.isTypeEnabled(service, AdType.POPUP)
                || PreferenceStore.isTypeEnabled(service, AdType.FLOATING)
                || PreferenceStore.isTypeEnabled(service, AdType.CARD)
                || PreferenceStore.isTypeEnabled(service, AdType.FEED);
        boolean scrollEnabled = PreferenceStore.isTypeEnabled(service, AdType.SCROLL)
                || PreferenceStore.isTypeEnabled(service, AdType.FEED);
        if (!startupEnabled && !closeEnabled && !scrollEnabled) return false;

        Rect screen = index.rootBounds();
        if (screen.width() <= 0 || screen.height() <= 0) return false;
        if(SafeActionTarget.forbiddenScreen(index))return false;
        Candidate best = null;
        AccessibilityNodeInfo selectedAction=null;
        for (AccessibilityNodeInfo node : index.nodes()) {
            Candidate candidate = candidate(node, screen, startupEnabled, closeEnabled);
            if(candidate==null)continue;
            AccessibilityNodeInfo action=SafeActionTarget.clickTarget(node,index);
            if(action==null)continue;
            if(selectedAction!=null && !selectedAction.equals(action))return false;
            selectedAction=action;best=candidate;
        }
        if(index.isTraversalIncomplete())return false;
        if (best != null) {
            boolean performed=perform(best,index);
            if(performed)lastAutomatedActionUptime = SystemClock.uptimeMillis();
            return performed;
        }

        if (scrollEnabled && now - lastFeedScrollUptime >= FEED_SCROLL_GUARD_MS
                && shouldTryFeedScroll(eventType) && scrollPastLabeledAd(index, screen)) {
            lastFeedScrollUptime = now;
            lastAutomatedActionUptime = now;
            return true;
        }

        return false;
    }

    private Candidate candidate(
            AccessibilityNodeInfo node,
            Rect screen,
            boolean startupEnabled,
            boolean closeEnabled
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return null;
        Rect bounds = bounds(node);
        if (!safeBounds(bounds, screen)) return null;
        String text = string(node.getText());
        String desc = string(node.getContentDescription());
        String id = string(node.getViewIdResourceName());
        String shortId = shortId(id);
        String combined = (text + ' ' + desc + ' ' + id + ' ' + shortId).toLowerCase(Locale.ROOT);
        if (unsafeFlow(combined)) return null;

        boolean explicitSkip = explicitSkip(text) || explicitSkip(desc) || explicitSkip(shortId);
        boolean explicitClose = explicitClose(text) || explicitClose(desc) || idClose(shortId);
        boolean dismissible = closeEnabled && explicitClose && supportsDismiss(node);

        boolean skip=startupEnabled && explicitSkip;
        if(!skip && !(closeEnabled && explicitClose))return null;
        return new Candidate(node, bounds, skip, dismissible);
    }

    private boolean scrollPastLabeledAd(AccessibilityNodeIndex index, Rect screen) {
        AccessibilityNodeInfo fallbackScrollable = null;
        for (AccessibilityNodeInfo node : index.nodes()) {
            if (node == null || !node.isVisibleToUser()) continue;
            if (fallbackScrollable == null && node.isScrollable()) {
                Rect bounds = bounds(node);
                if (bounds.width() >= screen.width() * 0.70f && bounds.height() >= screen.height() * 0.40f) {
                    fallbackScrollable = node;
                }
            }
            String value = (string(node.getText()) + ' ' + string(node.getContentDescription())).trim();
            if (!isFeedAdLabel(value)) continue;
            ActionVerifier.Ticket ticket=verifier.capture(currentPackageForFeedback(),"assist:scroll","swipe",node,index,null);
            if(ticket==null || !verifier.isCurrent(ticket) || !scrollAllowed())continue;
            AccessibilityNodeInfo scrollable = scrollableAncestor(node,index);
            if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                verifier.accepted(ticket);return true;
            }
            if (fallbackScrollable != null
                    && verifier.isCurrent(ticket) && scrollAllowed()
                    && fallbackScrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                verifier.accepted(ticket);return true;
            }
        }
        return false;
    }

    public boolean isGesturePending() {return gesturePending;}
    public void cancelPending() {gestureGeneration++;gesturePending=false;}
    private String currentPackageForFeedback() { return packageName; }

    private AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo node,AccessibilityNodeIndex index) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 9; depth++) {
            if (current.isScrollable() && current.isEnabled()) return current;
            current = index.cachedParent(current);
        }
        return null;
    }

    private boolean perform(Candidate candidate,AccessibilityNodeIndex index) {
        AccessibilityNodeInfo node=candidate.node;
        AccessibilityNodeInfo click=SafeActionTarget.clickTarget(node,index);
        if(click==null)return false;
        String key="assist:"+NodeTarget.string(node.getViewIdResourceName())+":"+NodeTarget.string(node.getText());
        ActionVerifier.Ticket ticket=verifier.capture(packageName,key,"click",node,index,null);
        if(ticket==null || !verifier.isCurrent(ticket) || !candidateAllowed(candidate))return false;
        if(candidate.dismiss && node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
            verifier.accepted(ticket);return true;
        }
        if(candidate.dismiss && (!verifier.isCurrent(ticket) || !candidateAllowed(candidate)))return false;
        if(click.isClickable() && click.isEnabled() && click.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            verifier.accepted(ticket);return true;
        }
        if(!verifier.isCurrent(ticket) || !candidateAllowed(candidate))return false;
        Path path=new Path();path.moveTo(candidate.bounds.exactCenterX(),candidate.bounds.exactCenterY());
        GestureDescription gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0L,48L)).build();
        long token=++gestureGeneration;gesturePending=true;
        boolean accepted;
        try {
            accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription value) {
                    if(token!=gestureGeneration)return;gesturePending=false;
                    if(candidateAllowed(candidate))verifier.accepted(ticket);
                }
                @Override public void onCancelled(GestureDescription value) {
                    if(token!=gestureGeneration)return;gesturePending=false;verifier.rejected(ticket);
                }
            },worker);
        } catch(RuntimeException error) {gesturePending=false;verifier.rejected(ticket);return false;}
        if(!accepted) {gesturePending=false;verifier.rejected(ticket);}
        else worker.postDelayed(() -> {
            if(token==gestureGeneration && gesturePending) {gesturePending=false;gestureGeneration++;verifier.rejected(ticket);}
        },1500L);
        return accepted;
    }

    private boolean candidateAllowed(Candidate candidate) {
        if(!PreferenceStore.isMasterEnabled(service) || !PreferenceStore.isGkdEnabled(service))return false;
        return candidate.skip?PreferenceStore.isTypeEnabled(service,AdType.STARTUP)
                :PreferenceStore.isTypeEnabled(service,AdType.POPUP)
                ||PreferenceStore.isTypeEnabled(service,AdType.FLOATING)
                ||PreferenceStore.isTypeEnabled(service,AdType.CARD)
                ||PreferenceStore.isTypeEnabled(service,AdType.FEED);
    }
    private boolean scrollAllowed() {
        return PreferenceStore.isMasterEnabled(service) && PreferenceStore.isGkdEnabled(service)
                && (PreferenceStore.isTypeEnabled(service,AdType.SCROLL)
                ||PreferenceStore.isTypeEnabled(service,AdType.FEED));
    }

    private static boolean shouldTryFeedScroll(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
    }

    private static boolean isFeedAdLabel(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace(" ", "").trim();
        return lower.equals("广告") || lower.equals("推广") || lower.equals("赞助")
                || lower.equals("广告内容") || lower.equals("广告推广")
                || lower.equals("advertisement") || lower.equals("sponsored")
                || lower.startsWith("广告·") || lower.startsWith("广告|")
                || lower.startsWith("sponsored·");
    }

    private static boolean supportsDismiss(AccessibilityNodeInfo node) {
        return (node.getActions() & AccessibilityNodeInfo.ACTION_DISMISS) != 0;
    }

    private static boolean safeBounds(Rect bounds, Rect screen) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;
        if (!Rect.intersects(bounds, screen)) return false;
        long area = (long) bounds.width() * bounds.height();
        long screenArea = (long) screen.width() * screen.height();
        if (screenArea <= 0 || area * 2L >= screenArea) return false;
        return bounds.width() <= screen.width() * 0.72f && bounds.height() <= screen.height() * 0.28f;
    }

    private static boolean explicitSkip(String value) {
        String lower = AdCloseText.normalize(value).toLowerCase(Locale.ROOT).replace(" ", "");
        return lower.equals("跳过广告") || lower.equals("跳過廣告")
                || lower.equals("skipad") || lower.equals("skipads") || lower.equals("skipadvertisement");
    }

    private static boolean explicitClose(String value) {
        String lower = AdCloseText.normalize(value).toLowerCase(Locale.ROOT).replace(" ", "");
        return lower.equals("关闭广告") || lower.equals("關閉廣告") || lower.equals("关闭推广")
                || lower.equals("closead") || lower.equals("dismissad");
    }

    private static boolean idClose(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!(lower.contains("close") || lower.contains("dismiss"))) return false;
        return lower.contains("ad_close") || lower.contains("close_ad") || lower.equals("adclose")
                || lower.contains("splash") || lower.contains("promo")
                || lower.contains("ksad") || lower.contains("gdt") || lower.contains("pangle")
                || lower.contains("tt_");
    }

    private static boolean unsafeFlow(String value) {
        return value.contains("权限") || value.contains("授权") || value.contains("支付")
                || value.contains("付款") || value.contains("安全警告") || value.contains("风险提示")
                || value.contains("路线") || value.contains("导航") || value.contains("隐私政策")
                || value.contains("用户协议") || value.contains("permission") || value.contains("payment");
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect;
    }

    private static String shortId(String value) {
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final boolean skip;
        final boolean dismiss;

        Candidate(AccessibilityNodeInfo node, Rect bounds, boolean skip, boolean dismiss) {
            this.node = node;
            this.bounds = new Rect(bounds);
            this.skip = skip;
            this.dismiss = dismiss;
        }
    }
}
