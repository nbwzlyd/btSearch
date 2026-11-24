# ResFindLite 进度状态（2026-11-22 晚 中断点）

## 已完成并交付
**v2.0（种子质量 + 清晰度徽章）** —— 已装机验证通过、已交付：
`/Users/liyaodong/Downloads/磁力搜/deliverable/找资源-v2.0-种子质量+清晰度.apk`
- 列表里每条形如：`🌱14 种 · 🔥69 下载中 · 4K`
- 种子质量走 HTTP tracker announce（DHT 在本网络不可用，UDP 6881 境外全被拦）
- 清晰度用 QualityTag 正则，已修 CJK 边界问题（「4K增强版」能识别）

## 进行中：磁力「边下边播」（代码完成、编译通过、**未真机验证**）
文件：
- `play/TorrentStreamSession.kt` —— jlibtorrent 拉元数据 + 只下最大的视频文件 + 顺序下载
- `play/LocalTorrentServer.kt` —— 127.0.0.1 本地 HTTP，支持 Range，按分片阻塞等待
- `play/PlayActivity.kt` —— ExoPlayer(media3 1.4.1) 播放本地流
- `addon/TrackerProbe.kt` —— 新增 `fetchPeers()` 抓 compact peers
- `net/SearchEngine.kt` —— 新增 `fetchBytes()`（bencode 二进制不能按文本解码）

### 已查明的两个关键 bug（都已在代码里修掉）
1. **magnet 加入后 libtorrent 会给 torrent 打 PAUSED 标志**（实测 `flags=24`，含 PAUSED=16），
   暂停状态下完全不 announce → peer 恒 0 → 永远拿不到元数据。
   `download()` 只清 AUTO_MANAGED，必须自己 `handle.resume()` → flags 变 8。
2. **Android 上即使 resume 了还是 peers=0**（桌面同样代码 3 秒就成功）。
   绕过办法：先用 OkHttp 手写 announce 抓 40 个 compact peer，
   再 `add_torrent_params.set_peers()` 直接注入，libtorrent 不靠 tracker 也能拉元数据。
   **桌面 T8 实测：注入后 5 秒拿到 METADATA。**

### 踩过的坑（别再犯）
- `AlertListener.alert()` 跑在 native 线程，回调里碰 swig 对象 → **SIGABRT 进程崩溃**。已彻底移除。
- `AlertType.values().map{swig()}` 会因 UNKNOWN 项返回 -1 → `ArrayIndexOutOfBoundsException`，要 `.filter{it>=0}`。
- `TorrentStatus` **没有** `isNoError`；`Sha1Hash` **没有** parseHex（自己 hex→bytes）。
- K40 MIUI 禁 adb input 注入，只能 `am start --es keyword/--es extra_magnet` 触发。

## 恢复工作时
1. 手机插回 → `adb devices`，确认是 `d4083aa6`（K40 / M2012K11AC），别用错另一台 24117RK2CC
2. 装 debug 包验证边下边播：
   ```
   adb -s d4083aa6 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s d4083aa6 shell am start -n com.aifindres.lite/com.aifindres.lite.play.PlayActivity \
     --es extra_magnet "magnet:?xt=urn:btih:ed0f013475535f19b5410d68fd20f627e4950b1f&dn=archlinux"
   ```
   看 logcat 的 `TorrentStream` tag：应出现 `injecting N peers` → `wait metadata` 里 peers 上升 → `ready: ...` → ExoPlayer 出画面
3. 通了之后打 release（`signing.gradle` 复用 debug.keystore），再交付

## 构建命令
```
cd /Users/liyaodong/Downloads/磁力搜/ResFindLite
GRADLE_USER_HOME=/Users/liyaodong/Documents/dev_new/yingshicang_dev/gradle_cache \
  java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --no-daemon
```
