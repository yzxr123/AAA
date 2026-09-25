package org.adguardian.app.gkd;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.ActionVerifier;
import org.adguardian.app.log.UserEventLog;
import org.adguardian.app.settings.PreferenceStore;
import org.adguardian.gkd.CompiledRule;
import org.adguardian.gkd.RuleMatch;
import org.adguardian.gkd.RulePosition;
import org.adguardian.gkd.RuleSession;
import org.adguardian.gkd.RuleSwipe;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.json.JSONException;

public final class GkdSubscriptionEngine {
    private static final Set<String> AD_CATEGORIES = new HashSet<>(Arrays.asList(
            "开屏广告", "局部广告", "全屏广告", "分段广告"));
    private final AccessibilityService service;
    private final Handler handler;
    private final Runnable requestScan;
    private final Supplier<String> foreground;
    private final ActionVerifier verifier;
    private int currentWindow=-1;
    private GkdRuleRepository repository;
    private RuleSession<AccessibilityNodeInfo> session;
    private String packageName = "";
    private boolean gesturePending;
    private volatile boolean waiting;
    private volatile long lastAction;
    private long generation;

    public GkdSubscriptionEngine(AccessibilityService service, Handler handler,
                                 Runnable requestScan, Supplier<String> foreground) {
        this(service,handler,requestScan,foreground,new ActionVerifier(service,handler,foreground,requestScan));
    }
    public GkdSubscriptionEngine(AccessibilityService service, Handler handler,
            Runnable requestScan,Supplier<String> foreground,ActionVerifier verifier) {
        this.verifier=verifier;
        this.service=service;
        this.handler=handler;
        this.requestScan=requestScan;
        this.foreground=foreground;
        try {
            repository=new GkdRuleRepository(service.getApplicationContext());
        } catch (IOException | JSONException error) {
            UserEventLog.error(service,"广告识别暂不可用 请重新打开应用");
        }
    }

    public void onPackageEntered(String value) {
        if (value.equals(packageName)) return;
        reset();
        packageName=value;
        session=null;
    }

    public void reset() {
        generation++;
        gesturePending=false;
        waiting=false;
        if(session!=null)session.reset();
    }

    public void cancelPendingActions() {
        generation++;gesturePending=false;waiting=false;
        if(session!=null)session.cancelPending();
    }
    public void close() {
        reset();
        session=null;
        if(repository!=null)repository.trim();
    }

    public boolean isWaiting() { return waiting; }
    public boolean isGesturePending() { return gesturePending; }
    public long lastAutomatedActionUptime() { return lastAction; }
    public long nextWakeUp() {
        if(session==null || gesturePending || !enabled())return -1L;
        return session.nextWakeUp(SystemClock.uptimeMillis());
    }

