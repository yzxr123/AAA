package org.adguardian.app.engine;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.Supplier;
import org.adguardian.app.gkd.GkdSubscriptionEngine;
import org.adguardian.app.litiaotiao.LiTiaotiaoRuleEngine;
import org.adguardian.app.settings.PreferenceStore;

public final class RuleEngine {
    private final AccessibilityService service;
    private final LiTiaotiaoRuleEngine liTiaotiao;
    private final GkdAssistEngine assist;
    private final GkdSubscriptionEngine subscription;
    private final ActionVerifier verifier;
    private final org.adguardian.app.sdk.SdkAdEngine sdk;
    private volatile long assistAction;
    private volatile boolean externalAction;
    private volatile long externalActionTime;
    private long externalGeneration;
    private String currentPackage="";

    public RuleEngine(AccessibilityService service, Handler worker,
                      Runnable requestScan,Supplier<String> foreground) {
        this.service=service;
        verifier=new ActionVerifier(service,worker,foreground,requestScan);
        sdk=new org.adguardian.app.sdk.SdkAdEngine(service,worker,foreground,verifier);
        liTiaotiao=new LiTiaotiaoRuleEngine(service,worker,foreground,verifier);
        assist=new GkdAssistEngine(service,worker,verifier);
        subscription=new GkdSubscriptionEngine(service,worker,requestScan,foreground,verifier);
        liTiaotiao.setExternalActionPending(() -> externalAction || sdk.isPending()
                || assist.isGesturePending() || subscription.isGesturePending() || verifier.isPending());
    }
    public void onPackageEntered(String packageName) {
        if(packageName.equals(currentPackage))return;
        cancelExternalAction();
        currentPackage=packageName;
        sdk.cancel();verifier.onPackageChanged();assist.cancelPending();
        liTiaotiao.onPackageEntered(packageName);
        subscription.onPackageEntered(packageName);
        assist.onPackageEntered(packageName);
    }
    public int liTiaotiaoPackageCount() { return liTiaotiao.packageCount(); }
    public int liTiaotiaoRuleCount() { return liTiaotiao.ruleCount(); }
    public long lastAutomatedActionUptime() {
        return Math.max(Math.max(Math.max(Math.max(assistAction,sdk.lastAction()),externalActionTime),verifier.lastAction()),
                Math.max(liTiaotiao.lastAutomatedActionUptime(),subscription.lastAutomatedActionUptime()));
    }
    public boolean beginExternalAction() {
        if(externalAction || isWaitingForRules())return false;
        long last=lastAutomatedActionUptime();
        if(last>0 && SystemClock.uptimeMillis()-last<900L)return false;
        externalAction=true;
        externalGeneration++;
        externalActionTime=SystemClock.uptimeMillis();
        return true;
    }
    public long externalActionToken() { return externalGeneration; }
    public void endExternalAction() { cancelExternalAction(); }
    private void cancelExternalAction() { externalAction=false;externalGeneration++; }
    public void endExternalAction(long token) { if(token==externalGeneration)externalAction=false; }
    public ActionVerifier verifier() { return verifier; }
    public void onUserInteraction() { sdk.cancel();cancelExternalAction();verifier.cancel();liTiaotiao.cancelPending();subscription.cancelPendingActions();assist.cancelPending(); }
    public boolean isWaitingForRules() { return sdk.isPending() || verifier.isPending()
            || assist.isGesturePending() || liTiaotiao.hasPendingAction() || subscription.isWaiting(); }
    public long nextWakeUp() {
        return subscription.nextWakeUp();
    }
    public void onSettingsChanged() { sdk.cancel();cancelExternalAction();verifier.cancel();assist.cancelPending();liTiaotiao.cancelPending();subscription.reset(); }
    public void close() { sdk.cancel();cancelExternalAction();verifier.cancel();assist.cancelPending();liTiaotiao.cancelPending();subscription.close(); }

    public boolean handle(String packageName,String activityName,int eventType,AccessibilityNodeInfo root) {
        if(root==null || packageName==null || packageName.isEmpty()
                || !PreferenceStore.isMasterEnabled(service))return false;
        onPackageEntered(packageName);
        if(externalAction || sdk.isPending() || verifier.isPending() || assist.isGesturePending())return true;
        if(subscription.isWaiting() && subscription.nextWakeUp()<0)return true;
        long last=lastAutomatedActionUptime();
        if(last>0 && SystemClock.uptimeMillis()-last<100L)return true;
        try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(root)) {
            if(PreferenceStore.isLiTiaotiaoEnabled(service)
                    && liTiaotiao.handle(packageName,root,index))return true;
            if(liTiaotiao.hasPendingAction())return true;
            if(PreferenceStore.isGkdEnabled(service) && subscription.handle(packageName,activityName,index))return true;
            if(sdk.handle(packageName,activityName,index))return true;
            if(!PreferenceStore.isGkdEnabled(service))return false;
            boolean handled=assist.handle(packageName,activityName,eventType,index);
            assistAction=assist.lastAutomatedActionUptime();
            return handled;
        }
    }
}
