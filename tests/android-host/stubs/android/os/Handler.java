package android.os;import java.util.*;public class Handler {
public static final List<Task> tasks=new ArrayList<>();public static class Task{Handler owner;Runnable r;long t;Task(Handler o,Runnable r,long t){owner=o;this.r=r;this.t=t;}}
private Looper looper;public Handler(Looper l){looper=l;}public Looper getLooper(){return looper;}
public boolean post(Runnable r){return postAtTime(r,SystemClock.now);}public boolean postDelayed(Runnable r,long d){return postAtTime(r,SystemClock.now+d);}
public boolean postAtTime(Runnable r,long t){tasks.add(new Task(this,r,t));return true;}
public void removeCallbacks(Runnable r){tasks.removeIf(t->t.owner==this&&t.r==r);}public void removeCallbacksAndMessages(Object token){tasks.removeIf(t->t.owner==this);}
public static void advance(long now){SystemClock.now=now;int guard=0;while(true){Task next=tasks.stream().filter(t->t.t<=now).min(Comparator.comparingLong(t->t.t)).orElse(null);if(next==null)return;tasks.remove(next);next.r.run();if(++guard>1000)throw new AssertionError("unbounded handler loop");}}
}