package dev.ampp.lastfm;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.amenhancer.plugin.api.AmppPlugin;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import dev.amenhancer.plugin.api.PluginRegistration;
import dev.amenhancer.plugin.api.PluginSettingsSession;
import dev.amenhancer.plugin.api.PluginUnsupportedException;
import dev.ampp.lastfm.core.Config;
import dev.ampp.lastfm.core.LoveStore;
import dev.ampp.lastfm.core.PlaybackTracker;
import dev.ampp.lastfm.core.ScrobbleQueue;
import dev.ampp.lastfm.core.Snapshot;
import dev.ampp.lastfm.core.Track;
import dev.ampp.lastfm.host.HostBridge;
import dev.ampp.lastfm.net.LastfmClient;
import dev.ampp.lastfm.ui.SettingsView;

public final class LastfmPlugin extends AmppPlugin implements PlaybackTracker.Sink {

    /** 播放中的轮询间隔：1 秒足够做 scrobble 判定。 */
    private static final long POLL_INTERVAL_MS = 1000L;
    /** 暂停时降频，省电。 */
    private static final long IDLE_POLL_INTERVAL_MS = 5000L;

    /** 设置页回调，运行在主线程。 */
    public interface UiCallback {
        void onResult(String message, boolean ok);
    }

    private PluginContext runtime;
    private Config config;
    private HostBridge bridge;
    private LastfmClient client;
    private ScrobbleQueue queue;
    private LoveStore loveStore;
    private PlaybackTracker tracker;
    private ExecutorService net;
    private Handler mainHandler;
    private final List<PluginRegistration> registrations = new ArrayList<PluginRegistration>();

    private volatile Object player;
    private volatile boolean running;
    private volatile boolean playing;
    private volatile String statusText = "等待播放";
    private long lastPolledPosition = -1L;
    private int diagSamples;
    private volatile int statePlaying = -1;
    private volatile int stateIdle = -1;
    private volatile Snapshot lastSnapshot;
    private volatile String lastLikeEvent = "尚无收藏事件";
    private String lastLikeKey;
    private long lastLikeAt;
    private volatile String sampleText = "尚无采样，播放一首歌后再看";
    private String sampleKey;
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            pollOnce();
            mainHandler.postDelayed(this, playing ? POLL_INTERVAL_MS : IDLE_POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onLoad(PluginContext context) throws Exception {
        runtime = context;
        config = Config.load(context.getDataDirectory());
        queue = new ScrobbleQueue(context.getDataDirectory());
        loveStore = new LoveStore(context.getDataDirectory());
        client = new LastfmClient(config);
        tracker = new PlaybackTracker(this);
        net = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        context.onClose(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        });

        bridge = HostBridge.resolve(context.getHostClassLoader());
        registerHooks();
        log("目标已定位：Apple Music " + context.getHostVersionName()
                + " (" + context.getHostVersionCode() + ")");
    }

    @Override
    public void onStart() {
        running = true;
        mainHandler.post(pollTask);
        log("已启动", null);
        if (config.hasSession()) {
            flushQueueAsync();
        }
    }

    @Override
    public void onStop() {
        running = false;
        mainHandler.removeCallbacks(pollTask);
        tracker.reset();
        log("已停止", null);
    }

    @Override
    public PluginSettingsSession createSettings(Context context) {
        return new SettingsView(context, this);
    }

    // ---------------------------------------------------------------- hooks

