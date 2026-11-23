package dev.ampp.lastfm.core;

import org.json.JSONArray;
import org.json.JSONException;
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

/** 离线队列：网络失败或未登录时先落盘，恢复后补交。 */
public final class ScrobbleQueue {

    public static final class Pending {
        public final String artist;
        public final String track;
        public final String album;
        public final String albumArtist;
        public final long durationSec;
        public final int trackNumber;
        public final long timestampSec;

        public Pending(String artist, String track, String album, String albumArtist,
                       long durationSec, int trackNumber, long timestampSec) {
            this.artist = artist;
            this.track = track;
            this.album = album;
            this.albumArtist = albumArtist;
            this.durationSec = durationSec;
            this.trackNumber = trackNumber;
            this.timestampSec = timestampSec;
        }
    }

    private static final int MAX_ITEMS = 500;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File file;
    private final List<Pending> items = new ArrayList<Pending>();

    public ScrobbleQueue(File dataDir) {
        this.file = new File(dataDir, "pending.json");
        load();
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized void add(Track track, long startedAtEpochSec) {
        if (startedAtEpochSec <= 0) {
            startedAtEpochSec = System.currentTimeMillis() / 1000L;
        }
        items.add(new Pending(track.artist, track.title, track.album, track.albumArtist,
                track.durationSec, track.trackNumber, startedAtEpochSec));
        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        save();
    }

    /** 取队首的一批（不移除），成功后调用 {@link #drop(int)}。 */
    public synchronized List<Pending> head(int max) {
        int n = Math.min(max, items.size());
        return new ArrayList<Pending>(items.subList(0, n));
    }

    public synchronized void drop(int count) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count && !items.isEmpty(); i++) {
            items.remove(0);
        }
        save();
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
                items.add(new Pending(
                        o.optString("artist"),
                        o.optString("track"),
                        o.optString("album"),
                        o.optString("albumArtist"),
                        o.optLong("duration"),
                        o.optInt("trackNumber"),
                        o.optLong("ts")));
            }
        } catch (Exception ignored) {
            // 文件损坏就当空队列，不阻塞插件启动。
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        for (Pending p : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("artist", p.artist);
                o.put("track", p.track);
                o.put("album", p.album);
                o.put("albumArtist", p.albumArtist);
                o.put("duration", p.durationSec);
                o.put("trackNumber", p.trackNumber);
                o.put("ts", p.timestampSec);
            } catch (JSONException ignored) {
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
            // 落盘失败不影响内存队列。
        } finally {
            closeQuietly(out);
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
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
