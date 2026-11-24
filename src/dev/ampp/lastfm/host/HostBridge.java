package dev.ampp.lastfm.host;

import java.lang.reflect.Method;

import dev.amenhancer.plugin.api.PluginUnsupportedException;
import dev.ampp.lastfm.core.Snapshot;

/**
 * Apple Music 播放器适配层。
 * 类名来自 AM++ 的 host-profile（apple-music-7.0.3-1626），这些类在 7.0.x 上未被混淆。
 * 新版本失效时只需要改这里。
 */
public final class HostBridge {

    public static final String CLS_CONTROLLER =
            "com.apple.android.music.playback.controller.LocalMediaPlayerController";
    public static final String CLS_PLAYER =
            "com.apple.android.music.playback.player.MediaPlayer";
    public static final String CLS_QUEUE_ITEM =
            "com.apple.android.music.playback.model.PlayerQueueItem";
    public static final String CLS_MEDIA_ITEM =
            "com.apple.android.music.playback.model.PlayerMediaItem";


    private final Class<?> controllerClass;
    private final Class<?> playerClass;
    private final Class<?> queueItemClass;

    private final Method getCurrentItem;
    private final Method getItem;
    private final Method getCurrentPosition;
    private final Method getPlaybackState;
    private final Method getPlaybackRate;

    private final Method getTitle;
    private final Method getArtistName;
    private final Method getAlbumTitle;
    private final Method getAlbumArtistName;
    private final Method getComposerName;
    private final Method shouldShowComposerAsArtist;
    private final Method getGenreName;
    private final Method getItemDuration;
    private final Method getPersistentId;
    private final Method getSubscriptionStoreId;
    private final Method getAlbumTrackNumber;
    private final Method getAlbumDiscNumber;
    private final Method isLiveRadio;
    private final Method isMediaKindVideo;

    /** 收藏事件入口方法，拿不到为 null。 */
    private final Method likeEventMethod;
    /** 从歌单移除事件入口（撤销喜爱走这条），拿不到为 null。 */
    private final Method playlistRemoveMethod;
    /** 收藏状态枚举类型（MediaLibrary$f）。 */
    private final Class<?> likeStateClass;

    private HostBridge(ClassLoader host) throws PluginUnsupportedException {
        controllerClass = load(host, CLS_CONTROLLER);
        playerClass = load(host, CLS_PLAYER);
        queueItemClass = load(host, CLS_QUEUE_ITEM);
        Class<?> mediaItemClass = load(host, CLS_MEDIA_ITEM);

        getCurrentItem = require(playerClass, "getCurrentItem");
        getItem = require(queueItemClass, "getItem");
        getCurrentPosition = require(playerClass, "getCurrentPosition");
        getPlaybackState = require(playerClass, "getPlaybackState");
        getPlaybackRate = require(playerClass, "getPlaybackRate");

        getTitle = require(mediaItemClass, "getTitle");
        getArtistName = require(mediaItemClass, "getArtistName");
        getAlbumTitle = require(mediaItemClass, "getAlbumTitle");
        getAlbumArtistName = require(mediaItemClass, "getAlbumArtistName");
        getComposerName = optional(mediaItemClass, "getComposerName");
        shouldShowComposerAsArtist = optional(mediaItemClass, "shouldShowComposerAsArtist");
        getGenreName = require(mediaItemClass, "getGenreName");
        getItemDuration = require(mediaItemClass, "getDuration");
        getPersistentId = require(mediaItemClass, "getPersistentId");
        getSubscriptionStoreId = require(mediaItemClass, "getSubscriptionStoreId");
        getAlbumTrackNumber = require(mediaItemClass, "getAlbumTrackNumber");
        getAlbumDiscNumber = require(mediaItemClass, "getAlbumDiscNumber");
        isLiveRadio = optional(mediaItemClass, "isLiveRadio");
        isMediaKindVideo = optional(mediaItemClass, "isMediaKindVideo");

        Class<?> likeStateType = null;
        try {
            likeStateType = Class.forName(
                    "com.apple.android.medialibrary.library.MediaLibrary$f", false, host);
        } catch (Throwable ignored) {
            // 没有收藏状态枚举就不做点赞同步。
        }
        likeStateClass = likeStateType;

        Method eventMethod = null;
        try {
            Class<?> fragment = Class.forName(
                    "com.apple.android.music.common.BaseActivityFragment", false, host);
            Class<?> event = Class.forName(
                    "com.apple.android.music.medialibrary.events.ItemLoveSuccessMLEvent", false, host);
            eventMethod = fragment.getDeclaredMethod("updateLikedState", event);
        } catch (Throwable ignored) {
            // 事件入口缺失则退化为不做点赞同步。
        }
        likeEventMethod = eventMethod;

        Method playlistRemove = null;
        try {
            Class<?> fragment = Class.forName(
                    "com.apple.android.music.common.BaseActivityFragment", false, host);
            Class<?> event = Class.forName(
                    "com.apple.android.music.medialibrary.events.RemoveFromPlaylistSuccessMLEvent",
                    false, host);
            playlistRemove = fragment.getDeclaredMethod(
                    "onRemoveItemFromPlaylistSuccessMLEvent", event);
        } catch (Throwable ignored) {
            // 缺少该入口时撤销喜爱不可用。
        }
        playlistRemoveMethod = playlistRemove;
    }

