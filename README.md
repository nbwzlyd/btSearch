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

BT 引擎用 [frostwire/jlibtorrent](https://github.com/frostwire/frostwire-jlibtorrent) 1.2.0.18（MIT）。

AGP **不会**从本地 jar 里提取 `.so`，所以原生库是手动解压后放在 `jniLibs` 的：

- `app/src/main/jniLibs/arm64-v8a/libjlibtorrent-1.2.0.18.so` —— 真机 arm64
- `app/src/main/jniLibs/armeabi-v7a/libjlibtorrent-1.2.0.18.so` —— 真机 32 位
- `app/src/debug/jniLibs/x86_64/libjlibtorrent-1.2.0.18.so` —— 仅 debug 包（模拟器验证用）

Java API 只用 `app/libs/jlibtorrent-1.2.0.18.jar`（纯 Java，无 native）。

## 边下边播已知的坑（改这块代码前先读）

1. **magnet 加入后 libtorrent 会给 torrent 打 `PAUSED` 标志**，暂停态完全不 announce，
   表现为 peers 恒 0、永远拿不到元数据。`download()` 只清 `AUTO_MANAGED`，
   必须自己 `handle.resume()`。
2. **Android 上 announce 可能不生效**（同代码在桌面正常）。兜底方案：自己用 OkHttp
   发 HTTP announce 抓 compact peers，再 `add_torrent_params.set_peers()` 注入。
3. **MP4 的 moov 常在文件尾部**，ExoPlayer 打开时会先请求末尾，而顺序下载把末尾排最后。
   必须由 HTTP 层调用 `prioritizeWindow()` 把播放器要的窗口提到最前，否则一直等到超时。
4. **libtorrent 建文件之前 `RandomAccessFile` 会抛 FileNotFoundException**，
   服务启动时要先 `createNewFile()` 占位，读到 0 字节时退避重试。
5. **`AlertListener.alert()` 跑在 native 线程**，回调里碰 swig 对象会直接 SIGABRT，
   诊断请用轮询 `TorrentStatus`。

## License

本项目代码供学习交流。`sites.json` 内的站点地址来自公开网络，其可用性不由本项目保证。
jlibtorrent 遵循 MIT 协议。
