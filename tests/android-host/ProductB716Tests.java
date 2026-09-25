import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.adguardian.app.MainActivity;
import org.adguardian.app.engine.RuleEngine;
import org.adguardian.app.ocr.OcrFallbackController;

/** Consumer-visible B7.16 product behavior. */
public final class ProductB716Tests {
    private static int passed;

    private static final class ScreenshotService extends AccessibilityService {
        int screenshots;
        @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
        @Override public void onInterrupt() { }
        @Override public void takeScreenshot(int displayId,java.util.concurrent.Executor executor,
                                             TakeScreenshotCallback callback) {
            screenshots++;
            throw new SecurityException("host test has no system screenshot provider");
        }
    }

    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }

    private static void run(String name,ThrowingRunnable body) throws Exception {
        Handler.tasks.clear();
        SystemClock.now=1000L;
        body.run();
        passed++;
        System.out.println("PASS "+name);
    }

    private static List<String> visibleText(View root) {
        List<String> result=new ArrayList<>();
        collect(root,result);
        return result;
    }

    private static void collect(View view,List<String> result) {
        if(view instanceof TextView text && text.getText()!=null)result.add(text.getText().toString());
        if(view instanceof ViewGroup group)for(View child:group.children)collect(child,result);
    }

    private static View find(View root,String exactText) {
        if(root instanceof TextView text && exactText.contentEquals(text.getText()))return root;
        if(root instanceof ViewGroup group)for(View child:group.children) {
            View found=find(child,exactText);
            if(found!=null)return found;
        }
        return null;
    }

    private static MainActivity launch() throws Exception {
        MainActivity activity=new MainActivity();
        Method create=MainActivity.class.getDeclaredMethod("onCreate",Bundle.class);
        create.setAccessible(true);
        create.invoke(activity,new Bundle());
        return activity;
    }

    public static void main(String[] args) throws Exception {
        run("visible interception history updates while the page stays open",()->{
            MainActivity activity=launch();
            org.adguardian.app.log.UserEventLog.clear(activity);
            Method resume=MainActivity.class.getDeclaredMethod("onResume");
            resume.setAccessible(true);
            resume.invoke(activity);
            org.adguardian.app.log.UserEventLog.record(activity,"下厨房 已执行广告关闭");
            Handler.advance(1000L);
            String visible=String.join("\n",visibleText(((Activity)activity).content));
            check(visible.contains("下厨房 已执行广告关闭"),"open history page did not receive the new record");
            Method pause=MainActivity.class.getDeclaredMethod("onPause");
            pause.setAccessible(true);
            pause.invoke(activity);
            org.adguardian.app.log.UserEventLog.record(activity,"淘宝 已执行广告关闭");
            Handler.advance(1000L);
            String hidden=String.join("\n",visibleText(((Activity)activity).content));
            check(!hidden.contains("淘宝 已执行广告关闭"),"paused history page retains a listener");
            resume.invoke(activity);
            check(String.join("\n",visibleText(((Activity)activity).content)).contains("淘宝 已执行广告关闭"),
                    "returning to the page did not reload saved records");
            pause.invoke(activity);
        });

        run("product screen exposes only the two named rule cores",()->{
            MainActivity activity=launch();
            List<String> text=visibleText(((Activity)activity).content);
            check(text.contains("LTT 核心"),"LTT core label missing");
            check(text.contains("GKD 核心"),"GKD core label missing");
            String all=String.join("\n",text);
            check(!all.contains("手动学习")&&!all.contains("已学习")&&!all.contains("录制记录"),
                    "manual learning remains visible");
            check(!all.contains("CLASS")&&!all.contains("课表增强"),"CLASS adapter remains visible");
            check(!all.contains("演示版")&&!all.contains("复制运行状态"),"demo or diagnostics language remains visible");
        });

        run("usage terms state the real background and coverage contract",()->{
            MainActivity activity=launch();
            View terms=find(((Activity)activity).content,"使用范围与限制");
            check(terms!=null && terms.performClick(),"usage terms cannot be opened");
            String message=AlertDialog.lastMessage;
            check(message.contains("约 70%"),"coverage boundary is missing");
            check(message.contains("保留最近任务") && message.contains("关闭后重新开启辅助功能"),
                    "task removal recovery contract is incomplete");
            check(!message.contains("演示版")&&!message.contains("学习"),"removed product language remains in terms");
        });

        run("OCR fallback starts within three hundred milliseconds after core rules miss",()->{
            ScreenshotService service=new ScreenshotService();
            Handler worker=new Handler(Looper.getMainLooper());
            RuleEngine core=new RuleEngine(service,worker,()->{},()->"host.example");
            OcrFallbackController ocr=new OcrFallbackController(service,core,worker,()->"host.example");
            ocr.beginSession("host.example","host.example.Ad");
            Handler.advance(1300L);
            check(service.screenshots==1,"first OCR fallback still waits longer than 300 ms");
            ocr.stop();
            core.close();
        });

        System.out.println("B7.16 product cases passed="+passed);
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
