import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.engine.GkdAssistEngine;
import org.adguardian.app.log.UserEventLog;

public final class RegressionB75Tests {
    private static int passed;
    private static int failed;
    static final class Service extends AccessibilityService {
        @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
        @Override public void onInterrupt() { }
    }
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    static void run(String name,Runnable test) {
        Handler.tasks.clear(); SystemClock.now=5000;
        try { test.run();passed++;System.out.println("PASS "+name); }
        catch(AssertionError error) { failed++;System.out.println("FAIL "+name+" "+error.getMessage()); }
    }
    static AccessibilityNodeInfo root() {
        AccessibilityNodeInfo r=new AccessibilityNodeInfo();r.pkg="host.example";r.name="android.widget.FrameLayout";
        r.bounds=new Rect(0,0,1080,2400);r.clickable=false;return r;
    }
    public static void main(String[] args) {
        run("SDK page without a matched close control must not blindly press Back",()-> {
            Service s=new Service();s.root=root();
            GkdAssistEngine engine=new GkdAssistEngine(s);
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(s.root)) {
                engine.handle("host.example","com.bytedance.sdk.openadsdk.activity.TTFullScreenVideoActivity",32,index);
            }
            check(s.backActions==0,"heuristic Back invoked without an actionable rule");
        });
        run("accepted click on unchanged UI is not advertised as successful dismissal",()-> {
            Service s=new Service();s.root=root();UserEventLog.clear(s);
            AccessibilityNodeInfo close=new AccessibilityNodeInfo();close.text="关闭广告";
            close.bounds=new Rect(960,60,1040,140);s.root.add(close);
            GkdAssistEngine engine=new GkdAssistEngine(s);
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(s.root)) {engine.handle("host.example","host.example.Main",32,index);}
            check(!UserEventLog.read(s).contains("广告已自动关闭"),"input acceptance was logged as actual dismissal");
        });
        System.out.println("B7.5 initial behavioral regressions passed="+passed+" failed="+failed);
        if(failed>0)throw new AssertionError("B7.5 regressions remain");
    }
}
