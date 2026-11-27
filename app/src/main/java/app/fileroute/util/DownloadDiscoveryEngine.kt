package app.fileroute.util

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.fileroute.data.ChannelMode
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.data.DetectedDownloadItem
import app.fileroute.data.DirectoryDiagItem
import app.fileroute.data.RouteAction
import app.fileroute.data.RuntimeStateStore
import app.fileroute.data.TransferHistoryItem
import java.io.File
import java.util.Locale

object DownloadDiscoveryEngine {

    private val IGNORED_EXTENSIONS = setOf("tmp", "crdownload", "part", "download", "pending", "bak")

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * 核心发现入口：同时扫描 MediaStore.Downloads / MediaStore.Files 与所有常见/自定义下载目录，
     * 即使不同手机 ROM 的微信/QQ下载路径不同也能自动捕获！
     */
    fun scanAndProcessRecent(
        context: Context,
        lookbackMinutes: Int = 15,
        notifyUser: Boolean = true,
        includeAlreadyHandledInPending: Boolean = false
    ): Int {
        RuntimeStateStore.ensureInit(context)
        val cutoffMillis = System.currentTimeMillis() - lookbackMinutes * 60_000L
        val foundMap = linkedMapOf<String, Pair<File, String?>>()

        // 1. Layer A: 系统 MediaStore 查询（完全不受厂商目录差异影响，还能拿到来源包名）
        queryMediaStoreRecent(context, cutoffMillis, foundMap)

        // 2. Layer B: 物理目录递归扫描（补齐 MediaStore 尚未索引的新写入文件）
        val rootsToScan = linkedSetOf<String>()
        RuntimeStateStore.rules
            .filter { it.enabled && it.channel == ChannelMode.PUBLIC_OBSERVER && it.action != RouteAction.IGNORE }
            .forEach { rule -> rootsToScan.addAll(rule.sourcePaths) }

        if (RuntimeStateStore.universalScanEnabled) {
            val extRoot = Environment.getExternalStorageDirectory().absolutePath
            rootsToScan.add("$extRoot/Download")
            rootsToScan.add("$extRoot/Downloads")
            rootsToScan.add("$extRoot/Pictures/WeiXin")
            rootsToScan.add("$extRoot/Pictures/QQ")
            rootsToScan.add("$extRoot/Documents/WeiXin")
            rootsToScan.add("$extRoot/Documents/QQ")
            rootsToScan.add("$extRoot/Tencent/MicroMsg/Download")
            rootsToScan.add("$extRoot/Tencent/QQfile_recv")
        }

        rootsToScan.forEach { path ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                walkRecentFiles(dir, cutoffMillis, depth = 0, maxDepth = 3, out = foundMap)
            }
        }

        var newlyHandledCount = 0
        for ((absPath, pair) in foundMap) {
            val (file, ownerPkg) = pair
            if (!isEligibleFile(file)) continue
            if (isInsideManagedTargetArchive(absPath)) continue

            val dedupKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
            val alreadyHandled = RuntimeStateStore.isAlreadyHandled(context, dedupKey)
            if (alreadyHandled && !includeAlreadyHandledInPending) continue

            val matchedRule = DefaultRuleRepository.matchRuleForFile(
                RuntimeStateStore.rules,
                absPath,
                ownerPkg
            )
            if (matchedRule == null && !RuntimeStateStore.universalScanEnabled) continue

            val appName = DefaultRuleRepository.inferAppLabel(absPath, ownerPkg, matchedRule)
            val (targetDir, matchedLabel) = DefaultRuleRepository.resolveTargetDir(
                matchedRule,
                file.name,
                RuntimeStateStore.smartClassifyEnabled
            )
            val action = matchedRule?.action ?: RouteAction.PROMPT

            // 如果文件就在目标目录里，无需重复提示
            val currentParent = DefaultRuleRepository.ensureTrailingSlash(file.parentFile?.absolutePath ?: "")
            if (currentParent.equals(targetDir, ignoreCase = true)) continue

            when (action) {
                RouteAction.AUTO_MOVE, RouteAction.AUTO_COPY -> {
                    if (!alreadyHandled) {
                        val copyOnly = action == RouteAction.AUTO_COPY
                        val finalName = DefaultRuleRepository.formatFileName(
                            matchedRule?.renamePattern ?: "{name}",
                            appName,
                            file.name
                        )
                        val ok = relocateFile(
                            context = context,
                            sourcePath = file.absolutePath,
                            sharedUri = null,
                            appName = appName,
                            fileName = finalName,
                            targetDirPath = targetDir,
                            copyOnly = copyOnly,
                            mode = "auto_rule"
                        )
                        if (ok) {
                            RuntimeStateStore.markHandled(context, dedupKey)
                            newlyHandledCount++
                        }
                    }
                }
                RouteAction.PROMPT -> {
                    val item = DetectedDownloadItem(
                        id = "det_${absPath.hashCode()}_${file.lastModified()}",
                        ruleId = matchedRule?.id ?: "universal",
                        appName = appName,
                        fileName = file.name,
                        sourcePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        suggestedTargetDir = targetDir,
                        matchedLabel = matchedLabel,
                        detectedAtText = DefaultRuleRepository.nowTimeText()
                    )
                    RuntimeStateStore.addOrUpdatePending(item)
                    if (!alreadyHandled) {
                        RuntimeStateStore.markHandled(context, dedupKey)
                        if (notifyUser) {
                            val posted = NotificationHelper.postNewFileHeadsUp(context, appName, file, targetDir)
                            RuntimeStateStore.appendLog(
                                "捕获 [${appName}] ${file.name} -> 通知${if (posted) "已弹出" else "未授权被拦截"}"
                            )
                        }
                        newlyHandledCount++
                    }
                }
                RouteAction.IGNORE -> Unit
            }
        }

