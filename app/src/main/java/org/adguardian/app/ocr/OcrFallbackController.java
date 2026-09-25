package org.adguardian.app.ocr;

import android.accessibilityservice.AccessibilityService;
import org.adguardian.app.runtime.RootAccess;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.Supplier;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.engine.AdSdkSignatures;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.RuleEngine;
import org.adguardian.app.engine.ActionVerifier;
import org.adguardian.app.log.UserEventLog;
import org.adguardian.app.settings.PreferenceStore;

public final class OcrFallbackController {
    public interface JumpEvidenceListener {
        void onEvidence(String sourcePackage,boolean jumpEvidence,boolean shakeEvidence);
    }
    private static final long[] ENTRY_OCR_DELAYS_MS={250L,900L,2200L};
    private static final long[] POPUP_OCR_DELAYS_MS={180L,650L,1500L};
    private static final long OCR_ENGINE_IDLE_RELEASE_MS=15000L;
    private static final long OCR_UI_IDLE_REQUIRED_MS=180L;
    private final AccessibilityService service;
    private final RuleEngine ruleEngine;
    private final Handler ruleWorker;
    private final Supplier<String> foreground;
    private final JumpEvidenceListener jumpEvidenceListener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable probeTask=() -> probe(this.generation);
    private final Runnable releaseTask=this::releaseIfIdle;
    private final Runnable deadlineTask=this::endSession;
    private OcrAdDetector detector;
    private volatile String packageName="";
    private volatile String activityName="";
    private volatile long generation;
    private volatile boolean active;
    private boolean capturePending;
    private boolean stopped;
    private int probeIndex;
    private int screenshotFailures;
    private long[] delays=ENTRY_OCR_DELAYS_MS;
    private long startedAt;
    private volatile long deadline;
    private volatile long lastAutomatedAction;
    private long lastUiEvent;
    private long lastOcrUse;

