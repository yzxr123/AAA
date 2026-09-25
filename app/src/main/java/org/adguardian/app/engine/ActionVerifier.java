package org.adguardian.app.engine;

import android.accessibilityservice.AccessibilityService;
import org.adguardian.app.runtime.RootAccess;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.adguardian.app.log.UserEventLog;
import org.adguardian.app.settings.PreferenceStore;

public final class ActionVerifier {
    private final AccessibilityService service;
    private final Handler worker;
    private final Supplier<String> foreground;
    private final Runnable requestScan;
    private final LinkedHashMap<String,Ticket> failures=new LinkedHashMap<>();
    private volatile Ticket pending;
    private long generation;
    private volatile long lastAction;

    public ActionVerifier(AccessibilityService service,Handler worker,Supplier<String> foreground,Runnable requestScan) {
        this.service=service;this.worker=new Handler(worker.getLooper());
        this.foreground=foreground;this.requestScan=requestScan;
    }
    public boolean isPending() { return pending!=null; }
    public long lastAction() { return lastAction; }
    public boolean allowed(String pkg,String key,int window) {
        for(Ticket failed:failures.values()) {
            if(failed.target==null && pkg.equals(failed.pkg) && key.equals(failed.key))return false;
        }
        return true;
    }
    public Ticket capture(String pkg,String key,String action,AccessibilityNodeInfo target,
                          AccessibilityNodeIndex index,String expression) {
        if(pending!=null || !PreferenceStore.isMasterEnabled(service) || !pkg.equals(foreground.get())
                || !pkg.equals(NodeTarget.string(index.root().getPackageName()))
                || !allowed(pkg,key,index.root().getWindowId()))return null;
        NodeTarget saved=NodeTarget.capture(target,index.rootBounds());
        if(saved==null && target!=null) {
            NodeTarget structural=NodeTarget.captureSelected(target,index.rootBounds());
            if(structural!=null && structural.isSafeLearningTarget())saved=structural.forLearning();
        }
        if(saved==null && (expression==null || expression.isEmpty()))return null;
        for(Ticket failed:failures.values()) {
            if(pkg.equals(failed.pkg) && sameTarget(saved,failed.target))return null;
        }
        return new Ticket(pkg,key,action,index.root().getWindowId(),saved,expression,generation);
    }
    /** A provider read may block while the foreground, settings or cancellation generation changes. */
    public boolean isCurrent(Ticket ticket) {
        if(!owns(ticket))return false;
        AccessibilityNodeInfo root=RootAccess.readFreshFor(service,ticket.pkg);
        try {return root!=null && root.getWindowId()==ticket.window && owns(ticket);}
        finally {if(root!=null)root.recycle();}
    }
    private boolean owns(Ticket ticket) {
        return ticket!=null && ticket.generation==generation && PreferenceStore.isMasterEnabled(service)
                && ticket.pkg.equals(foreground.get());
    }
    public void accepted(Ticket ticket) {
        if(ticket==null || ticket.generation!=generation || !ticket.pkg.equals(foreground.get()))return;
        lastAction=SystemClock.uptimeMillis();
        pending=ticket;
        UserEventLog.recordAction(service,ticket.pkg,"已执行广告操作");
        worker.postDelayed(() -> verify(ticket,0),150L);
    }
    public void rejected(Ticket ticket) {
        if(ticket==null || ticket.generation!=generation)return;
        block(ticket);requestScan.run();
    }
    public void cancel() {
        if(pending!=null)block(pending);
        generation++;pending=null;worker.removeCallbacksAndMessages(null);
    }
    public void onPackageChanged() { cancel();failures.clear(); }
    private void verify(Ticket ticket,int check) {
        if(pending!=ticket || ticket.generation!=generation)return;
        if(!PreferenceStore.isMasterEnabled(service) || !ticket.pkg.equals(foreground.get())) {cancel();return;}
        AccessibilityNodeInfo root=RootAccess.readFreshFor(service,ticket.pkg);
        boolean known=false;
        boolean present=true;
        try {
            if(root!=null && root.getWindowId()==ticket.window
                    && ticket.pkg.contentEquals(NodeTarget.string(root.getPackageName()))) {
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(root)) {
                    index.startQueryBudget(35L);
                    if(ticket.expression!=null && !ticket.expression.isEmpty())present=index.expressionExists(ticket.expression);
                    else {
                        present=false;
                        for(AccessibilityNodeInfo node:ticket.target.candidates(index)) {
                            if(ticket.target.matches(node,index.rootBounds(),false)) {present=true;break;}
                        }
                    }
                    known=!index.isTraversalIncomplete();
                }
            }
        } catch(RuntimeException error) {
            known=false;
        } finally { if(root!=null)root.recycle(); }
        // Reading a provider may outlive cancellation, a foreground switch or a newer ticket.
        // Never let the old observation clear or complete the replacement operation.
        if(pending!=ticket || ticket.generation!=generation)return;
        if(!PreferenceStore.isMasterEnabled(service) || !ticket.pkg.equals(foreground.get())) {cancel();return;}
        if(known && !present)ticket.absentChecks++;else ticket.absentChecks=0;
        if(ticket.absentChecks>=2) {
            pending=null;
            requestScan.run();return;
        }
        if(check<2) {worker.postDelayed(() -> verify(ticket,check+1),check==0?200L:550L);return;}
        pending=null;
        block(ticket);
        UserEventLog.recordAction(service,ticket.pkg,known && present
                ?"未能自动关闭 已停止重复操作":"未能确认关闭 已停止重复操作");
        requestScan.run();
    }
    private void block(Ticket ticket) {
        // A new rule name or recreated popup window is not evidence that the failed target is safe.
        // Keep only this target/rule quarantined until the app is left; other sequence targets can act.
        NodeTarget target=ticket.target;
        String identity=target==null?"rule:"+ticket.key:!target.id.isEmpty()?"id:"+target.id
                :target.name+":"+AdCloseText.normalize(target.label)+":"+Math.round(target.x*20)+":"+Math.round(target.y*20);
        failures.put(ticket.pkg+"|"+identity,ticket);
        if(failures.size()>64)failures.remove(failures.keySet().iterator().next());
    }
    private static boolean sameTarget(NodeTarget first,NodeTarget second) {
        if(first==null || second==null)return false;
        if(!first.id.isEmpty() || !second.id.isEmpty())return !first.id.isEmpty() && first.id.equals(second.id);
        return first.name.equals(second.name)
                && AdCloseText.normalize(first.label).equals(AdCloseText.normalize(second.label))
                && Math.abs(first.x-second.x)<=0.08f && Math.abs(first.y-second.y)<=0.08f;
    }
    public static final class Ticket {
        final String pkg,key,action,expression;
        final int window;
        final NodeTarget target;
        final long generation;
        int absentChecks;
        Ticket(String pkg,String key,String action,int window,NodeTarget target,String expression,long generation) {
            this.pkg=pkg;this.key=key;this.action=action;this.window=window;
            this.target=target;this.expression=expression;this.generation=generation;
        }
    }
}
