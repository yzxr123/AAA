package org.adguardian.app.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Short private lifecycle breadcrumbs, included only when the user copies diagnostics. */
public final class RuntimeHealth {
    private static long lastFailure;
    private RuntimeHealth() { }
    public static synchronized void event(Context context,String state) {
        try {
            SharedPreferences p=context.getSharedPreferences("runtime_health",Context.MODE_PRIVATE);
            String previous=p.getString("lifecycle","");
            String next=previous+System.currentTimeMillis()+" "+state+"\n";
            if(next.length()>4096) {next=next.substring(next.length()-3500);int newline=next.indexOf('\n');if(newline>=0)next=next.substring(newline+1);}
            p.edit().putString("lifecycle",next).apply();
        } catch(RuntimeException unavailable) { }
    }
    public static synchronized void failure(Context context,String stage,RuntimeException error) {
        long now=SystemClock.uptimeMillis();
        if(lastFailure>0 && now-lastFailure<8000L)return;
        lastFailure=now;
        StringBuilder text=new StringBuilder(stage).append(' ').append(error.getClass().getSimpleName());
        int count=0;
        for(StackTraceElement frame:error.getStackTrace()) {
            if(frame.getClassName().startsWith("org.adguardian.") && count++<3)
                text.append(' ').append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
        }
        event(context,text.toString());
    }
    public static String read(Context context) {
        try {return context.getSharedPreferences("runtime_health",Context.MODE_PRIVATE).getString("lifecycle","");}
        catch(RuntimeException unavailable){return "系统暂时无法读取历史状态\n";}
    }
}
