import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.gkd.GkdSubscriptionEngine;
import org.adguardian.gkd.RuleSession;

/** Never use a matched subscription node after its foreground window has changed. */
public final class SubscriptionOwnershipB716Tests {
    private static final String PACKAGE="host.example";

    private static GkdSubscriptionEngine engine(TestSupport.Service service,String action) throws Exception {
        RuleSession<AccessibilityNodeInfo> session=new RuleSession<>(PACKAGE,List.of(Map.of(
                "key",1,"name","全屏广告-测试","rules",List.of(Map.of(
                        "matches","[vid=\"ad_close\"]","action",action)))),List.of());
        GkdSubscriptionEngine engine=new GkdSubscriptionEngine(service,new Handler(Looper.getMainLooper()),()->{},()->PACKAGE);
        engine.onPackageEntered(PACKAGE);
        Field field=GkdSubscriptionEngine.class.getDeclaredField("session");
        field.setAccessible(true);field.set(engine,session);
        return engine;
    }

    public static void main(String[] args) {
        for(String action:List.of("click","back","clickCenter")) {
            TestSupport.run("GKD "+action+" rejects a matched window that is no longer active",()->{
                TestSupport.Service service=new TestSupport.Service();
                AccessibilityNodeInfo oldRoot=TestSupport.root(PACKAGE,"old");
                AccessibilityNodeInfo target=TestSupport.node(PACKAGE+":id/ad_close","关闭","android.widget.Button",900,100,80,50,true);
                oldRoot.add(target);
                service.root=TestSupport.root(PACKAGE,"new");service.root.window=2;
                GkdSubscriptionEngine engine=engine(service,action);
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(oldRoot)) {
                    TestSupport.check(!engine.handle(PACKAGE,PACKAGE+".Main",index),"stale-window action was accepted");
                    TestSupport.check(target.clicks==0 && service.gestures==0 && service.backActions==0,
                            "subscription acted on a stale window");
                } finally {engine.close();}
            });
        }
        TestSupport.run("GKD gesture fallback rechecks ownership after native action failure",()->{
            TestSupport.Service service=new TestSupport.Service();
            AccessibilityNodeInfo oldRoot=TestSupport.root(PACKAGE,"old");
            AccessibilityNodeInfo nextRoot=TestSupport.root(PACKAGE,"new");nextRoot.window=2;
            AccessibilityNodeInfo target=new AccessibilityNodeInfo() {
                @Override public boolean performAction(int action) {
                    service.root=nextRoot;
                    return false;
                }
            };
            target.id=PACKAGE+":id/ad_close";target.text="关闭";
            target.bounds=new android.graphics.Rect(900,100,980,150);
            oldRoot.add(target);service.root=oldRoot;
            GkdSubscriptionEngine engine=engine(service,"click");
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(oldRoot)) {
                TestSupport.check(!engine.handle(PACKAGE,PACKAGE+".Main",index),"fallback acted after native action changed the window");
                TestSupport.check(service.gestures==0,"gesture fallback used the previous window's coordinates");
            } finally {engine.close();}
        });
    }
}
