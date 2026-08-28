package com.local.shelldeck;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;

final class InputMemoryStore {
    private static final String PREFS = "input_memory";
    private static final String VALUES_PREFIX = "values.";
    private static final String ENABLED_PREFIX = "enabled.";
    private static final String ACTIVE_HASH_PREFIX = "active_hash.";
    private static final int MAX_VALUES = 32;
    private static final int MAX_VALUE_CHARS = 4096;
    private static final int MAX_PROMPT_CHARS = 240;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    static final class Entry {
        final String value;
        final String prompt;

        Entry(String value, String prompt) {
            this.value = value;
            this.prompt = prompt;
        }
    }

    InputMemoryStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isEnabled(String scriptId) {
        return scriptId != null && preferences.getBoolean(ENABLED_PREFIX + scriptId, false);
    }

    void setEnabled(String scriptId, boolean enabled) {
        preferences.edit().putBoolean(ENABLED_PREFIX + scriptId, enabled).apply();
    }

    List<Entry> load(String scriptId, String contentHash) {
        synchronized (LOCK) {
            List<Entry> result = new ArrayList<>();
            String key = valuesKey(scriptId, contentHash);
            if (key == null) return result;
            preferences.edit().putString(ACTIVE_HASH_PREFIX + scriptId, contentHash).apply();
            try {
                JSONArray values = new JSONArray(preferences.getString(key, "[]"));
                for (int index = 0; index < values.length() && index < MAX_VALUES; index++) {
                    Object stored = values.opt(index);
                    if (stored instanceof JSONObject) {
                        JSONObject entry = (JSONObject) stored;
                        result.add(new Entry(entry.optString("value", ""),
                                entry.optString("prompt", "")));
                    } else if (stored instanceof String) {
                        // Values saved before prompt-aware replay remain usable via heuristics.
                        result.add(new Entry((String) stored, ""));
                    }
                }
            } catch (Exception ignored) {
                preferences.edit().remove(key).apply();
            }
            return result;
        }
    }

    void append(String scriptId, String contentHash, String input, String prompt) {
        synchronized (LOCK) {
            String key = valuesKey(scriptId, contentHash);
            if (key == null || !isEnabled(scriptId)) return;
            List<Entry> values = load(scriptId, contentHash);
            if (values.size() >= MAX_VALUES) return;
            values.add(new Entry(input.length() > MAX_VALUE_CHARS
                    ? input.substring(0, MAX_VALUE_CHARS) : input,
                    trimPrompt(prompt)));
            JSONArray json = new JSONArray();
            for (Entry value : values) {
                JSONObject entry = new JSONObject();
                try {
                    entry.put("value", value.value);
                    entry.put("prompt", value.prompt);
                    json.put(entry);
                } catch (Exception ignored) {
                    // Strings are bounded and always valid JSON values.
                }
            }
            preferences.edit().putString(key, json.toString()).apply();
        }
    }

    private static String trimPrompt(String prompt) {
        if (prompt == null) return "";
        String value = prompt.trim();
        return value.length() > MAX_PROMPT_CHARS
                ? value.substring(value.length() - MAX_PROMPT_CHARS) : value;
    }

    void clear(String scriptId) {
        removeValueKeys(scriptId, false);
    }

    void remove(String scriptId) {
        removeValueKeys(scriptId, true);
    }

    int count(String scriptId) {
        if (scriptId == null) return 0;
        String activeHash = preferences.getString(ACTIVE_HASH_PREFIX + scriptId, null);
        if (activeHash != null && activeHash.matches("[0-9a-f]{64}")) {
            return countValues(VALUES_PREFIX + scriptId + "." + activeHash);
        }
        int count = 0;
        String prefix = VALUES_PREFIX + scriptId + ".";
        synchronized (LOCK) {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof String)) continue;
                try {
                    count += Math.min(new JSONArray((String) entry.getValue()).length(), MAX_VALUES);
                } catch (Exception ignored) {
                    // Invalid entries are ignored and can be removed by clear().
                }
            }
        }
        return count;
    }

    private int countValues(String key) {
        try {
            return Math.min(new JSONArray(preferences.getString(key, "[]")).length(), MAX_VALUES);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void removeValueKeys(String scriptId, boolean removeEnabled) {
        if (scriptId == null) return;
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            String currentPrefix = VALUES_PREFIX + scriptId + ".";
            String legacyKey = VALUES_PREFIX + scriptId;
            for (String key : preferences.getAll().keySet()) {
                if (key.equals(legacyKey) || key.startsWith(currentPrefix)) editor.remove(key);
            }
            if (removeEnabled) editor.remove(ENABLED_PREFIX + scriptId);
            if (removeEnabled) editor.remove(ACTIVE_HASH_PREFIX + scriptId);
            editor.apply();
        }
    }

    private static String valuesKey(String scriptId, String contentHash) {
        if (scriptId == null || contentHash == null
                || !contentHash.matches("[0-9a-f]{64}")) return null;
        return VALUES_PREFIX + scriptId + "." + contentHash;
    }
}
