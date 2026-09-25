package org.adguardian.app.gkd;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import kotlin.sequences.Sequence;
import li.gkd.selector.FastQuery;
import li.gkd.selector.NodeAdapter;
import org.adguardian.app.engine.AccessibilityNodeIndex;

public final class GkdNodeAdapter extends NodeAdapter<AccessibilityNodeInfo> {
    private final AccessibilityNodeIndex index;
    private final Rect bounds = new Rect();
    public GkdNodeAdapter(AccessibilityNodeIndex index) { this.index=index; }
    @Override public Object getAttr(Object target, String name) {
        index.checkBudget();
        if (!(target instanceof AccessibilityNodeInfo)) return null;
        AccessibilityNodeInfo node=(AccessibilityNodeInfo)target;
        switch (name) {
            case "id": return node.getViewIdResourceName();
            case "vid": {
                String id=node.getViewIdResourceName();
                return id==null ? null : id.substring(id.lastIndexOf('/')+1);
            }
            case "name": return node.getClassName();
            case "text": return node.getText();
            case "desc": return node.getContentDescription();
            case "clickable": return node.isClickable();
            case "focusable": return node.isFocusable();
            case "checkable": return node.isCheckable();
            case "checked": return node.isChecked();
            case "editable": return node.isEditable();
            case "enabled": return node.isEnabled();
            case "longClickable": return node.isLongClickable();
            case "visibleToUser": return node.isVisibleToUser();
            case "childCount": return node.getChildCount();
            case "index": return index.cachedIndex(node);
            case "depth": return index.cachedDepth(node);
            case "parent": return index.cachedParent(node);
            default:
                node.getBoundsInScreen(bounds);
                switch (name) {
                    case "left": return bounds.left;
                    case "top": return bounds.top;
                    case "right": return bounds.right;
                    case "bottom": return bounds.bottom;
                    case "width": return bounds.width();
                    case "height": return bounds.height();
                    default: return null;
                }
        }
    }
    @Override public Object getInvoke(Object target, String name, List<? extends Object> args) {
        if (target instanceof AccessibilityNodeInfo && "getChild".equals(name) && args.size()==1 && args.get(0) instanceof Number)
            return index.cachedChild((AccessibilityNodeInfo)target, ((Number)args.get(0)).intValue());
        return null;
    }
    @Override public String getName(AccessibilityNodeInfo node) {
        index.checkBudget();return node.getClassName()==null ? null : node.getClassName().toString();
    }
    @Override public int getChildCount(AccessibilityNodeInfo node) { index.checkBudget();return node.getChildCount(); }
    @Override public AccessibilityNodeInfo getChild(AccessibilityNodeInfo node,int child) { return index.cachedChild(node,child); }
    @Override public AccessibilityNodeInfo getParent(AccessibilityNodeInfo node) { return index.cachedParent(node); }
    @Override public AccessibilityNodeInfo getRoot(AccessibilityNodeInfo node) { return index.root(); }
    @Override public Object getNodeKey(AccessibilityNodeInfo node) { index.checkBudget();return node; }
    @Override public Sequence<AccessibilityNodeInfo> getFastQueryDescendants(AccessibilityNodeInfo parent,List<? extends FastQuery> queries) {
        return () -> new Iterator<AccessibilityNodeInfo>() {
            private final Set<AccessibilityNodeInfo> seen=new HashSet<>();
            private Iterator<AccessibilityNodeInfo> current=Collections.emptyIterator();
            private int queryIndex;
            private AccessibilityNodeInfo next;
            { seen.add(parent); }
            private void advance() {
                while (next==null) {
                    index.checkBudget();
                    while (current.hasNext()) {
                        AccessibilityNodeInfo node=current.next();
                        if (seen.add(node)) { next=node;return; }
                    }
                    if (queryIndex>=queries.size()) return;
                    FastQuery query=queries.get(queryIndex++);
                    String kind=query instanceof FastQuery.Id ? "id" : query instanceof FastQuery.Vid ? "vid" : "text";
                    current=index.fastQuery(parent,kind,query.getValue()).iterator();
                }
            }
            @Override public boolean hasNext() { advance();return next!=null; }
            @Override public AccessibilityNodeInfo next() {
                advance();if(next==null)throw new NoSuchElementException();
                AccessibilityNodeInfo result=next;next=null;return result;
            }
        };
    }
}
