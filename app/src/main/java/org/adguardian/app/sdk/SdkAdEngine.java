package org.adguardian.app.sdk;

import android.accessibilityservice.*;
import android.graphics.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.function.Supplier;
import org.adguardian.app.engine.*;
import org.adguardian.app.settings.PreferenceStore;

/** Current-window SDK assistance. Inventory presence never grants permission to click another page. */
public final class SdkAdEngine {
    private final AccessibilityService service;
    private final Handler handler;
    private final Supplier<String> foreground;
    private final ActionVerifier verifier;
    private boolean pending;
    private long epoch,lastAction;
    public SdkAdEngine(AccessibilityService service,Handler worker,Supplier<String> foreground,ActionVerifier verifier){
        this.service=service;handler=new Handler(worker.getLooper());this.foreground=foreground;this.verifier=verifier;
    }
    public boolean isPending(){return pending;}
    public long lastAction(){return lastAction;}
    public void cancel(){epoch++;pending=false;handler.removeCallbacksAndMessages(null);}
    private boolean allowed(String pkg){return PreferenceStore.isMasterEnabled(service)&&PreferenceStore.isSdkEnabled(service)
            &&PreferenceStore.isTypeEnabled(service,AdType.POPUP)&&pkg.equals(foreground.get());}
    private static boolean eligible(String activity){
        if(!AdSdkSignatures.isAdSdkActivity(activity)||AdSdkSignatures.isRewardedActivity(activity))return false;
        String name=activity.substring(activity.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(name.contains("privacy")||name.contains("consent")||name.contains("permission")||name.contains("install")
                ||name.contains("download")||name.contains("landing")||name.contains("browser"))return false;
        // Explicit activity classes for fullscreen/interstitial presentation, not SDK classes in general.
        return name.endsWith("activity")&&(name.contains("fullscreen")||name.contains("interstitial")||name.equals("adactivity"));
    }
    public boolean handle(String pkg,String activity,AccessibilityNodeIndex index){
        if(pkg==null||pkg.isEmpty()||index==null||!allowed(pkg)||!eligible(activity))return false;
        if(!pkg.equals(NodeTarget.string(index.root().getPackageName())))return false;
        if(pending||verifier.isPending())return true;
        if(lastAction>0&&SystemClock.uptimeMillis()-lastAction<900)return false;
        index.startQueryBudget(30);
        try{
            if(SafeActionTarget.forbiddenScreen(index))return false;
            LinkedHashMap<AccessibilityNodeInfo,AccessibilityNodeInfo> targets=new LinkedHashMap<>();
            for(AccessibilityNodeInfo n:index.nodes()){
                if(!n.isVisibleToUser()||!n.isEnabled()||n.isPassword()||n.isEditable())continue;
                if(!AdCloseText.isClose(NodeTarget.string(n.getText()))&&!AdCloseText.isClose(NodeTarget.string(n.getContentDescription())))continue;
                AccessibilityNodeInfo click=SafeActionTarget.clickTarget(n,index);if(click!=null)targets.putIfAbsent(click,n);
            }
            if(index.isTraversalIncomplete()||targets.size()!=1)return false;
            AccessibilityNodeInfo click=targets.keySet().iterator().next(),leaf=targets.values().iterator().next();
            NodeTarget descriptor=NodeTarget.captureSelected(leaf,index.rootBounds());
            String key="sdk:"+activity+":"+descriptor.id+":"+AdCloseText.normalize(descriptor.label)+":"+Math.round(descriptor.x*20)+":"+Math.round(descriptor.y*20);
            if(!verifier.allowed(pkg,key,index.root().getWindowId()))return false;
            ActionVerifier.Ticket ticket=verifier.capture(pkg,key,"click",leaf,index,null);if(ticket==null||!allowed(pkg))return false;
            if(click.isClickable()&&click.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                lastAction=SystemClock.uptimeMillis();verifier.accepted(ticket);return true;
            }
            if(!allowed(pkg))return false;
            Rect b=SafeActionTarget.bounds(click);Path path=new Path();path.moveTo(b.exactCenterX(),b.exactCenterY());
            GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,45)).build();
            long token=++epoch;pending=true;boolean accepted;
            try{accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
                @Override public void onCompleted(GestureDescription g){if(token!=epoch)return;pending=false;if(allowed(pkg))verifier.accepted(ticket);else verifier.rejected(ticket);}
                @Override public void onCancelled(GestureDescription g){if(token!=epoch)return;pending=false;verifier.rejected(ticket);}
            },handler);}catch(RuntimeException failed){accepted=false;}
            if(!accepted){pending=false;verifier.rejected(ticket);return false;}
            lastAction=SystemClock.uptimeMillis();handler.postDelayed(()->{if(token==epoch&&pending){cancel();verifier.rejected(ticket);}},1500);return true;
        }catch(RuntimeException unavailable){return false;}
        finally{index.clearQueryBudget();}
    }
}
