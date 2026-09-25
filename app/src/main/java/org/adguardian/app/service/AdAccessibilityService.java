package org.adguardian.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import org.adguardian.app.runtime.AccessibilityState;
import org.adguardian.app.runtime.RootAccess;
import org.adguardian.app.runtime.RuntimeHealth;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.PackagePolicy;
import org.adguardian.app.engine.JumpGuard;
import org.adguardian.app.engine.RuleEngine;
import org.adguardian.app.log.UserEventLog;
import org.adguardian.app.ocr.OcrFallbackController;
import org.adguardian.app.settings.PreferenceStore;

public final class AdAccessibilityService extends AccessibilityService {
    private static final long HOT_CONTENT_DEBOUNCE_MS=55L;
    private static final long IDLE_CONTENT_DEBOUNCE_MS=140L;
    private static volatile String lastObservedPackage="";
    private static volatile String lastObservedActivity="";
    public static String[] lastObservedPage() {return new String[]{lastObservedPackage,lastObservedActivity};}
    private static volatile WeakReference<AdAccessibilityService> activeService=new WeakReference<>(null);
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final Handler initializationResults=new Handler(Looper.getMainLooper());
    private boolean initializing;
    private int healthInitAttempts;
    private long healthInitNextAt;
    private final Object scanLock=new Object();
    private final org.adguardian.app.runtime.ActivityTracker activityTracker=new org.adguardian.app.runtime.ActivityTracker();
    private volatile int missingRootAttempts;
    private final LinkedHashMap<String,Long> activityRetryAt=new LinkedHashMap<>(16,0.75f,true);
    private final LinkedHashMap<String,Boolean> activityCache=new LinkedHashMap<>(32,0.75f,true);
    private final Runnable retryScan=this::requestCurrentScan;
    private volatile RuleEngine ruleEngine;
    private PackagePolicy packagePolicy;
    private OcrFallbackController ocrController;
    private JumpGuard jumpGuard;
    private boolean connected;
    private volatile long connectionGeneration;
    private HandlerThread scanThread;
    private Handler scanHandler;
    private ScanRequest pendingScan;
    private boolean drainScheduled;
    private volatile boolean destroyed;
    private volatile String foregroundPackage="";
    private volatile String eventClass="";
    private volatile int foregroundWindow=-1;
    private String workerPackage="";
    private volatile String activityName="";
    private long lastContentRequest;
    private long enteredAt;
    private long retryAt=Long.MAX_VALUE;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if(connected)return;
        connected=true;destroyed=false;
        AccessibilityState.connected(this);
        RuntimeHealth.event(this,"系统已连接辅助功能服务");
        activeService=new WeakReference<>(this);
        ProtectionService.sync(this);
        initializeRuntime();
    }
    private void initializeRuntime() {
        if(destroyed || !connected || initializing)return;
        initializing=true;
        final long token=++connectionGeneration;
        AccessibilityState.preparing(this);
        RuntimeHealth.event(this,"开始初始化规则引擎");
        try {
            AccessibilityServiceInfo info=getServiceInfo();
            if(info==null)info=new AccessibilityServiceInfo();
            info.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    |AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED|AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    |AccessibilityEvent.TYPE_VIEW_SCROLLED|AccessibilityEvent.TYPE_VIEW_CLICKED;
            info.feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout=60L;
            info.flags=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    |AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    |AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.packageNames=null;setServiceInfo(info);
            scanThread=new HandlerThread("AdRuleScan",Process.THREAD_PRIORITY_BACKGROUND);
            scanThread.start();scanHandler=new Handler(scanThread.getLooper());
            Handler worker=scanHandler;
            worker.post(() -> {
                try {
                    PackagePolicy policy=new PackagePolicy(this);
                    RuleEngine engine=new RuleEngine(this,worker,this::requestCurrentScan,() -> foregroundPackage);
                    // This completion is not removed by UI/task teardown. A stale result must close its engine.
                    initializationResults.post(() -> {
                        if(destroyed || token!=connectionGeneration || !connected) {engine.close();return;}
                        try {
                            ruleEngine=engine;packagePolicy=policy;
                            JumpGuard guard=new JumpGuard(getPackageName());jumpGuard=guard;
                            ocrController=new OcrFallbackController(this,engine,worker,() -> foregroundPackage,
                                    guard::arm);
                            initializing=false;healthInitAttempts=0;healthInitNextAt=0;AccessibilityState.initialized(this,true);
                            RuntimeHealth.event(this,"规则引擎已就绪");
                            ProtectionService.sync(this);worker.post(this::requestCurrentScan);
                        } catch(RuntimeException error) {initializationFailed(token,error);}
                    });
                } catch(RuntimeException error) {initializationResults.post(() -> initializationFailed(token,error));}
            });
        } catch(RuntimeException error) {initializationFailed(token,error);}
    }
    private void initializationFailed(long token,RuntimeException error) {
        if(destroyed || token!=connectionGeneration || !connected)return;
        initializing=false;AccessibilityState.initialized(this,false);
        RuntimeHealth.failure(this,"规则初始化失败",error);
        UserEventLog.error(this,"广告保护暂不可用 请重新打开应用");
    }

    public static void requestHealthInitialization() {
        AdAccessibilityService current=activeService.get();
        if(current==null||current.destroyed)return;
        current.mainHandler.post(()->{
            AccessibilityState.Snapshot state=AccessibilityState.read(current);
            long now=SystemClock.uptimeMillis();
            if(current!=activeService.get()||current.destroyed||!current.connected||current.initializing||!state.authorized||!state.enabled
                    ||!state.failed||current.healthInitAttempts>=3||now<current.healthInitNextAt)return;
            long delay=30000L<<current.healthInitAttempts;
            current.healthInitAttempts++;current.healthInitNextAt=now+delay;
            RuntimeHealth.event(current,"健康检查重试规则初始化 第 "+current.healthInitAttempts+" 次 未修改辅助功能授权");
            current.disposeRuntime();current.initializeRuntime();
        });
    }
    public static void retryInitialization() {
        AdAccessibilityService current=activeService.get();
        if(current==null || current.destroyed)return;
        current.mainHandler.post(() -> {
            AccessibilityState.Snapshot state=AccessibilityState.read(current);
            if(!state.failed)return;
            current.disposeRuntime();
            current.initializeRuntime();
        });
    }

    public static void notifySettingsChanged() {
        AdAccessibilityService current=activeService.get();
        if(current==null) {AccessibilityState.changed();return;}
        current.mainHandler.post(() -> {
            if(current.destroyed)return;
            if(current.ocrController!=null)current.ocrController.onSettingsChanged();
            if(current.jumpGuard!=null && (!PreferenceStore.isMasterEnabled(current)
                    || !PreferenceStore.isTypeEnabled(current,AdType.JUMP)))current.jumpGuard.clear();
            ProtectionService.sync(current);
            AccessibilityState.changed();
            Handler worker=current.scanHandler;
            if(worker!=null)worker.post(() -> {
                if(current.ruleEngine!=null)current.ruleEngine.onSettingsChanged();
                current.cancelRetry();
                current.requestCurrentScan();
            });
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        try {handleAccessibilityEvent(event);}
        catch(RuntimeException error) {RuntimeHealth.failure(this,"辅助事件处理失败",error);}
    }
    private void handleAccessibilityEvent(AccessibilityEvent event) {
        if(destroyed || event==null)return;
        // Startup events must not vanish while the worker is still loading the engines.
        if(ruleEngine==null || packagePolicy==null || ocrController==null) {
            if(connected && PreferenceStore.isMasterEnabled(this) && event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && event.getPackageName()!=null && !getPackageName().contentEquals(event.getPackageName())) {
                foregroundPackage=event.getPackageName().toString();
                eventClass=event.getClassName()==null?"":event.getClassName().toString();
                foregroundWindow=event.getWindowId();enteredAt=SystemClock.uptimeMillis();
                activityTracker.note(foregroundPackage,eventClass);
            }
            return;
        }
        if(!PreferenceStore.isMasterEnabled(this) || PreferenceStore.isTaskRemoved(this))return;
        long now=SystemClock.uptimeMillis();
        int type=event.getEventType();
        if(type==AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if(!ocrController.isLikelyAutomatedClick(now))jumpGuard.onUserInteraction();
            if(event.getPackageName()!=null && getPackageName().contentEquals(event.getPackageName()))return;
            if(!ocrController.isLikelyAutomatedClick(now)) {
                ocrController.noteUserInteraction();
                scanHandler.post(() -> {if(ruleEngine!=null)ruleEngine.onUserInteraction();});
            }
            return;
        }
        boolean state=type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        boolean windows=type==AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        boolean content=type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        boolean scroll=type==AccessibilityEvent.TYPE_VIEW_SCROLLED;
        if(!state && !windows && !content && !scroll)return;
        ocrController.noteUiEvent();
        String pkg=event.getPackageName()==null?foregroundPackage:event.getPackageName().toString();
        if(pkg.isEmpty())return;
        if(!state && !pkg.equals(foregroundPackage)) {
            requestReconcile();return;
        }
        if(getPackageName().equals(pkg)) {
            if(state && "org.adguardian.app.MainActivity".contentEquals(event.getClassName()==null?"":event.getClassName())) {
                foregroundPackage=pkg;eventClass="";activityTracker.enter(pkg);ocrController.endSession();
                enqueue(new ScanRequest(pkg,"",type,false));
            }
            return;
        }
        boolean changed=!pkg.equals(foregroundPackage);
        boolean windowChanged=state || (windows && foregroundWindow!=event.getWindowId());
        if(changed && rollbackJump(foregroundPackage,pkg))return;
        if(changed) {
            foregroundPackage=pkg;
            activityTracker.enter(pkg);missingRootAttempts=0;
            eventClass="";
            enteredAt=now;
            lastContentRequest=0;
            ocrController.endSession();
            if(packagePolicy.allow(pkg))ocrController.beginSession(pkg,"");
        }
        if(state && event.getClassName()!=null){eventClass=event.getClassName().toString();activityTracker.note(pkg,eventClass);missingRootAttempts=0;}
        foregroundWindow=event.getWindowId();
        if(!packagePolicy.allow(pkg)) {
            if(changed)enqueue(new ScanRequest(pkg,"",type,false));
            return;
        }
        if(content && !changed) {
            long lastAction=ruleEngine.lastAutomatedActionUptime();
            boolean hot=now-enteredAt<5000L || (lastAction>0 && now-lastAction<3000L);
            long debounce=hot?HOT_CONTENT_DEBOUNCE_MS:IDLE_CONTENT_DEBOUNCE_MS;
            if(now-lastContentRequest<debounce)return;
            lastContentRequest=now;
        }
        enqueue(new ScanRequest(pkg,eventClass,type,windowChanged && !changed));
    }

    private void enqueue(ScanRequest request) {
        Handler worker=scanHandler;
        if(worker==null || destroyed)return;
        synchronized(scanLock) {
            if(pendingScan!=null && pendingScan.packageName.equals(request.packageName)) {
                request=new ScanRequest(request.packageName,request.className,request.type,
                        request.popupProbe || pendingScan.popupProbe);
            }
            pendingScan=request;
            if(drainScheduled)return;
            drainScheduled=true;
        }
        worker.post(this::drainOne);
    }

    private void drainOne() {
        ScanRequest request;
        synchronized(scanLock) { request=pendingScan;pendingScan=null; }
        try {
            if(request!=null && !destroyed && ruleEngine!=null && runtimeEnabled())scan(request);
        } catch(RuntimeException error) {
            RuntimeHealth.failure(this,"广告规则扫描失败",error);
            UserEventLog.error(this,"广告保护暂时遇到问题");
        } finally {
            synchronized(scanLock) {
                if(pendingScan==null || destroyed) { drainScheduled=false;return; }
            }
            Handler worker=scanHandler;
            if(worker!=null)worker.post(this::drainOne);
        }
    }

    private void scan(ScanRequest request) {
        if(!runtimeEnabled())return;
        if(!request.packageName.equals(foregroundPackage))return;
        if(!request.packageName.equals(workerPackage)) {
            cancelRetry();
            workerPackage=request.packageName;
            activityName="";
            ruleEngine.onPackageEntered(workerPackage);
        }
        if(packagePolicy==null || !packagePolicy.allow(workerPackage))return;
        activityName=activityTracker.resolve(workerPackage,name -> isActivity(workerPackage,name));
        AccessibilityNodeInfo root=RootAccess.readFor(this,workerPackage);
        try {
            if(root==null || root.getPackageName()==null || !workerPackage.contentEquals(root.getPackageName())) {
                if(++missingRootAttempts<=3)scheduleRetry(missingRootAttempts==1?120:missingRootAttempts==2?300:700);
                return;
            }
            missingRootAttempts=0;
            String app=workerPackage;
            String activity=activityName;
            lastObservedPackage=app;lastObservedActivity=activity;
            boolean handled=ruleEngine.handle(app,activity,request.type,root);
            scheduleRetry(ruleEngine.nextWakeUp());
            mainHandler.post(() -> {
                if(destroyed || !app.equals(foregroundPackage) || ocrController==null)return;
                ocrController.updateActivity(activity);
                if(handled)ocrController.endSession();
                else if(request.popupProbe)ocrController.beginPopupProbe(app,activity);
            });
        } finally { if(root!=null)root.recycle(); }
    }

    private void requestReconcile() {
        Handler worker=scanHandler;
        if(worker==null || destroyed || !runtimeEnabled())return;
        worker.post(() -> {
            if(destroyed || !runtimeEnabled())return;
            AccessibilityNodeInfo root=RootAccess.read(this);
            try {
                if(root==null || root.getPackageName()==null)return;
                String pkg=root.getPackageName().toString();
                if(getPackageName().equals(pkg) || pkg.equals(foregroundPackage))return;
                if(rollbackJump(foregroundPackage,pkg))return;
                foregroundPackage=pkg;eventClass="";activityTracker.enter(pkg);missingRootAttempts=0;foregroundWindow=root.getWindowId();enteredAt=SystemClock.uptimeMillis();
                enqueue(new ScanRequest(pkg,"",AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,false));
            } finally {if(root!=null)root.recycle();}
        });
    }
    private boolean isActivity(String pkg,String name) {
        String key=pkg+"/"+name;
        Boolean cached=activityCache.get(key);
        if(cached!=null)return cached;
        long now=SystemClock.uptimeMillis();
        if(now<activityRetryAt.getOrDefault(key,0L))return false;
        boolean found;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg,name),0);
            found=true;
        } catch(PackageManager.NameNotFoundException error) { found=false; }
        catch(RuntimeException unavailable) {found=false;}
        if(found){activityCache.put(key,true);activityRetryAt.remove(key);}
        else {activityRetryAt.put(key,now+750L);if(activityRetryAt.size()>128)activityRetryAt.remove(activityRetryAt.keySet().iterator().next());}
        if(activityCache.size()>128)activityCache.remove(activityCache.keySet().iterator().next());
        return found;
    }

    private boolean rollbackJump(String source,String target) {
        JumpGuard guard=jumpGuard;
        if(guard==null || source==null || source.isEmpty() || !guard.shouldReturn(source,target))return false;
        if(!runtimeEnabled() || !PreferenceStore.isTypeEnabled(this,AdType.JUMP)
                || packagePolicy==null || !packagePolicy.allow(target))return false;
        if(!performGlobalAction(GLOBAL_ACTION_BACK))return false;
        UserEventLog.recordAction(this,source,JumpGuard.isTaobaoFamily(target)
                ?"淘宝系广告跳转 已执行返回操作"
                :"广告跳转 已执行返回操作");
        mainHandler.postDelayed(this::requestReconcile,250L);
        return true;
    }

    private void scheduleRetry(long delay) {
        if(delay<0) { cancelRetry();return; }
        long due=SystemClock.uptimeMillis()+Math.max(60L,delay);
        if(retryAt<=due)return;
        scanHandler.removeCallbacks(retryScan);
        retryAt=due;
        scanHandler.postAtTime(retryScan,due);
    }
    private void cancelRetry() {
        retryAt=Long.MAX_VALUE;
        if(scanHandler!=null)scanHandler.removeCallbacks(retryScan);
    }
    private void requestCurrentScan() {
        cancelRetry();
        if(destroyed || !runtimeEnabled())return;
        if(foregroundPackage.isEmpty()) {
            AccessibilityNodeInfo root=RootAccess.read(this);
            try { if(root!=null && root.getPackageName()!=null){foregroundPackage=root.getPackageName().toString();activityTracker.enter(foregroundPackage);} }
            finally { if(root!=null)root.recycle(); }
        }
        if(!foregroundPackage.isEmpty())enqueue(new ScanRequest(foregroundPackage,eventClass,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,false));
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        PreferenceStore.markTaskRemoved(this);
        RuntimeHealth.event(this,"最近任务被移除 下次打开需重新检查辅助功能连接");
        if(ocrController!=null)ocrController.endSession();
        if(jumpGuard!=null)jumpGuard.clear();
        cancelRetry();
        Handler worker=scanHandler;if(worker!=null)worker.post(()->{if(ruleEngine!=null)ruleEngine.onUserInteraction();});
        ProtectionService.sync(this);super.onTaskRemoved(rootIntent);
    }
    private boolean runtimeEnabled() {
        return PreferenceStore.isMasterEnabled(this) && !PreferenceStore.isTaskRemoved(this);
    }
    @Override public void onInterrupt() {
        if(ocrController!=null)ocrController.endSession();
        // onInterrupt concerns accessibility feedback, not service destruction or user consent.
        Handler worker=scanHandler;
        if(worker!=null)worker.post(() -> {if(ruleEngine!=null)ruleEngine.onUserInteraction();});
    }
    @Override public boolean onUnbind(Intent intent) {
        releaseConnection();
        return super.onUnbind(intent);
    }
    private void releaseConnection() {
        if(destroyed && !connected)return;
        destroyed=true;connected=false;
        RuntimeHealth.event(this,"辅助功能服务断开 系统授权另行核对");
        AccessibilityState.disconnected(this);
        disposeRuntime();
        if(activeService.get()==this)activeService.clear();
    }
    private void disposeRuntime() {
        connectionGeneration++;initializing=false;
        if(ocrController!=null)ocrController.stop();ocrController=null;
        mainHandler.removeCallbacksAndMessages(null);
        Handler worker=scanHandler;HandlerThread thread=scanThread;RuleEngine engine=ruleEngine;
        if(jumpGuard!=null)jumpGuard.clear();jumpGuard=null;
        ruleEngine=null;packagePolicy=null;scanHandler=null;scanThread=null;
        foregroundPackage="";workerPackage="";activityName="";eventClass="";activityCache.clear();activityRetryAt.clear();activityTracker.clear();missingRootAttempts=0;
        retryAt=Long.MAX_VALUE;
        if(worker!=null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {try {if(engine!=null)engine.close();}finally {if(thread!=null)thread.quitSafely();}});
        }
        synchronized(scanLock) {pendingScan=null;drainScheduled=false;}
    }
    @Override public void onDestroy() {
        releaseConnection();super.onDestroy();
    }
    private static final class ScanRequest {
        final String packageName;
        final String className;
        final int type;
        final boolean popupProbe;
        ScanRequest(String pkg,String name,int type,boolean popupProbe) {
            this.packageName=pkg;
            this.className=name;
            this.type=type;
            this.popupProbe=popupProbe;
        }
    }
}
