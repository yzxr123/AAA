import android.content.Context;
import org.adguardian.app.engine.JumpGuard;
import org.adguardian.app.ocr.OcrActionPolicy;
import org.adguardian.app.ocr.OcrTextEvidence;
import org.adguardian.app.settings.PreferenceStore;

/** B7.15 behavior checks: no timer-based guessing and no false sensor claim. */
public final class BehaviorB715Tests {
    private static int passed;

    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }

    private static void run(String name,Runnable body) {
        body.run();
        passed++;
        System.out.println("PASS "+name);
    }

    public static void main(String[] args) {
        run("verified ad-destination jump is one-shot with Taobao focus",()->{
            JumpGuard guard=new JumpGuard("org.adguardian.app");
            check(guard.arm("video.example",true,false),"strong jump evidence was not accepted");
            check(guard.shouldReturn("video.example","com.taobao.taobao"),"verified Taobao transition was not intercepted");
            check(!guard.shouldReturn("video.example","com.taobao.taobao"),"consumed evidence was reused");

            check(guard.arm("video.example",false,true),"shake-ad evidence was not accepted");
            check(guard.shouldReturn("video.example","com.tmall.wireless"),"second explicit target app was not covered");

            check(guard.arm("video.example",true,false),"common-ad destination evidence was not accepted");
            check(guard.shouldReturn("video.example","com.jingdong.app.mall"),"multi-app destination list did not cover JD");
        });

        run("user input and unrelated transition cancel jump evidence",()->{
            JumpGuard guard=new JumpGuard("org.adguardian.app");
            guard.arm("video.example",true,false);
            guard.onUserInteraction();
            check(!guard.shouldReturn("video.example","com.taobao.taobao"),"real user input did not cancel rollback");

            guard.arm("video.example",true,false);
            check(!guard.shouldReturn("video.example","reader.example"),"unlisted destination was intercepted");
            check(!guard.shouldReturn("video.example","com.taobao.taobao"),"stale evidence survived the first transition");
        });

        run("source and protected destinations are exact",()->{
            JumpGuard guard=new JumpGuard("org.adguardian.app");
            check(!guard.arm("org.adguardian.app",true,false),"self became an advertisement source");
            check(!guard.arm("com.eg.android.AlipayGphone",true,false),"payment app became an automatic jump source");
            check(!guard.arm("com.android.settings",true,false),"system settings became an automatic jump source");
            guard.arm("video.example",true,false);
            check(!guard.shouldReturn("other.example","com.taobao.taobao"),"different source consumed another app's evidence");
            guard.arm("video.example",true,false);
            check(!guard.shouldReturn("video.example","com.android.settings"),"settings transition was treated as an ad jump");
        });

        run("OCR evidence obeys the matching category switch",()->{
            check(OcrActionPolicy.shouldClose(false,false,true,false,false,true,false),"enabled jump evidence did not close");
            check(!OcrActionPolicy.shouldClose(true,true,true,false,true,false,true),"disabled jump category leaked through generic ad evidence");
            check(OcrActionPolicy.shouldClose(false,false,false,true,false,false,true),"enabled shake evidence did not close");
            check(!OcrActionPolicy.shouldClose(true,true,false,true,true,true,false),"disabled shake category leaked through generic ad evidence");
            check(OcrActionPolicy.shouldClose(true,false,false,false,true,false,false),"ordinary ad evidence ignored enabled close categories");
        });

        run("Taobao and shake wording are explicit OCR evidence",()->{
            check(OcrTextEvidence.jump("点击打开淘宝查看商品"),"Taobao jump wording was not recognized");
            check(OcrTextEvidence.jump("前往天猫继续"),"Tmall jump wording was not recognized");
            check(OcrTextEvidence.shake("摇一摇或点击进入广告"),"shake wording was not recognized");
            check(!OcrTextEvidence.jump("打开应用设置"),"ordinary settings wording became ad-jump evidence");
        });

        run("task-removal notice is delivered once on next open",()->{
            Context context=new Context();
            check(!PreferenceStore.consumeTaskRemoved(context),"fresh install reported a removed task");
            PreferenceStore.markTaskRemoved(context);
            check(PreferenceStore.isTaskRemoved(context),"runtime did not pause after task removal");
            check(PreferenceStore.consumeTaskRemoved(context),"removed task was not reported");
            check(!PreferenceStore.isTaskRemoved(context),"reopening did not release the task-removal pause");
            check(!PreferenceStore.consumeTaskRemoved(context),"same removal notice repeated indefinitely");
        });

        System.out.println("B7.15 behavior cases passed="+passed);
    }
}
