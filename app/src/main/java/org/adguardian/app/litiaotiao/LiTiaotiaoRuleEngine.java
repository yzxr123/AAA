package org.adguardian.app.litiaotiao;

import android.accessibilityservice.AccessibilityService;
import org.adguardian.app.runtime.RootAccess;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import org.adguardian.app.settings.PreferenceStore;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.ActionVerifier;
import org.adguardian.app.engine.AdCloseText;
import org.adguardian.app.engine.NodeTarget;
import org.adguardian.app.engine.SafeActionTarget;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.log.UserEventLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LiTiaotiaoRuleEngine {
    private static final List<String> DEFAULT_KEYWORDS = Arrays.asList(
            "count_down", "countdown", "跳过", "skip", "跳過"
    );
    private static final Pattern COORDINATE = Pattern.compile("x\\s*:\\s*(\\d+)\\s*y\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOUNDS_ACTION = Pattern.compile("\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*[01])?\\s*");
    private static final long SAME_ACTION_GUARD_MS = 360L;
    private static final long SEARCH_INTERVAL_MS = 220L;
    private static final long ENTRY_WINDOW_MS = 12000L;

    private final AccessibilityService service;
    private final ActionVerifier verifier;
    private volatile boolean gesturePending;
    private final LiTiaotiaoRuleRepository repository;
    private final Handler handler;
    private final Supplier<String> foreground;
    private BooleanSupplier externalActionPending = () -> false;
    private volatile boolean pendingAction;
    private final Map<String, Integer> successCounts = new HashMap<>();
    private final Map<String, Long> lastAttempts = new HashMap<>();
    private final Set<String> delayedPending = new HashSet<>();

    private String packageName = "";
    private long generation;
    private long enteredAt;
    private long gestureGeneration;
    private boolean startupDone;
    private boolean searchBurstPending;
    private volatile long lastAutomatedActionUptime;

    public LiTiaotiaoRuleEngine(AccessibilityService service, Handler worker, Supplier<String> foreground) {
        this(service,worker,foreground,new ActionVerifier(service,worker,foreground,()->{}));
    }
    public LiTiaotiaoRuleEngine(AccessibilityService service,Handler worker,Supplier<String> foreground,ActionVerifier verifier) {
        this.verifier=verifier;
        this.service = service;
        this.handler = new Handler(worker.getLooper());
        this.foreground = foreground;
        this.repository = new LiTiaotiaoRuleRepository(service.getApplicationContext());
    }

    public void setExternalActionPending(BooleanSupplier predicate) { externalActionPending=predicate; }

    public boolean hasPendingAction() { return pendingAction || gesturePending; }

    public void cancelPending() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        delayedPending.clear();
        pendingAction=false;gesturePending=false;
        searchBurstPending=false;
    }

    private boolean mayAct(String currentPackage) {
        return PreferenceStore.isMasterEnabled(service)
                && PreferenceStore.isLiTiaotiaoEnabled(service)
                && currentPackage.equals(foreground.get()) && !gesturePending && !externalActionPending.getAsBoolean();
    }

    public int packageCount() {
        return repository.packageCount();
    }

    public int ruleCount() {
        return repository.ruleCount();
    }

    public long lastAutomatedActionUptime() {
        return lastAutomatedActionUptime;
    }

    public void onPackageEntered(String currentPackage) {
        String next = currentPackage == null ? "" : currentPackage;
        if (next.equals(packageName)) return;
        cancelPending();
        packageName = next;
        enteredAt = SystemClock.uptimeMillis();
        startupDone = false;
        searchBurstPending = false;
        successCounts.clear();
        lastAttempts.clear();
        delayedPending.clear();
    }

    public boolean handle(String currentPackage, AccessibilityNodeInfo root, AccessibilityNodeIndex index) {
        if (currentPackage == null || currentPackage.isEmpty() || root == null || index == null || !mayAct(currentPackage)) return false;
        if (!currentPackage.equals(packageName)) onPackageEntered(currentPackage);
        LiTiaotiaoRuleBundle bundle = repository.forPackage(currentPackage);
        if (bundle != null && !bundle.lttService) return false;
        boolean handled = handleInternal(currentPackage, root, index, bundle, true);
        return handled;
    }

    private boolean handleInternal(
            String currentPackage,
            AccessibilityNodeInfo root,
            AccessibilityNodeIndex index,
            LiTiaotiaoRuleBundle bundle,
            boolean allowSearchBurst
    ) {
        if(SafeActionTarget.forbiddenScreen(index))return false;
        if (PreferenceStore.isTypeEnabled(service,AdType.STARTUP) && !startupDone && handleStartupKeywords(currentPackage, index, bundle)) {
            return true;
        }
        boolean popupEnabled=PreferenceStore.isTypeEnabled(service,AdType.POPUP);
        boolean popupHandled = popupEnabled && handlePopupRules(currentPackage, index, bundle);
        if (popupHandled) return true;
        if (popupEnabled && allowSearchBurst && bundle != null && bundle.searchTimesPopup > 1 && !bundle.popupRules.isEmpty()) {
            scheduleSearchBurst(currentPackage, bundle.searchTimesPopup - 1);
        }
        return false;
    }

    private boolean handleStartupKeywords(
            String currentPackage,
            AccessibilityNodeIndex index,
            LiTiaotiaoRuleBundle bundle
    ) {
        if(SystemClock.uptimeMillis()-enteredAt>ENTRY_WINDOW_MS)return false;
        List<String> keywords = new ArrayList<>();
        int clickWay = 0;
        int delay = 0;
        if (bundle != null) {
            clickWay = bundle.clickWay;
            delay = bundle.delayMs;
            if (bundle.keywordsSpecified) {
                keywords.addAll(bundle.keywords);
            } else {
                keywords.addAll(DEFAULT_KEYWORDS);
                keywords.addAll(bundle.keywordsAppend);
            }
        } else {
            keywords.addAll(DEFAULT_KEYWORDS);
        }
        if (keywords.isEmpty()) return false;
        AccessibilityNodeInfo target = index.findKeyword(keywords);
        if (!safeStartupTarget(target,index)) return false;
        if (delay > 0) {
            scheduleStartupDelay(currentPackage, keywords, clickWay, delay);
            return false;
        }
        if (performNodeClick(target, clickWay == 1, index,
                "ltt:startup",() -> startupDone=true)) return true;
        return false;
    }

    private boolean handlePopupRules(
            String currentPackage,
            AccessibilityNodeIndex index,
            LiTiaotiaoRuleBundle bundle
    ) {
        if (bundle == null || bundle.popupRules.isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        boolean sawUniteState = false;
        for (int i = 0; i < bundle.popupRules.size(); i++) {
            LiTiaotiaoRule rule = bundle.popupRules.get(i);
            int limit = rule.times == null ? bundle.times : Math.max(0, rule.times);
            String key = currentPackage + '|' + i;
            if (limit > 0 && successCounts.getOrDefault(key, 0) >= limit) continue;
            AccessibilityNodeInfo evidence = index.findPrimaryNode(rule.id);
            if (evidence == null) continue;
            if (limit == 0) {
                sawUniteState = true;
                continue;
            }
            Long last = lastAttempts.get(key);
            if (last != null && now - last < SAME_ACTION_GUARD_MS) continue;
            lastAttempts.put(key, now);
            int delay = rule.delayPopupMs == null ? bundle.delayPopupMs : Math.max(0, rule.delayPopupMs);
            if (delay > 0) {
                schedulePopupDelay(currentPackage, key, rule, bundle.clickWayPopup == 1, delay);
                if (!bundle.unitePopupRules) return false;
                continue;
            }
            if (performPopupAction(rule, bundle.clickWayPopup == 1, index,key,
                    () -> successCounts.put(key,successCounts.getOrDefault(key,0)+1))) {
                if (!bundle.unitePopupRules) return true;
                sawUniteState = true;
            }
        }
        return sawUniteState && bundle.unitePopupRules && lastAutomatedActionUptime + 120L >= SystemClock.uptimeMillis();
    }

    private boolean performPopupAction(LiTiaotiaoRule rule,boolean forceGesture,
            AccessibilityNodeIndex index,String key,Runnable completion) {
        if("GLOBAL_ACTION_BACK".equals(rule.action)) {
            return false;
        }
        Matcher coordinates=COORDINATE.matcher(rule.action);
        Matcher boundsAction=BOUNDS_ACTION.matcher(rule.action);
        if(coordinates.find()) {
            return false;
        }
        if(boundsAction.matches()) {
            return false;
        }
        AccessibilityNodeInfo target=index.findActionNode(rule.action);
        if(target==null && equivalent(rule.id,rule.action))target=index.findPrimaryNode(rule.id);
        return target!=null && performNodeClick(target,forceGesture,index,"ltt:"+key,completion);
    }

    private void scheduleStartupDelay(
            String currentPackage,
            List<String> keywords,
            int clickWay,
            int delayMs
    ) {
        String key = currentPackage + "|startup";
        if (!delayedPending.add(key)) return;
        pendingAction=true;
        long token = generation;
        handler.postDelayed(() -> {
            delayedPending.remove(key);
            pendingAction=!delayedPending.isEmpty();
            if (token != generation || !currentPackage.equals(packageName) || startupDone
                    || !mayAct(currentPackage) || !PreferenceStore.isTypeEnabled(service,AdType.STARTUP)) return;
            AccessibilityNodeInfo root = RootAccess.read(service);
            try {
                if (!ownsPackage(root, currentPackage)) return;
                try (AccessibilityNodeIndex index = AccessibilityNodeIndex.build(root)) {
                    AccessibilityNodeInfo target = index.findKeyword(keywords);
                    if(SystemClock.uptimeMillis()-enteredAt<=ENTRY_WINDOW_MS && safeStartupTarget(target,index))
                        performNodeClick(target,clickWay==1,index,"ltt:startup",() -> startupDone=true);
                }
            } catch(RuntimeException unavailable) { } finally { if(root!=null)root.recycle(); }
        }, delayMs);
    }

    private void schedulePopupDelay(
            String currentPackage,
            String key,
            LiTiaotiaoRule rule,
            boolean forceGesture,
            int delayMs
    ) {
        if (!delayedPending.add(key)) return;
        pendingAction=true;
        long token = generation;
        handler.postDelayed(() -> {
            delayedPending.remove(key);
            pendingAction=!delayedPending.isEmpty();
            if (token != generation || !currentPackage.equals(packageName) || !mayAct(currentPackage)
                    || !PreferenceStore.isTypeEnabled(service,AdType.POPUP)) return;
            AccessibilityNodeInfo root = RootAccess.read(service);
            try {
                if (!ownsPackage(root, currentPackage)) return;
                try (AccessibilityNodeIndex index = AccessibilityNodeIndex.build(root)) {
                    if (!index.expressionExists(rule.id)) return;
                    performPopupAction(rule,forceGesture,index,key,
                            () -> successCounts.put(key,successCounts.getOrDefault(key,0)+1));
                }
            } catch(RuntimeException unavailable) { } finally { if(root!=null)root.recycle(); }
        }, delayMs);
    }

    private void scheduleSearchBurst(String currentPackage, int extraSearches) {
        if (extraSearches <= 0 || searchBurstPending) return;
        searchBurstPending = true;
        long token = generation;
        for (int i = 1; i <= extraSearches; i++) {
            final boolean last = i == extraSearches;
            handler.postDelayed(() -> {
                if (last) searchBurstPending = false;
                if (token != generation || !currentPackage.equals(packageName) || !mayAct(currentPackage)
                    || !PreferenceStore.isTypeEnabled(service,AdType.POPUP)) return;
                AccessibilityNodeInfo root = RootAccess.read(service);
                try {
                    if (!ownsPackage(root, currentPackage)) return;
                    try (AccessibilityNodeIndex index = AccessibilityNodeIndex.build(root)) {
                        LiTiaotiaoRuleBundle bundle = repository.forPackage(currentPackage);
                        if (bundle != null && bundle.lttService) {
                            handleInternal(currentPackage, root, index, bundle, false);
                        }
                    }
                } catch(RuntimeException unavailable) { } finally { if(root!=null)root.recycle(); }
            }, SEARCH_INTERVAL_MS * i);
        }
    }

    private boolean performNodeClick(AccessibilityNodeInfo target,boolean forceGesture,
            AccessibilityNodeIndex index,String key,Runnable completion) {
        if(!mayAct(packageName) || SafeActionTarget.forbiddenScreen(index))return false;
        AccessibilityNodeInfo click=SafeActionTarget.clickTarget(target,index);
        if(click==null)return false;
        ActionVerifier.Ticket ticket=verifier.capture(packageName,key,"click",target,index,null);
        AdType type="ltt:startup".equals(key)?AdType.STARTUP:AdType.POPUP;
        if(ticket==null || !verifier.isCurrent(ticket) || !mayAct(packageName)
                || !PreferenceStore.isTypeEnabled(service,type))return false;
        if(!forceGesture) {
            if(click.isClickable() && click.isEnabled() && click.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                complete(ticket,completion);return true;
            }
        }
        Rect rect=new Rect();target.getBoundsInScreen(rect);
        Rect rootBounds=index.rootBounds();
        if(rect.width()<=0 || rect.height()<=0 || !Rect.intersects(rootBounds,rect))return false;
        return dispatchTap(rect.exactCenterX(),rect.exactCenterY(),ticket,completion,type);
    }
    private boolean dispatchTap(float x,float y,ActionVerifier.Ticket ticket,Runnable completion,AdType type) {
        if(!verifier.isCurrent(ticket) || !mayAct(packageName) || !PreferenceStore.isTypeEnabled(service,type))return false;
        if(x<0 || y<0 || x>=service.getResources().getDisplayMetrics().widthPixels
                || y>=service.getResources().getDisplayMetrics().heightPixels)return false;
        Path path=new Path();path.moveTo(x,y);
        GestureDescription gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0L,45L)).build();
        long epoch=generation;long token=++gestureGeneration;String pkg=packageName;gesturePending=true;markAction();
        boolean accepted;
        try {
            accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription value) {
                    if(epoch!=generation || token!=gestureGeneration)return;
                    gesturePending=false;
                    if(pkg.equals(foreground.get()) && PreferenceStore.isMasterEnabled(service)
                            && PreferenceStore.isLiTiaotiaoEnabled(service)
                            && PreferenceStore.isTypeEnabled(service,type))complete(ticket,completion);
                }
                @Override public void onCancelled(GestureDescription value) {
                    if(epoch!=generation || token!=gestureGeneration)return;gesturePending=false;verifier.rejected(ticket);
                }
            },handler);
        } catch(RuntimeException error) {gesturePending=false;verifier.rejected(ticket);return false;}
        if(!accepted) {gesturePending=false;verifier.rejected(ticket);}
        else handler.postDelayed(() -> {
            if(epoch==generation && token==gestureGeneration && gesturePending) {gesturePending=false;gestureGeneration++;verifier.rejected(ticket);}
        },1500L);
        return accepted;
    }
    private void complete(ActionVerifier.Ticket ticket,Runnable completion) {
        completion.run();markAction();verifier.accepted(ticket);
    }

    private void markAction() {
        lastAutomatedActionUptime = SystemClock.uptimeMillis();
    }

    private boolean safeStartupTarget(AccessibilityNodeInfo target,AccessibilityNodeIndex index) {
        if(target==null || !target.isVisibleToUser() || !target.isEnabled())return false;
        String text=NodeTarget.string(target.getText()),desc=NodeTarget.string(target.getContentDescription());
        if(!safeSkipLabel(text) && !safeSkipLabel(desc)) {
            String id=NodeTarget.string(target.getViewIdResourceName()).toLowerCase(java.util.Locale.ROOT);
            if(!id.contains("skip") || !(id.contains("ad_") || id.contains("splash") || id.contains("tt_")))return false;
        }
        AccessibilityNodeInfo action=SafeActionTarget.clickTarget(target,index);
        if(action==null)return false;
        for(AccessibilityNodeInfo other:index.nodes()) {
            if(other.equals(target) || !other.isVisibleToUser() || !other.isEnabled())continue;
            if(!safeSkipLabel(NodeTarget.string(other.getText())) && !safeSkipLabel(NodeTarget.string(other.getContentDescription())))continue;
            AccessibilityNodeInfo otherAction=SafeActionTarget.clickTarget(other,index);
            if(otherAction!=null && !otherAction.equals(action))return false;
        }
        return !index.isTraversalIncomplete();
    }
    private static boolean safeSkipLabel(String value) {
        String label=AdCloseText.normalize(value);
        return label.equals("跳过") || label.equals("跳過") || label.equals("跳过广告")
                || label.equals("跳過廣告") || label.equals("skip") || label.equals("skip ad");
    }

    private static boolean equivalent(String left, String right) {
        if (left == null || right == null) return false;
        return stripOperator(left).equals(stripOperator(right));
    }

    private static String stripOperator(String value) {
        String result = value.trim();
        if (result.startsWith("|")) result = result.substring(1).trim();
        if (result.startsWith("=") || result.startsWith("+") || result.startsWith("-")) result = result.substring(1);
        return result.trim();
    }

    private static boolean ownsPackage(AccessibilityNodeInfo root, String expected) {
        return root != null && root.getPackageName() != null && expected.equals(root.getPackageName().toString());
    }
}
