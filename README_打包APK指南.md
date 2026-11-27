# 归位 FileRoute — Android 原生工程（免 Root / 免 Shizuku）

本工程根据你在网页端配置的 **7 条活跃规则**（5 条公共目录自动监听 + 2 条分享菜单一键归位）实时生成。

## 核心能力（全程零 Root、零 Shizuku、零电脑调试）

1. **公共下载目录自动监听**：针对微信 (`/Download/WeiXin/`)、新版 QQ (`/Download/QQ/`)、钉钉、飞书、浏览器等公共存储目录，仅需手机系统自带的「所有文件访问权限」，通过 Android 原生 `FileObserver` + 前台服务实时监测新下载并弹出顶部系统通知横幅。
2. **QQ / TIM 分享菜单一键归位**：针对位于 `Android/data` 的 QQ / TIM 私有目录文件，无需 Shizuku；在 QQ 内点开文件 → 右上角「用其他应用打开」或「分享」→ 选择「归位 FileRoute」，即刻从底部弹出目录选择器一键另存至任意目标目录。
3. **顶部通知横幅 → 底部目录选择器**：收到新文件通知后点击横幅，弹出 Material 3 `ModalBottomSheet`，一键选择归位目录、切换移动/复制或记住该扩展名默认目录。

---

## 方法一：无需安装 Android Studio（GitHub Actions 免费云构建 APK）

1. 在 GitHub 新建一个私有仓库（Private Repository）。
2. 将本压缩包解压后的全部文件上传至该仓库根目录（包含 `.github/workflows/build-apk.yml`）。
3. 打开仓库顶部的 **Actions** 页面，等待约 2 分钟构建完成。
4. 点击最新一次成功构建底部的 **Artifacts → FileRoute-debug-apk** 下载 APK，传到手机直接安装即可。

## 方法二：使用 Android Studio 本地打包

1. 安装 Android Studio（Ladybug 或更新版本，JDK 17）。
2. 选择 **Open** 打开本工程目录，等待 Gradle 自动同步依赖。
3. 点击菜单栏 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 在 `app/build/outputs/apk/debug/app-debug.apk` 获取安装包传到手机安装。

## 首次在 Android 15 手机上运行的 2 步授权

1. 打开「归位 FileRoute」，点击授权 **「所有文件访问权限」**（用于监测微信、钉钉、飞书与新版 QQ 公共目录）。
2. 开启 **「通知权限」**（用于弹出顶部下载提醒横幅）并将本 App 加入手机电池白名单。
