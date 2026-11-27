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
    val subRules: List<ExtensionSubRule>
)

object DefaultRuleRepository {

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
            id = "rule-wechat",
            appName = "微信",
            packageName = "com.tencent.mm",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("/storage/emulated/0/Download/WeiXin/", "/storage/emulated/0/Pictures/WeiXin/"),
            defaultTargetDir = "/storage/emulated/0/Documents/微信归档/",
            renamePattern = "{date}_{name}",
            subRules = listOf(
                ExtensionSubRule("办公文档与表格", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"), "/storage/emulated/0/Documents/办公文档/"),
                ExtensionSubRule("安卓安装包", listOf("apk", "apks", "xapk"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("压缩包文件", listOf("zip", "rar", "7z"), "/storage/emulated/0/Download/压缩包/")
            )
        ),
        AppRouteRule(
            id = "rule-qq-share",
            appName = "QQ（分享菜单归位）",
            packageName = "com.tencent.mobileqq",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.SHARE_INTENT,
            action = RouteAction.PROMPT,
            sourcePaths = listOf("content://com.tencent.mobileqq.fileprovider/QQfile_recv/"),
            defaultTargetDir = "/storage/emulated/0/Documents/QQ归档/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("课件与学习资料", listOf("pdf", "docx", "pptx", "epub", "txt"), "/storage/emulated/0/Documents/学习资料/"),
                ExtensionSubRule("群文件安装包", listOf("apk", "apk.1"), "/storage/emulated/0/Download/安装包/"),
                ExtensionSubRule("群压缩包", listOf("zip", "rar", "7z"), "/storage/emulated/0/Download/压缩包/")
            )
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
            sourcePaths = listOf("/storage/emulated/0/Download/QQ/", "/storage/emulated/0/Pictures/QQ/"),
            defaultTargetDir = "/storage/emulated/0/Documents/QQ归档/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("QQ 保存的图片视频", listOf("jpg", "jpeg", "png", "mp4", "mov"), "/storage/emulated/0/Pictures/社交归档/")
            )
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
                ExtensionSubRule("报表与电子发票", listOf("xlsx", "xls", "csv", "pdf"), "/storage/emulated/0/Documents/财务票据/")
            )
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
                ExtensionSubRule("演示与方案材料", listOf("pptx", "key", "pdf"), "/storage/emulated/0/Documents/办公文档/")
            )
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

            )
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
            sourcePaths = listOf("/storage/emulated/0/Quark/Download/", "/storage/emulated/0/Download/"),
            defaultTargetDir = "/storage/emulated/0/Download/浏览器整理/",
            renamePattern = "{name}",
            subRules = listOf(
                ExtensionSubRule("网页下载安装包", listOf("apk"), "/storage/emulated/0/Download/安装包/")
            )
        )
    )

    fun resolveTargetDir(rule: AppRouteRule, fileName: String): Pair<String, String?> {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val matched = rule.subRules.firstOrNull { sub ->
            sub.extensions.any { it.equals(ext, ignoreCase = true) }
        }
        return if (matched != null) {
            matched.targetDir to matched.label
        } else {
            rule.defaultTargetDir to null
        }
    }

    fun formatFileName(pattern: String, appName: String, originalName: String): String {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val cleanApp = appName.replace(Regex("（.*?）"), "")
        return pattern
            .replace("{app}", cleanApp)
            .replace("{date}", dateStr)
            .replace("{name}", originalName)
    }
}
