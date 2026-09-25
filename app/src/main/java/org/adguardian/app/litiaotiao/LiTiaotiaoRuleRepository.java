package org.adguardian.app.litiaotiao;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class LiTiaotiaoRuleRepository {
    private static final String ASSET = "ltt/AllRules.json";
    private final Map<String, LiTiaotiaoRuleBundle> byHash = new HashMap<>();
    private int ruleCount;

    LiTiaotiaoRuleRepository(Context context) {
        load(context);
    }

    LiTiaotiaoRuleBundle forPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        return byHash.get(Integer.toString(packageName.hashCode()));
    }

    int packageCount() {
        return byHash.size();
    }

    int ruleCount() {
        return ruleCount;
    }

    private void load(Context context) {
        try (InputStream input = context.getAssets().open(ASSET)) {
            String raw = readAll(input);
            JSONArray outer = new JSONArray(raw);
            for (int i = 0; i < outer.length(); i++) {
                JSONObject wrapper = outer.optJSONObject(i);
                if (wrapper == null) continue;
                Iterator<String> keys = wrapper.keys();
                while (keys.hasNext()) {
                    String hash = keys.next();
                    String inner = wrapper.optString(hash, "");
                    if (inner.isEmpty()) continue;
                    LiTiaotiaoRuleBundle bundle = parseBundle(inner);
                    byHash.put(hash, bundle);
                    ruleCount += bundle.popupRules.size();
                }
            }
        } catch (Exception ignored) {
            byHash.clear();
            ruleCount = 0;
        }
    }

    private LiTiaotiaoRuleBundle parseBundle(String raw) throws Exception {
        JSONObject object = new JSONObject(sanitize(raw));
        boolean keywordsSpecified = object.has("keywords");
        List<String> keywords = strings(object.optJSONArray("keywords"));
        List<String> keywordsAppend = strings(object.optJSONArray("keywords_append"));
        List<LiTiaotiaoRule> popupRules = new ArrayList<>();
        JSONArray rules = object.optJSONArray("popup_rules");
        if (rules != null) {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject item = rules.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String action = item.optString("action", "");
                Integer times = item.has("times") ? integer(item, "times", 1) : null;
                Integer delay = item.has("delay_popup") ? integer(item, "delay_popup", 0) : null;
                popupRules.add(new LiTiaotiaoRule(id, action, times, delay));
            }
        }
        return new LiTiaotiaoRuleBundle(
                keywordsSpecified,
                keywords,
                keywordsAppend,
                popupRules,
                integer(object, "click_way", 0),
                integer(object, "click_way_popup", 0),
                Math.max(1, integer(object, "search_times_popup", 1)),
                Math.max(0, integer(object, "delay", 0)),
                Math.max(0, integer(object, "delay_popup", 0)),
                Math.max(0, integer(object, "times", 1)),
                booleanValue(object, "unite_popup_rules", false),
                booleanValue(object, "ltt_service", true)
        );
    }

    private static String sanitize(String value) {
        String current = value == null ? "" : value;
        String previous;
        do {
            previous = current;
            current = current.replaceAll(",\\s*([}\\]])", "$1");
        } while (!current.equals(previous));
        return current;
    }

    private static List<String> strings(JSONArray array) {
        ArrayList<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static int integer(JSONObject object, String key, int fallback) {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean booleanValue(JSONObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        String text = value == null ? "" : String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return fallback;
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
