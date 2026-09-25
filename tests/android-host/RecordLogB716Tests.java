import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Method;
import org.adguardian.app.log.UserEventLog;

public final class RecordLogB716Tests {
    private static int passed, failed;
    private interface Case { void run() throws Exception; }
    private static final class LogContext extends Context {
        final File directory;
        LogContext() throws Exception { directory=Files.createTempDirectory("adguardian-events-").toFile(); }
        @Override public File getFilesDir() { return directory; }
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    private static void run(String name,Case body) {
        Handler.tasks.clear();SystemClock.now=10_000L;
        try { body.run();passed++;System.out.println("PASS "+name); }
        catch(Throwable error) { failed++;System.out.println("FAIL "+name+": "+error); }
    }
    private static void action(Context context,String pkg,String outcome) throws Exception {
        Method method;
        try { method=UserEventLog.class.getMethod("recordAction",Context.class,String.class,String.class); }
        catch(NoSuchMethodException missing) { throw new AssertionError("package-labelled action logging is missing"); }
        method.invoke(null,context,pkg,outcome);
    }
    private static AutoCloseable watch(Context context,Runnable callback) throws Exception {
        try { return (AutoCloseable)Class.forName("org.adguardian.app.log.UserEventLog$Watch")
                .getConstructor(Context.class,Runnable.class).newInstance(context,callback); }
        catch(ClassNotFoundException missing) { throw new AssertionError("event-driven log observation is missing"); }
    }
    public static void main(String[] args) throws Exception {
        run("consecutive distinct operations with the same outcome are retained",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            UserEventLog.record(context,"广告关闭目标已消失");
            UserEventLog.record(context,"广告关闭目标已消失");
            String[] lines=UserEventLog.read(context).trim().split("\\n");
            check(lines.length==2,"separate operations were discarded by message-only de-duplication");
        });
        run("recent outcomes appear before old history",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            UserEventLog.record(context,"earlier outcome");UserEventLog.record(context,"latest outcome");
            String result=UserEventLog.read(context);
            check(result.indexOf("latest outcome")<result.indexOf("earlier outcome"),"history is oldest-first");
        });
        run("read truncation preserves complete UTF-8 event lines",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            for(int i=0;i<320;i++)UserEventLog.record(context,"记录"+i+"-"+"广".repeat(80));
            String result=UserEventLog.read(context);
            check(!result.contains("\uFFFD"),"read split a UTF-8 code point");
            for(String line:result.split("\\n"))check(line.matches("\\d{2}:\\d{2}:\\d{2}  记录\\d+-广+"),"read returned a partial event line");
            check(result.contains("记录319-"),"latest event missing");
        });
        run("rotation keeps recent events instead of emptying all history",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            for(int i=0;i<360;i++)UserEventLog.record(context,"记录"+i+"-"+"广".repeat(90));
            String result=UserEventLog.read(context);
            check(result.contains("记录359-")&&result.contains("记录300-"),"rotation erased recent retained history");
            check(new File(context.directory,"qunideguanggao-events.log").length()<=96*1024L,"log file exceeded bound");
        });
        run("action records include the installed application label",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            context.pm=new PackageManager(){
                @Override public CharSequence getApplicationLabel(ApplicationInfo info){ return "地图测试"; }
            };
            action(context,"com.example.maps","已尝试关闭广告，结果待确认");
            String result=UserEventLog.read(context);
            check(result.contains("地图测试")&&result.contains("结果待确认"),"app identity or honest attempt outcome missing");
        });
        run("unavailable application labels fall back to the package",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            context.pm=new PackageManager(){
                @Override public ApplicationInfo getApplicationInfo(String pkg,int flags)throws NameNotFoundException{
                    throw new NameNotFoundException();
                }
            };
            action(context,"com.example.hidden","已尝试关闭广告，结果待确认");
            check(UserEventLog.read(context).contains("com.example.hidden"),"package fallback missing");
        });
        run("event observers refresh after writes and clear but stop after close",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            int[] calls={0};String[] observed={""};
            AutoCloseable watch=watch(context,()->{calls[0]++;observed[0]=UserEventLog.read(context);});
            Handler.advance(SystemClock.now);int initial=calls[0];
            UserEventLog.record(context,"visible outcome");
            check(calls[0]==initial,"observer ran on writer thread instead of main handler");
            Handler.advance(SystemClock.now);
            check(calls[0]==initial+1&&observed[0].contains("visible outcome"),"write did not notify active screen");
            UserEventLog.clear(context);Handler.advance(SystemClock.now);
            check(calls[0]==initial+2&&observed[0].equals("暂无拦截记录"),"clear did not notify active screen");
            UserEventLog.record(context,"queued before close");watch.close();Handler.advance(SystemClock.now);
            UserEventLog.record(context,"after close");Handler.advance(SystemClock.now);
            check(calls[0]==initial+2,"closed observer still received log changes");
        });
        run("warning storms remain bounded without suppressing action records",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            UserEventLog.warning(context,"无法确认广告状态");UserEventLog.warning(context,"无法确认广告状态");
            check(UserEventLog.read(context).trim().split("\\n").length==1,"duplicate warning storm was not bounded");
            UserEventLog.record(context,"无法确认广告状态");UserEventLog.record(context,"无法确认广告状态");
            check(UserEventLog.read(context).trim().split("\\n").length==3,"warning suppression discarded distinct action records");
        });
        run("failed appends do not suppress the next writable attempt",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            File target=new File(context.directory,"qunideguanggao-events.log");
            check(target.mkdir(),"failure fixture unavailable");
            UserEventLog.warning(context,"storage recovery warning");
            check(target.delete(),"failure fixture cleanup failed");
            UserEventLog.warning(context,"storage recovery warning");
            check(UserEventLog.read(context).contains("storage recovery warning"),"failed write poisoned duplicate suppression");
        });
        run("silently failed rotations retain history and allow immediate retry",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            File target=new File(context.directory,"qunideguanggao-events.log");
            String history="12:00:00  history\n".repeat(6000);
            Files.writeString(target.toPath(),history,StandardCharsets.UTF_8);
            int[] updates={0};
            try(AutoCloseable observer=watch(context,()->updates[0]++)) {
                android.util.AtomicFile.dropNextCommit=true;
                UserEventLog.warning(context,"rotation recovery warning");Handler.advance(SystemClock.now);
                check(updates[0]==0,"failed rotation reported a committed update");
                check(Files.readString(target.toPath()).equals(history),"failed rotation damaged old history");
                UserEventLog.warning(context,"rotation recovery warning");Handler.advance(SystemClock.now);
                check(UserEventLog.read(context).contains("rotation recovery warning"),"failed rotation suppressed writable retry");
            } finally { android.util.AtomicFile.dropNextCommit=false; }
        });
        run("message length limits never split a Unicode code point",()->{
            LogContext context=new LogContext();UserEventLog.clear(context);
            UserEventLog.record(context,"a".repeat(119)+"\uD83D\uDE00");
            check(!UserEventLog.read(context).contains("?"),"message limit split a surrogate pair");
            context.pm=new PackageManager(){
                @Override public CharSequence getApplicationLabel(ApplicationInfo info){ return "b".repeat(59)+"\uD83D\uDE00"; }
            };
            action(context,"com.example.maps","outcome");
            check(!UserEventLog.read(context).contains("?"),"application label limit split a surrogate pair");
        });
        System.out.println("B7.16 record log regressions passed="+passed+" failed="+failed);
        if(failed>0)throw new AssertionError("record log regressions remain");
    }
}