    /**
     * 收藏成功事件：BaseActivityFragment#updateLikedState(ItemLoveSuccessMLEvent)。
     * 这是 AM 内部点赞/取消点赞真正会触发的地方，播放器快照不会刷新点赞状态。
     */
    public Method likeEventMethod() {
        return likeEventMethod;
    }

    /** 从歌单移除事件入口（撤销喜爱）。 */
    public Method playlistRemoveMethod() {
        return playlistRemoveMethod;
    }

    /** 解析收藏事件，拿不到返回 null。 */
    public LikeEvent parseLikeEvent(Object event) {
        if (event == null) {
            return null;
        }
        try {
            LikeEvent parsed = new LikeEvent();
            java.lang.reflect.Field idField = findField(event.getClass(), String.class);
            if (idField != null) {
                Object value = idField.get(event);
                parsed.id = value instanceof String ? (String) value : "";
            }
            java.lang.reflect.Field longField = findField(event.getClass(), long.class);
            if (longField != null) {
                parsed.numericId = longField.getLong(event);
            }
            if (likeStateClass != null) {
                java.lang.reflect.Field stateField = findField(event.getClass(), likeStateClass);
                if (stateField != null) {
                    Object value = stateField.get(event);
                    parsed.state = value == null ? "" : String.valueOf(value);
                }
            }
            return parsed;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 收藏事件的可读形式。 */
    public static final class LikeEvent {
        public String id = "";
        public long numericId;
        /** Liked / Unliked / None / Unknown */
        public String state = "";

        public boolean liked() {
            return "Liked".equals(state);
        }

        public boolean unliked() {
            return "Unliked".equals(state);
        }

        /** 是否指向当前播放的这首歌。 */
        public boolean matches(Snapshot snapshot) {
            if (snapshot == null) {
                return false;
            }
            if (numericId != 0L && numericId == snapshot.persistentId) {
                return true;
            }
            if (id.length() == 0) {
                return false;
            }
            if (snapshot.storeId.length() > 0
                    && (id.equals(snapshot.storeId) || id.endsWith(snapshot.storeId))) {
                return true;
            }
            return snapshot.persistentId != 0L
                    && id.equals(String.valueOf(snapshot.persistentId));
        }

        @Override
        public String toString() {
            return "id=" + id + " num=" + numericId + " state=" + state;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, Class<?> fieldType) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (field.getType() == fieldType) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }


    public static HostBridge resolve(ClassLoader host) throws PluginUnsupportedException {
        return new HostBridge(host);
    }

    public Class<?> controllerClass() {
        return controllerClass;
    }

    public Class<?> playerClass() {
        return playerClass;
    }

    public Class<?> queueItemClass() {
        return queueItemClass;
    }

    /** 定位控制器上的回调方法，用于注册观察。 */
    public Method controllerMethod(String name, Class<?>... parameterTypes) {
        try {
            return controllerClass.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 读取当前播放状态；失败返回 null。 */
    public Snapshot read(Object player) {
        if (player == null) {
            return null;
        }
        try {
            Object queueItem = invoke(getCurrentItem, player);
            if (queueItem == null) {
                return null;
            }
            Object mediaItem = invoke(getItem, queueItem);
            if (mediaItem == null) {
                return null;
            }
            Snapshot s = new Snapshot();
            s.title = str(invoke(getTitle, mediaItem));
            s.artist = str(invoke(getArtistName, mediaItem));
            s.album = str(invoke(getAlbumTitle, mediaItem));
            s.albumArtist = str(invoke(getAlbumArtistName, mediaItem));
            s.composer = str(invoke(getComposerName, mediaItem));
            s.composerAsArtist = bool(invoke(shouldShowComposerAsArtist, mediaItem), false);
            s.genre = str(invoke(getGenreName, mediaItem));
            s.storeId = str(invoke(getSubscriptionStoreId, mediaItem));
            s.persistentId = lng(invoke(getPersistentId, mediaItem), 0L);
            s.durationMs = lng(invoke(getItemDuration, mediaItem), 0L);
            s.positionMs = lng(invoke(getCurrentPosition, player), 0L);
            s.trackNumber = (int) lng(invoke(getAlbumTrackNumber, mediaItem), 0L);
            s.discNumber = (int) lng(invoke(getAlbumDiscNumber, mediaItem), 0L);
            int state = (int) lng(invoke(getPlaybackState, player), 0L);
            float rate = flt(invoke(getPlaybackRate, player), 0f);
            s.rawState = state;
            s.rawRate = rate;
            // 实测：播放中 state=1 rate=1.0，暂停 state=2 rate=0.0。
            s.playing = rate > 0f || state == 1;
            s.live = bool(invoke(isLiveRadio, mediaItem), false);
            s.video = bool(invoke(isMediaKindVideo, mediaItem), false);
            if (s.durationMs <= 0L) {
                s.durationMs = 0L;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> load(ClassLoader host, String name) throws PluginUnsupportedException {
        try {
            return Class.forName(name, false, host);
        } catch (ClassNotFoundException e) {
            throw new PluginUnsupportedException("当前 Apple Music 版本缺少 " + name + "，请更新插件");
        }
    }

    private static Method require(Class<?> type, String name) throws PluginUnsupportedException {
        Method m = optional(type, name);
        if (m == null) {
            throw new PluginUnsupportedException(
                    "当前 Apple Music 版本缺少 " + type.getSimpleName() + "#" + name + "()，请更新插件");
        }
        return m;
    }

    private static Method optional(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 继续向父类查找。
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object target) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static long lng(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static float flt(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
