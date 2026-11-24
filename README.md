# btSearch（找资源 / ResFindLite）

一个 Android 磁力搜索 App。原始版本（`com.aifindres.lite` v1.1）源码丢失，
本项目是按其行为**用 Kotlin 完整复刻重写**的版本，并在原功能基础上增加了
「种子质量」「清晰度」徽章和「磁力边下边播」能力。

## 功能

- **29 个站点聚合搜索**：站点配置在 `app/src/main/assets/sites.json`，
  每个站点用 `request{method,mainUrl,searchUrl,header}` + XPath 模板
  `expressionModel{group,title,date,size,url,magnet}` 描述，支持 `[keyword]`/`[page]`/`[page0]` 占位符。
  可多选站点、分页加载更多（扫描时并发 6 路）。
- **种子质量徽章** 🌱*N* 种 · 🔥*M* 下载中：不走 DHT（国内网络境外 UDP 6881 基本不可用），
  而是取 magnet 里的 `tr=` 做 HTTP tracker announce，解析 bencode 的 `complete` / `incomplete`。
- **清晰度徽章**：从标题正则解析 8K / 4K / 1080P / 720P / 杜比视界 / HDR / HEVC / 全景声。
- **边下边播**：点结果里「⚡ 在线预览」即可直接播放（jlibtorrent 拉流 + ExoPlayer 解码）。
- 支持一键复制磁力、调用迅雷下载。

## 工程结构

```
app/src/main/kotlin/com/aifindres/lite/
├── MainActivity.kt          # ViewPager2 + BottomNav
├── data/                    # 数据模型与站点仓库
│   ├── MagnetItem.kt        # 结果项（含 @Volatile 探测状态）
│   ├── Sites.kt             # 站点配置模型
│   ├── Prefs.kt             # SharedPreferences（启用的站点等）
│   └── SiteRepository.kt    # 读取 assets/sites.json
├── net/
│   ├── HtmlParser.kt        # HtmlCleaner + XPath
│   └── SearchEngine.kt      # 抓取、占位符替换、磁力归一化
├── addon/
│   ├── TrackerProbe.kt      # HTTP tracker announce → 做种/下载中 + 抓 compact peers
│   ├── QualityTag.kt        # 标题 → 清晰度徽章
│   └── DhtProbe.kt          # BEP-9 DHT 探测（当前网络不可用，保留备查）
├── ui/
│   ├── MagnetFragment.kt    # 搜索页：并发扫描、去重、详情弹窗
│   ├── MagnetAdapter.kt     # 结果列表
│   └── SitesSheet.kt        # 站点管理面板
└── play/                    # 边下边播
    ├── TorrentStreamSession.kt  # jlibtorrent 会话：拉元数据、选最大视频文件、顺序下载
    ├── LocalTorrentServer.kt    # 127.0.0.1 HTTP 服务，支持 Range/HEAD，按分片阻塞等待
    └── PlayActivity.kt          # ExoPlayer 播放本地流转出的视频
```

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35，minSdk 23，targetSdk 28）。

```bash
# 先写好 local.properties
echo 'sdk.dir=/path/to/Android/sdk' > local.properties

./gradlew :app:assembleDebug      # 或 :app:assembleRelease
```

> 本仓库没有 `gradlew` 脚本时，可直接用 wrapper jar：
> ```bash
> java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug
> ```

`app/signing.gradle` 默认复用 `~/.android/debug.keystore` 给 release 签名，
便于侧载覆盖安装（原应用的签名密钥已随源码丢失）。正式发布请替换成自己的 keystore。

## 原生库说明

