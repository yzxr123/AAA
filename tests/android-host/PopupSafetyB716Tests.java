import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.engine.ActionVerifier;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.GkdAssistEngine;
import org.adguardian.app.litiaotiao.LiTiaotiaoRuleEngine;
import org.adguardian.app.log.UserEventLog;
import org.adguardian.app.settings.PreferenceStore;

/** Android API doubles; exercises real action gates, never substitutes the rule engines. */
public final class PopupSafetyB716Tests {
    private static int passed,failed;
    private static final String PKG="popup.example";
    private static final class Service extends AccessibilityService {
        @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
        @Override public void onInterrupt() { }
    }
    private interface Case { void run() throws Exception; }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private static void run(String name,Case body) {
        Handler.tasks.clear();SystemClock.now=1000L;
        try {body.run();passed++;System.out.println("PASS "+name);}
        catch(Throwable error) {failed++;System.out.println("FAIL "+name+": "+error);}
    }
    private static Service service() {
        Service s=new Service();s.root=new AccessibilityNodeInfo();s.root.pkg=PKG;
        s.root.name="android.widget.FrameLayout";s.root.clickable=false;
        s.root.bounds=new Rect(0,0,1080,2400);return s;
    }
    private static AccessibilityNodeInfo node(String id,String text,int x) {
        AccessibilityNodeInfo n=new AccessibilityNodeInfo();n.id=id;n.text=text;
        n.name="android.widget.TextView";n.bounds=new Rect(x,80,x+90,150);return n;
    }
    private static ActionVerifier verifier(Service s) {
        return new ActionVerifier(s,new Handler(Looper.getMainLooper()),()->PKG,()->{});
    }
    private static ActionVerifier.Ticket capture(ActionVerifier v,Service s,String key,AccessibilityNodeInfo n) {
        try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(s.root)) {
            return v.capture(PKG,key,"click",n,index,null);
        }
    }
    private static void fail(ActionVerifier v,Service s,AccessibilityNodeInfo n) {
        ActionVerifier.Ticket ticket=capture(v,s,"ltt:first",n);
        check(ticket!=null,"initial safe target rejected");v.accepted(ticket);
        Handler.advance(1150);Handler.advance(1350);Handler.advance(1900);
        check(!v.isPending(),"verification did not finish");
    }
    private static boolean assist(Service s) {
        GkdAssistEngine engine=new GkdAssistEngine(s);
        try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(s.root)) {
            return engine.handle(PKG,PKG+".Main",AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,index);
        }
    }
    private static LiTiaotiaoRuleEngine ltt(Service s) {
        LiTiaotiaoRuleEngine e=new LiTiaotiaoRuleEngine(s,new Handler(Looper.getMainLooper()),()->PKG);
        e.onPackageEntered(PKG);return e;
    }
    private static boolean handle(LiTiaotiaoRuleEngine e,Service s) {
        try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(s.root)){return e.handle(PKG,s.root,index);}
    }
    @SuppressWarnings("unchecked")
    private static void popupRule(LiTiaotiaoRuleEngine e,String action) throws Exception {
        Field f=LiTiaotiaoRuleEngine.class.getDeclaredField("repository");f.setAccessible(true);Object repo=f.get(e);
        Method parse=repo.getClass().getDeclaredMethod("parseBundle",String.class);parse.setAccessible(true);
        Object bundle=parse.invoke(repo,"{\"keywords\":[],\"popup_rules\":[{\"id\":\"广告证据\",\"action\":\""+action+"\"}]}");
        Field byHash=repo.getClass().getDeclaredField("byHash");byHash.setAccessible(true);
        ((Map<String,Object>)byHash.get(repo)).put(Integer.toString(PKG.hashCode()),bundle);
    }
    public static void main(String[] args) {
        run("failed target stays blocked across engines",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            ActionVerifier v=verifier(s);fail(v,s,n);
            check(capture(v,s,"assist:other",n)==null,"same node retried under a different rule key");
        });
        run("failed target stays blocked across recreated windows",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            ActionVerifier v=verifier(s);fail(v,s,n);s.root.window=2;n.window=2;
            check(capture(v,s,"ltt:first",n)==null,"window recreation bypassed target quarantine");
        });
        run("elapsed time alone never re-arms an unchanged failed target",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            ActionVerifier v=verifier(s);fail(v,s,n);Handler.advance(60000);
            check(capture(v,s,"ltt:first",n)==null,"unchanged target automatically retried after cooldown");
        });
        run("failure quarantine leaves distinct sequence targets actionable",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_menu","广告菜单",900);s.root.add(n);
            ActionVerifier v=verifier(s);fail(v,s,n);
            AccessibilityNodeInfo second=node(PKG+":id/dislike","不感兴趣",300);s.root.add(second);
            check(capture(v,s,"gkd:step2",second)!=null,"one failure blocked the entire foreground");
            v.onPackageChanged();check(capture(v,s,"ltt:first",n)!=null,"package change did not start a new ad session");
        });
        run("failure quarantine does not disable a different target of the same rule",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            ActionVerifier v=verifier(s);fail(v,s,n);
            AccessibilityNodeInfo second=node(PKG+":id/other_close","关闭广告",100);s.root.add(second);
            check(capture(v,s,"ltt:first",second)!=null,"failed target disabled unrelated target sharing its rule");
        });
        run("target disappearance does not produce a second unsupported success record",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            UserEventLog.clear(s);ActionVerifier v=verifier(s);v.accepted(capture(v,s,"test",n));
            String accepted=UserEventLog.read(s);check(accepted.contains("测试应用"),"accepted action was not recorded with app identity");
            s.root.children.clear();Handler.advance(1150);Handler.advance(1350);
            check(accepted.equals(UserEventLog.read(s)),"target disappearance generated an unproved ad-close success");
        });
        run("cancelling verification preserves the accepted action record",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            UserEventLog.clear(s);ActionVerifier v=verifier(s);v.accepted(capture(v,s,"test",n));v.cancel();
            Handler.advance(5000);check(UserEventLog.read(s).contains("测试应用"),"cancellation lost the actual operation record");
        });
        run("user cancellation cannot re-arm an accepted unverified target",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            ActionVerifier v=verifier(s);v.accepted(capture(v,s,"test",n));Handler.advance(1700);v.cancel();
            check(capture(v,s,"other:engine",n)==null,"manual dismissal re-armed the automatic popup opener");
            AccessibilityNodeInfo different=node(PKG+":id/new_close","关闭广告",100);s.root.add(different);
            check(capture(v,s,"test",different)!=null,"cancellation blocked a different ad target");
        });
        run("assist cannot guess an unlabeled edge icon is a close control",()->{
            Service s=service();AccessibilityNodeInfo icon=node(null,null,900);icon.name="android.widget.ImageView";
            s.root.add(icon).add(node(null,"广告",400));
            check(!assist(s)&&icon.clicks==0&&s.gestures==0,"ad evidence made an anonymous icon actionable");
        });
        run("assist cannot globally click ordinary close words beside an ad",()->{
            Service s=service();AccessibilityNodeInfo close=node(null,"关闭",900);
            s.root.add(close).add(node(null,"广告",400));
            check(!assist(s)&&close.clicks==0&&s.gestures==0,"global ordinary close label was guessed");
        });
        run("assist requires a unique explicit close target",()->{
            Service s=service();AccessibilityNodeInfo a=node(null,"关闭广告",900),b=node(null,"关闭广告",100);
            s.root.add(a).add(b);check(!assist(s)&&a.clicks==0&&b.clicks==0,"ambiguous targets were ranked into a click");
        });
        run("assist never clicks an oversized advertisement parent",()->{
            Service s=service();s.root.clickable=true;AccessibilityNodeInfo close=node(null,"关闭广告",900);close.clickable=false;s.root.add(close);
            assist(s);check(s.root.clicks==0,"leaf evidence escalated to a full-screen clickable parent");
        });
        run("assist discards a target after its window loses ownership",()->{
            Service s=service();AccessibilityNodeInfo old=s.root,n=node(PKG+":id/ad_close","关闭广告",900);old.add(n);
            GkdAssistEngine engine=new GkdAssistEngine(s);
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(old)) {
                s.root=service().root;s.root.window=2;
                check(!engine.handle(PKG,PKG+".Main",32,index),"stale window reported handled");
            }
            check(n.clicks==0&&s.gestures==0,"assist touched the target after a window switch");
        });
        run("assist rechecks its switch after a blocking root read",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            s.root.onRefresh=()->PreferenceStore.setGkdEnabled(s,false);
            check(!assist(s)&&n.clicks==0&&s.gestures==0,"GKD disabled during root read but assist still acted");
        });
        run("assist rechecks the ad category after a blocking root read",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_skip","跳过广告",900);s.root.add(n);
            s.root.onRefresh=()->PreferenceStore.setTypeEnabled(s,AdType.STARTUP,false);
            check(!assist(s)&&n.clicks==0&&s.gestures==0,"startup disabled during root read but assist still acted");
        });
        run("assist rechecks ownership before falling back from dismiss to click",()->{
            Service s=service();AccessibilityNodeInfo n=new AccessibilityNodeInfo() {
                @Override public int getActions(){return ACTION_DISMISS;}
                @Override public boolean performAction(int action){
                    if(action==ACTION_DISMISS){PreferenceStore.setGkdEnabled(s,false);return false;}
                    return super.performAction(action);
                }
            };
            n.text="关闭广告";n.bounds=new Rect(900,80,990,150);s.root.add(n);
            check(!assist(s)&&n.clicks==0&&s.gestures==0,"dismiss disabled GKD but fallback click still ran");
        });
        run("assist rechecks ownership before trying a second scroll container",()->{
            Service s=service();s.root.scrollable=true;SystemClock.now=5000;
            AccessibilityNodeInfo container=new AccessibilityNodeInfo(){
                @Override public boolean performAction(int action){PreferenceStore.setGkdEnabled(s,false);return false;}
            };
            container.clickable=false;container.scrollable=true;container.bounds=new Rect(0,0,1080,1200);
            container.add(node(null,"广告",300));s.root.add(container);
            check(!assist(s)&&s.root.clicks==0,"rejected first scroll disabled GKD but fallback scroll still ran");
        });
        run("LTT startup matching excludes normal video skip settings",()->{
            Service s=service();AccessibilityNodeInfo skip=node(null,"跳过视频片头",900);s.root.add(skip);
            check(!handle(ltt(s),s)&&skip.clicks==0&&s.gestures==0,"substring fallback touched ordinary UI");
        });
        run("LTT global startup fallback expires after app entry",()->{
            Service s=service();LiTiaotiaoRuleEngine e=ltt(s);Handler.advance(60000);
            AccessibilityNodeInfo skip=node(null,"跳过",900);s.root.add(skip);
            check(!handle(e,s)&&skip.clicks==0,"startup keyword remained active throughout the session");
        });
        run("LTT never clicks an oversized advertisement parent",()->{
            Service s=service();s.root.clickable=true;AccessibilityNodeInfo skip=node(null,"跳过广告",900);skip.clickable=false;s.root.add(skip);
            handle(ltt(s),s);check(s.root.clicks==0,"LTT clicked full-screen parent instead of close control");
        });
        run("LTT rechecks the ad category after a blocking root read",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_skip","跳过广告",900);s.root.add(n);
            s.root.onRefresh=()->PreferenceStore.setTypeEnabled(s,AdType.STARTUP,false);
            check(!handle(ltt(s),s)&&n.clicks==0&&s.gestures==0,"startup disabled during root read but LTT still acted");
        });
        run("LTT rejects unverified coordinate actions",()->{
            Service s=service();s.root.add(node(null,"广告证据",200));LiTiaotiaoRuleEngine e=ltt(s);popupRule(e,"x:50 y:100");
            check(!handle(e,s)&&s.gestures==0,"LTT used a blind coordinate without a live close target");
        });
        run("LTT rejects automatic generic Back",()->{
            Service s=service();s.root.add(node(null,"广告证据",200));LiTiaotiaoRuleEngine e=ltt(s);popupRule(e,"GLOBAL_ACTION_BACK");
            check(!handle(e,s)&&s.backActions==0,"LTT performed Back without a safe live close target");
        });
        run("explicit unique ad close remains immediate",()->{
            Service s=service();AccessibilityNodeInfo n=node(PKG+":id/ad_close","关闭广告",900);s.root.add(n);
            check(assist(s)&&n.clicks==1,"safe unique close no longer acts on first scan");
            check(SystemClock.now==1000,"safe close acquired an artificial delay");
        });
        System.out.println("Popup safety cases passed="+passed+" failed="+failed);
        if(failed>0)throw new AssertionError("Popup safety regressions="+failed);
    }
}
