# 归位 FileRoute v3.2.2 — Android 17（API 37）

![归位 FileRoute 仓库封面](docs/FileRoute-AppCover-Android17.png)

程序图标：

![归位 FileRoute 程序图标](docs/FileRoute-AppIcon.png)

免 Root。当前版本 **3.2.2**。从这一版起，GitHub 上的安装包使用同一把签名，之后可以覆盖安装。3.2.1 及更早的包每次构建签名都不同，系统会报「安装失败(-7) / 与已安装应用签名不同」。遇到这个错误时，先在系统设置里卸载「归位 FileRoute」，再安装 3.2.2。卸载前如需保留规则，用应用里的「导出 JSON」。

最低安装系统仍是 Android 8.0（API 26）。公共目录监听不需要 Shizuku。

## 版本

| 版本 | 安装包 |
| --- | --- |
| [v3.2.2-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.2.2-android17) | `FileRoute-v3.2.2-android17.apk` |
| [v3.2.1-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.2.1-android17) | `FileRoute-v3.2.1-android17.apk` |
| [v3.2.0-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.2.0-android17) | `FileRoute-v3.2.0-android17.apk` |

请先卸载当前应用，再安装 v3.2.2。之后从 v3.2.2 往上升级不必再卸载。

## 这一版

1. 安装包改为固定签名。卸载一次并装上 3.2.2 之后，后续 Release 可以覆盖安装。
2. 保留 3.2.1 的微信私有目录诊断，以及通过 Shizuku 读取、删除私有目录文件。
3. 推送 `v*` 标签后，Actions 自动把 APK 附到对应 Release。

## 安装

打开 [v3.2.2-android17](https://github.com/legeng49/FileRoute-Android-NoRoot/releases/tag/v3.2.2-android17)，下载 `FileRoute-v3.2.2-android17.apk`。
