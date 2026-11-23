package dev.ampp.lastfm.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** 插件配置，保存在 AM++ 分配的专属 data 目录。 */
public final class Config {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "config.json";

    public String apiKey = "";
    public String apiSecret = "";
    public String sessionKey = "";
    public String username = "";
    public String pendingToken = "";

    public boolean nowPlayingEnabled = true;
    public boolean scrobbleEnabled = true;
    public boolean skipVideo = true;
    /** 古典乐：作曲家作为艺术家，演奏者写入专辑艺术家。 */
    public boolean classicalComposerAsArtist = true;
    /** 古典乐激进模式：只要有作曲家就用作曲家，不看 Apple 的判断。 */
    public boolean classicalAlwaysComposer = false;
    /** 合辑：Various Artists 换成真实专辑艺术家。 */
    public boolean mergeVariousArtists = true;
    /** AM 点♥ 时同步标记 Last.fm loved。 */
    public boolean syncLove = true;
    /** AM 取消♥ 时同步取消 loved（仅限本插件标过的）。 */
    public boolean syncUnlove = true;

    private File file;

    public static Config load(File dataDir) {
        Config c = new Config();
        c.file = new File(dataDir, FILE_NAME);
        if (!c.file.isFile()) {
            return c;
        }
        try {
            JSONObject o = new JSONObject(readAll(c.file));
            c.apiKey = o.optString("apiKey", "");
            c.apiSecret = o.optString("apiSecret", "");
            c.sessionKey = o.optString("sessionKey", "");
            c.username = o.optString("username", "");
            c.pendingToken = o.optString("pendingToken", "");
            c.nowPlayingEnabled = o.optBoolean("nowPlayingEnabled", true);
            c.scrobbleEnabled = o.optBoolean("scrobbleEnabled", true);
            c.skipVideo = o.optBoolean("skipVideo", true);
            c.classicalComposerAsArtist = o.optBoolean("classicalComposerAsArtist", true);
            c.classicalAlwaysComposer = o.optBoolean("classicalAlwaysComposer", false);
            c.mergeVariousArtists = o.optBoolean("mergeVariousArtists", true);
            c.syncLove = o.optBoolean("syncLove", true);
            c.syncUnlove = o.optBoolean("syncUnlove", true);
        } catch (Exception ignored) {
            // 配置损坏则用默认值，不阻塞插件启动。
        }
        return c;
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("apiKey", apiKey);
            o.put("apiSecret", apiSecret);
            o.put("sessionKey", sessionKey);
            o.put("username", username);
            o.put("pendingToken", pendingToken);
            o.put("nowPlayingEnabled", nowPlayingEnabled);
            o.put("scrobbleEnabled", scrobbleEnabled);
            o.put("skipVideo", skipVideo);
            o.put("classicalComposerAsArtist", classicalComposerAsArtist);
            o.put("classicalAlwaysComposer", classicalAlwaysComposer);
            o.put("mergeVariousArtists", mergeVariousArtists);
            o.put("syncLove", syncLove);
            o.put("syncUnlove", syncUnlove);
        } catch (Exception ignored) {
            return;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(o.toString().getBytes(UTF8));
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

    public boolean hasCredentials() {
        return apiKey.length() > 0 && apiSecret.length() > 0;
    }

    public boolean hasSession() {
        return hasCredentials() && sessionKey.length() > 0;
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
