package org.adguardian.app.runtime;

import android.os.SystemClock;
import java.util.EnumMap;

/** Last bounded state per subsystem. No node references, body text, coordinates or screenshots. */
public final class ScanDiagnostics {
    public enum Area {
        ROOT("当前窗口读取");
        final String title;Area(String title){this.title=title;}
    }
    public enum Reason {
        RULES_UNAVAILABLE("内置课表规则暂时无法加载"), READY("已取得可用页面"), ACTIVITY_UNKNOWN("尚未确认真实页面"),
        VERSION_UNKNOWN("应用版本暂时不可读取"), VERSION_OTHER("应用版本不在定向范围"),
        SCOPE_MISMATCH("前台应用或页面不匹配"), ROOT_UNAVAILABLE("当前应用未提供可读取窗口"),
        ROOT_STALE("当前窗口缓存已失效 正在等待新界面"),
        SIZE_UNKNOWN("当前窗口尺寸不可用"), PROTECTED("当前是受保护页面 不执行广告操作"),
        CHILD_UNAVAILABLE("部分子控件暂时未能读取 请保持页面后重试"),
        TOO_MANY_NODES("页面超过完整采集节点上限"), INSUFFICIENT("页面缺少稳定结构 不能建立安全条件"),
        BUDGET("本轮节点读取超过时间预算"), UNAVAILABLE("系统暂时未返回完整节点信息"),
        NO_CLOSE("未找到可确认的关闭控件"), AMBIGUOUS("存在多个关闭候选 未选择其中任意一个"),
        WAITING("等待前一步操作完成"), ACTION_REQUESTED("已提交关闭操作 等待结果复核"),
        SWITCH_OFF("用户关闭了对应功能"), NOT_AD_PAGE("当前不属于已确认的广告页面");
        final String message;Reason(String message){this.message=message;}
    }
    private static final class Entry {
        final Reason reason;final int nodes,targets;final long time;
        Entry(Reason reason,int nodes,int targets){this.reason=reason;this.nodes=nodes;this.targets=targets;time=SystemClock.uptimeMillis();}
    }
    private static final EnumMap<Area,Entry> entries=new EnumMap<>(Area.class);
    private ScanDiagnostics(){}
    public static synchronized void record(Area area,Reason reason,int nodes,int targets) {
        entries.put(area,new Entry(reason,Math.max(0,nodes),Math.max(0,targets)));
    }
    public static synchronized String explain(Area area) {
        Entry entry=entries.get(area);
        if(entry==null)return "当前页面尚未返回控件 请保持广告显示后重新读取";
        return entry.reason.message+" 已读取 "+entry.nodes+" 个控件 可选目标 "+entry.targets+" 个";
    }
    public static synchronized String read() {
        StringBuilder out=new StringBuilder("最近识别状态 仅在复制时导出 不含页面正文\n");
        for(Area area:Area.values()){
            Entry entry=entries.get(area);if(entry==null)continue;
            out.append(area.title).append(' ').append(entry.reason.message).append(" 节点 ").append(entry.nodes)
                    .append(" 候选 ").append(entry.targets).append(" 开机毫秒 ").append(entry.time).append('\n');
        }
        return out.toString();
    }
}
