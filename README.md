# 归位 FileRoute v2.1 — Android 17（API 37）

![归位 FileRoute 仓库封面](docs/FileRoute-AppCover-Android17.png)

程序图标：

![归位 FileRoute 程序图标](docs/FileRoute-AppIcon.png)

免 Root、免 Shizuku。当前工程是 **v2.1 完整实机版**（compileSdk 37 / targetSdk 37 / AGP 8.10 / Kotlin 2.1），规则来自网页端配置的 **7 条活跃规则**（5 条公共目录自动监听 + 2 条分享菜单归位）。

最低安装系统仍是 Android 8.0（API 26）。

## 版本

| 版本 | 说明 | 安装包 |
| --- | --- | --- |
| [v1.0.0](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v1.0.0) | 只适配到 Android 15（API 35） | `FileRoute-v1.0.0-debug.apk` |
| [v2.0.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.0.0-android17) | 上一版，实机无反应、页面空白 | `FileRoute-v2.0.0-android17-debug.apk` |
| [v2.1.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.1.0-android17) | 当前版本 | `FileRoute-v2.1.0-android17-debug.apk` |

请安装 v2.1。

## v2.1 修复

1. **通知不再被静默丢掉**：触发式唤醒和前台守护在推送前都会注册 `fileroute_new_file_alert` 高优先级通道。
2. **不再死守固定路径**：`DownloadDiscoveryEngine` 同时查 `MediaStore.Downloads`、`MediaStore.Files`，并递归扫描 `/Download/` 与 `/Pictures/`，用 `OWNER_PACKAGE_NAME` 和 `RELATIVE_PATH` 识别来源应用。
3. **四个页面**：首页守护（权限、测试通知、立即扫描、待归位队列）、归位规则、历史记录与一键撤销、路径诊断。

## 安装

打开 [Releases](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.1.0-android17)，下载 `FileRoute-v2.1.0-android17-debug.apk`。推送到 `main` 后，Actions 会重新构建并更新这个 Release。构建产物名是 `FileRoute-android17-debug-apk`。

## 首次验证

1. 在首页打开 **所有文件访问权限** 和 **通知权限**，点 **发测试通知**，确认顶部横幅能弹出。
2. 先用 **前台实时守护**，到微信或 QQ 下载一个文件。确认有反应后，再切回 **触发式低功耗唤醒**。
3. 路径不对时打开 **诊断**，看手机上的真实目录和最新 MediaStore 记录。

## 本地打包

1. 安装 Android Studio（Meerkat 2024.3.1 或更新）、JDK 17，以及 Android 17 / API 37 SDK。
2. 打开本目录，等待 Gradle 8.11 同步。
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 安装包在 `app/build/outputs/apk/debug/app-debug.apk`。