    public OcrFallbackController(AccessibilityService service,RuleEngine engine,
                                 Handler worker,Supplier<String> foreground) {
        this(service,engine,worker,foreground,(source,jump,shake)->{});
    }
    public OcrFallbackController(AccessibilityService service,RuleEngine engine,
                                 Handler worker,Supplier<String> foreground,
                                 JumpEvidenceListener jumpEvidenceListener) {
        this.service=service;ruleEngine=engine;ruleWorker=worker;this.foreground=foreground;
        this.jumpEvidenceListener=jumpEvidenceListener==null?(source,jump,shake)->{}:jumpEvidenceListener;
    }
    public void beginSession(String pkg,String activity) { start(pkg,activity,ENTRY_OCR_DELAYS_MS); }
    public void beginPopupProbe(String pkg,String activity) {
        if(pkg==null || pkg.isEmpty())return;
        if(!pkg.equals(packageName) || !active)start(pkg,activity,POPUP_OCR_DELAYS_MS);
        else updateActivity(activity);
    }
    public void updateActivity(String activity) { activityName=activity==null?"":activity; }
    public void noteUiEvent() { lastUiEvent=SystemClock.uptimeMillis(); }
    public void noteUserInteraction() { endSession(); }
    public boolean isLikelyAutomatedClick(long now) {
        long last=Math.max(lastAutomatedAction,ruleEngine.lastAutomatedActionUptime());
        return last>0 && now-last<=650L;
    }
    public void endSession() {
        active=false;generation++;capturePending=false;
        handler.removeCallbacks(probeTask);
        handler.removeCallbacks(deadlineTask);
        if(detector!=null)detector.cancel();
        handler.removeCallbacks(releaseTask);
        handler.postDelayed(releaseTask,Math.max(1L,lastOcrUse+OCR_ENGINE_IDLE_RELEASE_MS-SystemClock.uptimeMillis()));
    }
    public void stop() {
        stopped=true;active=false;generation++;
        handler.removeCallbacksAndMessages(null);
        releaseDetector();
    }
    public void onSettingsChanged() {
        if(!enabled()) {
            active=false;generation++;capturePending=false;
            handler.removeCallbacks(probeTask);
            handler.removeCallbacks(deadlineTask);
            handler.removeCallbacks(releaseTask);
            releaseDetector();
        }
    }
    private void start(String pkg,String activity,long[] schedule) {
        if(stopped || pkg==null || pkg.isEmpty() || !enabled())return;
        if(detector!=null)detector.cancel();
        handler.removeCallbacks(probeTask);
        handler.removeCallbacks(releaseTask);
        handler.removeCallbacks(deadlineTask);
        capturePending=false;
        packageName=pkg;updateActivity(activity);generation++;active=true;
        probeIndex=0;screenshotFailures=0;delays=schedule;
        startedAt=SystemClock.uptimeMillis();
        deadline=startedAt+(schedule==ENTRY_OCR_DELAYS_MS?12000L:6500L);
        lastUiEvent=startedAt;
        handler.postAtTime(deadlineTask,deadline);
        scheduleNext();
    }
    private void scheduleNext() {
        handler.removeCallbacks(probeTask);
        if(!active)return;
        if(probeIndex>=delays.length || SystemClock.uptimeMillis()>=deadline) { endSession();return; }
        handler.postAtTime(probeTask,Math.max(SystemClock.uptimeMillis()+1,startedAt+delays[probeIndex]));
    }
    private void probe(long token) {
        if(!enabled()) { onSettingsChanged();return; }
        if(!isActive(token)) { endSession();return; }
        if(ruleEngine.isWaitingForRules()) { endSession();return; }
        long now=SystemClock.uptimeMillis();
        long last=ruleEngine.lastAutomatedActionUptime();
        if(last>0 && now-last<=900L) { endSession();return; }
        long idle=now-lastUiEvent;
        if(idle<OCR_UI_IDLE_REQUIRED_MS || capturePending || (detector!=null && detector.isBusy())) {
            handler.postDelayed(probeTask,Math.max(100L,OCR_UI_IDLE_REQUIRED_MS-idle));
            return;
        }
        capturePending=true;
        probeIndex++;
        try {
        service.takeScreenshot(Display.DEFAULT_DISPLAY,service.getMainExecutor(),new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                if(token==generation)capturePending=false;
                if(!isActive(token) || !enabled() || ruleEngine.isWaitingForRules()) {
                    screenshot.getHardwareBuffer().close();return;
                }
                if(detector==null)detector=new OcrAdDetector(service.getApplicationContext());
                lastOcrUse=SystemClock.uptimeMillis();
                boolean accepted=detector.analyzeScreenshot(screenshot,new OcrAdDetector.Callback() {
                    @Override public void onResult(OcrAdDetector.Result result) {
                        if(!isActive(token))return;
                        if(result!=null && result.strongEvidence()) {
                            if(PreferenceStore.isTypeEnabled(service,AdType.JUMP)
                                    && (result.jumpEvidence || (result.shakeEvidence
                                    && PreferenceStore.isTypeEnabled(service,AdType.SHAKE))))
                                jumpEvidenceListener.onEvidence(packageName,result.jumpEvidence,result.shakeEvidence);
                            ruleWorker.post(() -> {
                                boolean acted=applyResult(token,result);
                                // The shared action verifier records completed input, including its app.
                                if(!acted)handler.post(() -> { if(isActive(token))scheduleNext(); });
                            });
                        } else scheduleNext();
                    }
                    @Override public void onError(Exception error) {
                        if(isActive(token))scheduleNext();
                    }
                });
                if(!accepted) {
                    screenshot.getHardwareBuffer().close();
                    scheduleNext();
                }
            }
            @Override public void onFailure(int errorCode) {
                if(token==generation)capturePending=false;
                if(!isActive(token))return;
                screenshotFailures++;
                if(screenshotFailures>=3)UserEventLog.error(service,"屏幕识别暂时不可用");
                scheduleNext();
            }
        });
        } catch(RuntimeException error) {
            capturePending=false;
            screenshotFailures++;
            if(screenshotFailures>=3)UserEventLog.error(service,"屏幕识别暂时不可用");
            if(isActive(token))scheduleNext();
        }
    }
    private boolean applyResult(long token,OcrAdDetector.Result result) {
        if(!isActive(token) || !enabled() || !packageName.equals(foreground.get())
                || ruleEngine.isWaitingForRules() || result.onboardingEvidence)return false;
        AccessibilityNodeInfo root=RootAccess.readFor(service,packageName);
        try {
            if(root==null || root.getPackageName()==null || !packageName.contentEquals(root.getPackageName()))return false;
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(root)) {
                index.startQueryBudget(100L);
                if(result.targetFound && result.targetBounds!=null && evidenceEnabled(result)) {
                    Rect bounds=result.targetBounds;
                    AccessibilityNodeInfo target=null;
                    for(AccessibilityNodeInfo node:index.nodes()) {
                        Rect nodeBounds=new Rect();node.getBoundsInScreen(nodeBounds);
                        if(node.isVisibleToUser() && nodeBounds.contains((int)bounds.exactCenterX(),(int)bounds.exactCenterY())
                                && nodeBounds.width()<=index.rootBounds().width()*0.55f
                                && nodeBounds.height()<=index.rootBounds().height()*0.22f)target=node;
                    }
                    ActionVerifier.Ticket ticket=ruleEngine.verifier().capture(packageName,"ocr:target","click",target,index,null);
                    return ticket!=null && tap(token,bounds.exactCenterX(),bounds.exactCenterY(),ticket);
                }
                boolean sdk=AdSdkSignatures.isAdSdkActivity(activityName) || index.hasAdSdkMarker();
                boolean strong=result.adEvidence || result.jumpEvidence || result.shakeEvidence || (sdk && result.commercialEvidence);
                if(!strong)return false;
                AccessibilityNodeInfo close=index.findLikelyCloseCandidate();
                if(close!=null && evidenceEnabled(result)) {
                    Rect bounds=new Rect();close.getBoundsInScreen(bounds);
                    if(bounds.width()>0 && bounds.height()>0) {
                        ActionVerifier.Ticket ticket=ruleEngine.verifier().capture(packageName,"ocr:close","click",close,index,null);
                        return ticket!=null && tap(token,bounds.exactCenterX(),bounds.exactCenterY(),ticket);
                    }
                }
            }
        } catch(RuntimeException limit) {
            // No action is taken when the complete evidence could not be checked.
        } finally { if(root!=null)root.recycle(); }
        return false;
    }
    private boolean tap(long token,float x,float y,ActionVerifier.Ticket ticket) {
        if(x<0 || y<0 || x>=service.getResources().getDisplayMetrics().widthPixels
                || y>=service.getResources().getDisplayMetrics().heightPixels)return false;
        if(!isActive(token) || !enabled() || !ruleEngine.beginExternalAction())return false;
        long actionToken=ruleEngine.externalActionToken();
        Path path=new Path();path.moveTo(x,y);
        GestureDescription gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,48L)).build();
        boolean[] finished={false};
        boolean accepted;
        try {
        accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription value) {
                if(finished[0])return;finished[0]=true;
                ruleEngine.endExternalAction(actionToken);
                if(isActive(token) && enabled() && packageName.equals(foreground.get())) {
                    ruleEngine.verifier().accepted(ticket);finish(token);
                }
            }
            @Override public void onCancelled(GestureDescription value) {
                if(finished[0])return;finished[0]=true;
                ruleEngine.endExternalAction(actionToken);
                ruleEngine.verifier().rejected(ticket);
                handler.post(() -> { if(isActive(token))scheduleNext(); });
            }
        },ruleWorker);
        } catch(RuntimeException error) {
            ruleEngine.endExternalAction(actionToken);ruleEngine.verifier().rejected(ticket);
            return false;
        }
        if(!accepted) {ruleEngine.endExternalAction(actionToken);ruleEngine.verifier().rejected(ticket);}
        else ruleWorker.postDelayed(() -> {
            if(finished[0])return;finished[0]=true;
            ruleEngine.verifier().rejected(ticket);
            ruleEngine.endExternalAction(actionToken);
            handler.post(() -> { if(isActive(token))scheduleNext(); });
        },1500L);
        return accepted;
    }
    private void finish(long token) {
        lastAutomatedAction=SystemClock.uptimeMillis();
        handler.post(() -> { if(token==generation)endSession(); });
    }
    private boolean evidenceEnabled(OcrAdDetector.Result result) {
        boolean ordinary=PreferenceStore.isTypeEnabled(service,AdType.STARTUP)
                ||PreferenceStore.isTypeEnabled(service,AdType.POPUP)
                ||PreferenceStore.isTypeEnabled(service,AdType.FLOATING)
                ||PreferenceStore.isTypeEnabled(service,AdType.CARD)
                ||PreferenceStore.isTypeEnabled(service,AdType.FEED)
                ||PreferenceStore.isTypeEnabled(service,AdType.SCROLL);
        return OcrActionPolicy.shouldClose(result.adEvidence,result.commercialEvidence,
                result.jumpEvidence,result.shakeEvidence,ordinary,
                PreferenceStore.isTypeEnabled(service,AdType.JUMP),
                PreferenceStore.isTypeEnabled(service,AdType.SHAKE));
    }
    private boolean enabled() { return PreferenceStore.isMasterEnabled(service) && PreferenceStore.isOcrEnabled(service)
            && !PreferenceStore.isTaskRemoved(service); }
    private boolean isActive(long token) { return active && generation==token && SystemClock.uptimeMillis()<deadline; }
    private void releaseIfIdle() {
        if(active)return;
        if(capturePending || (detector!=null && detector.isBusy()))handler.postDelayed(releaseTask,1000L);
        else releaseDetector();
    }
    private void releaseDetector() {
        OcrAdDetector current=detector;detector=null;
        if(current!=null)current.close();
    }
}
