package com.local.shelldeck;

import org.json.JSONException;
import org.json.JSONObject;

final class ScriptItem {
    final String id;
    final String uri;
    final String name;
    final long size;
    final long modified;
    final String localPath;

    ScriptItem(String id, String uri, String name, long size, long modified, String localPath) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.size = size;
        this.modified = modified;
        this.localPath = localPath;
    }

    JSONObject toJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("id", id);
        value.put("uri", uri);
        value.put("name", name);
        value.put("size", size);
        value.put("modified", modified);
        if (localPath != null) value.put("localPath", localPath);
        return value;
    }

    static ScriptItem fromJson(JSONObject value) throws JSONException {
        String id = value.getString("id");
        if (!isValidId(id)) throw new JSONException("Invalid script id");
        return new ScriptItem(
                id,
                value.getString("uri"),
                value.getString("name"),
                value.optLong("size", -1),
                value.optLong("modified", 0),
                value.has("localPath") ? value.optString("localPath", null) : null);
    }

    static boolean isValidId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}");
    }
}
