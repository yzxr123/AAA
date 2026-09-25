package org.adguardian.app.runtime;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import org.adguardian.app.engine.NodeTarget;

public final class RootAccess {
    private static volatile long lastFailure;
    private RootAccess() { }
    public static AccessibilityNodeInfo read(AccessibilityService service) {return readFor(service,null);}

    /** Returned root belongs to the caller. Never select an app behind a different foreground app. */
    public static AccessibilityNodeInfo readFor(AccessibilityService service,String expected) {
        AccessibilityNodeInfo primary=null,selected=null;
        List<AccessibilityWindowInfo> windows=null;
        try {
            primary=service.getRootInActiveWindow();
            if(primary!=null) {
                String pkg=NodeTarget.string(primary.getPackageName());
                if(expected!=null && expected.equals(pkg)) {AccessibilityNodeInfo result=primary;primary=null;return result;}
                if(!pkg.equals(service.getPackageName())) {
                    if(expected==null) {AccessibilityNodeInfo result=primary;primary=null;return result;}
                    return null;
                }
            }
            windows=service.getWindows();
            if(windows==null || windows.size()>32)return null;
            boolean ownOverlay=false;
            for(AccessibilityWindowInfo window:windows) {
                if(window==null)continue;
                if(primary!=null && window.getId()==primary.getWindowId()) {
                    if(window.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        if(expected==null) {AccessibilityNodeInfo result=primary;primary=null;return result;}
                        return null;
                    }
                    ownOverlay=true;
                }
                if(primary==null && window.isActive() && window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION
                        && window.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)return null;
            }
            int layer=Integer.MIN_VALUE;
            for(AccessibilityWindowInfo window:windows) {
                if(window==null || window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                if(!ownOverlay && !window.isActive() && !window.isFocused())continue;
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null)continue;
                String pkg=NodeTarget.string(root.getPackageName());
                if(window.getLayer()>layer) {
                    if(selected!=null && selected!=root)selected.recycle();selected=root;layer=window.getLayer();
                } else if(root!=primary && root!=selected)root.recycle();
            }
            if(selected!=null && expected!=null && !expected.equals(NodeTarget.string(selected.getPackageName())))return null;
            AccessibilityNodeInfo result=selected;selected=null;return result;
        } catch(RuntimeException unavailable) {lastFailure=SystemClock.uptimeMillis();return null;}
        finally {
            if(primary!=null)primary.recycle();if(selected!=null && selected!=primary)selected.recycle();
            if(windows!=null)for(AccessibilityWindowInfo window:windows)if(window!=null)window.recycle();
        }
    }
    /** Critical teaching/verification reads must not use the previous cached page as proof. */
    public static AccessibilityNodeInfo readFreshFor(AccessibilityService service,String expected) {
        AccessibilityNodeInfo root=readFor(service,expected);
        if(root==null){ScanDiagnostics.record(ScanDiagnostics.Area.ROOT,ScanDiagnostics.Reason.ROOT_UNAVAILABLE,0,0);return null;}
        try {
            if(android.os.Build.VERSION.SDK_INT>=33)service.clearCachedSubtree(root);
            if(!root.refresh() || (expected!=null && !expected.equals(NodeTarget.string(root.getPackageName())))) {
                ScanDiagnostics.record(ScanDiagnostics.Area.ROOT,ScanDiagnostics.Reason.ROOT_STALE,0,0);root.recycle();return null;
            }
            ScanDiagnostics.record(ScanDiagnostics.Area.ROOT,ScanDiagnostics.Reason.READY,1,0);return root;
        } catch(RuntimeException unavailable){lastFailure=SystemClock.uptimeMillis();root.recycle();return null;}
    }
    public static long lastFailureUptime() {return lastFailure;}
}
