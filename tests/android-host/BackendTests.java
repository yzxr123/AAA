import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.engine.RuleEngine;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.gkd.GkdNodeAdapter;
import org.adguardian.app.gkd.GkdRuleRepository;
import org.adguardian.app.gkd.GkdSubscriptionEngine;
import org.adguardian.app.ocr.OcrFallbackController;
import org.adguardian.app.settings.PreferenceStore;
import org.adguardian.gkd.RuleSession;

public final class BackendTests {
    private static int passed;
    private static final class Service extends AccessibilityService {
        boolean throwGesture;
        boolean throwScreenshot;
        int screenshots;
        @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
        @Override public void onInterrupt() { }
        @Override public boolean dispatchGesture(GestureDescription d, GestureResultCallback c,Handler h) {
            if(throwGesture)throw new IllegalStateException("simulated system rejection");
            return super.dispatchGesture(d,c,h);
        }
        @Override public void takeScreenshot(int d,Executor e,TakeScreenshotCallback cb) {
            screenshots++;
            if(throwScreenshot)throw new SecurityException("simulated unavailable screenshot permission");
        }
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void run(String name,Runnable test) {
        Handler.tasks.clear();SystemClock.now=1000L;
        test.run();passed++;System.out.println("PASS "+name);
    }
    private static AccessibilityNodeInfo root(String pkg) {
        AccessibilityNodeInfo r=new AccessibilityNodeInfo();r.pkg=pkg;r.name="android.widget.FrameLayout";
        r.bounds=new Rect(0,0,1080,2400);r.clickable=false;return r;
    }
    private static AccessibilityNodeInfo skip(boolean clickable) {
        AccessibilityNodeInfo n=new AccessibilityNodeInfo();n.name="android.widget.TextView";
        n.text="跳过";n.clickable=clickable;n.bounds=new Rect(900,100,1000,160);return n;
    }
    private static boolean handle(GkdSubscriptionEngine engine,String pkg,AccessibilityNodeInfo r) {
        try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(r)) { return engine.handle(pkg,pkg+".MainActivity",index); }
    }
    public static void main(String[] args) throws Exception {
        run("node index is lazy and shares child reads",()->{
            AccessibilityNodeInfo r=root("host.example"),s=skip(true);r.add(s);
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(r)) {
                check(r.childReads==0,"index eagerly traversed tree");
                int first=0;for(AccessibilityNodeInfo n:index.nodes())first++;
                int reads=r.childReads;
                int second=0;for(AccessibilityNodeInfo n:index.nodes())second++;
                check(first==2&&second==2,"traversal changed");
                check(reads==r.childReads,"second layer reread child binder nodes");
            }
            check(r.recycles==0&&s.recycles==1,"index must release owned children but not caller root");
        });
        run("real selector calls native view ID fast query",()->{
            AccessibilityNodeInfo r=root("host.example"),s=skip(true);s.id="host.example:id/ad_close";r.add(s);
            Map<String,Object> rule=Map.of("matches","[id=\"host.example:id/ad_close\"]");
            RuleSession<AccessibilityNodeInfo> session=new RuleSession<>("host.example",List.of(Map.of("key",1,"name","局部广告-测试","fastQuery",true,"rules",List.of(rule))),List.of());
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(r)) {
                check(session.find(r,new GkdNodeAdapter(index),"host.example.Main",1,1000,Set.of("局部广告"))!=null,"actual selector did not find node");
                check(r.fastReads>0&&r.childReads==0,"view ID match lost native fast query");
            }
        });
        run("real bundled global rule skips advertisement",()->{
            Service s=new Service();String pkg="host.example";s.root=root(pkg);AccessibilityNodeInfo n=skip(true);s.root.add(n);
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(s,new Handler(Looper.getMainLooper()),()->{},()->pkg);
            check(handle(engine,pkg,s.root),"bundled global rule failed");check(n.clicks==1,"advertisement not clicked");
            check(!handle(engine,pkg,s.root),"cooldown not applied");check(n.clicks==1,"duplicate click occurred");
            engine.close();
        });
        run("Xiachufang splash skip acts on the first rule scan without a timer",()->{
            Service service=new Service();String pkg="com.xiachufang";service.root=root(pkg);
            AccessibilityNodeInfo button=skip(true);button.id=pkg+":id/skip_container";button.text="";
            service.root.add(button);
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(service,
                    new Handler(Looper.getMainLooper()),()->{},()->pkg);
            check(handle(engine,pkg,service.root),"Xiachufang startup selector did not match");
            check(button.clicks==1,"Xiachufang skip was not clicked on the first scan");
            check(SystemClock.uptimeMillis()==1000L,"Xiachufang rule inserted a timer before the click");
            engine.close();
        });
        run("subscription exclusion preserves normal agreement page",()->{
            Service s=new Service();String pkg="host.example";s.root=root(pkg);AccessibilityNodeInfo n=skip(true),agreement=skip(false);agreement.text="阅读并同意";s.root.add(n).add(agreement);
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(s,new Handler(Looper.getMainLooper()),()->{},()->pkg);
            check(!handle(engine,pkg,s.root),"normal agreement page matched");check(n.clicks==0&&s.gestures==0,"normal UI was touched");engine.close();
        });
        run("old structural fallback cannot override normal page exclusion",()->{
            Service s=new Service();String pkg="host.example";s.root=root(pkg);
            AccessibilityNodeInfo n=skip(true),agreement=skip(false);agreement.text="阅读并同意";
            s.root.add(n).add(agreement);PreferenceStore.setLiTiaotiaoEnabled(s,false);
            RuleEngine core=new RuleEngine(s,new Handler(Looper.getMainLooper()),()->{},()->pkg);
            check(!core.handle(pkg,pkg+".Main",AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,s.root),"fallback overrode GKD normal page exclusion");
            check(n.clicks==0&&s.gestures==0,"normal page touched by structural fallback");core.close();
        });
        run("GKD switch and ad category switches gate actions",()->{
            Service s=new Service();String pkg="host.example";s.root=root(pkg);AccessibilityNodeInfo n=skip(true);s.root.add(n);
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(s,new Handler(Looper.getMainLooper()),()->{},()->pkg);
            PreferenceStore.setGkdEnabled(s,false);check(!handle(engine,pkg,s.root),"disabled GKD acted");
            PreferenceStore.setGkdEnabled(s,true);PreferenceStore.setTypeEnabled(s,AdType.STARTUP,false);check(!handle(engine,pkg,s.root),"disabled startup group acted");
            PreferenceStore.setTypeEnabled(s,AdType.STARTUP,true);engine.reset();check(handle(engine,pkg,s.root)&&n.clicks==1,"reenable did not restore ads");engine.close();
        });
        run("gesture completion releases waiting state",()->{
            Service s=new Service();String pkg="host.example";s.root=root(pkg);s.root.add(skip(false));
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(s,new Handler(Looper.getMainLooper()),()->{},()->pkg);
            check(handle(engine,pkg,s.root)&&s.gestures==1&&engine.isWaiting(),"gesture not dispatched");
            Handler.advance(1050);check(!engine.isWaiting(),"completed gesture left blocked state");engine.close();
        });
        run("foreground switch invalidates gesture callback",()->{
            Service s=new Service();AtomicReference<String> pkg=new AtomicReference<>("host.example");s.root=root(pkg.get());s.root.add(skip(false));
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(s,new Handler(Looper.getMainLooper()),()->{},pkg::get);
            check(handle(engine,pkg.get(),s.root),"gesture missing");pkg.set("host.other");engine.onPackageEntered(pkg.get());
            Handler.advance(1050);check(!engine.isWaiting(),"stale callback changed new session");engine.close();
        });
        run("complete LiTiaotiao assets remain readable",()->{
            Service s=new Service();RuleEngine core=new RuleEngine(s,new Handler(Looper.getMainLooper()),()->{},()->"host.example");
            check(core.liTiaotiaoPackageCount()==328,"LiTiaotiao entries altered");check(core.liTiaotiaoRuleCount()==795,"LiTiaotiao rules altered");core.close();
        });
        run("platform gesture exception cannot wedge subscription",()->{
            Service s=new Service();String pkg="host.example";s.root=root(pkg);s.root.add(skip(false));s.throwGesture=true;
            GkdSubscriptionEngine engine=new GkdSubscriptionEngine(s,new Handler(Looper.getMainLooper()),()->{},()->pkg);
            check(!handle(engine,pkg,s.root),"rejected gesture reported success");check(!engine.isWaiting(),"rejected gesture wedged engine");engine.close();
        });
        run("screenshot platform exception retries without stuck capture flag",()->{
            Service s=new Service();s.throwScreenshot=true;Handler h=new Handler(Looper.getMainLooper());RuleEngine core=new RuleEngine(s,h,()->{},()->"host.example");
            OcrFallbackController ocr=new OcrFallbackController(s,core,h,()->"host.example");ocr.beginSession("host.example","host.example.Main");
            Handler.advance(2800);check(s.screenshots==1,"first OCR screenshot request absent");Handler.advance(5200);check(s.screenshots==2,"capture flag prevented retry");ocr.stop();core.close();
        });
        run("missing screenshot callback ends at bounded session deadline",()->{
            Service s=new Service();Handler h=new Handler(Looper.getMainLooper());RuleEngine core=new RuleEngine(s,h,()->{},()->"host.example");
            OcrFallbackController ocr=new OcrFallbackController(s,core,h,()->"host.example");ocr.beginSession("host.example","host.example.Main");
            Handler.advance(2800);check(s.screenshots==1,"first capture missing");Handler.advance(14000);
            ocr.beginPopupProbe("host.example","host.example.Dialog");Handler.advance(14700);
            check(s.screenshots==2,"expired capture/session prevented later popup OCR");ocr.stop();core.close();
        });
        run("OCR disabled before due time makes no screenshot",()->{
            Service s=new Service();Handler h=new Handler(Looper.getMainLooper());RuleEngine core=new RuleEngine(s,h,()->{},()->"host.example");
            OcrFallbackController ocr=new OcrFallbackController(s,core,h,()->"host.example");ocr.beginSession("host.example","host.example.Main");
            PreferenceStore.setOcrEnabled(s,false);ocr.onSettingsChanged();Handler.advance(14000);
            check(s.screenshots==0,"disabled OCR still screenshots");ocr.stop();core.close();
        });
        System.out.println("Android backend host cases passed="+passed+" (API doubles; no device or graphics/native execution)");
        RegressionB75Tests.main(new String[0]);
        SdkRuntimeB712Tests.main(new String[0]);
        BehaviorB715Tests.main(new String[0]);
        ProductB716Tests.main(new String[0]);
        RecordLogB716Tests.main(new String[0]);
        PopupSafetyB716Tests.main(new String[0]);
        SubscriptionCancellationB716Tests.main(new String[0]);
        SubscriptionOwnershipB716Tests.main(new String[0]);

    }
}
