# 归位 FileRoute v3.0 — Android 17（API 37）

![归位 FileRoute 仓库封面](docs/FileRoute-AppCover-Android17.png)

程序图标：

![归位 FileRoute 程序图标](docs/FileRoute-AppIcon.png)

免 Root、免 Shizuku。当前安装包版本是 **3.0.0**（compileSdk 37 / targetSdk 37），规则为 **10 条**（8 条公共目录自动监听 + 2 条分享菜单归位）。

最低安装系统仍是 Android 8.0（API 26）。

## 版本

| 版本 | 说明 | 安装包 |
| --- | --- | --- |
| [v1.0.0](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v1.0.0) | 只适配到 Android 15（API 35） | `FileRoute-v1.0.0-debug.apk` |
| [v2.0.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.0.0-android17) | 实机无反应、页面空白 | `FileRoute-v2.0.0-android17-debug.apk` |
| [v2.1.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.1.0-android17) | 7 条规则的实机版 | `FileRoute-v2.1.0-android17-debug.apk` |
| [v3.0.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.0.0-android17) | 当前版本 | `FileRoute-v3.0.0-android17-debug.apk` |

请安装 v3.0.0。

## 这一版

1. 规则增至 10 条。
2. 通知里可以 **打开源文件夹**。
3. 推送前仍会注册高优先级通知通道；发现引擎继续查 MediaStore，并扫描 `/Download/` 与 `/Pictures/`。
4. 四个页面：首页守护、归位规则、历史撤销、路径诊断。

## 安装

打开 [Releases](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.0.0-android17)，下载 `FileRoute-v3.0.0-android17-debug.apk`。

## 首次验证

1. 在首页打开 **所有文件访问权限** 和 **通知权限**，点 **发测试通知**。
2. 先用 **前台实时守护**，到微信或 QQ 下载一个文件。确认有反应后，再切回 **触发式低功耗唤醒**。
3. 路径不对时打开 **诊断**。

## 本地打包

1. 安装 Android Studio（Meerkat 2024.3.1 或更新）、JDK 17，以及 Android 17 / API 37 SDK。
2. 打开本目录，等待 Gradle 8.11 同步。
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 安装包在 `app/build/outputs/apk/debug/app-debug.apk`。
