package com.local.shelldeck;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class ScriptStore {
    private static final String PREFS = "script_store";
    private static final String KEY_ITEMS = "items";
    private final Context context;
    private final SharedPreferences preferences;

    ScriptStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<ScriptItem> load() {
        List<ScriptItem> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(KEY_ITEMS, "[]"));
            for (int index = 0; index < values.length(); index++) {
                result.add(ScriptItem.fromJson(values.getJSONObject(index)));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_ITEMS).apply();
        }
        return result;
    }

    synchronized ScriptItem addOrUpdate(Uri uri) throws IOException {
        ScriptItem metadata = readMetadata(uri, null);
        List<ScriptItem> items = load();
        for (int index = 0; index < items.size(); index++) {
            ScriptItem old = items.get(index);
            if (old.uri.equals(uri.toString())) {
                metadata = new ScriptItem(old.id, metadata.uri, old.name,
                        metadata.size, metadata.modified, null);
                items.set(index, metadata);
                save(items);
                return metadata;
            }
        }
        items.add(metadata);
        save(items);
        return metadata;
    }

    synchronized ScriptItem importCopy(Uri uri) throws IOException {
        ScriptItem metadata = readMetadata(uri, null);
        List<ScriptItem> items = load();
        ScriptItem oldMatch = null;
        for (ScriptItem item : items) {
            if (item.uri.equals(uri.toString())) {
                oldMatch = item;
                break;
            }
        }
        String id = oldMatch == null ? metadata.id : oldMatch.id;
        File directory = new File(context.getFilesDir(), "imports");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建导入目录");
        File destination = new File(directory, id + ".sh");
        File temporary = new File(directory, id + ".tmp");
        if (temporary.exists() && !temporary.delete()) throw new IOException("无法清理导入临时文件");
        try {
            ContentResolver resolver = context.getContentResolver();
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取脚本");
                copyInputToFile(input, temporary);
            } catch (SecurityException error) {
                throw new IOException("脚本读取权限已失效，请重新添加", error);
            }
            if (destination.exists() && !destination.delete()) throw new IOException("无法替换导入副本");
            if (!temporary.renameTo(destination)) throw new IOException("无法保存导入副本");
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw error;
        }
        String displayName = oldMatch == null ? metadata.name : oldMatch.name;
        ScriptItem imported = new ScriptItem(id, uri.toString(), displayName,
                destination.length(), System.currentTimeMillis(), destination.getAbsolutePath());
        if (oldMatch == null) items.add(imported);
        else items.set(items.indexOf(oldMatch), imported);
        save(items);
        return imported;
    }

    synchronized ScriptItem updateSource(ScriptItem target, Uri uri) throws IOException {
        if (target == null || !ScriptItem.isValidId(target.id)) {
            throw new IOException("脚本标识无效");
        }
        ScriptItem metadata = readMetadata(uri, target.id);
        List<ScriptItem> items = load();
        int position = -1;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id.equals(target.id)) {
                position = index;
                target = items.get(index);
                break;
            }
        }
        if (position < 0) throw new IOException("脚本已不在列表中");

        File directory = new File(context.getFilesDir(), "imports");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建导入目录");
        File destination = new File(directory, target.id + ".sh");
        File temporary = new File(directory, target.id + ".update.tmp");
        if (temporary.exists() && !temporary.delete()) throw new IOException("无法清理更新临时文件");
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取新的脚本源");
                copyInputToFile(input, temporary);
            } catch (SecurityException error) {
                throw new IOException("新的脚本源读取权限无效", error);
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw error;
        }

        ScriptItem updated = new ScriptItem(target.id, uri.toString(), target.name,
                metadata.size >= 0 ? metadata.size : destination.length(),
                metadata.modified > 0 ? metadata.modified : System.currentTimeMillis(),
                destination.getAbsolutePath());
        items.set(position, updated);
        save(items);
        return updated;
    }

    synchronized List<ScriptItem> refresh() {
        List<ScriptItem> current = load();
        List<ScriptItem> refreshed = new ArrayList<>();
        for (ScriptItem item : current) {
            if (item.localPath != null) {
                File local = new File(item.localPath);
                refreshed.add(new ScriptItem(item.id, item.uri, item.name,
                        local.exists() ? local.length() : item.size,
                        local.exists() ? local.lastModified() : item.modified,
                        item.localPath));
                continue;
            }
            try {
                ScriptItem metadata = readMetadata(Uri.parse(item.uri), item.id);
                refreshed.add(new ScriptItem(metadata.id, metadata.uri, item.name,
                        metadata.size, metadata.modified, metadata.localPath));
            } catch (IOException ignored) {
                refreshed.add(item);
            }
        }
        save(refreshed);
        return refreshed;
    }

    synchronized void remove(ScriptItem item) {
        List<ScriptItem> items = load();
        items.removeIf(candidate -> candidate.id.equals(item.id));
        save(items);
        if (item.localPath != null) {
            File imported = new File(item.localPath);
            if (imported.exists()) {
                //noinspection ResultOfMethodCallIgnored
                imported.delete();
            }
        }
        new InputMemoryStore(context).remove(item.id);
    }

    synchronized ScriptItem rename(ScriptItem item, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) return item;
        if (name.length() > 80) name = name.substring(0, 80);
        List<ScriptItem> items = load();
        for (int index = 0; index < items.size(); index++) {
            ScriptItem current = items.get(index);
            if (!current.id.equals(item.id)) continue;
            ScriptItem renamed = new ScriptItem(current.id, current.uri, name,
                    current.size, current.modified, current.localPath);
            items.set(index, renamed);
            save(items);
            return renamed;
        }
        return item;
    }

    synchronized void reorder(List<ScriptItem> ordered) {
        List<ScriptItem> current = load();
        if (ordered == null || ordered.size() != current.size()) return;
        List<ScriptItem> result = new ArrayList<>();
        for (ScriptItem requested : ordered) {
            ScriptItem match = null;
            for (ScriptItem existing : current) {
                if (existing.id.equals(requested.id)) {
                    match = existing;
                    break;
                }
            }
            if (match == null || result.contains(match)) return;
            result.add(match);
        }
        save(result);
    }

    File prepareScript(ScriptItem item, String runId) throws IOException {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IOException("运行会话标识无效");
        }
        File runsDirectory = new File(context.getFilesDir(), "runs");
        File directory = new File(runsDirectory, runId);
        if (directory.exists() || !directory.mkdirs()) {
            throw new IOException("无法创建独立运行目录");
        }
        File destination = new File(directory, "script.sh");
        File temporary = new File(directory, "script.tmp");
        try {
            if (item.localPath != null) {
                File imported = new File(item.localPath);
                if (!imported.isFile()) throw new IOException("导入的脚本已丢失，请重新添加");
                try (InputStream input = new FileInputStream(imported)) {
                    copyInputToFile(input, temporary);
                }
            } else {
                ContentResolver resolver = context.getContentResolver();
                try (InputStream input = resolver.openInputStream(Uri.parse(item.uri))) {
                    if (input == null) throw new IOException("无法读取脚本");
                    copyInputToFile(input, temporary);
                } catch (SecurityException error) {
                    throw new IOException("脚本读取权限已失效，请重新添加", error);
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("无法替换运行快照");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("无法保存运行快照");
            }
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
            throw error;
        }
        //noinspection ResultOfMethodCallIgnored
        destination.setExecutable(true, true);
        return destination;
    }

    File prepareWorkingDirectory(ScriptItem item) throws IOException {
        if (item == null || !ScriptItem.isValidId(item.id)) {
            throw new IOException("脚本标识无效");
        }
        File workspaces = new File(context.getFilesDir(), "workspaces");
        if (!workspaces.exists() && !workspaces.mkdirs()) {
            throw new IOException("无法创建脚本工作区");
        }
        File directory = new File(workspaces, item.id);
        if (!directory.exists() && !directory.mkdir()) {
            throw new IOException("无法创建脚本工作目录");
        }
        try {
            File canonicalRoot = workspaces.getCanonicalFile();
            File canonicalDirectory = directory.getCanonicalFile();
            if (!canonicalRoot.equals(canonicalDirectory.getParentFile())
                    || !canonicalDirectory.isDirectory()) {
                throw new IOException("脚本工作目录无效");
            }
            return canonicalDirectory;
        } catch (SecurityException error) {
            throw new IOException("无法访问脚本工作目录", error);
        }
    }

    private static void copyInputToFile(InputStream input, File destination) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", part));
            return value.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("设备不支持 SHA-256", error);
        }
    }

    private ScriptItem readMetadata(Uri uri, String existingId) throws IOException {
        String name = "script.sh";
        long size = -1;
        long modified = 0;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) modified = cursor.getLong(modifiedIndex);
            }
        } catch (Exception error) {
            throw new IOException("无法读取脚本信息", error);
        }
        return new ScriptItem(existingId == null ? UUID.randomUUID().toString() : existingId,
                uri.toString(), name, size, modified, null);
    }

    private void save(List<ScriptItem> items) {
        JSONArray values = new JSONArray();
        try {
            for (ScriptItem item : items) values.put(item.toJson());
            preferences.edit().putString(KEY_ITEMS, values.toString()).apply();
        } catch (JSONException ignored) {
            // The model contains only primitive values, so serialization cannot normally fail.
        }
    }
}
