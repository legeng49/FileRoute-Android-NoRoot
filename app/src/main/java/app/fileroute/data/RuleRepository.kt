package app.fileroute.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChannelMode { PUBLIC_OBSERVER, SHARE_INTENT }
enum class RouteAction { PROMPT, AUTO_MOVE, AUTO_COPY, IGNORE }
enum class PassiveTriggerType { MEDIASTORE_TRIGGER, INTENT_FILTER, FOREGROUND_OBSERVER }

data class ExtensionSubRule(
    val label: String,
    val extensions: List<String>,
    val targetDir: String
)

data class QuickFolder(
    val label: String,
    val path: String,
    val description: String
)

data class AppRouteRule(
    val id: String,
    val appName: String,
    val packageName: String,
    val enabled: Boolean,
    val passiveWakeEnabled: Boolean,
    val passiveTrigger: PassiveTriggerType,
    val channel: ChannelMode,
    val action: RouteAction,
    val sourcePaths: List<String>,
    val defaultTargetDir: String,
    val renamePattern: String,
    val subRules: List<ExtensionSubRule>,
    val cleanupStrategy: String = "keep",
    val cleanupDelayDays: Int = 7
)

data class DetectedDownloadItem(
    val id: String,
    val ruleId: String,
    val appName: String,
    val fileName: String,
    val sourcePath: String,
    val fileSizeBytes: Long,
    val suggestedTargetDir: String,
    val matchedLabel: String?,
    val detectedAtText: String
)

data class TransferHistoryItem(
    val id: String,
    val appName: String,
    val fileName: String,
    val fromPath: String,
    val toPath: String,
    val fileSizeBytes: Long,
    val copyOnly: Boolean,
    val restored: Boolean,
    val timeText: String,
    val mode: String = "prompt_confirmed",
    val status: String = "completed"
)

data class DirectoryDiagItem(
    val label: String,
    val path: String,
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val fileCount: Int,
    val latestFileName: String?,
    val hint: String = ""
)

data class DiscoveredDeviceDir(
    val path: String,
    val inferredAppName: String,
    val matchedRuleId: String?,
    val ownerPackages: List<String>,
    val fileCount: Int,
    val latestFileName: String?,
    val latestModifiedMillis: Long,
    val discoverySource: String,
    val alreadyBound: Boolean
)

data class RecentMediaSample(
    val fileName: String,
    val dirPath: String,
    val ownerPackage: String,
    val inferredAppName: String
)

object DefaultRuleRepository {

