import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.adguardian.app.engine.AccessibilityNodeIndex;
import org.adguardian.app.gkd.GkdNodeAdapter;
import org.adguardian.app.gkd.GkdSubscriptionEngine;
import org.adguardian.gkd.RuleMatch;
import org.adguardian.gkd.RuleSession;

/** User input cancels pending GKD work, not the rule's execution budgets. */
public final class SubscriptionCancellationB716Tests {
    private static final String PACKAGE="host.example";

    private static final class Fixture implements AutoCloseable {
        final TestSupport.Service service=new TestSupport.Service();
        final RuleSession<AccessibilityNodeInfo> session;
        final GkdSubscriptionEngine engine;
        Fixture(Map<String,Object> options,List<Map<String,Object>> rules) throws Exception {
            Map<String,Object> group=new java.util.HashMap<>(options);
            group.put("key",1);group.put("name","分段广告-测试");group.put("rules",rules);
            session=new RuleSession<>(PACKAGE,List.of(group),List.of());
            engine=new GkdSubscriptionEngine(service,new Handler(Looper.getMainLooper()),()->{},()->PACKAGE);
            engine.onPackageEntered(PACKAGE);
            Field field=GkdSubscriptionEngine.class.getDeclaredField("session");
            field.setAccessible(true);field.set(engine,session);
        }
        RuleMatch<AccessibilityNodeInfo> query(String id,long now) {
            AccessibilityNodeInfo root=TestSupport.root(PACKAGE,"root");
            if(id!=null)root.add(TestSupport.node(PACKAGE+":id/"+id,"关闭","android.widget.Button",900,100,80,50,true));
            try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(root)) {
                return session.find(root,new GkdNodeAdapter(index),PACKAGE+".Main",1,now,Set.of("分段广告"));
            }
        }
        @Override public void close() {engine.close();}
    }
    private static Map<String,Object> rule(int key,String id) {
        return Map.of("key",key,"matches","[vid=\""+id+"\"]");
    }

    public static void main(String[] args) {
        TestSupport.run("GKD user cancellation preserves shared app maximum",()->{
            try(Fixture f=new Fixture(Map.of("resetMatch","app","actionMaximum",1,"actionMaximumKey",0),
                    List.of(rule(0,"one"),rule(1,"two")))) {
                RuleMatch<AccessibilityNodeInfo> first=f.query("one",1000);
                TestSupport.check(first!=null,"fixture did not match first action");
                f.session.complete(first,1000,true);
                f.engine.cancelPendingActions();
                TestSupport.check(f.query("two",5000)==null,"user click reset shared action maximum and reopened the menu");
                f.engine.reset();
                TestSupport.check(f.query("two",5100)!=null,"full lifecycle reset no longer starts a new app session");
            }
        });
        TestSupport.run("GKD user cancellation preserves shared action cooldown",()->{
            try(Fixture f=new Fixture(Map.of("actionCd",1000,"actionCdKey",0),List.of(rule(0,"one"),rule(1,"two")))) {
                f.session.complete(f.query("one",1000),1000,true);
                f.engine.cancelPendingActions();
                TestSupport.check(f.query("two",1100)==null,"user click bypassed shared cooldown");
                TestSupport.check(f.query("two",2000)!=null,"preserved cooldown never expires");
            }
        });
        TestSupport.run("GKD user cancellation does not restart expired match time",()->{
            try(Fixture f=new Fixture(Map.of("matchTime",500),List.of(rule(0,"one")))) {
                TestSupport.check(f.query(null,1000)==null,"empty tree unexpectedly matched");
                f.engine.cancelPendingActions();
                TestSupport.check(f.query("one",1600)==null,"user click restarted expired rule matching window");
            }
        });
        TestSupport.run("GKD user cancellation discards continuation proof",()->{
            try(Fixture f=new Fixture(Map.of(),List.of(rule(0,"one"),
                    Map.of("key",1,"matches","[vid=\"two\"]","preKeys",List.of(0))))) {
                f.session.complete(f.query("one",1000),1000,true);
                f.engine.cancelPendingActions();
                TestSupport.check(f.query("two",1100)==null,"user input left a stale automatic continuation authorized");
            }
        });
        TestSupport.run("GKD user cancellation invalidates already returned matches",()->{
            try(Fixture f=new Fixture(Map.of("actionMaximum",1),List.of(rule(0,"one")))) {
                RuleMatch<AccessibilityNodeInfo> stale=f.query("one",1000);
                f.engine.cancelPendingActions();
                f.session.complete(stale,1100,true);
                TestSupport.check(f.query("one",1200)!=null,"stale callback consumed the surviving rule budget");
            }
        });
        TestSupport.run("GKD user cancellation preserves failed action backoff",()->{
            try(Fixture f=new Fixture(Map.of(),List.of(rule(0,"one")))) {
                f.session.complete(f.query("one",1000),1000,false);
                f.engine.cancelPendingActions();
                TestSupport.check(f.query("one",1100)==null,"user click erased failed action backoff");
                TestSupport.check(f.query("one",1300)!=null,"failed action backoff never expires");
            }
        });
        TestSupport.run("GKD quarantined target stops polling without blocking a distinct target",()->{
            try(Fixture f=new Fixture(Map.of(),List.of(Map.of("key",0,"matches","[text=\"关闭\"]")))) {
                AccessibilityNodeInfo first=TestSupport.node(PACKAGE+":id/first_close","关闭","android.widget.Button",900,100,80,50,true);
                f.service.root=TestSupport.root(PACKAGE,"first_page");f.service.root.add(first);
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(f.service.root)) {
                    TestSupport.check(f.engine.handle(PACKAGE,PACKAGE+".Main",index),"first target was not handled");
                }
                Handler.advance(10150);Handler.advance(10350);Handler.advance(10900);
                SystemClock.now=11000;
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(f.service.root)) {
                    TestSupport.check(!f.engine.handle(PACKAGE,PACKAGE+".Main",index),"failed unchanged target was retried");
                }
                TestSupport.check(first.clicks==1,"quarantined target was clicked again");
                TestSupport.check(f.engine.nextWakeUp()<0,"quarantined target kept an autonomous rescan loop alive");
                AccessibilityNodeInfo second=TestSupport.node(PACKAGE+":id/second_close","关闭","android.widget.Button",900,100,80,50,true);
                f.service.root.add(second);
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(f.service.root)) {
                    TestSupport.check(f.engine.handle(PACKAGE,PACKAGE+".Main",index),"same rule could not handle a distinct target while the quarantined target remained");
                }
                TestSupport.check(second.clicks==1,"distinct target never received its safe close action");
            }
        });
        TestSupport.run("GKD skips a quarantined opener and preserves proof for its distinct closer",()->{
            try(Fixture f=new Fixture(Map.of(),List.of(rule(0,"first_close"),Map.of(
                    "key",1,"matches","[vid=\"second_close\"]","preKeys",List.of(0))))) {
                AccessibilityNodeInfo opener=TestSupport.node(PACKAGE+":id/first_close","关闭","android.widget.Button",900,100,80,50,true);
                f.service.root=TestSupport.root(PACKAGE,"page");f.service.root.add(opener);
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(f.service.root)) {
                    TestSupport.check(f.engine.handle(PACKAGE,PACKAGE+".Main",index),"opener action did not establish continuation proof");
                }
                Handler.advance(10150);Handler.advance(10350);Handler.advance(10900);
                SystemClock.now=11000;
                AccessibilityNodeInfo closer=TestSupport.node(PACKAGE+":id/second_close","关闭","android.widget.Button",800,200,80,50,true);
                f.service.root.add(closer);
                try(AccessibilityNodeIndex index=AccessibilityNodeIndex.build(f.service.root)) {
                    TestSupport.check(f.engine.handle(PACKAGE,PACKAGE+".Main",index),
                            "quarantined first match starved the distinct continuation rule");
                }
                TestSupport.check(opener.clicks==1 && closer.clicks==1,
                        "quarantined opener was repeated or valid closer lost its predecessor proof");
            }
        });
    }
}
