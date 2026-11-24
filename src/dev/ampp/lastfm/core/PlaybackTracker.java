package dev.ampp.lastfm.core;

/**
 * 播放会话状态机：把每秒的快照转换成"开始播放 / 计入一次收听"两种事件。
 * 处理换歌、暂停、拖动进度、单曲循环。
 */
public final class PlaybackTracker {

    public interface Sink {
        void onNowPlaying(Snapshot snapshot);

        void onScrobble(Snapshot snapshot, long startedAtEpochSec);
    }

    /** 单次轮询最多累计的时长，避免后台卡顿后一次灌入大量时间。 */
    private static final long MAX_STEP_MS = 5_000L;
    /** 进度回退超过该值视为重新开始（单曲循环）。 */
    private static final long RESTART_POSITION_MS = 3_000L;

    private final Sink sink;
    private String currentKey;
    private long startedAtEpochSec;
    private long playedMs;
    private long lastPositionMs;
    private boolean nowPlayingSent;
    private boolean scrobbled;

    public PlaybackTracker(Sink sink) {
        this.sink = sink;
    }

    public synchronized void reset() {
        currentKey = null;
        playedMs = 0L;
        lastPositionMs = 0L;
        nowPlayingSent = false;
        scrobbled = false;
    }

    /** 本曲该发的都发完了（Now Playing 已上报且 scrobble 已结算），可以降频轮询。 */
    public synchronized boolean isSettled() {
        return nowPlayingSent && scrobbled;
    }

    public synchronized void update(Snapshot snapshot, long nowMs) {
        if (snapshot == null || !snapshot.usable()) {
            // 停止播放（队列清空、直播、无元数据）：只结束当前会话，不上报。
            reset();
            return;
        }

        String key = snapshot.key();
        boolean sameTrack = key.equals(currentKey);
        boolean restarted = sameTrack
                && snapshot.positionMs + RESTART_POSITION_MS < lastPositionMs
                && snapshot.positionMs < RESTART_POSITION_MS;

        if (!sameTrack || restarted) {
            if (restarted && !scrobbled && ScrobbleRules.shouldScrobble(lastKnownDurationMs, playedMs)) {
                // 单曲循环：上一轮已经达标但还没结算，补一次。
                sink.onScrobble(lastSnapshot, startedAtEpochSec);
            }
            beginSession(snapshot, key, nowMs);
            return;
        }

        long delta = snapshot.positionMs - lastPositionMs;
        boolean advancing = delta > 0;
        if (advancing) {
            // 进度真的在前进才算听过；拖动跳转的部分被 MAX_STEP_MS 截断。
            playedMs += Math.min(delta, MAX_STEP_MS);
        }
        lastPositionMs = snapshot.positionMs;
        lastKnownDurationMs = snapshot.durationMs;
        lastSnapshot = snapshot;

        if (!nowPlayingSent && (snapshot.playing || advancing)) {
            nowPlayingSent = true;
            sink.onNowPlaying(snapshot);
        }

        if (!scrobbled && ScrobbleRules.shouldScrobble(snapshot.durationMs, playedMs)) {
            scrobbled = true;
            sink.onScrobble(snapshot, startedAtEpochSec);
        }
    }

    private long lastKnownDurationMs;
    private Snapshot lastSnapshot;

    private void beginSession(Snapshot snapshot, String key, long nowMs) {
        currentKey = key;
        playedMs = 0L;
        lastPositionMs = snapshot.positionMs;
        nowPlayingSent = false;
        scrobbled = false;
        lastKnownDurationMs = snapshot.durationMs;
        lastSnapshot = snapshot;
        // 从中间开始播（例如恢复上次进度）时，按起点折算已经听过的时间。
        if (snapshot.positionMs > 0) {
            playedMs = Math.min(snapshot.positionMs, ScrobbleRules.MAX_THRESHOLD_MS);
        }
        startedAtEpochSec = (nowMs - snapshot.positionMs) / 1000L;
        if (snapshot.playing || snapshot.positionMs > 0) {
            nowPlayingSent = true;
            sink.onNowPlaying(snapshot);
        }
    }
}
