package dev.ampp.lastfm.core;

/**
 * 最终上报给 Last.fm 的曲目信息。
 * 把「Apple 给的原始元数据」按用户配置归一成「要写进 Last.fm 的名字」。
 */
public final class Track {
    public String artist = "";
    public String title = "";
    public String album = "";
    public String albumArtist = "";
    public long durationSec;
    public int trackNumber;

    private static final String VARIOUS = "various artists";

    public static Track from(Snapshot snapshot, Config config) {
        Track track = new Track();
        track.title = snapshot.title;
        track.album = snapshot.album;
        track.artist = snapshot.artist;
        track.albumArtist = snapshot.albumArtist;
        track.durationSec = snapshot.durationSeconds();
        track.trackNumber = snapshot.trackNumber;

        // 古典：Apple 判断「这里应该显示作曲家」时跟着它走；激进模式下只要有作曲家就用。
        boolean useComposer = snapshot.composer.length() > 0
                && ((config.classicalComposerAsArtist && snapshot.composerAsArtist)
                    || config.classicalAlwaysComposer);
        if (useComposer) {
            track.albumArtist = snapshot.artist;
            track.artist = snapshot.composer;
        }

        // 合辑：Various Artists 不是真实艺人，换成专辑艺术家。
        if (config.mergeVariousArtists
                && isVarious(track.artist)
                && track.albumArtist.length() > 0
                && !isVarious(track.albumArtist)) {
            track.artist = track.albumArtist;
        }

        return track;
    }

    private static boolean isVarious(String name) {
        return name != null && VARIOUS.equals(name.trim().toLowerCase());
    }

    @Override
    public String toString() {
        return artist + " - " + title + (album.length() > 0 ? " <" + album + ">" : "");
    }
}
