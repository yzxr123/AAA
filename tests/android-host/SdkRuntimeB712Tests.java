import android.os.*;import android.view.accessibility.*;import org.adguardian.app.engine.*;import org.adguardian.app.settings.*;
public final class SdkRuntimeB712Tests {
 static final String FULLSCREEN_ACTIVITY="com.bytedance.sdk.openadsdk.core.component.reward.activity.TTFullScreenVideoActivity";
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static Object make(TestSupport.Service s,String pkg)throws Exception{
  Class<?> c;try{c=Class.forName("org.adguardian.app.sdk.SdkAdEngine");}catch(ClassNotFoundException missing){throw new AssertionError("SDK runtime handler missing");}
  Handler h=new Handler(Looper.getMainLooper());
  return c.getConstructor(android.accessibilityservice.AccessibilityService.class,Handler.class,java.util.function.Supplier.class,ActionVerifier.class).newInstance(s,h,(java.util.function.Supplier<String>)()->pkg,new ActionVerifier(s,h,()->pkg,()->{}));
 }
 static boolean handle(Object e,String p,String a,AccessibilityNodeInfo root)throws Exception{
  try(var index=AccessibilityNodeIndex.build(root)){return (boolean)e.getClass().getMethod("handle",String.class,String.class,AccessibilityNodeIndex.class).invoke(e,p,a,index);}
 }
 static TestSupport.Service screen(String text){var s=new TestSupport.Service();s.root=TestSupport.root("host.example","host.example:id/root");s.root.add(TestSupport.node("",text,"android.widget.TextView",800,150,160,80,true));return s;}
 public static void main(String[]args)throws Exception{
  TestSupport.passed=0;
  TestSupport.run("SDK ordinary fullscreen resolves a live explicit skip after initial app hot window",()->{
   var s=screen("3s | 跳过");Object e=make(s,"host.example");SystemClock.now=60000;
   check(handle(e,"host.example",FULLSCREEN_ACTIVITY,s.root),"confirmed fullscreen skip not handled");check(s.root.children.get(0).clicks==1,"live skip not clicked");
  });
  TestSupport.run("SDK package presence does not authorize a normal app page click",()->{
   var s=screen("跳过");Object e=make(s,"host.example");check(!handle(e,"host.example","host.example.MainActivity",s.root),"normal page treated as SDK ad");check(s.root.children.get(0).clicks==0,"normal page clicked");
  });
  TestSupport.run("rewarded video and SDK privacy pages are not force-dismissed",()->{
   var s=screen("关闭");Object e=make(s,"host.example");
   check(!handle(e,"host.example","com.bytedance.sdk.openadsdk.core.component.reward.activity.TTRewardVideoActivity",s.root),"reward page dismissed");
   check(!handle(e,"host.example","com.bytedance.sdk.openadsdk.PrivacyActivity",s.root),"privacy page dismissed");
  });
  TestSupport.run("SDK handler cannot guess an unlabelled corner as an advertisement close",()->{
   var s=screen("");s.root.children.get(0).name="android.widget.ImageView";Object e=make(s,"host.example");
   check(!handle(e,"host.example",FULLSCREEN_ACTIVITY,s.root),"unlabelled corner blindly clicked");
  });
  TestSupport.run("SDK handler declines two distinct close targets",()->{
   var s=screen("关闭");s.root.add(TestSupport.node("","跳过","android.widget.TextView",100,150,150,80,true));Object e=make(s,"host.example");check(!handle(e,"host.example",FULLSCREEN_ACTIVITY,s.root),"ambiguous target clicked");
  });
  TestSupport.run("SDK detection uses namespace boundaries not an arbitrary substring",()->{
   check(!AdSdkSignatures.isAdSdkActivity("host.com.bytedance.sdk.openadsdk.FakeActivity"),"substring is not SDK namespace");
   check(!AdSdkSignatures.isAdSdkActivity("com.kuaishou.weapon.SecurityActivity"),"security SDK identified as ad page");
  });
  TestSupport.run("SDK option is wired through the real rule pipeline independently of GKD",()->{
   var s=screen("跳过");PreferenceStore.setGkdEnabled(s,false);PreferenceStore.setLiTiaotiaoEnabled(s,false);
   var engine=new RuleEngine(s,new Handler(Looper.getMainLooper()),()->{},()->"host.example");
   check(engine.handle("host.example",FULLSCREEN_ACTIVITY,AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,s.root),"SDK helper not wired when GKD is disabled");
   check(s.root.children.get(0).clicks==1,"rule pipeline did not perform SDK action");engine.close();
  });
  TestSupport.run("SDK helper off leaves all SDK helper targets untouched",()->{
   var s=screen("跳过");PreferenceStore.setSdkEnabled(s,false);Object e=make(s,"host.example");
   check(!handle(e,"host.example",FULLSCREEN_ACTIVITY,s.root),"SDK option ignored");check(s.root.children.get(0).clicks==0,"disabled SDK helper clicked");
  });
  System.out.println("B7.12 SDK runtime tests passed="+TestSupport.passed+" failed="+0);if(0>0)throw new AssertionError("SDK runtime regressions");
 }
}
