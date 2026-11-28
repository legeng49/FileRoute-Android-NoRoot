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
import app.fileroute.data.DiscoveredDeviceDir
import app.fileroute.data.RecentMediaSample
import app.fileroute.data.RouteAction
import app.fileroute.data.RuntimeStateStore
import app.fileroute.data.TransferHistoryItem
import java.io.File
import java.net.URLDecoder
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
                RuntimeStateStore.smartClassifyEnabled,
                RuntimeStateStore.smartGroupByApp
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
                        val lowerPkg = ownerPkg?.lowercase(Locale.ROOT).orEmpty()
                        val isDownloadsUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            uri == MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val matchesConfiguredRule = RuntimeStateStore.rules.any { rule ->
                            rule.enabled && (
                                (lowerPkg.isNotBlank() && rule.packageName.equals(lowerPkg, ignoreCase = true)) ||
                                    rule.sourcePaths.any { sp ->
                                        val clean = sp.trimEnd('/').lowercase(Locale.ROOT)
                                        clean.isNotBlank() && lower.startsWith(clean)
                                    }
                                )
                        }
                        val isKnownOwnerPkg = lowerPkg.contains("tencent.mm") ||
                            lowerPkg.contains("mobileqq") ||
                            lowerPkg.contains("tencent.tim") ||
                            lowerPkg.contains("rimet") ||
                            lowerPkg.contains("lark") ||
                            lowerPkg.contains("quark") ||
                            lowerPkg.contains("ucmobile") ||
                            lowerPkg.contains("browser") ||
                            lowerPkg.contains("chrome") ||
                            lowerPkg.contains("baidu") ||
                            lowerPkg.contains("telegram")
                        val isRelevantFolder = isDownloadsUri ||
                            matchesConfiguredRule ||
                            isKnownOwnerPkg ||
                            lower.contains("/download") ||
                            lower.contains("/documents/") ||
                            lower.contains("weixin") ||
                            lower.contains("wechat") ||
                            lower.contains("/qq") ||
                            lower.contains("qqfile") ||
                            lower.contains("dingtalk") ||
                            lower.contains("lark") ||
                            lower.contains("feishu") ||
                            lower.contains("quark") ||
                            lower.contains("ucdownload") ||
                            lower.contains("baidunetdisk")

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

    fun findExistingConflict(targetDirPath: String, fileName: String): File? {
        val cleanDir = DefaultRuleRepository.ensureTrailingSlash(targetDirPath)
        if (cleanDir.isBlank() || fileName.isBlank()) return null
        val candidate = File(cleanDir, fileName)
        return if (candidate.exists() && candidate.isFile) candidate else null
    }

    fun buildTimestampedFileName(fileName: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(java.util.Date())
        val lower = fileName.lowercase(Locale.ROOT)
        if (lower.endsWith(".apk.1")) {
            return "${fileName.dropLast(6)}_$stamp.apk.1"
        }
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) {
            "${fileName.substring(0, dot)}_$stamp${fileName.substring(dot)}"
        } else {
            "${fileName}_$stamp"
        }
    }

    fun computeSha256(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun isIdenticalFile(srcFile: File, dstFile: File): Boolean {
        if (!srcFile.exists() || !dstFile.exists() || !srcFile.isFile || !dstFile.isFile) return false
        if (srcFile.length() != dstFile.length()) return false
        val srcHash = computeSha256(srcFile) ?: return false
        val dstHash = computeSha256(dstFile) ?: return false
        return srcHash.equals(dstHash, ignoreCase = true)
    }

    fun isIdenticalUriToFile(context: Context, uri: Uri, dstFile: File): Boolean {
        if (!dstFile.exists() || !dstFile.isFile) return false
        return runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    digest.update(buf, 0, read)
                }
            } ?: return false
            if (total != dstFile.length()) return false
            val srcHash = digest.digest().joinToString("") { "%02x".format(it) }
            val dstHash = computeSha256(dstFile) ?: return false
            srcHash.equals(dstHash, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun relocateFile(
        context: Context,
        sourcePath: String?,
        sharedUri: Uri?,
        appName: String,
        fileName: String,
        targetDirPath: String,
        copyOnly: Boolean,
        mode: String = if (sharedUri != null) "share_saved" else "prompt_confirmed",
        conflictStrategy: String = "rename_timestamp"
    ): Boolean {
        RuntimeStateStore.ensureInit(context)
        return runCatching {
            val cleanDir = DefaultRuleRepository.ensureTrailingSlash(targetDirPath)
            val targetDir = File(cleanDir)
            if (!targetDir.exists()) targetDir.mkdirs()

            val directCandidate = File(targetDir, fileName)

            // 文件完整性校验：目标目录存在同名文件时先比对字节大小再比对 SHA-256，完全一致则自动跳过
            if (directCandidate.exists() && RuntimeStateStore.integrityCheckEnabled) {
                val identical = when {
                    !sourcePath.isNullOrBlank() -> isIdenticalFile(File(sourcePath), directCandidate)
                    sharedUri != null -> isIdenticalUriToFile(context, sharedUri, directCandidate)
                    else -> false
                }
                if (identical) {
                    if (!sourcePath.isNullOrBlank()) {
                        RuntimeStateStore.removePendingByPath(sourcePath)
                    }
                    RuntimeStateStore.appendLog("完整性校验一致（大小与 SHA-256 相同），已自动跳过重复归位：$fileName")
                    return true
                }
            }

            if (directCandidate.exists() && conflictStrategy == "skip") {
                if (!sourcePath.isNullOrBlank()) {
                    RuntimeStateStore.removePendingByPath(sourcePath)
                }
                RuntimeStateStore.appendLog("目标已存在同名文件，已跳过：$fileName")
                return true
            }

            val allowOverwrite = conflictStrategy == "overwrite"
            val uniqueTarget = when {
                !directCandidate.exists() || allowOverwrite -> directCandidate
                conflictStrategy == "rename_timestamp" -> resolveUniqueTargetFile(
                    targetDir,
                    buildTimestampedFileName(fileName)
                )
                else -> resolveUniqueTargetFile(targetDir, fileName)
            }
            var fileSize = 0L
            val fromLabel: String

            if (!sourcePath.isNullOrBlank()) {
                val src = File(sourcePath)
                if (!src.exists() || !src.isFile) return false
                fileSize = src.length()
                fromLabel = src.absolutePath
                src.copyTo(uniqueTarget, overwrite = allowOverwrite)
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
            NotificationHelper.vibrateOnArchiveSuccess(context.applicationContext)
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
     * 将系统目录选择器（OpenDocumentTree）返回的 SAF Uri 转换为真实共享存储绝对路径
     */
    fun resolveTreeUriToPath(treeUri: Uri): String? {
        return runCatching {
            val decoded = URLDecoder.decode(treeUri.toString(), "UTF-8")
            val marker = "/tree/"
            val idx = decoded.indexOf(marker)
            if (idx < 0) return null
            val docId = decoded.substring(idx + marker.length)
            val extRoot = Environment.getExternalStorageDirectory().absolutePath
            when {
                docId.startsWith("primary:") -> {
                    val rel = docId.removePrefix("primary:").trim('/')
                    if (rel.isEmpty()) "$extRoot/" else "$extRoot/$rel/"
                }
                docId.contains(":") -> {
                    val rel = docId.substringAfter(":").trim('/')
                    if (rel.isEmpty()) "$extRoot/" else "$extRoot/$rel/"
                }
                else -> null
            }
        }.getOrNull()
    }

    /**
     * 两级极速本机路径探测引擎（无需慢速全盘遍历，通常 < 200ms）：
     * - 第 1 层：MediaStore.Files 全局媒体库反查（直接拿到本机所有公开下载/文档/图片目录与来源包名）
     * - 第 2 层：受限 2 层浅层目录探测（补齐尚未入库的非媒体目录，自动跳过 Android/data 与缓存）
     */
    fun discoverRealDeviceDirs(context: Context): List<DiscoveredDeviceDir> {
        RuntimeStateStore.ensureInit(context)
        val extRoot = Environment.getExternalStorageDirectory().absolutePath
        val extRootLower = extRoot.lowercase(Locale.ROOT)

        data class MutableDirBucket(
            val path: String,
            val ownerPackages: MutableSet<String> = linkedSetOf(),
            var fileCount: Int = 0,
            var latestFileName: String? = null,
            var latestModifiedMillis: Long = 0L,
            var source: String = "MediaStore反查"
        )

        val buckets = linkedMapOf<String, MutableDirBucket>()

        fun shouldIgnoreDir(absDir: String): Boolean {
            val low = absDir.lowercase(Locale.ROOT)
            if (!low.startsWith(extRootLower)) return true
            if (low == extRootLower) return true
            if (low.contains("/android/data") || low.contains("/android/obb")) return true
            if (low.contains("/.") || low.contains("thumbnails") || low.contains("/cache")) return true
            if (low.endsWith("/dcim/camera") || low.endsWith("/pictures/screenshots") || low.endsWith("/dcim/screenshots")) return true
            if (isInsideManagedTargetArchive("$absDir/dummy")) return true
            return false
        }

        // Layer 1: MediaStore.Files 毫秒级反查（不需要遍历硬盘即可发现真实路径与写入应用包名）
        runCatching {
            val uri = MediaStore.Files.getContentUri("external")
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

            context.contentResolver.query(
                uri,
                projection,
                "${MediaStore.MediaColumns.SIZE} > 0",
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val pkgIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else -1

                var scanned = 0
                while (cursor.moveToNext() && scanned < 600) {
                    scanned++
                    val rawPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    val relPath = if (relIdx >= 0) cursor.getString(relIdx) else null
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val modMillis = if (modIdx >= 0) cursor.getLong(modIdx) * 1000L else 0L
                    val ownerPkg = if (pkgIdx >= 0) cursor.getString(pkgIdx) else null

                    val parentDir = when {
                        !rawPath.isNullOrBlank() -> File(rawPath).parent
                        !relPath.isNullOrBlank() -> File(extRoot, relPath).absolutePath
                        else -> null
                    }?.trimEnd('/') ?: continue

                    if (shouldIgnoreDir(parentDir)) continue
                    val dirObj = File(parentDir)
                    if (!dirObj.exists() || !dirObj.isDirectory) continue

                    val key = parentDir.lowercase(Locale.ROOT)
                    val bucket = buckets.getOrPut(key) { MutableDirBucket(path = parentDir, source = "MediaStore反查") }
                    bucket.fileCount++
                    if (!ownerPkg.isNullOrBlank()) bucket.ownerPackages.add(ownerPkg)
                    if (modMillis >= bucket.latestModifiedMillis && !name.isNullOrBlank()) {
                        bucket.latestModifiedMillis = modMillis
                        bucket.latestFileName = name
                    }
                }
            }
        }

        // Layer 2: 受限 2 层浅层目录探测（仅查公共父目录及其 1-2 层子目录，绝不慢速全盘递归）
        val appKeywords = listOf(
            "weixin", "wechat", "micromsg", "qq", "tim", "dingtalk", "lark", "feishu", "飞书",
            "quark", "夸克", "ucdownload", "browser", "chrome", "baidu", "wps", "telegram", "download", "recv"
        )
        val probeParents = listOf(
            File(extRoot, "Download"),
            File(extRoot, "Downloads"),
            File(extRoot, "Documents"),
            File(extRoot, "Pictures"),
            File(extRoot, "Movies"),
            File(extRoot, "DCIM"),
            File(extRoot, "Tencent"),
            File(extRoot, "Android/media")
        )

        fun inspectShallowDir(dir: File) {
            val abs = dir.absolutePath.trimEnd('/')
            if (shouldIgnoreDir(abs) || !dir.exists() || !dir.isDirectory || !dir.canRead()) return
            val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
            val lowName = abs.lowercase(Locale.ROOT)
            val isKeywordMatch = appKeywords.any { lowName.contains(it) }
            if (files.isEmpty() && !isKeywordMatch) return

            val key = lowName
            val existing = buckets[key]
            val latestFile = files.maxByOrNull { it.lastModified() }
            if (existing == null) {
                buckets[key] = MutableDirBucket(
                    path = abs,
                    fileCount = files.size,
                    latestFileName = latestFile?.name,
                    latestModifiedMillis = latestFile?.lastModified() ?: dir.lastModified(),
                    source = "浅层目录探测"
                )
            } else {
                if (files.size > existing.fileCount) existing.fileCount = files.size
                if (latestFile != null && latestFile.lastModified() > existing.latestModifiedMillis) {
                    existing.latestModifiedMillis = latestFile.lastModified()
                    existing.latestFileName = latestFile.name
                }
            }
        }

        // 顶层常见第三方下载目录（如 /QuarkDownloads, /UCDownloads, /BaiduNetdisk, /DingTalk）
        File(extRoot).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { topDir ->
            val low = topDir.name.lowercase(Locale.ROOT)
            if (appKeywords.any { low.contains(it) }) {
                inspectShallowDir(topDir)
                topDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.take(12)?.forEach { sub ->
                    inspectShallowDir(sub)
                }
            }
        }

        probeParents.forEach { parent ->
            if (parent.exists() && parent.isDirectory && parent.canRead()) {
                inspectShallowDir(parent)
                parent.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.take(24)?.forEach { child ->
                    inspectShallowDir(child)
                    // 针对 Android/media/<pkg> 或 Documents/Tencent/<subDir> 再向下探测 1 层
                    if (parent.name.equals("media", ignoreCase = true) || child.name.equals("Tencent", ignoreCase = true) || child.name.equals("com.tencent.mm", ignoreCase = true)) {
                        child.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.take(10)?.forEach { grand ->
                            inspectShallowDir(grand)
                        }
                    }
                }
            }
        }

        val boundPathSet = RuntimeStateStore.rules
            .filter { it.enabled }
            .flatMap { it.sourcePaths }
            .map { DefaultRuleRepository.ensureTrailingSlash(it).lowercase(Locale.ROOT) }
            .toSet()

        return buckets.values
            .map { b ->
                val clean = DefaultRuleRepository.ensureTrailingSlash(b.path)
                val (ruleId, appName) = DefaultRuleRepository.resolveRuleIdForDiscoveredDir(
                    RuntimeStateStore.rules,
                    clean,
                    b.ownerPackages.toList()
                )
                val isBound = boundPathSet.any { bp ->
                    clean.lowercase(Locale.ROOT) == bp || clean.lowercase(Locale.ROOT).startsWith(bp)
                }
                DiscoveredDeviceDir(
                    path = clean,
                    inferredAppName = appName,
                    matchedRuleId = ruleId,
                    ownerPackages = b.ownerPackages.toList(),
                    fileCount = b.fileCount,
                    latestFileName = b.latestFileName,
                    latestModifiedMillis = b.latestModifiedMillis,
                    discoverySource = b.source,
                    alreadyBound = isBound
                )
            }
            .sortedWith(
                compareByDescending<DiscoveredDeviceDir> { it.fileCount > 0 }
                    .thenByDescending { it.matchedRuleId != null }
                    .thenByDescending { it.latestModifiedMillis }
            )
            .take(30)
    }

    /**
     * 实机诊断：检测常见预设路径（带原因说明）+ 全公共目录 MediaStore 最新写入记录（支持一键绑定）
     */
    fun diagnosePhonePaths(context: Context): Pair<List<DirectoryDiagItem>, List<RecentMediaSample>> {
        val extRoot = Environment.getExternalStorageDirectory().absolutePath
        val candidates = listOf(
            Triple("系统总下载目录", "$extRoot/Download", "公共下载根目录"),
            Triple("微信下载目录", "$extRoot/Download/WeiXin", "需在微信打开文件后点「保存到手机」才会自动创建"),
            Triple("微信图片目录", "$extRoot/Pictures/WeiXin", "微信保存的图片通常落在此目录"),
            Triple("微信文档目录", "$extRoot/Documents/WeChat", "部分机型或新版微信文档目录"),
            Triple("QQ 公共下载目录", "$extRoot/Download/QQ", "QQ 接收文件多在私有沙盒，建议用「其他应用打开 → 归位」"),
            Triple("QQ 图片目录", "$extRoot/Pictures/QQ", "QQ 保存的图片通常落在此目录"),
            Triple("钉钉下载目录", "$extRoot/Download/DingTalk", "未安装钉钉或未下载过文件时不存在属正常现象"),
            Triple("飞书下载目录", "$extRoot/Download/Lark", "未安装飞书或未下载过文件时不存在属正常现象"),
            Triple("夸克下载目录", "$extRoot/Quark/Download", "部分版本在 /QuarkDownloads 或 /Download/Quark")
        )

        val dirResults = candidates.map { (label, path, hintText) ->
            val dir = File(path)
            val exists = dir.exists() && dir.isDirectory
            val readable = exists && dir.canRead()
            val writable = exists && dir.canWrite()
            val directFiles = if (readable) dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty() else emptyList()
            val subDirFiles = if (readable && path.endsWith("/Download")) {
                dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.flatMap { sub -> sub.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty() }
                    .orEmpty()
            } else {
                emptyList()
            }
            val allFiles = directFiles + subDirFiles
            val latest = allFiles.maxByOrNull { it.lastModified() }?.name
            DirectoryDiagItem(
                label = label,
                path = path,
                exists = exists,
                readable = readable,
                writable = writable,
                fileCount = allFiles.size,
                latestFileName = latest,
                hint = if (!exists) hintText else if (allFiles.isEmpty()) hintText else ""
            )
        }

        // 查询 MediaStore.Files（覆盖 Pictures / Documents / Download / Movies 等全部公共目录）
        val mediaSamples = mutableListOf<RecentMediaSample>()
        runCatching {
            val uri = MediaStore.Files.getContentUri("external")
            val cols = buildList {
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                    add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                }
            }.toTypedArray()

            context.contentResolver.query(
                uri,
                cols,
                "${MediaStore.MediaColumns.SIZE} > 0",
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val rIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val pIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else -1

                while (cursor.moveToNext() && mediaSamples.size < 10) {
                    val n = if (nIdx >= 0) cursor.getString(nIdx) else null
                    val raw = if (dIdx >= 0) cursor.getString(dIdx) else null
                    val rel = if (rIdx >= 0) cursor.getString(rIdx) else null
                    val pkg = if (pIdx >= 0) cursor.getString(pIdx) else null
                    if (n.isNullOrBlank() || n.startsWith(".")) continue

                    val parent = when {
                        !raw.isNullOrBlank() -> File(raw).parent
                        !rel.isNullOrBlank() -> File(extRoot, rel).absolutePath
                        else -> null
                    } ?: continue
                    val low = parent.lowercase(Locale.ROOT)
                    if (low.contains("/android/data") || low.contains("/android/obb") || low.contains("thumbnails")) continue

                    val cleanDir = DefaultRuleRepository.ensureTrailingSlash(parent)
                    mediaSamples.add(
                        RecentMediaSample(
                            fileName = n,
                            dirPath = cleanDir,
                            ownerPackage = pkg ?: "系统索引",
                            inferredAppName = DefaultRuleRepository.inferAppLabel(cleanDir, pkg, null)
                        )
                    )
                }
            }
        }
        return dirResults to mediaSamples
    }
}
