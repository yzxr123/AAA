package org.adguardian.app.engine;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

public final class NodeTarget {
    public final String id, name, label;
    public final float x,y,width,height;
    public NodeTarget(String id,String name,String label,float x,float y,float width,float height) {
        this.id=id;this.name=name;this.label=label;this.x=x;this.y=y;this.width=width;this.height=height;
    }
    public static NodeTarget capture(AccessibilityNodeInfo node,Rect screen) {
        return capture(node,screen,false);
    }
    public static NodeTarget captureSelected(AccessibilityNodeInfo node,Rect screen) {
        return capture(node,screen,true);
    }
    private static NodeTarget capture(AccessibilityNodeInfo node,Rect screen,boolean selected) {
        if(node==null || screen.width()<=0 || screen.height()<=0 || node.isPassword() || node.isEditable())return null;
        String id=string(node.getViewIdResourceName());
        String name=string(node.getClassName());
        String label=string(node.getText());
        if(label.isEmpty())label=string(node.getContentDescription());
        if(label.length()>80)label="";
        if(!selected && id.isEmpty() && label.isEmpty())return null;
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        if(bounds.width()<=0 || bounds.height()<=0)return null;
        return new NodeTarget(id,name,label,(bounds.exactCenterX()-screen.left)/screen.width(),
                (bounds.exactCenterY()-screen.top)/screen.height(),
                (float)bounds.width()/screen.width(),(float)bounds.height()/screen.height());
    }
    public boolean isSafeLearningTarget() {
        String lower=(id+" "+label+" "+name).toLowerCase(Locale.ROOT);
        if(lower.contains("edittext") || lower.contains("password") || lower.contains("支付") || lower.contains("付款")
                || lower.contains("登录") || lower.contains("授权") || lower.contains("同意") || lower.contains("permission") || lower.contains("下载") || lower.contains("安装") || lower.contains("购买") || lower.contains("领取") || lower.contains("打开"))return false;
        if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)
                || x<0 || y<0 || x>1 || y>1 || width<=0 || height<=0 || width>0.55f || height>0.22f)return false;
        return !id.isEmpty() || AdCloseText.isClose(label)
                || (id.isEmpty() && label.isEmpty() && width<=0.20f && height<=0.12f
                    && (name.endsWith("ImageView") || name.endsWith("ImageButton") || name.equals("android.view.View")));
    }
    public NodeTarget forLearning() {
        String safe=AdCloseText.isClose(label)?normalizeClose(label):"";
        return new NodeTarget(id,name,safe,x,y,width,height);
    }
    public boolean matches(AccessibilityNodeInfo node,Rect screen,boolean strictPosition) {
        if(node==null || !node.isVisibleToUser() || node.isPassword() || node.isEditable())return false;
        if(!name.isEmpty() && !name.contentEquals(string(node.getClassName())))return false;
        if(!id.isEmpty()) {
            if(!id.equals(string(node.getViewIdResourceName())))return false;
        } else if(!label.isEmpty() && !sameLabel(label,string(node.getText()))
                && !sameLabel(label,string(node.getContentDescription())))return false;
        else if(id.isEmpty() && label.isEmpty())strictPosition=true;
        if(!strictPosition)return true;
        if(!label.isEmpty() && !sameLabel(label,string(node.getText()))
                && !sameLabel(label,string(node.getContentDescription())))return false;
        Rect b=new Rect();node.getBoundsInScreen(b);
        if(screen.width()<=0 || screen.height()<=0 || b.width()<=0 || b.height()<=0)return false;
        float nx=(b.exactCenterX()-screen.left)/screen.width();
        float ny=(b.exactCenterY()-screen.top)/screen.height();
        return Math.abs(nx-x)<=0.065f && Math.abs(ny-y)<=0.075f
                && Math.abs((float)b.width()/screen.width()-width)<=0.07f
                && Math.abs((float)b.height()/screen.height()-height)<=0.07f;
    }
    public Iterable<AccessibilityNodeInfo> candidates(AccessibilityNodeIndex index) {
        if(!id.isEmpty()){java.util.List<AccessibilityNodeInfo> found=index.fastQuery(index.root(),"id",id);return found.isEmpty()?index.nodes():found;}
        if(label.isEmpty())return index.nodes();
        java.util.List<AccessibilityNodeInfo> found=index.fastQuery(index.root(),"text",normalizeClose(label));
        return found.isEmpty()?index.nodes():found;
    }
    private static boolean sameLabel(String expected,String actual) {
        return expected.equals(actual) || (!expected.isEmpty() && normalizeClose(expected).equals(normalizeClose(actual)));
    }
    public static String normalizeClose(String value) {
        return AdCloseText.normalize(value);
    }
    public static String string(CharSequence value) { return value==null?"":value.toString().trim(); }
}
