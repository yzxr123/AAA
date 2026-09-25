package org.adguardian.app.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Window events also name Dialog and View classes. Only validated Activity candidates set the page. */
public final class ActivityTracker {
    private String pkg="",resolved="";
    private long epoch;
    private final ArrayList<String> candidates=new ArrayList<>();
    public synchronized void enter(String app) {
        if(!pkg.equals(app)){pkg=app;resolved="";candidates.clear();epoch++;}
    }
    public synchronized void note(String app,String name) {
        enter(app);
        if(name==null || name.isEmpty() || name.length()>512)return;
        if(!candidates.isEmpty() && candidates.get(candidates.size()-1).equals(name))return;
        candidates.remove(name);candidates.add(name);epoch++;
        if(candidates.size()>8)candidates.remove(0);
    }
    public String resolve(String app,Predicate<String> isActivity) {
        List<String> copy;long token;
        synchronized(this){if(!pkg.equals(app))return "";copy=new ArrayList<>(candidates);token=epoch;}
        String found="";
        for(int n=copy.size()-1;n>=0;n--)if(isActivity.test(copy.get(n))){found=copy.get(n);break;}
        synchronized(this){
            if(token!=epoch || !pkg.equals(app))return "";
            if(!found.isEmpty())resolved=found;
            return resolved;
        }
    }
    public synchronized void clear(){pkg="";resolved="";candidates.clear();epoch++;}
}
