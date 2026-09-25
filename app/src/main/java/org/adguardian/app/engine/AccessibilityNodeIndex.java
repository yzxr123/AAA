package org.adguardian.app.engine;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class AccessibilityNodeIndex implements AutoCloseable {
    private static final int MAX_NODES = 4096;
    private static final int LEGACY_SCAN_NODES = 480;
    private final AccessibilityNodeInfo root;
    private final Rect rootBounds = new Rect();
    private final Map<AccessibilityNodeInfo, Record> cache = new HashMap<>();
    private final Map<AccessibilityNodeInfo, Map<String,List<AccessibilityNodeInfo>>> fastCache = new HashMap<>();
    private long deadline = Long.MAX_VALUE;
    private boolean traversalIncomplete;
    private boolean childUnavailable;
    private final Map<String,String> fingerprints=new HashMap<>();
    public String memoizedFingerprint(String key,java.util.function.Supplier<String> compute) {
        if(fingerprints.containsKey(key))return fingerprints.get(key);
        String value=compute.get();fingerprints.put(key,value);return value;
    }
    public boolean isTraversalIncomplete() {return traversalIncomplete;}
    public boolean hasUnavailableChildren() {return childUnavailable;}
    public int cachedNodeCount() {return cache.size();}

    private static final class Record {
        final AccessibilityNodeInfo node;
        final Map<Integer, AccessibilityNodeInfo> children = new HashMap<>();
        AccessibilityNodeInfo parent;
        boolean parentKnown;
        int index = -1;
        Record(AccessibilityNodeInfo node) { this.node = node; }
    }

    public static final class QueryLimit extends RuntimeException {
        public QueryLimit() { super("Accessibility query budget exhausted", null, false, false); }
    }

    private AccessibilityNodeIndex(AccessibilityNodeInfo root) {
        this.root = root;
        if (root != null) {
            root.getBoundsInScreen(rootBounds);
            Record record = new Record(root);
            record.parentKnown = true;
            record.index = 0;
            cache.put(root, record);
        }
    }

    public static AccessibilityNodeIndex build(AccessibilityNodeInfo root) { return new AccessibilityNodeIndex(root); }
    public AccessibilityNodeInfo root() { return root; }
    public Rect rootBounds() { return new Rect(rootBounds); }
    public void startQueryBudget(long millis) { deadline = SystemClock.uptimeMillis() + millis; }
    public void clearQueryBudget() { deadline = Long.MAX_VALUE; }
    public void checkBudget() { if (SystemClock.uptimeMillis() > deadline) throw new QueryLimit(); }

    private Record record(AccessibilityNodeInfo node) {
        checkBudget();
        Record existing = cache.get(node);
        if (existing != null) return existing;
        if (cache.size() >= MAX_NODES) throw new QueryLimit();
        Record created = new Record(node);
        cache.put(node, created);
        return created;
    }

    private void recycleIfUnowned(AccessibilityNodeInfo node) {
        Record owned=cache.get(node);
        if(node!=root && (owned==null || owned.node!=node))node.recycle();
    }

    public AccessibilityNodeInfo cachedChild(AccessibilityNodeInfo node, int index) {
        if (node == null || index < 0 || index >= node.getChildCount()) return null;
        Record parent = record(node);
        if (parent.children.containsKey(index)) return parent.children.get(index);
        AccessibilityNodeInfo child = node.getChild(index);
        if (child != null) {
            Record childRecord;
            try {childRecord=record(child);}
            finally {recycleIfUnowned(child);}
            childRecord.parent = node;
            childRecord.parentKnown = true;
            childRecord.index = index;
            child = childRecord.node;
        }
        if(child==null){traversalIncomplete=true;childUnavailable=true;}
        parent.children.put(index, child);
        return child;
    }

    public AccessibilityNodeInfo cachedParent(AccessibilityNodeInfo node) {
        if (node == null || node.equals(root)) return null;
        Record record = record(node);
        if (!record.parentKnown) {
            AccessibilityNodeInfo parent = node.getParent();
            try {record.parent = parent == null ? null : record(parent).node;}
            finally {if(parent!=null)recycleIfUnowned(parent);}
            record.parentKnown = true;
        }
        return record.parent;
    }

    public int cachedIndex(AccessibilityNodeInfo node) {
        Record record = record(node);
        if (record.index >= 0) return record.index;
        AccessibilityNodeInfo parent = cachedParent(node);
        if (parent == null) return 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (node.equals(cachedChild(parent, i))) { record.index = i; return i; }
        }
        return 0;
    }

    public Integer cachedDepth(AccessibilityNodeInfo node) {
        Set<AccessibilityNodeInfo> visited = new HashSet<>();
        int depth = 0;
        while (node != null && visited.add(node)) {
            node = cachedParent(node);
            if (node == null) return depth;
            depth++;
        }
        return null;
    }

    public List<AccessibilityNodeInfo> fastQuery(AccessibilityNodeInfo parent, String kind, String value) {
        checkBudget();
        String key = kind + "|" + value;
        Map<String,List<AccessibilityNodeInfo>> scope=fastCache.computeIfAbsent(parent,k -> new HashMap<>());
        List<AccessibilityNodeInfo> result = scope.get(key);
        if (result != null) return result;
        List<AccessibilityNodeInfo> found = "text".equals(kind)
                ? parent.findAccessibilityNodeInfosByText(value)
                : parent.findAccessibilityNodeInfosByViewId("vid".equals(kind)
                        ? parent.getPackageName() + ":id/" + value : value);
        java.util.LinkedHashSet<AccessibilityNodeInfo> canonical = new java.util.LinkedHashSet<>();
        try {
            if(found!=null)for(AccessibilityNodeInfo node:found)if(node!=null)canonical.add(record(node).node);
        } finally {
            if(found!=null) {
                java.util.Set<AccessibilityNodeInfo> seen=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for(AccessibilityNodeInfo node:found)if(node!=null && seen.add(node))recycleIfUnowned(node);
            }
        }
        result = Collections.unmodifiableList(new ArrayList<>(canonical));
        scope.put(key, result);
        return result;
    }

    public Iterable<AccessibilityNodeInfo> nodes() {return nodes(LEGACY_SCAN_NODES);}
    public Iterable<AccessibilityNodeInfo> nodes(int limit) {
        final int nodeLimit=Math.min(MAX_NODES,Math.max(1,limit));
        return () -> new Iterator<AccessibilityNodeInfo>() {
            private final ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            private final Set<AccessibilityNodeInfo> visited = new HashSet<>();
            private int count;
            { if (root != null) { queue.add(root); visited.add(root); } }
            public boolean hasNext() { return count < nodeLimit && !queue.isEmpty(); }
            public AccessibilityNodeInfo next() {
                checkBudget();
                if (!hasNext()) throw new NoSuchElementException();
                AccessibilityNodeInfo node = queue.removeFirst();
                count++;
                if(node.getChildCount()+count+queue.size()>nodeLimit)traversalIncomplete=true;
                for (int i = 0; i < node.getChildCount() && count + queue.size() < nodeLimit; i++) {
                    AccessibilityNodeInfo child = cachedChild(node, i);
                    if (child != null && visited.add(child)) queue.addLast(child);
                }
                return node;
            }
        };
    }

    @Override
    @SuppressWarnings("deprecation")
    public void close() {
        for (Record record : cache.values()) {
            if (record.node != root) record.node.recycle();
        }
        cache.clear();
        fastCache.clear();fingerprints.clear();
    }

    public boolean expressionExists(String expression) {
        return findPrimaryNode(expression) != null;
    }

    public AccessibilityNodeInfo findPrimaryNode(String expression) {
        String normalized = normalizeExpression(expression);
        if (normalized.isEmpty()) {
            return null;
        }
        String[] parts = normalized.split("&");
        if (parts.length == 1) {
            return findTerm(parts[0]);
        }
        AccessibilityNodeInfo first = null;
        for (String part : parts) {
            AccessibilityNodeInfo found = findTerm(part);
            if (found == null) {
                return null;
            }
            if (first == null) {
                first = found;
            }
        }
        return first;
    }

    public AccessibilityNodeInfo findActionNode(String expression) {
        return findPrimaryNode(expression);
    }

    public AccessibilityNodeInfo findKeyword(List<String> keywords) {
        if (keywords == null) {
            return null;
        }
        for (String keyword : keywords) {
            AccessibilityNodeInfo found = findPrimaryNode(keyword);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public AccessibilityNodeInfo findLikelyCloseCandidate() {
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        long screenArea = (long) rootBounds.width() * rootBounds.height();
        for (AccessibilityNodeInfo node : nodes()) {
            if (!node.isVisibleToUser() || !node.isEnabled()) {
                continue;
            }
            Rect bounds = bounds(node);
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                continue;
            }
            long area = (long) bounds.width() * bounds.height();
            if (area * 5L >= screenArea
                    || bounds.width() > rootBounds.width() * 0.42f
                    || bounds.height() > rootBounds.height() * 0.22f) {
                continue;
            }
            String value = combined(node).toLowerCase(Locale.ROOT);
            if (!containsCloseToken(value)) {
                continue;
            }
            int score = 35;
            if (node.isClickable()) score += 25;
            if (containsAdMarker(value)) score += 30;
            float cx = bounds.exactCenterX() - rootBounds.left;
            float cy = bounds.exactCenterY() - rootBounds.top;
            if (cx >= rootBounds.width() * 0.62f || cx <= rootBounds.width() * 0.38f) score += 15;
            if (cy <= rootBounds.height() * 0.58f) score += 10;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return bestScore >= 55 ? best : null;
    }

    public boolean hasAdSdkMarker() {
        int count = 0;
        for (AccessibilityNodeInfo node : nodes()) {
            if (count++ >= 160) {
                break;
            }
            if (containsAdMarker(lower(node.getViewIdResourceName()))) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findTerm(String rawTerm) {
        String term = normalizeTerm(rawTerm);
        if (term.isEmpty()) {
            return null;
        }
        BoundsSpec bounds = BoundsSpec.parse(term);
        for (AccessibilityNodeInfo node : nodes()) {
            if (bounds != null) {
                if (bounds.matches(node)) {
                    return node;
                }
                continue;
            }
            if (matchesNode(node, term)) {
                return node;
            }
        }
        return null;
    }

    private boolean matchesNode(AccessibilityNodeInfo node, String term) {
        MatchMode mode = MatchMode.CONTAINS;
        String value = term;
        if (value.startsWith("=")) {
            mode = MatchMode.EXACT;
            value = value.substring(1);
        } else if (value.startsWith("+")) {
            mode = MatchMode.STARTS;
            value = value.substring(1);
        } else if (value.startsWith("-")) {
            mode = MatchMode.ENDS;
            value = value.substring(1);
        }
        value = value.trim();
        if (value.isEmpty()) {
            return false;
        }
        String[] candidates = {
                string(node.getText()),
                string(node.getContentDescription()),
                string(node.getViewIdResourceName()),
                shortId(node.getViewIdResourceName())
        };
        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            if (mode.matches(candidate, value)) return true;
        }
        return false;
    }

    private static String normalizeExpression(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeTerm(String value) {
        if (value == null) return "";
        String term = value.trim();
        if (term.startsWith("|")) {
            term = term.substring(1).trim();
        }
        return term;
    }

    private static String combined(AccessibilityNodeInfo node) {
        return string(node.getText()) + ' ' + string(node.getContentDescription()) + ' '
                + string(node.getViewIdResourceName()) + ' ' + shortId(node.getViewIdResourceName());
    }

    private static String shortId(CharSequence value) {
        String id = string(value);
        int slash = id.lastIndexOf('/');
        return slash >= 0 && slash + 1 < id.length() ? id.substring(slash + 1) : id;
    }

    private static String lower(CharSequence value) {
        return string(value).toLowerCase(Locale.ROOT);
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect;
    }

    private static boolean containsCloseToken(String value) {
        return value.contains("close") || value.contains("dismiss") || value.contains("skip")
                || value.contains("cancel_ad") || value.contains("关闭") || value.contains("跳过")
                || value.equals("x") || value.equals("×");
    }

    private static boolean containsAdMarker(String value) {
        return value.contains("ad_") || value.contains("_ad") || value.contains("advert")
                || value.contains("splash") || value.contains("promo") || value.contains("gdt")
                || value.contains("ksad") || value.contains("pangle") || value.contains("tt_")
                || value.contains("topon") || value.contains("bytedance");
    }

    private enum MatchMode {
        CONTAINS, EXACT, STARTS, ENDS;
        boolean matches(String candidate, String expected) {
            switch (this) {
                case EXACT: return candidate.equals(expected);
                case STARTS: return candidate.startsWith(expected);
                case ENDS: return candidate.endsWith(expected);
                default: return candidate.contains(expected);
            }
        }
    }

    private static final class BoundsSpec {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final Boolean checked;

        BoundsSpec(int left, int top, int right, int bottom, Boolean checked) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.checked = checked;
        }

        static BoundsSpec parse(String value) {
            String[] parts = value.split(",");
            if (parts.length != 4 && parts.length != 5) return null;
            try {
                int l = Integer.parseInt(parts[0].trim());
                int t = Integer.parseInt(parts[1].trim());
                int r = Integer.parseInt(parts[2].trim());
                int b = Integer.parseInt(parts[3].trim());
                Boolean checked = null;
                if (parts.length == 5) checked = "1".equals(parts[4].trim());
                return new BoundsSpec(l, t, r, b, checked);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        boolean matches(AccessibilityNodeInfo node) {
            Rect rect = bounds(node);
            boolean same = rect.left == left && rect.top == top && rect.right == right && rect.bottom == bottom;
            return same && (checked == null || node.isChecked() == checked);
        }
    }
}
