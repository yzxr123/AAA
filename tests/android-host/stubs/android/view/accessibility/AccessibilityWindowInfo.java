package android.view.accessibility;
public class AccessibilityWindowInfo {
 public static final int TYPE_APPLICATION=1,TYPE_INPUT_METHOD=2,TYPE_SYSTEM=3,TYPE_ACCESSIBILITY_OVERLAY=4;
 public int id,type=TYPE_APPLICATION,layer;public boolean active,focused;public AccessibilityNodeInfo root;
 public int getId(){return id;}public int getType(){return type;}public int getLayer(){return layer;}
 public boolean isActive(){return active;}public boolean isFocused(){return focused;}
 public AccessibilityNodeInfo getRoot(){return root;}public void recycle(){}
}