    val smartTemplates: List<ExtensionSubRule> = listOf(
        ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
        ExtensionSubRule("文档与表格", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub"), "/storage/emulated/0/Documents/"),
        ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
        ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
        ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
        ExtensionSubRule("压缩归档包", listOf("zip", "rar", "7z", "tar", "gz"), "/storage/emulated/0/Download/压缩包/")
    )

    val quickFolders: List<QuickFolder> = listOf(
        QuickFolder("办公文档", "/storage/emulated/0/Documents/办公文档/", "合同、报表、方案 PDF/Word"),
        QuickFolder("学习资料", "/storage/emulated/0/Documents/学习资料/", "课件 PPT、论文、电子书"),
        QuickFolder("财务票据", "/storage/emulated/0/Documents/财务票据/", "电子发票、报销单、凭证"),
        QuickFolder("安装包目录", "/storage/emulated/0/Download/安装包/", "集中存放 APK 安装包"),
        QuickFolder("压缩包归档", "/storage/emulated/0/Download/压缩包/", "ZIP / RAR / 7Z 素材包"),
        QuickFolder("社交影音", "/storage/emulated/0/Pictures/社交归档/", "重要图片与保存的视频")
    )

    val initialRules: List<AppRouteRule> = listOf(
        AppRouteRule(
            id = "rule-scan-1795841637540-asjk",
            appName = "小米浏览器",
            packageName = "com.android.browser",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/Download/MiBrowser/"),
            defaultTargetDir = "/storage/emulated/0/Download/浏览器整理/",
            renamePattern = "{name}",
            subRules = listOf(

            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-scan-1795841637540-s3x0",
            appName = "WPS Office",
            packageName = "cn.wps.moffice_eng",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/Documents/WPS/"),
            defaultTargetDir = "/storage/emulated/0/Documents/办公文档/",
            renamePattern = "{name}",
            subRules = listOf(

            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-scan-1795841637540-qgee",
            appName = "Telegram",
            packageName = "org.telegram.messenger",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/Download/Telegram/"),
            defaultTargetDir = "/storage/emulated/0/Documents/Telegram归档/",
            renamePattern = "{name}",
            subRules = listOf(

            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-wechat",
            appName = "微信",
            packageName = "com.tencent.mm",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/Pictures/WeiXin/", "/storage/emulated/0/Documents/WeChat/", "/storage/emulated/0/Movies/WeiXin/"),
            defaultTargetDir = "/storage/emulated/0/Documents/微信归档/",
            renamePattern = "{date}_{name}",
            subRules = listOf(
                ExtensionSubRule("办公文档与表格", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"), "/storage/emulated/0/Documents/办公文档/"),
                ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩包文件", listOf("zip", "rar", "7z"), "/storage/emulated/0/Download/压缩包/"),
                ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("txt", "md", "csv", "epub"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-qq-share",
            appName = "QQ（分享菜单归位）",
            packageName = "com.tencent.mobileqq",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.INTENT_FILTER,
            channel = ChannelMode.SHARE_INTENT,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("content://com.tencent.mobileqq.fileprovider/QQfile_recv/"),
            defaultTargetDir = "/storage/emulated/0/Documents/QQ归档/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("课件与学习资料", listOf("pdf", "docx", "pptx", "epub", "txt"), "/storage/emulated/0/Documents/学习资料/"),
                ExtensionSubRule("群文件安装包", listOf("apk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("群压缩包", listOf("zip", "rar", "7z"), "/storage/emulated/0/Download/压缩包/"),
                ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("doc", "xls", "xlsx", "ppt", "md", "csv"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apks", "xapk"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-qq-public",
            appName = "QQ（公共下载目录）",
            packageName = "com.tencent.mobileqq",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/Download/QQ/", "/storage/emulated/0/Pictures/QQ/", "/storage/emulated/0/Documents/Tencent/QQfile_recv/"),
            defaultTargetDir = "/storage/emulated/0/Documents/QQ归档/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("QQ 保存的图片视频", listOf("jpg", "jpeg", "png", "mp4", "mov"), "/storage/emulated/0/Pictures/社交归档/"),
                ExtensionSubRule("图片与相册", listOf("webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("zip", "rar", "7z", "tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-dingtalk",
            appName = "钉钉",
            packageName = "com.alibaba.android.rimet",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.AUTO_MOVE,
            sourcePaths = listOf("/storage/emulated/0/Download/DingTalk/"),
            defaultTargetDir = "/storage/emulated/0/Documents/办公文档/",
            renamePattern = "{date}_{name}",
            subRules = listOf(
                ExtensionSubRule("报表与电子发票", listOf("xlsx", "xls", "csv", "pdf"), "/storage/emulated/0/Documents/财务票据/"),
                ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("doc", "docx", "ppt", "pptx", "txt", "md", "epub"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("zip", "rar", "7z", "tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-feishu",
            appName = "飞书",
            packageName = "com.ss.android.lark",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.AUTO_MOVE,
            sourcePaths = listOf("/storage/emulated/0/Download/Lark/"),
            defaultTargetDir = "/storage/emulated/0/Documents/办公文档/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("演示与方案材料", listOf("pptx", "key", "pdf"), "/storage/emulated/0/Documents/办公文档/"),
                ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("doc", "docx", "xls", "xlsx", "ppt", "txt", "md", "csv", "epub"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("zip", "rar", "7z", "tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-tim",
            appName = "TIM（分享菜单归位）",
            packageName = "com.tencent.tim",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.INTENT_FILTER,
            channel = ChannelMode.SHARE_INTENT,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("content://com.tencent.tim.fileprovider/TIMfile_recv/"),
            defaultTargetDir = "/storage/emulated/0/Documents/办公文档/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("zip", "rar", "7z", "tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        ),
        AppRouteRule(
            id = "rule-quark",
            appName = "夸克 / 系统浏览器",
            packageName = "com.quark.browser",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/QuarkDownloads/", "/storage/emulated/0/Download/"),
            defaultTargetDir = "/storage/emulated/0/Download/浏览器整理/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("网页下载安装包", listOf("apk"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("图片与相册", listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"), "/storage/emulated/0/Pictures/"),
                ExtensionSubRule("文档与表格", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub"), "/storage/emulated/0/Documents/"),
                ExtensionSubRule("视频影像", listOf("mp4", "mov", "mkv", "avi", "webm", "m4v"), "/storage/emulated/0/Movies/"),
                ExtensionSubRule("音频与录音", listOf("mp3", "wav", "flac", "m4a", "aac", "ogg"), "/storage/emulated/0/Music/"),
                ExtensionSubRule("安卓安装包", listOf("apks", "xapk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩归档包", listOf("zip", "rar", "7z", "tar", "gz"), "/storage/emulated/0/Download/压缩包/")
            ),
            cleanupStrategy = "keep",
            cleanupDelayDays = 7
        )
    )

    fun resolveTargetDir(
        rule: AppRouteRule?,
        fileName: String,
        smartClassifyEnabled: Boolean,
        smartGroupByApp: Boolean = false
    ): Pair<String, String?> {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = if (lower.endsWith(".apk.1")) "apk.1" else lower.substringAfterLast('.', "")

        if (rule != null) {
            val matched = rule.subRules.firstOrNull { sub ->
                sub.extensions.any { it.equals(ext, ignoreCase = true) }
            }
            if (matched != null) {
                return ensureTrailingSlash(matched.targetDir) to matched.label
            }
        }

        if (smartClassifyEnabled) {
            val smart = smartTemplates.firstOrNull { tpl ->
                tpl.extensions.any { it.equals(ext, ignoreCase = true) }
            }
            if (smart != null) {
                val cleanApp = rule?.appName?.replace(Regex("（.*?）"), "")?.trim().orEmpty()
                val dir = if (smartGroupByApp && cleanApp.isNotBlank()) {
                    "${ensureTrailingSlash(smart.targetDir)}$cleanApp/"
                } else {
                    ensureTrailingSlash(smart.targetDir)
                }
                return dir to "智能后缀·${smart.label}"
            }
        }

        val fallback = rule?.defaultTargetDir ?: "/storage/emulated/0/Documents/已归位文件/"
        return ensureTrailingSlash(fallback) to null
    }

    fun matchRuleForFile(
        rules: List<AppRouteRule>,
        absolutePath: String,
        ownerPackage: String?
    ): AppRouteRule? {
        val activeRules = rules.filter { it.enabled && it.action != RouteAction.IGNORE }

        // 1. 严格按用户定义的规则顺序匹配路径前缀（越靠前优先级越高，多规则重叠同一路径时优先生效）
        val normalized = absolutePath.lowercase(Locale.ROOT)
        activeRules
            .filter { it.channel == ChannelMode.PUBLIC_OBSERVER }
            .firstOrNull { r ->
                r.sourcePaths.any { src ->
                    val cleanSrc = src.trimEnd('/').lowercase(Locale.ROOT)
                    cleanSrc.isNotBlank() && normalized.startsWith(cleanSrc)
                }
            }?.let { return it }

        // 2. 路径未直接命中时，按系统 MediaStore 写入来源包名依优先级匹配规则
        if (!ownerPackage.isNullOrBlank()) {
            activeRules.firstOrNull {
                it.packageName.equals(ownerPackage, ignoreCase = true) && it.channel == ChannelMode.PUBLIC_OBSERVER
            }?.let { return it }
            activeRules.firstOrNull {
                it.packageName.equals(ownerPackage, ignoreCase = true)
            }?.let { return it }
        }

        // 3. 关键字模糊匹配（适配不同手机 ROM 的微信/QQ/钉钉/飞书目录变体）
        return when {
            normalized.contains("weixin") || normalized.contains("micromsg") || normalized.contains("wechat") ->
                activeRules.firstOrNull { it.packageName == "com.tencent.mm" }
            normalized.contains("/qq/") || normalized.contains("qqfile_recv") || normalized.contains("mobileqq") ->
                activeRules.firstOrNull { it.packageName == "com.tencent.mobileqq" && it.channel == ChannelMode.PUBLIC_OBSERVER }
                    ?: activeRules.firstOrNull { it.packageName == "com.tencent.mobileqq" }
            normalized.contains("dingtalk") ->
                activeRules.firstOrNull { it.packageName == "com.alibaba.android.rimet" }
            normalized.contains("lark") || normalized.contains("feishu") ->
                activeRules.firstOrNull { it.packageName == "com.ss.android.lark" }
            normalized.contains("quark") || normalized.contains("/download/") ->
                activeRules.firstOrNull { it.id == "rule-quark" }
            else -> null
        }
    }

    fun inferAppLabel(absolutePath: String, ownerPackage: String?, matchedRule: AppRouteRule?): String {
        if (matchedRule != null) return matchedRule.appName
        val pkg = ownerPackage?.lowercase(Locale.ROOT).orEmpty()
        val p = absolutePath.lowercase(Locale.ROOT)
        return when {
            pkg.contains("tencent.mm") || p.contains("weixin") || p.contains("micromsg") || p.contains("wechat") -> "微信"
            pkg.contains("mobileqq") || p.contains("/qq/") || p.endsWith("/qq") || p.contains("qqfile") -> "QQ"
            pkg.contains("tencent.tim") || p.contains("timfile") || p.contains("/tim/") -> "TIM"
            pkg.contains("rimet") || p.contains("dingtalk") -> "钉钉"
            pkg.contains("lark") || p.contains("feishu") || p.contains("飞书") -> "飞书"
            pkg.contains("quark") || p.contains("quark") || p.contains("夸克") -> "夸克浏览器"
            pkg.contains("ucmobile") || p.contains("ucdownload") -> "UC 浏览器"
            pkg.contains("baidu") || p.contains("baidunetdisk") -> "百度网盘"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("wps") || p.contains("kingsoft") -> "WPS 文档"
            pkg.contains("chrome") || pkg.contains("browser") || p.contains("mibrowser") -> "手机浏览器"
            else -> "本机目录"
        }
    }

    fun resolveRuleIdForDiscoveredDir(
        rules: List<AppRouteRule>,
        dirPath: String,
        ownerPackages: List<String>
    ): Pair<String?, String> {
        val lowerPath = dirPath.lowercase(Locale.ROOT)
        val lowerPkgs = ownerPackages.map { it.lowercase(Locale.ROOT) }

        // 1. 优先用系统 MediaStore 记录的真实写入包名精准匹配规则
        for (pkg in lowerPkgs) {
            val hit = rules.firstOrNull {
                it.packageName.equals(pkg, ignoreCase = true) && it.channel == ChannelMode.PUBLIC_OBSERVER
            } ?: rules.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
            if (hit != null) return hit.id to hit.appName
        }

        // 2. 路径关键字智能归属（适配不同厂商 ROM 与多版本目录名）
        val ruleByKeyword: AppRouteRule? = when {
            lowerPath.contains("weixin") || lowerPath.contains("wechat") || lowerPath.contains("micromsg") ->
                rules.firstOrNull { it.packageName == "com.tencent.mm" }
            lowerPath.contains("/qq") || lowerPath.contains("qqfile_recv") || lowerPath.contains("mobileqq") ->
                rules.firstOrNull { it.packageName == "com.tencent.mobileqq" && it.channel == ChannelMode.PUBLIC_OBSERVER }
                    ?: rules.firstOrNull { it.packageName == "com.tencent.mobileqq" }
            lowerPath.contains("/tim") || lowerPath.contains("timfile") ->
                rules.firstOrNull { it.packageName == "com.tencent.tim" }
            lowerPath.contains("dingtalk") ->
                rules.firstOrNull { it.packageName == "com.alibaba.android.rimet" }
            lowerPath.contains("lark") || lowerPath.contains("feishu") || lowerPath.contains("飞书") ->
                rules.firstOrNull { it.packageName == "com.ss.android.lark" }
            lowerPath.contains("quark") || lowerPath.contains("夸克") || lowerPath.contains("browser") || lowerPath.contains("chrome") || lowerPath.contains("ucdownload") ->
                rules.firstOrNull { it.id == "rule-quark" || it.packageName.contains("quark") || it.packageName.contains("browser") }
            else -> null
        }

        if (ruleByKeyword != null) {
            return ruleByKeyword.id to ruleByKeyword.appName
        }

        val fallbackName = inferAppLabel(dirPath, ownerPackages.firstOrNull(), null).let { inferred ->
            if (inferred == "本机目录") {
                dirPath.trimEnd('/').substringAfterLast('/').ifBlank { "自定义目录" }
            } else {
                inferred
            }
        }
        return null to fallbackName
    }

    fun ensureTrailingSlash(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/storage/emulated/0/Documents/"
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun formatFileName(pattern: String, appName: String, originalName: String): String {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val cleanApp = appName.replace(Regex("（.*?）"), "")
        return pattern
            .replace("{app}", cleanApp)
            .replace("{date}", dateStr)
            .replace("{name}", originalName)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.2f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }

    fun nowTimeText(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    fun nowDateTimeText(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
}
