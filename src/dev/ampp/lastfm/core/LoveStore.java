package dev.ampp.lastfm.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录「本插件成功标记过 loved 的曲目」。
 * 用曲目 ID 作键（而不是艺人+曲名），这样名字归一规则变化时仍能找回原记录；
 * 同时保存当初上报给 Last.fm 的艺人/曲名，取消喜爱时直接用它们撤销。
 */
public final class LoveStore {

    public static final int MAX_ITEMS = 5000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Entry {
        public final String id;
        public final long num;
        public final String artist;
        public final String title;

        Entry(String id, long num, String artist, String title) {
            this.id = id == null ? "" : id;
            this.num = num;
            this.artist = artist == null ? "" : artist;
            this.title = title == null ? "" : title;
        }
    }

    private final File file;
    private final List<Entry> items = new ArrayList<Entry>();

    public LoveStore(File dataDir) {
        this.file = new File(dataDir, "loved.json");
        load();
    }

    public synchronized void add(String id, long num, String artist, String title) {
        Entry existing = find(id, num);
        if (existing != null) {
            return;
        }
        items.add(new Entry(id, num, artist, title));
        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        save();
    }

    /** 按曲目 ID 查找；num 为 0 时只用字符串 ID。 */
    public synchronized Entry find(String id, long num) {
        for (Entry entry : items) {
            if (num != 0L && entry.num == num) {
                return entry;
            }
            if (id != null && id.length() > 0 && entry.id.length() > 0
                    && (id.equals(entry.id) || id.endsWith(entry.id))) {
                return entry;
            }
        }
        return null;
    }

    public synchronized boolean remove(String id, long num) {
        Entry entry = find(id, num);
        if (entry == null) {
            return false;
        }
        items.remove(entry);
        save();
        return true;
    }

    public synchronized int size() {
        return items.size();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(readAll(file));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                items.add(new Entry(o.optString("id"), o.optLong("num"),
                        o.optString("artist"), o.optString("title")));
            }
        } catch (Exception ignored) {
            // 文件损坏或旧格式（纯字符串数组）就当空记录。
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        for (Entry entry : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", entry.id);
                o.put("num", entry.num);
                o.put("artist", entry.artist);
                o.put("title", entry.title);
            } catch (Exception ignored) {
                continue;
            }
            array.put(o);
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(array.toString().getBytes(UTF8));
            out.flush();
        } catch (IOException ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF8);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