        if (newlyHandledCount > 0) {
            RuntimeStateStore.appendLog("本次扫描发现 $newlyHandledCount 个新下载文件")
        }
        return newlyHandledCount
    }

    private fun queryMediaStoreRecent(
        context: Context,
        cutoffMillis: Long,
        out: MutableMap<String, Pair<File, String?>>
    ) {
        val cutoffSec = cutoffMillis / 1000L
        val uris = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
        uris.add(MediaStore.Files.getContentUri("external"))

        val projection = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            }
        }.toTypedArray()

        val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
        val selectionArgs = arrayOf(cutoffSec.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        for (uri in uris) {
            runCatching {
                context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else -1
                    val pkgIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    } else -1

                    var count = 0
                    while (cursor.moveToNext() && count < 40) {
                        val rawPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                        val relPath = if (relIdx >= 0) cursor.getString(relIdx) else null
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val ownerPkg = if (pkgIdx >= 0) cursor.getString(pkgIdx) else null

                        val candidateFile = when {
                            !rawPath.isNullOrBlank() -> File(rawPath)
                            !relPath.isNullOrBlank() && !name.isNullOrBlank() ->
                                File(Environment.getExternalStorageDirectory(), "$relPath/$name")
                            else -> null
                        } ?: continue

                        val abs = candidateFile.absolutePath
                        val lower = abs.lowercase(Locale.ROOT)
                        val isDownloadsUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            uri == MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val isRelevantFolder = isDownloadsUri ||
                            lower.contains("/download") ||
                            lower.contains("weixin") ||
                            lower.contains("/qq") ||
                            lower.contains("dingtalk") ||
                            lower.contains("lark") ||
                            lower.contains("quark")

                        if (isRelevantFolder && candidateFile.exists() && candidateFile.isFile) {
                            out.putIfAbsent(abs, candidateFile to ownerPkg)
                            count++
                        }
                    }
                }
            }
        }
    }

    private fun walkRecentFiles(
        dir: File,
        cutoffMillis: Long,
        depth: Int,
        maxDepth: Int,
        out: MutableMap<String, Pair<File, String?>>
    ) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                val n = child.name
                if (n == "安装包" || n == "压缩包" || n == "浏览器整理") continue
                walkRecentFiles(child, cutoffMillis, depth + 1, maxDepth, out)
            } else if (child.isFile && child.lastModified() >= cutoffMillis) {
                out.putIfAbsent(child.absolutePath, child to null)
            }
        }
    }

    private fun isEligibleFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        val name = file.name
        if (name.startsWith(".") || name.startsWith(".pending-")) return false
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in IGNORED_EXTENSIONS) return false
        return true
    }

    private fun isInsideManagedTargetArchive(absPath: String): Boolean {
        return absPath.contains("/Documents/办公文档/") ||
            absPath.contains("/Documents/学习资料/") ||
            absPath.contains("/Documents/财务票据/") ||
            absPath.contains("/Documents/微信归档/") ||
            absPath.contains("/Documents/QQ归档/") ||
            absPath.contains("/Download/安装包/") ||
            absPath.contains("/Download/压缩包/") ||
            absPath.contains("/Download/浏览器整理/")
    }

    fun relocateFile(
        context: Context,
        sourcePath: String?,
        sharedUri: Uri?,
        appName: String,
        fileName: String,
        targetDirPath: String,
        copyOnly: Boolean,
        mode: String = if (sharedUri != null) "share_saved" else "prompt_confirmed"
    ): Boolean {
        RuntimeStateStore.ensureInit(context)
        return runCatching {
            val cleanDir = DefaultRuleRepository.ensureTrailingSlash(targetDirPath)
            val targetDir = File(cleanDir)
            if (!targetDir.exists()) targetDir.mkdirs()

            val uniqueTarget = resolveUniqueTargetFile(targetDir, fileName)
            var fileSize = 0L
            val fromLabel: String

            if (!sourcePath.isNullOrBlank()) {
                val src = File(sourcePath)
                if (!src.exists() || !src.isFile) return false
                fileSize = src.length()
                fromLabel = src.absolutePath
                src.copyTo(uniqueTarget, overwrite = false)
                if (!copyOnly) src.delete()
                RuntimeStateStore.removePendingByPath(sourcePath)
            } else if (sharedUri != null) {
                fromLabel = sharedUri.toString()
                context.contentResolver.openInputStream(sharedUri)?.use { input ->
                    uniqueTarget.outputStream().use { output -> input.copyTo(output) }
                } ?: return false
                fileSize = uniqueTarget.length()
            } else {
                return false
            }

            // Android 17 MediaStore 索引即时刷新
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(uniqueTarget.absolutePath),
                null,
                null
            )

            RuntimeStateStore.recordHistory(
                context,
                TransferHistoryItem(
                    id = "hist_${System.currentTimeMillis()}",
                    appName = appName,
                    fileName = uniqueTarget.name,
                    fromPath = fromLabel,
                    toPath = uniqueTarget.absolutePath,
                    fileSizeBytes = fileSize,
                    copyOnly = copyOnly || sharedUri != null,
                    restored = false,
                    timeText = DefaultRuleRepository.nowDateTimeText(),
                    mode = mode,
                    status = "completed"
                )
            )
            RuntimeStateStore.appendLog("${if (copyOnly) "复制" else "移动"}成功：${uniqueTarget.name} -> $cleanDir")
            true
        }.onFailure { err ->
            RuntimeStateStore.appendLog("归位失败：${err.message ?: "存储权限不足"}")
        }.getOrDefault(false)
    }

    fun undoTransfer(context: Context, item: TransferHistoryItem): Boolean {
        if (item.restored || item.status == "ignored") return false
        return runCatching {
            val dstFile = File(item.toPath)
            if (!dstFile.exists()) return false
            if (item.copyOnly || item.fromPath.startsWith("content://")) {
                dstFile.delete()
            } else {
                val originalFile = File(item.fromPath)
                originalFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                dstFile.copyTo(originalFile, overwrite = true)
                dstFile.delete()
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    arrayOf(originalFile.absolutePath),
                    null,
                    null
                )
            }
            RuntimeStateStore.markHistoryRestored(context, item.id)
            RuntimeStateStore.appendLog("已撤销归位：${item.fileName}")
            true
        }.getOrDefault(false)
    }

    private fun resolveUniqueTargetFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_($i)$ext")
            i++
        }
        return candidate
    }

    /**
     * 实机诊断：逐个检测常见下载目录在本机是否真实存在、可读可写，并列出最新文件
     */
    fun diagnosePhonePaths(context: Context): Pair<List<DirectoryDiagItem>, List<String>> {
        val extRoot = Environment.getExternalStorageDirectory().absolutePath
        val candidates = listOf(
            "系统总下载目录" to "$extRoot/Download",
            "微信下载目录" to "$extRoot/Download/WeiXin",
            "微信图片目录" to "$extRoot/Pictures/WeiXin",
            "QQ 公共下载目录" to "$extRoot/Download/QQ",
            "QQ 图片目录" to "$extRoot/Pictures/QQ",
            "钉钉下载目录" to "$extRoot/Download/DingTalk",
            "飞书下载目录" to "$extRoot/Download/Lark",
            "夸克下载目录" to "$extRoot/Quark/Download",
            "旧版微信目录" to "$extRoot/Tencent/MicroMsg/Download"
        )

        val dirResults = candidates.map { (label, path) ->
            val dir = File(path)
            val exists = dir.exists() && dir.isDirectory
            val readable = exists && dir.canRead()
            val writable = exists && dir.canWrite()
            val files = if (readable) dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty() else emptyList()
            val latest = files.maxByOrNull { it.lastModified() }?.name
            DirectoryDiagItem(
                label = label,
                path = path,
                exists = exists,
                readable = readable,
                writable = writable,
                fileCount = files.size,
                latestFileName = latest
            )
        }

        val mediaSamples = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val cols = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                )
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cols,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val nIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val rIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val pIdx = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    var c = 0
                    while (cursor.moveToNext() && c < 8) {
                        val n = if (nIdx >= 0) cursor.getString(nIdx) else "?"
                        val r = if (rIdx >= 0) cursor.getString(rIdx) else "?"
                        val p = if (pIdx >= 0) cursor.getString(pIdx) else "未知包名"
                        mediaSamples.add("$n  |  $r  |  来源:$p")
                        c++
                    }
                }
            }
        }
        return dirResults to mediaSamples
    }
}
