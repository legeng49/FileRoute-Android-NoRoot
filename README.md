# 归位 FileRoute v3.1 — Android 17（API 37）

![归位 FileRoute 仓库封面](docs/FileRoute-AppCover-Android17.png)

程序图标：

![归位 FileRoute 程序图标](docs/FileRoute-AppIcon.png)

免 Root、免 Shizuku。当前版本 **3.1.0**（compileSdk 37 / targetSdk 37）。推送 `v*` 标签后，GitHub Actions 会编译并把 APK 挂到对应 Release。已安装的旧版打开后会提示下载更新。

最低安装系统仍是 Android 8.0（API 26）。

## 版本

| 版本 | 安装包 |
| --- | --- |
| [v3.1.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.1.0-android17) | `FileRoute-v3.1.0-android17.apk` |
| [v3.0.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.0.0-android17) | `FileRoute-v3.0.0-android17-debug.apk` |
| [v2.1.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v2.1.0-android17) | `FileRoute-v2.1.0-android17-debug.apk` |

请安装 v3.1.0。

## 这一版

1. 应用内检查 GitHub Release，发现更新后下载 APK 并调起安装。
2. 推送 `v*` 标签（例如 `v3.1.0-android17`）时，Actions 自动把 APK 附到该 Release。
3. 通知仍可打开源文件夹。规则为 10 条。

## 安装

打开 [v3.1.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.1.0-android17)，下载 `FileRoute-v3.1.0-android17.apk`。

## 首次验证

1. 在首页打开 **所有文件访问权限** 和 **通知权限**，点 **发测试通知**。
2. 先用 **前台实时守护**，到微信或 QQ 下载一个文件。
3. 安装未知来源权限打开后，旧版才能安装从 Release 下载的更新包。
