package dev.ampp.lastfm.net;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.ampp.lastfm.core.Config;
import dev.ampp.lastfm.core.ScrobbleQueue;
import dev.ampp.lastfm.core.Track;

/** Last.fm API 客户端（零第三方依赖，使用 HttpURLConnection + org.json）。 */
public final class LastfmClient {

    public static final String ENDPOINT = "https://ws.audioscrobbler.com/2.0/";
    public static final String AUTH_URL = "https://www.last.fm/api/auth/";
    /** 单次最多提交 50 条 scrobble。 */
    public static final int MAX_BATCH = 50;

    /** Last.fm 业务错误。 */
    public static final class LastfmException extends Exception {
        public final int code;

        public LastfmException(int code, String message) {
            super(message + " (code " + code + ")");
            this.code = code;
        }
    }

    private final Config config;

    public LastfmClient(Config config) {
        this.config = config;
    }

    public boolean hasSession() {
        return config.hasSession();
    }

    /** 第一步：申请 token。 */
    public String getToken() throws IOException, LastfmException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "auth.getToken");
        JSONObject result = call(params);
        String token = result.optString("token", "");
        if (token.length() == 0) {
            throw new LastfmException(-1, "返回中没有 token");
        }
        return token;
    }

    /** 用户在浏览器授权后，用 token 换取 session key。返回 {key, name}。 */
    public String[] getSession(String token) throws IOException, LastfmException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "auth.getSession");
        params.put("token", token);
        JSONObject result = call(params);
        JSONObject session = result.optJSONObject("session");
        if (session == null) {
            throw new LastfmException(-1, "返回中没有 session");
        }
        return new String[]{session.optString("key", ""), session.optString("name", "")};
    }

    public void updateNowPlaying(Track track) throws IOException, LastfmException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "track.updateNowPlaying");
        params.put("artist", track.artist);
        params.put("track", track.title);
        params.put("sk", config.sessionKey);
        if (track.album.length() > 0) {
            params.put("album", track.album);
        }
        if (track.albumArtist.length() > 0) {
            params.put("albumArtist", track.albumArtist);
        }
        if (track.trackNumber > 0) {
            params.put("trackNumber", String.valueOf(track.trackNumber));
        }
        if (track.durationSec > 0) {
            params.put("duration", String.valueOf(track.durationSec));
        }
        call(params);
    }

    /** 标记为 loved。 */
    public void love(Track track) throws IOException, LastfmException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "track.love");
        params.put("artist", track.artist);
        params.put("track", track.title);
        params.put("sk", config.sessionKey);
        call(params);
    }

    /** 取消 loved。 */
    public void unlove(Track track) throws IOException, LastfmException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "track.unlove");
        params.put("artist", track.artist);
        params.put("track", track.title);
        params.put("sk", config.sessionKey);
        call(params);
    }

    /**
     * 查询该曲目在当前账号下是否已是 loved。
     * 用 track.getInfo + username，不依赖本地记录，所以跨端（iPhone/Mac/网页）标的红心也能认出来。
     */
    public boolean isLoved(Track track) throws IOException, LastfmException {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "track.getInfo");
        params.put("artist", track.artist);
        params.put("track", track.title);
        if (config.username.length() > 0) {
            params.put("username", config.username);
        }
        JSONObject result = call(params);
        JSONObject info = result.optJSONObject("track");
        if (info == null) {
            return false;
        }
        Object value = info.opt("userloved");
        if (value == null) {
            value = info.opt("userLoved");
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    public void scrobble(List<ScrobbleQueue.Pending> batch) throws IOException, LastfmException {        if (batch.isEmpty()) {
            return;
        }
        Map<String, String> params = new TreeMap<String, String>();
        params.put("method", "track.scrobble");
        params.put("sk", config.sessionKey);
        for (int i = 0; i < batch.size(); i++) {
            ScrobbleQueue.Pending p = batch.get(i);
            params.put("artist[" + i + "]", p.artist);
            params.put("track[" + i + "]", p.track);
            params.put("timestamp[" + i + "]", String.valueOf(p.timestampSec));
            if (p.album != null && p.album.length() > 0) {
                params.put("album[" + i + "]", p.album);
            }
            if (p.albumArtist != null && p.albumArtist.length() > 0) {
                params.put("albumArtist[" + i + "]", p.albumArtist);
            }
            if (p.trackNumber > 0) {
                params.put("trackNumber[" + i + "]", String.valueOf(p.trackNumber));
            }
            if (p.durationSec > 0) {
                params.put("duration[" + i + "]", String.valueOf(p.durationSec));
            }
        }
        call(params);
    }

    private JSONObject call(Map<String, String> params) throws IOException, LastfmException {
        if (!config.hasCredentials()) {
            throw new LastfmException(-1, "未填写 API Key / Secret");
        }
        params.put("api_key", config.apiKey);
        params.put("api_sig", sign(params, config.apiSecret));
        params.put("format", "json");
        String body = post(ENDPOINT, params);
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("响应不是合法 JSON: " + head(body));
        }
        if (json.has("error")) {
            throw new LastfmException(json.optInt("error", -1), json.optString("message", "未知错误"));
        }
        return json;
    }

    /** api_sig = md5(按 key 排序后拼接的 key+value + secret)，format/callback 不参与签名。 */
    static String sign(Map<String, String> params, String secret) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(params).entrySet()) {
            String key = entry.getKey();
            if ("format".equals(key) || "callback".equals(key) || "api_sig".equals(key)) {
                continue;
            }
            sb.append(key).append(entry.getValue() == null ? "" : entry.getValue());
        }
        sb.append(secret);
        return md5(sb.toString());
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String post(String endpoint, Map<String, String> params) throws IOException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        byte[] payload = body.toString().getBytes("UTF-8");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "AMPP-Lastfm/1.0");
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status >= 400 && text.length() == 0) {
                throw new IOException("HTTP " + status);
            }
            return text;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String head(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /** 生成用户授权页面地址。 */
    public static String authorizeUrl(String apiKey, String token) {
        return AUTH_URL + "?api_key=" + encode(apiKey) + "&token=" + encode(token);
    }

    /** 供设置页展示：把待提交队列转成可读列表。 */
    public static List<String> describe(List<ScrobbleQueue.Pending> batch) {
        List<String> out = new ArrayList<String>();
        for (ScrobbleQueue.Pending p : batch) {
            out.add(p.artist + " - " + p.track);
        }
        return out;
    }
}
