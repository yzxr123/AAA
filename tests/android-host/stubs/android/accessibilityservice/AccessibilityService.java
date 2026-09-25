package android.accessibilityservice;import android.content.*;import android.os.*;import android.graphics.*;import android.hardware.*;import android.view.accessibility.*;import java.util.concurrent.*;
public abstract class AccessibilityService extends android.app.Service{
public static final int GLOBAL_ACTION_BACK=1;public AccessibilityNodeInfo root;public int gestures,backActions;
public java.util.List<AccessibilityWindowInfo> windows=new java.util.ArrayList<>();public java.util.List<AccessibilityWindowInfo> getWindows(){return windows;}
public boolean clearCachedSubtree(AccessibilityNodeInfo n){return true;}public AccessibilityNodeInfo getRootInActiveWindow(){return root;}public boolean performGlobalAction(int a){backActions++;return true;}
public boolean dispatchGesture(GestureDescription g,GestureResultCallback c,Handler h){gestures++;if(c!=null)(h==null?new Handler(Looper.getMainLooper()):h).postDelayed(()->c.onCompleted(g),50);return true;}
public static class GestureResultCallback{public void onCompleted(GestureDescription g){}public void onCancelled(GestureDescription g){}}
public static class ScreenshotResult{public HardwareBuffer getHardwareBuffer(){return null;}public ColorSpace getColorSpace(){return null;}}
public interface TakeScreenshotCallback{void onSuccess(ScreenshotResult r);void onFailure(int code);}
public void takeScreenshot(int d,Executor e,TakeScreenshotCallback c){throw new UnsupportedOperationException("No Android screenshot in host tests");}
protected void onServiceConnected(){systemConnectedCalls++;}public void onTaskRemoved(Intent i){}public void onDestroy(){}public abstract void onAccessibilityEvent(AccessibilityEvent e);public abstract void onInterrupt();
public AccessibilityServiceInfo serviceInfo=new AccessibilityServiceInfo();public boolean failServiceInfo;public int systemConnectedCalls;public AccessibilityServiceInfo getServiceInfo(){return serviceInfo;}public void setServiceInfo(AccessibilityServiceInfo i){if(failServiceInfo)throw new IllegalStateException("binder unavailable");serviceInfo=i;}
}