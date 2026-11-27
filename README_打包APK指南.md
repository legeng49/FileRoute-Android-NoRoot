# 归位 FileRoute v2.0 — Android 17（API 37）

免 Root、免 Shizuku。当前工程按 **Android 17** 构建（compileSdk 37 / targetSdk 37 / AGP 8.10 / Kotlin 2.1），规则来自网页端配置的 **7 条活跃规则**（5 条公共目录自动监听 + 2 条分享菜单一键归位）。

最低安装系统仍是 Android 8.0（API 26）。「适配到 Android 17」指目标版本升到 API 37，不是只能装在 Android 17 上。

## 版本

| 版本 | 目标系统 | 安装包 |
| --- | --- | --- |
| [v1.0.0](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v1.0.0) | Android 15（API 35） | `FileRoute-v1.0.0-debug.apk` |
| [v2.0.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.0.0-android17) | Android 17（API 37） | `FileRoute-v2.0.0-android17-debug.apk` |

v1.0.0 只适配到 Android 15。Android 16 / 17 请安装 v2.0.0。

## Android 17（API 37）适配

1. **前台服务超时接管**：遵循 Android 15–17 `dataSync` 前台服务 6 小时配额。`onTimeout()` 触发后交给 `WorkManager` 与 MediaStore 被动唤醒，避免超时崩溃。系统拒绝启动前台服务时，同样自动退回触发式唤醒。
2. **媒体库即时刷新**：移动或复制完成后调用 `MediaScannerConnection.scanFile()`，相册和系统文件管理器马上能看到新文件。
3. **大屏与平板**：满足 API 37 在 `sw >= 600dp` 上取消方向锁定、允许调整窗口的要求。界面用 `BoxWithConstraints` 在手机上单栏、在平板和折叠屏上双栏。
4. **全面屏与预测性返回**：接入 `enableEdgeToEdge()`、`enableOnBackInvokedCallback="true"`，分享入口改用 `IntentCompat.getParcelableExtra()`。

## 安装

打开 [Releases](https://github.com/legeng49/FileRoute-Android-NoRoot/releases)，下载 **v2.0.0-android17** 的 `FileRoute-v2.0.0-android17-debug.apk`，传到手机安装。

每次推送到 `main` 后，GitHub Actions 会重新构建，并更新这个 Release。构建记录在 [Actions](https://github.com/legeng49/FileRoute-Android-NoRoot/actions)，产物名是 `FileRoute-android17-debug-apk`。

## 本地打包

1. 安装 Android Studio（Meerkat 2024.3.1 或更新版本）、JDK 17，以及 Android 17 / API 37 SDK。
2. 用 **Open** 打开本目录，等待 Gradle 8.11 同步。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 安装包在 `app/build/outputs/apk/debug/app-debug.apk`。

## 首次在 Android 17 上授权

1. 打开「归位 FileRoute」，授予 **所有文件访问权限**，用于监测微信、钉钉、飞书和新版 QQ 的公共目录。
2. 开启 **通知权限**，用于顶部下载提醒。默认使用触发式唤醒时，不需要长期常驻后台。
