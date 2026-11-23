# AM++ Last.fm Scrobbler 插件

自用插件。把 Apple Music（Android）的当前播放推送到 Last.fm。AM++ 插件 SDK v1 格式。

<table>
  <tr>
    <td align="center"><img width="320" src="https://github.com/user-attachments/assets/05a4090c-047f-4fa8-b8bd-23e727c56796" alt="插件设置页" /></td>
    <td align="center"><img width="320" src="https://github.com/user-attachments/assets/af1e8629-cc2f-4359-b1b9-8d246b55a5f2" alt="诊断信息" /></td>
  </tr>
  <tr>
    <td align="center">插件设置页</td>
    <td align="center">诊断信息</td>
  </tr>
</table>

## 功能

- Now Playing 实时上报（切歌即发）
- 自动 scrobble，规则与 Last.fm 官方一致：时长 ≥ 30 秒，且累计收听 ≥ min(240 秒, 时长一半)
- 时间戳使用「开始播放的时刻」，不是提交时刻
- 离线队列：网络失败/未登录先落盘，恢复后批量补交（单批 ≤ 50 条）
- 跳过 MV / 视频；单曲循环按轮次分别计数；拖动进度不会虚增收听时长
- scrobble 会带上 `albumArtist` 与 `trackNumber`，提高专辑/曲序匹配率

## 名字归一规则

| 场景 | 处理 | 开关 |
|---|---|---|
| 古典乐（保守） | `shouldShowComposerAsArtist()` 为真且作曲家非空时，`artist` 用作曲家，原艺术家落到 `albumArtist` | 古典乐用作曲家作为艺术家（默认开） |
| 古典乐（激进） | 只要有作曲家就用，不看 Apple 的判断 | 古典乐总是使用作曲家（默认关） |
| 合辑 | `artist == "Various Artists"` 且专辑艺术家不是 Various Artists 时，换成专辑艺术家 | 合辑 Various Artists 归一 |
| 多艺术家 | **原样透传**，不按 `&` / `、` / `·` / `feat.` 切分 | 无（固定） |

多艺术家不切分是有意的。Apple Music 的中文显示里：`、` 和 `&` 都可能是分隔符（`王羽佳、柏林爱乐乐团、西蒙 · 拉特尔爵士` / `里卡多 · 慕提 & 凱魯畢尼管弦樂團`），但 `·` 是**名字内部的空格替代符**（`劳拉 · 范 · 德 · 海登` = Laura van der Heijden），切分会把名字拆碎；`Simon & Garfunkel`、`Earth, Wind & Fire` 这类组合名本身就含分隔符。所以整串透传是唯一不会造出错误艺人的做法。

**做不到的**：Apple Music 7.0.3 的 `PlayerMediaItem` 只有 `getTitle()`，没有作品名（work）/ 乐章（movement）字段，所以无法把古典曲目拼成「作曲家 - 作品名: 乐章」，`track` 保持 Apple 原名。

## 数据来源

不依赖框架 MediaSession，直接读 Apple Music 自己的播放器（类名未混淆）：

| 用途 | 目标 |
|---|---|
| 播放器实例 | `playback.controller.LocalMediaPlayerController#onPlaybackStateChanged(MediaPlayer,int,int)` |
| 切歌 | `#onPlaybackIndexChanged(MediaPlayer,int,int)` |
| 元数据 | `#onMetadataUpdated(MediaPlayer, PlayerQueueItem)` |
| 进度/状态 | `MediaPlayer#getCurrentPosition/getDuration/getPlaybackState/getPlaybackRate` |
| 曲目信息 | `PlayerQueueItem#getItem()` → `PlayerMediaItem#getTitle/getArtistName/getAlbumTitle/getDuration/...` |

类名取自 AM++ 1.7.1 内置的 `host-profiles/7.0.3-1626.json`。Apple Music 升级后若这些类改名，只需要改 `host/HostBridge.java`。

## 构建