    public boolean handle(String currentPackage, String activity, AccessibilityNodeIndex index) {
        onPackageEntered(currentPackage);
        if(!enabled() || repository==null)return false;
        if(gesturePending)return true;
        if(session==null) {
            try {
                session=new RuleSession<>(currentPackage,repository.forPackage(currentPackage),repository.globals());
                session.setRuleFilter(this::ruleEnabled);
                boolean system=false;
                try {
                    ApplicationInfo app=service.getPackageManager().getApplicationInfo(currentPackage,0);
                    system=(app.flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
                } catch(PackageManager.NameNotFoundException error) { }
                session.setEnvironment(system,false);
            } catch (IOException | JSONException | IllegalArgumentException error) {
                UserEventLog.error(service,"部分广告暂时无法处理");
                return false;
            }
        }
        currentWindow=index.root().getWindowId();
        GkdNodeAdapter adapter=new GkdNodeAdapter(index);
        RuleMatch<AccessibilityNodeInfo> match;
        index.startQueryBudget(120L);
        try {
            match=session.find(index.root(),adapter,activity.isEmpty()?null:activity,
                    index.root().getWindowId(),SystemClock.uptimeMillis(),AD_CATEGORIES,
                    (rule,node) -> "none".equals(rule.getAction()) || verifier.capture(
                            currentPackage,key(rule),rule.getAction(),node,index,null)!=null);
        } catch(AccessibilityNodeIndex.QueryLimit limit) {
            waiting=false;
            return false;
        } finally {
            index.clearQueryBudget();
        }
        waiting=session.getHasPendingMatch();
        if(match==null)return waiting;
        if(!enabled() || !currentPackage.equals(foreground.get())) {
            session.complete(match,SystemClock.uptimeMillis(),false);
            waiting=false;
            return false;
        }
        return perform(match,index);
    }

    private boolean enabled() {
        return PreferenceStore.isMasterEnabled(service) && PreferenceStore.isGkdEnabled(service);
    }

    private boolean ruleEnabled(CompiledRule rule) {
        String name=rule.getGroupName();
        AdType type;
        if(name.startsWith("开屏广告"))type=AdType.STARTUP;
        else if(name.contains("信息流"))type=AdType.FEED;
        else if(name.contains("悬浮"))type=AdType.FLOATING;
        else if(name.startsWith("局部广告"))type=AdType.CARD;
        else type=AdType.POPUP;
        return verifier.allowed(packageName,key(rule),currentWindow) && PreferenceStore.isTypeEnabled(service,type)
                && (!"swipe".equals(rule.getAction()) || PreferenceStore.isTypeEnabled(service,AdType.SCROLL));
    }

    private String key(CompiledRule rule) { return "gkd:"+rule.getGlobal()+":"+rule.getGroupKey()+":"+rule.getOrdinal(); }

    private boolean perform(RuleMatch<AccessibilityNodeInfo> match, AccessibilityNodeIndex index) {
        CompiledRule rule=match.getRule();
        String action=rule.getAction();
        AccessibilityNodeInfo node=match.getNode();
        if("none".equals(action)) {
            finish(session,match,true,false,null);
            return true;
        }
        ActionVerifier.Ticket ticket=verifier.capture(packageName,key(rule),action,node,index,null);
        if(ticket==null) {finish(session,match,false,false,null);return false;}
        if("back".equals(action)) {
            if(!canPerform(ticket,rule)) {finish(session,match,false,false,ticket);return false;}
            boolean ok=service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            finish(session,match,ok,true,ticket);
            return ok;
        }
        boolean longClick=action.startsWith("longClick");
        boolean nodeOnly=action.endsWith("Node");
        boolean centerOnly=action.endsWith("Center") || "swipe".equals(action);
        int nodeAction=longClick?AccessibilityNodeInfo.ACTION_LONG_CLICK:AccessibilityNodeInfo.ACTION_CLICK;
        if(!centerOnly && node.isEnabled() && (longClick?node.isLongClickable():node.isClickable())) {
            if(!canPerform(ticket,rule)) {finish(session,match,false,false,ticket);return false;}
            if(node.performAction(nodeAction)) {
                finish(session,match,true,true,ticket);
                return true;
            }
        }
        if(nodeOnly) {
            finish(session,match,false,false,ticket);
            return false;
        }
        Rect bounds=new Rect();
        node.getBoundsInScreen(bounds);
        float screenWidth=service.getResources().getDisplayMetrics().widthPixels;
        float screenHeight=service.getResources().getDisplayMetrics().heightPixels;
        RuleSwipe swipe=rule.getSwipe();
        float[] start;
        float[] end=null;
        long duration=longClick?500L:45L;
        if(swipe!=null && "swipe".equals(action)) {
            start=position(swipe.getStart(),bounds,screenWidth,screenHeight);
            end=position(swipe.getEnd(),bounds,screenWidth,screenHeight);
            duration=swipe.getDuration();
        } else {
            start=rule.getPosition()==null
                    ?new float[]{bounds.exactCenterX(),bounds.exactCenterY()}
                    :position(rule.getPosition(),bounds,screenWidth,screenHeight);
        }
        if(!inside(start,screenWidth,screenHeight) || (swipe!=null && !inside(end,screenWidth,screenHeight))) {
            finish(session,match,false,false,ticket);
            return false;
        }
        Path path=new Path();
        path.moveTo(start[0],start[1]);
        if(end!=null)path.lineTo(end[0],end[1]);
        GestureDescription description=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0L,duration)).build();
        if(!canPerform(ticket,rule)) {finish(session,match,false,false,ticket);return false;}
        RuleSession<AccessibilityNodeInfo> owner=session;
        long token=++generation;
        gesturePending=true;
        waiting=true;
        lastAction=SystemClock.uptimeMillis();
        boolean accepted;
        try {
            accepted=service.dispatchGesture(description,new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) {
                if(token!=generation || owner!=session)return;
                gesturePending=false;
                finish(owner,match,enabled() && packageName.equals(foreground.get()),true,ticket);
                requestScan.run();
            }
            @Override public void onCancelled(GestureDescription gesture) {
                if(token!=generation || owner!=session)return;
                gesturePending=false;
                finish(owner,match,false,false,ticket);
                requestScan.run();
            }
        },handler);
        } catch(RuntimeException error) {
            gesturePending=false;
            finish(owner,match,false,false,ticket);
            return false;
        }
        if(!accepted) {
            gesturePending=false;
            finish(owner,match,false,false,ticket);
        } else {
            handler.postDelayed(() -> {
                if(token==generation && gesturePending && owner==session) {
                    generation++;
                    gesturePending=false;
                    finish(owner,match,false,false,ticket);
                    requestScan.run();
                }
            },duration+1500L);
        }
        return accepted;
    }

    private boolean canPerform(ActionVerifier.Ticket ticket,CompiledRule rule) {
        return verifier.isCurrent(ticket) && enabled() && ruleEnabled(rule);
    }

    private void finish(RuleSession<AccessibilityNodeInfo> owner, RuleMatch<AccessibilityNodeInfo> match,
                        boolean success, boolean userAction,ActionVerifier.Ticket ticket) {
        owner.complete(match,SystemClock.uptimeMillis(),success);
        waiting=false;
        if(!success)verifier.rejected(ticket);
        if(success && userAction) {
            lastAction=SystemClock.uptimeMillis();
            verifier.accepted(ticket);
        }
    }

    private float[] position(RulePosition value,Rect bounds,float width,float height) {
        return value.coordinates(bounds.left,bounds.top,bounds.right,bounds.bottom,width,height);
    }
    private boolean inside(float[] point,float width,float height) {
        return point!=null && point[0]>=0 && point[1]>=0 && point[0]<width && point[1]<height;
    }
}