    private void registerHooks() throws PluginUnsupportedException {
        Class<?> playerClass = bridge.playerClass();
        Method onStateChanged = bridge.controllerMethod("onPlaybackStateChanged", playerClass, int.class, int.class);
        Method onIndexChanged = bridge.controllerMethod("onPlaybackIndexChanged", playerClass, int.class, int.class);
        Method onMetadataUpdated = bridge.controllerMethod("onMetadataUpdated", playerClass, bridge.queueItemClass());

        int installed = 0;
        installed += observePlayer(onStateChanged);
        installed += observePlayer(onIndexChanged);
        installed += observePlayer(onMetadataUpdated);

        if (installed == 0) {
            throw new PluginUnsupportedException(
                    "当前 Apple Music 版本没有找到播放器回调，插件无法获取播放状态");
        }
        log("已注册 " + installed + " 个播放器观察点", null);

        Method likeEvent = bridge.likeEventMethod();
        if (likeEvent != null) {
            registrations.add(runtime.getHooks().observe(likeEvent, new PluginObserver() {
                @Override
                public void before(PluginObservation call) {
                    // 只读观察。
                }

                @Override
                public void after(PluginObservation call) {
                    Object[] args = call.getArguments();
                    if (args != null && args.length > 0 && args[0] != null) {
                        handleLikeEvent(args[0]);
                    }
                }
            }));
            log("已注册收藏事件观察点", null);
        } else {
            log("未找到收藏事件入口，点赞同步不可用", null);
        }

        Method playlistRemove = bridge.playlistRemoveMethod();
        if (playlistRemove != null) {
            registrations.add(runtime.getHooks().observe(playlistRemove, new PluginObserver() {
                @Override
                public void before(PluginObservation call) {
                    // 只读观察。
                }

                @Override
                public void after(PluginObservation call) {
                    Object[] args = call.getArguments();
                    if (args != null && args.length > 0 && args[0] != null) {
                        handlePlaylistRemoveEvent(args[0]);
                    }
                }
            }));
            log("已注册歌单移除观察点", null);
        } else {
            log("未找到歌单移除入口，撤销喜爱不可用", null);
        }
    }