只需要 JDK 17 + `android.jar` + `d8.jar`，不需要 Gradle / Android Studio / AM++ 源码。

本机已经下好便携工具链（`toolchain/jdk-17.0.21.1+2`、`lib/android.jar`、`lib/d8.jar`），直接：

```bash
export JAVA_HOME="$(pwd)/toolchain/jdk-17.0.21.1+2"
bash build.sh
```

产物 `ampp-lastfm.zip`，根目录含 `plugin.json` / `code.jar`（内含 classes.dex）/ `assets`。

`lib/ampp-plugin-api-v1.jar` 不存在时会由 `sdk-stub/` 自动生成 —— 那份桩是按 AM++ 1.7.1 的真实 dex 签名导出的，仅用于编译，不会打进插件。

要重新下载工具链：`python fetch-toolchain.py`。

## 已验证 / 未验证

已静态验证（对着你给的 apks 逐项比对）：

- 插件 dex 里 13 处 SDK 方法引用，与 AM++ 1.7.1 真实 dex 完全一致
- 19 处宿主目标（类 + 方法 + 描述符）在 Apple Music 7.0.3 (1626) 的 dex 中全部命中
- 产物结构：`plugin.json` + `code.jar`，dex 内无 SDK 类泄漏（否则加载器会拒绝）
- SDK 类型身份：`PluginContext`/`PluginHooks`/`PluginObserver`/`PluginRegistration`/`PluginSettingsSession` 是 interface，`PluginCall`/`PluginObservation` 是 final class —— 桩类已按此声明，避免 `IncompatibleClassChangeError`

未验证（必须真机）：

- `getPlaybackState()` 的常量含义。所以「是否在播」以**进度是否前进**为准，状态码只作辅助
- 三个 `observe` 目标在真实播放时是否被调用
- Last.fm 授权流程与上报


## 安装

1. Apple Music → 设置 → AM++ 模块设置 → 插件 → 导入 ZIP
2. 启用「下次启动启用」，完全停止并重开 Apple Music
3. 打开插件设置：
   - 填 API Key / API Secret（在 https://www.last.fm/api/account/create 申请）
   - 点「开始授权」→ 浏览器里授权 → 回来点「完成授权」

日志：`adb logcat -s AppleMusicEnhancer`

## 已知限制

- 设置页用原生 View，未使用 Miuix/Compose（v1 打包边界不接受 AAR 资源）。功能可用，样式与 AM++ 原生页面不一致。
- 播放状态靠 1 秒轮询，切换瞬间的 Now Playing 可能晚 1 秒内。
- 直播电台不上报。

## 版本

当前 `0.1.0`。之后的小修小步都在 `0.1.x` 上迭代，每次发布 `versionCode` +1。
AM++ 按插件 ID 判断替换，`versionCode` 只用于展示版本变化，不做升降级门禁。

## 目录结构

```
ampp-lastfm/
├── plugin.json          插件清单（id / 版本 / 入口类）
├── build.sh             构建脚本：javac → d8 → zip
├── fetch-toolchain.py   下载便携工具链（JDK17 / android.jar / d8）
├── sdk-stub/            AM++ 插件 SDK 的编译桩，仅编译期使用，不进产物
├── src/dev/ampp/lastfm/
│   ├── LastfmPlugin.java      入口：生命周期、Hook 注册、轮询、上报
│   ├── host/HostBridge.java   Apple Music 播放器适配层（换版本只改这里）
│   ├── core/                  PlaybackTracker / ScrobbleRules / Track / 队列 / 配置
│   ├── net/LastfmClient.java  签名、Now Playing、scrobble、token 授权
│   └── ui/SettingsView.java   设置页（纯代码 View，无资源文件）
└── assets/              可选普通素材
```

## 许可证

GPL-3.0。AM++ 本体是 GPLv3，本插件在其进程内运行并链接其插件 API，
`sdk-stub/` 重新声明了该 API 的接口签名，因此沿用同一许可证最稳妥。
