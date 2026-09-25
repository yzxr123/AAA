import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

final class TestSupport {
    static int passed;
    interface Case { void run() throws Exception; }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void run(String name, Case body) {
        Handler.tasks.clear();
        SystemClock.now = 10_000L;
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new RuntimeException(error);
        }
    }

    static final class Service extends AccessibilityService {
        @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
        @Override public void onInterrupt() { }
    }

    static AccessibilityNodeInfo root(String pkg, String id) {
        AccessibilityNodeInfo node = new AccessibilityNodeInfo();
        node.pkg = pkg;
        node.id = id;
        node.name = "android.widget.FrameLayout";
        node.bounds = new Rect(0, 0, 1080, 2400);
        return node;
    }

    static AccessibilityNodeInfo node(String id, String text, String name,
                                      int x, int y, int width, int height, boolean clickable) {
        AccessibilityNodeInfo node = new AccessibilityNodeInfo();
        node.id = id;
        node.text = text;
        node.name = name;
        node.bounds = new Rect(x, y, x + width, y + height);
        node.clickable = clickable;
        return node;
    }
}
