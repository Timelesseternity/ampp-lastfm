package dev.ampp.lastfm.core;

/** 一次播放状态的只读快照。 */
public final class Snapshot {
    public String title = "";
    public String artist = "";
    public String album = "";
    public String albumArtist = "";
    public String composer = "";
    /** Apple 判断「这里应该把作曲家当作艺术家显示」（古典乐）。 */
    public boolean composerAsArtist;
    public String genre = "";
    public String storeId = "";
    public long persistentId;
    public long durationMs;
    public long positionMs;
    public int trackNumber;
    public int discNumber;
    public boolean playing;
    public boolean live;
    public boolean video;
    /** 宿主点赞状态（LikeState: NONE / LIKED / DISLIKED / UNKNOWN），-1 表示读不到。 */
    public int likeState = -1;
    /** 宿主原始播放状态码，仅用于日志诊断。 */
    public int rawState;
    /** 宿主原始播放速率，仅用于日志诊断。 */
    public float rawRate;

    /** 是否具备可上报的身份信息。 */
    public boolean usable() {
        return !live && title.length() > 0 && artist.length() > 0;
    }

    /** 用于判定"是否换歌"的稳定键。 */
    public String key() {
        if (storeId.length() > 0) {
            return "s:" + storeId;
        }
        if (persistentId != 0L) {
            return "p:" + persistentId;
        }
        return "t:" + title + '\u0001' + artist + '\u0001' + album;
    }

    public long durationSeconds() {
        return durationMs > 0 ? durationMs / 1000L : 0L;
    }

    @Override
    public String toString() {
        return artist + " - " + title + (album.length() > 0 ? " <" + album + ">" : "")
                + " " + positionMs / 1000L + "/" + durationMs / 1000L + (playing ? " playing" : " paused");
    }
}