BT 引擎用 [libtorrent4j](https://github.com/aldenml/libtorrent4j) **2.1.0-39**（libtorrent 2.1.0.0，MIT）。

> 早期用过 frostwire/jlibtorrent 1.2.0.18，但那个 arm64 构建（2018 年编译）在 Android 上
> 存在异常（连 TCP listen socket 都建不起来、native 工作线程不起），已整体替换为 libtorrent4j。

AGP **不会**从本地 jar 里提取 `.so`，所以原生库是手动解压后放在 `jniLibs` 的：

- `app/src/main/jniLibs/arm64-v8a/libtorrent4j.so` —— 真机 arm64（16MB）
- `app/src/main/jniLibs/armeabi-v7a/libtorrent4j.so` —— 真机 32 位（14MB）
- `app/src/debug/jniLibs/x86_64/libtorrent4j.so` —— 仅 debug 包（模拟器验证用）

Java API 只用 `app/libs/libtorrent4j-2.1.0-39.jar`（纯 Java，无 native，816KB）。
Maven 坐标 `org.libtorrent4j:libtorrent4j:2.1.0-39`，原生库在
`libtorrent4j-android-arm64` / `-android-arm` / `-android-x86_64` 三个包里。

## 边下边播已知的坑（改这块代码前先读，全是真机踩出来的）

1. **★ 绝对不要设 `outgoing_interfaces`**。在 Android 上把它写成具体本机 IP 后，
   libtorrent 的出站连接会**全部失效且不报错**：`/proc/net/tcp` 里连 SYN_SENT 都没有、
   peers 恒 0、永远拿不到元数据。这个坑花了两天才定位（逐档 settings 对照法：
   0=全默认 ✅ / 仅 utp+tracker ✅ / 仅 listen_interfaces ✅ / **仅 outgoing_interfaces ❌**）。
   `listen_interfaces` 保持默认双栈通配 `0.0.0.0:6881,[::]:6881` 最稳。
2. **magnet 加入后 libtorrent 会给 torrent 打 `PAUSED` 标志**，暂停态完全不 announce。
   `download()` 只清 `AUTO_MANAGED`，必须自己 `handle.resume()`
   （并 `unsetFlags(AUTO_MANAGED)` 让 flags 从 40 变成 8，与桌面成功态一致）。
3. **国内网络封非 53 端口 UDP**（实测 UDP 6881 全 timeout，只有 UDP 53 通），而
   libtorrent 默认 `enable_outgoing_utp=true` 会优先走 uTP(UDP) → 连不上 peer。
   必须关掉 `enable_outgoing_utp` / `enable_incoming_utp` 强制走 TCP。
4. **`TorrentStatus.total()` 在 libtorrent 2.x 恒为 0**（1.x 的旧语义已废弃），
   进度要用 `totalWanted()` / `totalWantedDone()`，并用视频文件大小兜底，
   否则界面显示「0% · 0 B」。
5. **MP4 的 moov 常在文件尾部**，ExoPlayer 打开时会先请求末尾，而顺序下载把末尾排最后。
   必须由 HTTP 层调用 `prioritizeWindow()` 把播放器要的窗口提到最前，否则一直等到超时。
6. **libtorrent 建文件之前 `RandomAccessFile` 会抛 FileNotFoundException**，
   服务启动时要先 `createNewFile()` 占位，读到 0 字节时退避重试。
7. **`AlertListener.alert()` 跑在 native 线程**，回调里碰 swig 对象会直接 SIGABRT；
   而且 `AlertType` 里有 `swig()==-1` 的项，`modifyListeners` 会抛
   `ArrayIndexOutOfBoundsException`。诊断请用轮询 `TorrentStatus`。
8. **列表页的 magnet 常是截断显示文本**（如 `btih:D2F6...n%3A8000`），这种哈希不可用，
   进播放页只会白等 60 秒超时，入口处要先校验（`MagnetItem.infoHash() == null` 就拦下）。
9. **不要把 `iso` 当视频扩展名**：ISO 镜像不是视频，ExoPlayer 会报
   `UnrecognizedInputFormatException`。

## 真机验证记录

红米 K40（M2012K11AC / Android 13 / MIUI）实测：
- 元数据 5 秒内到手，peer 连接正常；
- 清空缓存后播放 263MB 的 Big Buck Bunny，**下载到 45% 时画面已经在播**，
  91% / 100% 全程流畅，下载速度峰值 14.9 MB/s；
- release 包（19MB，含 arm64+v7a 两个 libtorrent4j.so）同样验证通过。

## License

本项目代码供学习交流。`sites.json` 内的站点地址来自公开网络，其可用性不由本项目保证。
jlibtorrent 遵循 MIT 协议。
