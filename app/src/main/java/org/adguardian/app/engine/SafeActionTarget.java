package org.adguardian.app.engine;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** Safety checks shared by automatic close-button handlers. */
public final class SafeActionTarget {
    private SafeActionTarget() { }

    public static Rect bounds(AccessibilityNodeInfo node) {
        Rect result = new Rect();
        node.getBoundsInScreen(result);
        return result;
    }

    private static boolean safeDescriptor(NodeTarget target) {
        if (target == null) return false;
        if (target.isSafeLearningTarget()) return true;
        if (!target.id.isEmpty() || !target.label.isEmpty()) return false;
        NodeTarget surrogate = new NodeTarget("", "android.view.View", "",
                target.x, target.y, target.width, target.height);
        return surrogate.isSafeLearningTarget()
                && (target.name.equals("android.widget.LinearLayout")
                || target.name.equals("android.widget.RelativeLayout")
                || target.name.equals("android.widget.FrameLayout"));
    }

    private static boolean protectedLabel(CharSequence value) {
        String label = NodeTarget.string(value);
        return label.equals("确认支付") || label.equals("立即支付") || label.equals("支付密码")
                || label.equals("付款码") || label.equals("允许本次使用") || label.equals("授予权限")
                || label.equals("指纹支付") || label.equalsIgnoreCase("Confirm payment")
                || label.equalsIgnoreCase("Payment password");
    }

    public static boolean forbiddenScreen(AccessibilityNodeIndex index) {
        for (AccessibilityNodeInfo node : index.nodes()) {
            if (node.isVisibleToUser() && (node.isPassword()
                    || protectedLabel(node.getText()) || protectedLabel(node.getContentDescription()))) {
                return true;
            }
        }
        return false;
    }

    public static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo leaf,
                                                     AccessibilityNodeIndex index) {
        if (leaf == null || !leaf.isVisibleToUser() || !leaf.isEnabled()) return null;
        NodeTarget selected = NodeTarget.captureSelected(leaf, index.rootBounds());
        if (!safeDescriptor(selected)) return null;
        Rect original = bounds(leaf);
        Rect screen = index.rootBounds();
        AccessibilityNodeInfo node = leaf;
        for (int depth = 0; depth < 4 && node != null; depth++) {
            Rect current = bounds(node);
            if (node.isPassword() || node.isEditable() || !node.isVisibleToUser() || !node.isEnabled()
                    || current.width() > screen.width() * .55f
                    || current.height() > screen.height() * .22f
                    || current.width() > Math.max(original.width() * 3, screen.width() * .22f)
                    || current.height() > Math.max(original.height() * 3, screen.height() * .12f)) {
                break;
            }
            if (node.isClickable()) return node;
            node = index.cachedParent(node);
        }
        return leaf;
    }
}