    private int observePlayer(Method target) {
        if (target == null) {
            return 0;
        }
        registrations.add(runtime.getHooks().observe(target, new PluginObserver() {
            @Override
            public void before(PluginObservation call) {
                // 只读观察，不改宿主行为。
            }

            @Override
            public void after(PluginObservation call) {
                Object[] args = call.getArguments();
                if (args == null || args.length == 0 || args[0] == null) {
                    return;
                }
                player = args[0];
                // 回调线程不保证是主线程，切到主线程统一读状态。
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        pollOnce();
                    }
                });
            }
        }));
        return 1;
    }

    private void pollOnce() {
        Object current = player;
        if (current == null) {
            return;
        }
        try {
            Snapshot snapshot = bridge.read(current);
            if (snapshot == null) {
                return;
            }
            long delta = lastPolledPosition < 0 ? 0 : snapshot.positionMs - lastPolledPosition;
            lastPolledPosition = snapshot.positionMs;
            boolean advancing = delta > 0 && delta < 15_000L;
            // 顺手学习状态码：进度前进时记下状态，静止时记下状态，之后据此判断"在播"。
            if (advancing) {
                statePlaying = snapshot.rawState;
            } else if (delta == 0) {
                stateIdle = snapshot.rawState;
            }
            playing = advancing || (statePlaying >= 0 && snapshot.rawState == statePlaying);

            if (diagSamples < 5) {
                diagSamples++;
                Track resolved = Track.from(snapshot, config);
                log("采样 state=" + snapshot.rawState + " rate=" + snapshot.rawRate
                        + " pos=" + snapshot.positionMs + " dur=" + snapshot.durationMs
                        + " | genre=" + snapshot.genre
                        + " | artist=" + snapshot.artist
                        + " | albumArtist=" + snapshot.albumArtist
                        + " | composer=" + snapshot.composer
                        + " | composerAsArtist=" + snapshot.composerAsArtist
                        + " | 上报为=" + resolved.artist);
            }

            String key = snapshot.key();
            if (!key.equals(sampleKey)) {
                sampleKey = key;
                sampleText = describe(snapshot);
            }

            if (config.skipVideo && snapshot.video) {
                tracker.reset();
                return;
            }

            lastSnapshot = snapshot;
            tracker.update(snapshot, System.currentTimeMillis());
        } catch (Throwable t) {
            log("读取播放状态失败", t);
        }
    }

    // ---------------------------------------------------------------- 点赞同步

    private void handleLikeEvent(Object raw) {
        if (!config.syncLove) {
            return;
        }
        HostBridge.LikeEvent event = bridge.parseLikeEvent(raw);
        if (event == null) {
            return;
        }
        lastLikeEvent = event.toString();

        // 每个 Fragment 都会收到同一个事件，3 秒内同 id+state 只处理一次。
        String dedup = event.id + '|' + event.numericId + '|' + event.state;
        long now = System.currentTimeMillis();
        if (dedup.equals(lastLikeKey) && now - lastLikeAt < 3000L) {
            return;
        }
        lastLikeKey = dedup;
        lastLikeAt = now;

        Snapshot snapshot = lastSnapshot;
        boolean matched = snapshot != null && event.matches(snapshot);
        lastLikeEvent = event + " 匹配=" + matched;

        if (event.liked()) {
            if (!matched) {
                log("收藏事件未匹配当前播放：" + event, null);
                return;
            }
            requestLove(Track.from(snapshot, config), event.id, event.numericId);
        } else if (config.syncUnlove && !"Unknown".equals(event.state)) {
            // 实测：撤销喜爱时 AM 给的是 state=None（不是 Unliked），所以非 Liked 都按撤销处理；
            // 只有记录表里存在该曲目时才会真的发 unlove。
            requestUnlove(event.id, event.numericId);
        }
    }

    /** 撤销喜爱走「从歌单移除」事件。只处理当前正在播放的那首，避免误伤普通歌单操作。 */
    private void handlePlaylistRemoveEvent(Object raw) {
        if (!config.syncLove || !config.syncUnlove) {
            return;
        }
        HostBridge.LikeEvent event = bridge.parseLikeEvent(raw);
        if (event == null) {
            return;
        }
        Snapshot snapshot = lastSnapshot;
        boolean matched = snapshot != null && event.matches(snapshot);
        lastLikeEvent = "歌单移除 " + event + " 匹配=" + matched;
        if (!matched) {
            return;
        }
        String dedup = "rm|" + event.id + '|' + event.numericId;
        long now = System.currentTimeMillis();
        if (dedup.equals(lastLikeKey) && now - lastLikeAt < 3000L) {
            return;
        }
        lastLikeKey = dedup;
        lastLikeAt = now;
        requestUnlove(event.id, event.numericId);
    }

    private void requestLove(final Track track, final String id, final long num) {
        if (!config.hasSession()) {
            return;
        }
        statusText = "已标记喜欢：" + track.artist + " - " + track.title;
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    client.love(track);
                    loveStore.add(id, num, track.artist, track.title);
                    log("已 love：" + track, null);
                } catch (Throwable t) {
                    log("love 失败", t);
                }
            }
        });
    }

    private void requestUnlove(String id, long num) {
        final LoveStore.Entry entry = loveStore.find(id, num);
        if (entry == null) {
            // 不是本插件标的，可能是网页或其它客户端标的，不动它。
            log("撤销喜爱但不在本插件记录中，跳过：id=" + id + " num=" + num, null);
            return;
        }
        if (!config.hasSession()) {
            return;
        }
        final Track track = new Track();
        track.artist = entry.artist;
        track.title = entry.title;
        statusText = "已取消喜欢：" + track.artist + " - " + track.title;
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    client.unlove(track);
                    loveStore.remove(entry.id, entry.num);
                    log("已 unlove：" + track, null);
                } catch (Throwable t) {
                    log("unlove 失败", t);
                }
            }
        });
    }

    // ---------------------------------------------------------------- sink

    @Override
    public void onNowPlaying(Snapshot snapshot) {
        final Track track = Track.from(snapshot, config);
        statusText = "正在播放：" + track.artist + " - " + track.title;
        if (!config.nowPlayingEnabled || !config.hasSession()) {
            return;
        }
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    client.updateNowPlaying(track);
                } catch (Throwable t) {
                    log("Now Playing 上报失败", t);
                }
            }
        });
    }

    @Override
    public void onScrobble(Snapshot snapshot, long startedAtEpochSec) {
        Track track = Track.from(snapshot, config);
        queue.add(track, startedAtEpochSec);
        statusText = "已加入队列：" + track.artist + " - " + track.title;
        log("计入一次收听：" + track, null);
        if (config.scrobbleEnabled) {
            flushQueueAsync();
        }
    }

    // ---------------------------------------------------------------- 网络

    public void flushQueueAsync() {
        if (!config.hasSession() || queue.size() == 0) {
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    drainQueue();
                } finally {
                    flushing.set(false);
                }
            }
        });
    }

    private void drainQueue() {
        for (int guard = 0; guard < 20; guard++) {
            List<ScrobbleQueue.Pending> batch = queue.head(LastfmClient.MAX_BATCH);
            if (batch.isEmpty()) {
                return;
            }
            try {
                client.scrobble(batch);
                queue.drop(batch.size());
                statusText = "已提交 " + batch.size() + " 条";
                log(statusText, null);
            } catch (LastfmClient.LastfmException e) {
                statusText = "提交失败：" + e.getMessage();
                log(statusText, e);
                if (e.code == 9) {
                    // session 失效，等用户重新授权。
                    config.sessionKey = "";
                    config.save();
                }
                return;
            } catch (Throwable t) {
                statusText = "网络异常，稍后重试";
                log("提交 scrobble 失败", t);
                return;
            }
        }
    }

    /** 第一步：申请 token 并打开浏览器授权页。 */
    public void beginAuth(final UiCallback callback) {
        config.save();
        if (!config.hasCredentials()) {
            post(callback, "请先填写并保存 API Key / Secret", false);
            return;
        }
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = client.getToken();
                    config.pendingToken = token;
                    config.save();
                    openBrowser(LastfmClient.authorizeUrl(config.apiKey, token));
                    post(callback, "已打开授权页，授权后回到这里点「完成授权」", true);
                } catch (Throwable t) {
                    log("申请 token 失败", t);
                    post(callback, "申请 token 失败：" + t.getMessage(), false);
                }
            }
        });
    }

    /** 第二步：用 token 换 session key。 */
    public void completeAuth(final UiCallback callback) {
        if (config.pendingToken.length() == 0) {
            post(callback, "没有待用的 token，请先点「开始授权」", false);
            return;
        }
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] session = client.getSession(config.pendingToken);
                    config.sessionKey = session[0];
                    config.username = session[1];
                    config.pendingToken = "";
                    config.save();
                    post(callback, "已登录 Last.fm：" + config.username, true);
                    flushQueueAsync();
                } catch (Throwable t) {
                    log("换取 session 失败", t);
                    post(callback, "完成授权失败：" + t.getMessage(), false);
                }
            }
        });
    }

    public void signOut() {
        config.sessionKey = "";
        config.username = "";
        config.pendingToken = "";
        config.save();
    }

    private void openBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runtime.getApplication().startActivity(intent);
        } catch (Throwable t) {
            log("无法打开浏览器，请手动访问 " + url, t);
        }
    }

    private void post(final UiCallback callback, final String message, final boolean ok) {
        if (callback == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(message, ok);
            }
        });
    }

    private void shutdown() {
        running = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(pollTask);
        }
        for (PluginRegistration registration : registrations) {
            try {
                registration.close();
            } catch (Throwable ignored) {
            }
        }
        registrations.clear();
        if (net != null) {
            net.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 给设置页用

    public Config config() {
        return config;
    }

    public String statusText() {
        return statusText;
    }

    public String queueSummary() {
        return "队列 " + queue.size() + " 条"
                + (config.hasSession() ? "" : " · 未登录");
    }

    /** 最近一首歌的原始元数据，供设置页诊断用。 */
    public String sampleText() {
        return sampleText;
    }

    public int lovedCount() {
        return loveStore.size();
    }

    public int lovedLimit() {
        return LoveStore.MAX_ITEMS;
    }

    private String describe(Snapshot s) {
        Track track = Track.from(s, config);
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(s.rawState)
                .append("  rate=").append(s.rawRate)
                .append("  like=").append(s.likeState)
                .append("  pos=").append(s.positionMs / 1000L)
                .append("/").append(s.durationMs / 1000L).append("s\n");
        sb.append("state 学习：播放时=").append(statePlaying < 0 ? "?" : String.valueOf(statePlaying))
                .append("  静止时=").append(stateIdle < 0 ? "?" : String.valueOf(stateIdle)).append('\n');
        sb.append("genre=").append(orEmpty(s.genre)).append('\n');
        sb.append("artist=").append(orEmpty(s.artist)).append('\n');
        sb.append("albumArtist=").append(orEmpty(s.albumArtist)).append('\n');
        sb.append("composer=").append(orEmpty(s.composer))
                .append("  composerAsArtist=").append(s.composerAsArtist).append('\n');
        sb.append("LikeState: LIKED=").append(bridge.likedState())
                .append(" NONE=").append(bridge.noneState())
                .append("  like=").append(s.likeState).append('\n');
        sb.append("最近收藏事件=").append(lastLikeEvent);
        sb.append("上报为=").append(track.artist);
        return sb.toString();
    }

    private static String orEmpty(String value) {
        return value == null || value.length() == 0 ? "(空)" : value;
    }

    public void log(String message) {
        log(message, null);
    }

    public void log(String message, Throwable error) {
        if (runtime != null) {
            runtime.log(message, error);
        }
    }
}
