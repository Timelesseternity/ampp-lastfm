package dev.ampp.lastfm.core;

/** Last.fm 官方 scrobble 判定规则。纯函数，方便单测。 */
public final class ScrobbleRules {
    /** 短于 30 秒的音轨不计入。 */
    public static final long MIN_TRACK_MS = 30_000L;
    /** 最长只需要听 4 分钟即可计入。 */
    public static final long MAX_THRESHOLD_MS = 240_000L;

    private ScrobbleRules() {
    }

    /** 需要累计收听的毫秒数：min(240s, 时长的一半)。 */
    public static long thresholdMs(long durationMs) {
        if (durationMs <= 0) {
            return MAX_THRESHOLD_MS;
        }
        return Math.min(MAX_THRESHOLD_MS, durationMs / 2L);
    }

    public static boolean shouldScrobble(long durationMs, long playedMs) {
        if (durationMs < MIN_TRACK_MS) {
            return false;
        }
        return playedMs >= thresholdMs(durationMs);
    }
}
