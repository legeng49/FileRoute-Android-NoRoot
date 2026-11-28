package app.fileroute.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeStateStore {
    private const val PREF_NAME = "fileroute_runtime_v30"
    private const val KEY_UNIVERSAL_SCAN = "universal_scan"
    private const val KEY_SMART_CLASSIFY = "smart_classify"
    private const val KEY_SMART_GROUP_BY_APP = "smart_group_by_app"
    private const val KEY_EVENT_TRIGGERED = "event_triggered"
    private const val KEY_POWER_MODE = "power_mode"
    private const val KEY_WRITE_CHECK = "require_write_check"
    private const val KEY_COALESCE_BATCH = "coalesce_batch"
    private const val KEY_VERIFY_DELAY_MS = "verify_delay_ms"
    private const val KEY_GUARD_ARMED = "guard_armed"
    private const val KEY_HANDLED_SET = "handled_files"
    private const val KEY_HISTORY_JSON = "history_json"
    private const val KEY_CUSTOM_RULES_JSON = "custom_rules_json"
    private const val KEY_QUICK_FOLDERS_JSON = "quick_folders_json"
    private const val KEY_INTEGRITY_CHECK = "integrity_check_enabled"
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    private const val KEY_GITHUB_UPDATE_REPO = "github_update_repo"
    private const val KEY_IGNORED_UPDATE_TAG = "ignored_update_tag"
    private const val KEY_QUICK_SILENT_SHARE = "quick_silent_share_archive"
    private const val KEY_SAF_TREE_URIS = "saf_tree_uris"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    @Volatile
    private var initialized = false

    val rules = mutableStateListOf<AppRouteRule>()
    val quickFolders = mutableStateListOf<QuickFolder>()
    val pendingItems = mutableStateListOf<DetectedDownloadItem>()
    val historyItems = mutableStateListOf<TransferHistoryItem>()
    val runtimeLogs = mutableStateListOf<String>()
    val safTreeUris = mutableStateListOf<String>()

    var universalScanEnabled by mutableStateOf(true)
    var smartClassifyEnabled by mutableStateOf(true)
    var smartGroupByApp by mutableStateOf(false)
    var useEventTriggeredMode by mutableStateOf(true)
    var powerModeName by mutableStateOf("event_triggered")
    var requireWritePermissionCheck by mutableStateOf(true)
    var coalesceBatchDownloads by mutableStateOf(true)
    var writeVerifyDelayMs by mutableStateOf(1500L)
    var guardArmed by mutableStateOf(false)
    var integrityCheckEnabled by mutableStateOf(true)
    var autoCheckUpdateEnabled by mutableStateOf(true)
    var githubUpdateRepo by mutableStateOf("legeng49/FileRoute-Android-NoRoot")
    var ignoredUpdateTag by mutableStateOf("")
    var quickSilentShareArchive by mutableStateOf(false)

    private val handledKeys = linkedSetOf<String>()

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        universalScanEnabled = prefs.getBoolean(KEY_UNIVERSAL_SCAN, true)
        smartClassifyEnabled = prefs.getBoolean(KEY_SMART_CLASSIFY, true)
        smartGroupByApp = prefs.getBoolean(KEY_SMART_GROUP_BY_APP, false)
        powerModeName = prefs.getString(KEY_POWER_MODE, "event_triggered") ?: "event_triggered"
        useEventTriggeredMode = prefs.getBoolean(KEY_EVENT_TRIGGERED, powerModeName == "event_triggered")
        requireWritePermissionCheck = prefs.getBoolean(KEY_WRITE_CHECK, true)
        coalesceBatchDownloads = prefs.getBoolean(KEY_COALESCE_BATCH, true)
        writeVerifyDelayMs = prefs.getLong(KEY_VERIFY_DELAY_MS, 1500L)
        guardArmed = prefs.getBoolean(KEY_GUARD_ARMED, false)
        integrityCheckEnabled = prefs.getBoolean(KEY_INTEGRITY_CHECK, true)
        autoCheckUpdateEnabled = prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        githubUpdateRepo = prefs.getString(KEY_GITHUB_UPDATE_REPO, "legeng49/FileRoute-Android-NoRoot")
            ?.takeIf { it.isNotBlank() } ?: "legeng49/FileRoute-Android-NoRoot"
        ignoredUpdateTag = prefs.getString(KEY_IGNORED_UPDATE_TAG, "") ?: ""
        quickSilentShareArchive = prefs.getBoolean(KEY_QUICK_SILENT_SHARE, false)

        safTreeUris.clear()
        prefs.getStringSet(KEY_SAF_TREE_URIS, emptySet())?.let { safTreeUris.addAll(it) }

        rules.clear()
        rules.addAll(DefaultRuleRepository.initialRules)
        loadCustomRules(prefs.getString(KEY_CUSTOM_RULES_JSON, null))

        quickFolders.clear()
        loadQuickFolders(prefs.getString(KEY_QUICK_FOLDERS_JSON, null))
        if (quickFolders.isEmpty()) {
            quickFolders.addAll(DefaultRuleRepository.quickFolders)
        }

        handledKeys.clear()
        prefs.getStringSet(KEY_HANDLED_SET, emptySet())?.let { handledKeys.addAll(it) }

        historyItems.clear()
        loadHistory(prefs.getString(KEY_HISTORY_JSON, null))

        if (runtimeLogs.isEmpty()) {
            appendLog("引擎已初始化 · 全盘发现=${if (universalScanEnabled) "开启" else "关闭"} · 智能归类=${if (smartClassifyEnabled) "开启" else "关闭"}")
        }
        initialized = true
    }

    fun appendLog(message: String) {
        val entry = "[${DefaultRuleRepository.nowTimeText()}] $message"
        runOnMain {
            runtimeLogs.add(0, entry)
            if (runtimeLogs.size > 60) {
                runtimeLogs.removeAt(runtimeLogs.lastIndex)
            }
        }
    }

    fun isAlreadyHandled(context: Context, key: String): Boolean {
        ensureInit(context)
        synchronized(handledKeys) {
            return handledKeys.contains(key)
        }
    }

    fun markHandled(context: Context, key: String) {
        ensureInit(context)
        val snapshot: Set<String>
        synchronized(handledKeys) {
            handledKeys.add(key)
            while (handledKeys.size > 300) {
                val first = handledKeys.firstOrNull() ?: break
                handledKeys.remove(first)
            }
            snapshot = handledKeys.toSet()
        }
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_HANDLED_SET, snapshot).apply()
    }

    fun addOrUpdatePending(item: DetectedDownloadItem) {
        runOnMain {
            val existingIdx = pendingItems.indexOfFirst { it.sourcePath == item.sourcePath }
            if (existingIdx >= 0) {
                pendingItems[existingIdx] = item
            } else {
                pendingItems.add(0, item)
            }
        }
    }

    fun removePendingByPath(sourcePath: String) {
        runOnMain {
            pendingItems.removeAll { it.sourcePath == sourcePath }
        }
    }

    fun recordHistory(context: Context, item: TransferHistoryItem) {
        ensureInit(context)
        runOnMain {
            historyItems.add(0, item)
            if (historyItems.size > 500) {
                historyItems.removeAt(historyItems.lastIndex)
            }
            saveHistory(context)
        }
    }

    fun recordIgnored(context: Context, item: DetectedDownloadItem) {
        ensureInit(context)
        removePendingByPath(item.sourcePath)
        recordHistory(
            context,
            TransferHistoryItem(
                id = "ign_${System.currentTimeMillis()}",
                appName = item.appName,
                fileName = item.fileName,
                fromPath = item.sourcePath,
                toPath = item.sourcePath,
                fileSizeBytes = item.fileSizeBytes,
                copyOnly = false,
                restored = false,
                timeText = DefaultRuleRepository.nowDateTimeText(),
                mode = "ignored",
                status = "ignored"
            )
        )
        appendLog("已忽略：${item.fileName}（保留原目录）")
    }

    fun markHistoryRestored(context: Context, recordId: String) {
        runOnMain {
            val idx = historyItems.indexOfFirst { it.id == recordId }
            if (idx >= 0) {
                historyItems[idx] = historyItems[idx].copy(restored = true, status = "restored")
                saveHistory(context)
            }
        }
    }

    fun deleteHistoryItem(context: Context, recordId: String) {
        runOnMain {
            historyItems.removeAll { it.id == recordId }
            saveHistory(context)
        }
    }

    fun clearHistory(context: Context) {
        runOnMain {
            historyItems.clear()
            saveHistory(context)
        }
        appendLog("已清空全部历史归位记录")
    }

    fun exportHistoryCsv(context: Context): File? {
        ensureInit(context)
        return runCatching {
            val docsDir = File(Environment.getExternalStorageDirectory(), "Documents")
            if (!docsDir.exists()) docsDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
            val outFile = File(docsDir, "FileRoute_历史记录_$stamp.csv")
            val sb = StringBuilder()
            sb.append("时间,来源应用,文件名,大小,操作方式,状态,原路径,目标路径\n")
            historyItems.forEach { h ->
                fun csvEsc(s: String): String {
                    val escaped = s.replace("\"", "\"\"")
                    return "\"$escaped\""
                }
                sb.append(
                    listOf(
                        csvEsc(h.timeText),
                        csvEsc(h.appName),
                        csvEsc(h.fileName),
                        csvEsc(DefaultRuleRepository.formatSize(h.fileSizeBytes)),
                        csvEsc(h.mode),
                        csvEsc(h.status),
                        csvEsc(h.fromPath),
                        csvEsc(h.toPath)
                    ).joinToString(",")
                ).append("\n")
            }
            outFile.writeText(sb.toString(), Charsets.UTF_8)
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(outFile.absolutePath),
                null,
                null
            )
            appendLog("已导出历史记录 CSV：${outFile.absolutePath}")
            outFile
        }.getOrNull()
    }

    /**
     * 导出跨设备同步 JSON 配置文件（兼容网页端与另一台手机导入）
     */
    fun exportConfigJson(context: Context): File? {
        ensureInit(context)
        return runCatching {
            val docsDir = File(Environment.getExternalStorageDirectory(), "Documents")
            if (!docsDir.exists()) docsDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
            val outFile = File(docsDir, "FileRoute_Config_$stamp.json")

            val rulesArr = JSONArray()
            rules.forEach { r ->
                rulesArr.put(JSONObject().apply {
                    put("id", r.id)
                    put("appName", r.appName)
                    put("packageName", r.packageName)
                    put("enabled", r.enabled)
                    put("passiveWakeEnabled", r.passiveWakeEnabled)
                    put("passiveTriggerType", r.passiveTrigger.name.lowercase(Locale.ROOT))
                    put("channelMode", r.channel.name.lowercase(Locale.ROOT))
                    put("action", r.action.name.lowercase(Locale.ROOT))
                    put("sourcePaths", JSONArray(r.sourcePaths))
                    put("defaultTargetDir", r.defaultTargetDir)
                    put("renamePattern", r.renamePattern)
                    val subArr = JSONArray()
                    r.subRules.forEach { s ->
                        subArr.put(JSONObject().apply {
                            put("label", s.label)
                            put("extensions", JSONArray(s.extensions))
                            put("targetDir", s.targetDir)
                        })
                    }
                    put("subRules", subArr)
                })
            }

            val foldersArr = JSONArray()
            quickFolders.forEach { f ->
                foldersArr.put(JSONObject().apply {
                    put("label", f.label)
                    put("path", f.path)
                    put("desc", f.description)
                })
            }

            val root = JSONObject().apply {
                put("schema", "fileroute.config.v1")
                put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).format(Date()))
                put("appVersion", "2.1.0")
                put("rules", rulesArr)
                put("quickFolders", foldersArr)
            }
            outFile.writeText(root.toString(2), Charsets.UTF_8)
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(outFile.absolutePath),
                null,
                null
            )
            appendLog("已导出规则配置 JSON：${outFile.absolutePath}")
            outFile
        }.getOrNull()
    }

    /**
     * 导入网页端或旧设备导出的 JSON 配置文件，返回 (导入规则数, 导入常用目录数)
     */
    fun importConfigJson(context: Context, rawJson: String): Pair<Int, Int>? {
        ensureInit(context)
        return runCatching {
            val root = JSONObject(rawJson)
            val rulesArr = root.optJSONArray("rules")
            val foldersArr = root.optJSONArray("quickFolders")

            val importedRules = mutableListOf<AppRouteRule>()
            if (rulesArr != null) {
                for (i in 0 until rulesArr.length()) {
                    val o = rulesArr.optJSONObject(i) ?: continue
                    val appName = o.optString("appName").trim()
                    if (appName.isEmpty()) continue
                    val srcArr = o.optJSONArray("sourcePaths")
                    val paths = mutableListOf<String>()
                    if (srcArr != null) {
                        for (j in 0 until srcArr.length()) {
                            val p = srcArr.optString(j).trim()
                            if (p.isNotEmpty()) paths.add(p)
                        }
                    }
                    val subArr = o.optJSONArray("subRules")
                    val subs = mutableListOf<ExtensionSubRule>()
                    if (subArr != null) {
                        for (j in 0 until subArr.length()) {
                            val so = subArr.optJSONObject(j) ?: continue
                            val extArr = so.optJSONArray("extensions") ?: continue
                            val exts = mutableListOf<String>()
                            for (k in 0 until extArr.length()) {
                                val e = extArr.optString(k).trim().lowercase(Locale.ROOT)
                                if (e.isNotEmpty()) exts.add(e)
                            }
                            if (exts.isNotEmpty()) {
                                subs.add(
                                    ExtensionSubRule(
                                        label = so.optString("label", "自定义分类"),
                                        extensions = exts,
                                        targetDir = DefaultRuleRepository.ensureTrailingSlash(
                                            so.optString("targetDir", "/storage/emulated/0/Documents/")
                                        )
                                    )
                                )
                            }
                        }
                    }
                    val ch = if (o.optString("channelMode").equals("share_intent", true)) {
                        ChannelMode.SHARE_INTENT
                    } else {
                        ChannelMode.PUBLIC_OBSERVER
                    }
                    val act = runCatching {
                        RouteAction.valueOf(o.optString("action", "PROMPT").uppercase(Locale.ROOT))
                    }.getOrDefault(RouteAction.PROMPT)
                    val trig = runCatching {
                        PassiveTriggerType.valueOf(o.optString("passiveTriggerType", "MEDIASTORE_TRIGGER").uppercase(Locale.ROOT))
                    }.getOrDefault(PassiveTriggerType.MEDIASTORE_TRIGGER)

                    importedRules.add(
                        AppRouteRule(
                            id = o.optString("id").ifBlank { "imp_${System.currentTimeMillis()}_$i" },
                            appName = appName,
                            packageName = o.optString("packageName", "custom.app"),
                            enabled = o.optBoolean("enabled", true),
                            passiveWakeEnabled = o.optBoolean("passiveWakeEnabled", true),
                            passiveTrigger = trig,
                            channel = ch,
                            action = act,
                            sourcePaths = if (paths.isNotEmpty()) paths else listOf("/storage/emulated/0/Download/"),
                            defaultTargetDir = DefaultRuleRepository.ensureTrailingSlash(
                                o.optString("defaultTargetDir", "/storage/emulated/0/Documents/")
                            ),
                            renamePattern = o.optString("renamePattern", "{name}"),
                            subRules = subs
                        )
                    )
                }
            }

            val importedFolders = mutableListOf<QuickFolder>()
            if (foldersArr != null) {
                for (i in 0 until foldersArr.length()) {
                    val o = foldersArr.optJSONObject(i) ?: continue
                    val label = o.optString("label").trim()
                    val path = o.optString("path").trim()
                    if (label.isNotEmpty() && path.isNotEmpty()) {
                        importedFolders.add(
                            QuickFolder(
                                label = label,
                                path = DefaultRuleRepository.ensureTrailingSlash(path),
                                description = o.optString("desc", o.optString("description", "导入的常用目录"))
                            )
                        )
                    }
                }
            }

            if (importedRules.isEmpty() && importedFolders.isEmpty()) return null

            runOnMain {
                if (importedRules.isNotEmpty()) {
                    rules.clear()
                    rules.addAll(importedRules)
                    saveCustomRules(context)
                }
                if (importedFolders.isNotEmpty()) {
                    quickFolders.clear()
                    quickFolders.addAll(importedFolders)
                    saveQuickFolders(context)
                }
            }
            appendLog("已导入 JSON 配置：${importedRules.size} 条规则，${importedFolders.size} 个常用目录")
            importedRules.size to importedFolders.size
        }.getOrNull()
    }

    private fun saveQuickFolders(context: Context) {
        val arr = JSONArray()
        quickFolders.forEach { f ->
            arr.put(JSONObject().apply {
                put("label", f.label)
                put("path", f.path)
                put("desc", f.description)
            })
        }
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_FOLDERS_JSON, arr.toString())
            .apply()
    }

    private fun loadQuickFolders(raw: String?) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val label = o.optString("label").trim()
                val path = o.optString("path").trim()
                if (label.isNotEmpty() && path.isNotEmpty()) {
                    quickFolders.add(QuickFolder(label, path, o.optString("desc", "")))
                }
            }
        }
    }

    fun updateSettings(
        context: Context,
        universal: Boolean = universalScanEnabled,
        smartClassify: Boolean = smartClassifyEnabled,
        groupByApp: Boolean = smartGroupByApp,
        eventTriggered: Boolean = useEventTriggeredMode,
        powerMode: String = powerModeName,
        writeCheck: Boolean = requireWritePermissionCheck,
        coalesceBatch: Boolean = coalesceBatchDownloads,
        verifyDelayMs: Long = writeVerifyDelayMs,
        armed: Boolean = guardArmed,
        integrityCheck: Boolean = integrityCheckEnabled,
        autoCheckUpdate: Boolean = autoCheckUpdateEnabled,
        updateRepo: String = githubUpdateRepo,
        quickSilentShare: Boolean = quickSilentShareArchive
    ) {
        ensureInit(context)
        universalScanEnabled = universal
        smartClassifyEnabled = smartClassify
        smartGroupByApp = groupByApp
        powerModeName = powerMode
        useEventTriggeredMode = eventTriggered
        requireWritePermissionCheck = writeCheck
        coalesceBatchDownloads = coalesceBatch
        writeVerifyDelayMs = verifyDelayMs
        guardArmed = armed
        integrityCheckEnabled = integrityCheck
        autoCheckUpdateEnabled = autoCheckUpdate
        githubUpdateRepo = updateRepo.trim().ifBlank { "legeng49/FileRoute-Android-NoRoot" }
        quickSilentShareArchive = quickSilentShare
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UNIVERSAL_SCAN, universal)
            .putBoolean(KEY_SMART_CLASSIFY, smartClassify)
            .putBoolean(KEY_SMART_GROUP_BY_APP, groupByApp)
            .putString(KEY_POWER_MODE, powerMode)
            .putBoolean(KEY_EVENT_TRIGGERED, eventTriggered)
            .putBoolean(KEY_WRITE_CHECK, writeCheck)
            .putBoolean(KEY_COALESCE_BATCH, coalesceBatch)
            .putLong(KEY_VERIFY_DELAY_MS, verifyDelayMs)
            .putBoolean(KEY_GUARD_ARMED, armed)
            .putBoolean(KEY_INTEGRITY_CHECK, integrityCheck)
            .putBoolean(KEY_AUTO_CHECK_UPDATE, autoCheckUpdate)
            .putString(KEY_GITHUB_UPDATE_REPO, githubUpdateRepo)
            .putBoolean(KEY_QUICK_SILENT_SHARE, quickSilentShare)
            .apply()
    }

    fun ignoreUpdateTag(context: Context, tag: String) {
        ensureInit(context)
        ignoredUpdateTag = tag
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IGNORED_UPDATE_TAG, tag)
            .apply()
    }

    fun addSafTreeUri(context: Context, uriString: String) {
        ensureInit(context)
        if (!safTreeUris.contains(uriString)) {
            runOnMain { safTreeUris.add(uriString) }
            val nextSet = safTreeUris.toMutableSet().apply { add(uriString) }
            context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SAF_TREE_URIS, nextSet)
                .apply()
            appendLog("已添加 SAF 聊天目录深度监控授权：$uriString")
        }
    }

    fun removeSafTreeUri(context: Context, uriString: String) {
        ensureInit(context)
        runOnMain { safTreeUris.remove(uriString) }
        val nextSet = safTreeUris.toMutableSet().apply { remove(uriString) }
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SAF_TREE_URIS, nextSet)
            .apply()
    }

    data class PathConflictNotice(
        val severity: String, // "shadowed" | "duplicate" | "safe"
        val higherRuleName: String,
        val higherRank: Int,
        val higherPath: String,
        val lowerRuleId: String,
        val lowerRuleName: String,
        val lowerRank: Int,
        val lowerPath: String,
        val summary: String
    )

    fun analyzePathConflicts(): List<PathConflictNotice> {
        val enabled = rules.mapIndexedNotNull { idx, r ->
            if (r.enabled && r.action != RouteAction.IGNORE) (idx + 1 to r) else null
        }
        val list = mutableListOf<PathConflictNotice>()
        val seen = mutableSetOf<String>()
        for (i in enabled.indices) {
            val (rankH, higher) = enabled[i]
            for (j in i + 1 until enabled.size) {
                val (rankL, lower) = enabled[j]
                for (pH in higher.sourcePaths) {
                    val normH = DefaultRuleRepository.ensureTrailingSlash(pH).lowercase(Locale.ROOT)
                    if (normH.isBlank()) continue
                    for (pL in lower.sourcePaths) {
                        val normL = DefaultRuleRepository.ensureTrailingSlash(pL).lowercase(Locale.ROOT)
                        if (normL.isBlank()) continue
                        if (higher.channel != lower.channel && normH != normL) continue
                        when {
                            normH == normL -> {
                                val key = "dup:${higher.id}:${lower.id}:$normH"
                                if (seen.add(key)) {
                                    list.add(
                                        PathConflictNotice(
                                            severity = "duplicate",
                                            higherRuleName = higher.appName,
                                            higherRank = rankH,
                                            higherPath = pH,
                                            lowerRuleId = lower.id,
                                            lowerRuleName = lower.appName,
                                            lowerRank = rankL,
                                            lowerPath = pL,
                                            summary = "#${String.format("%02d", rankH)} ${higher.appName} 与 #${String.format("%02d", rankL)} ${lower.appName} 监听了完全相同的路径"
                                        )
                                    )
                                }
                            }
                            higher.channel == ChannelMode.PUBLIC_OBSERVER &&
                                lower.channel == ChannelMode.PUBLIC_OBSERVER &&
                                normL.startsWith(normH) && normL.length > normH.length -> {
                                val key = "sh:${higher.id}:${lower.id}:$normH:$normL"
                                if (seen.add(key)) {
                                    list.add(
                                        PathConflictNotice(
                                            severity = "shadowed",
                                            higherRuleName = higher.appName,
                                            higherRank = rankH,
                                            higherPath = pH,
                                            lowerRuleId = lower.id,
                                            lowerRuleName = lower.appName,
                                            lowerRank = rankL,
                                            lowerPath = pL,
                                            summary = "宽泛父路径拦截：#${String.format("%02d", rankH)} ${higher.appName} 会抢先截获 #${String.format("%02d", rankL)} ${lower.appName}"
                                        )
                                    )
                                }
                            }
                            higher.channel == ChannelMode.PUBLIC_OBSERVER &&
                                lower.channel == ChannelMode.PUBLIC_OBSERVER &&
                                normH.startsWith(normL) && normH.length > normL.length -> {
                                val key = "sf:${higher.id}:${lower.id}:$normH:$normL"
                                if (seen.add(key)) {
                                    list.add(
                                        PathConflictNotice(
                                            severity = "safe",
                                            higherRuleName = higher.appName,
                                            higherRank = rankH,
                                            higherPath = pH,
                                            lowerRuleId = lower.id,
                                            lowerRuleName = lower.appName,
                                            lowerRank = rankL,
                                            lowerPath = pL,
                                            summary = "父子重叠（已安全排序）：#${String.format("%02d", rankH)} ${higher.appName} 优先于 #${String.format("%02d", rankL)} ${lower.appName}"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    fun optimizeRulePriorities(context: Context): Int {
        ensureInit(context)
        val movedIds = mutableSetOf<String>()
        val maxSteps = (rules.size * rules.size).coerceAtLeast(8)
        repeat(maxSteps) {
            val conflict = analyzePathConflicts().firstOrNull { it.severity == "shadowed" } ?: return@repeat
            val higherIdx = conflict.higherRank - 1
            val lowerIdx = conflict.lowerRank - 1
            if (higherIdx !in rules.indices || lowerIdx !in rules.indices || lowerIdx <= higherIdx) return@repeat
            val child = rules.removeAt(lowerIdx)
            rules.add(higherIdx, child)
            movedIds.add(child.id)
        }
        if (movedIds.isNotEmpty()) {
            saveCustomRules(context)
            appendLog("已自动优化规则优先级，上移 ${movedIds.size} 条子目录规则以消除路径拦截")
        }
        return movedIds.size
    }

    /**
     * 规则健康度一键修复：
     * 1) 存在路径冲突时按路径深度（从深到浅）自动重排公共目录监听规则优先级；
     * 2) 合并并去重多条规则中重复匹配的文件后缀与同目标子规则。
     * 返回 Pair(重排规则数, 合并重复后缀数)
     */
    fun autoFixRuleHealthIssues(context: Context): Pair<Int, Int> {
        ensureInit(context)
        fun pathDepth(path: String): Int =
            path.trim().trim('/').split('/').filter { it.isNotBlank() }.size

        val beforeOrder = rules.map { it.id }
        val conflicts = analyzePathConflicts()
        val hasConflict = conflicts.any { it.severity == "shadowed" || it.severity == "exact_duplicate" }
        var reorderedCount = 0

        if (hasConflict) {
            val publicSlots = mutableListOf<Int>()
            val publicRules = mutableListOf<AppRouteRule>()
            rules.forEachIndexed { idx, r ->
                if (r.enabled && r.action != RouteAction.IGNORE && r.channel == ChannelMode.PUBLIC_OBSERVER) {
                    publicSlots.add(idx)
                    publicRules.add(r)
                }
            }
            val sortedPublic = publicRules.sortedWith(
                compareByDescending<AppRouteRule> { rule ->
                    rule.sourcePaths.maxOfOrNull { pathDepth(it) } ?: 0
                }.thenBy { beforeOrder.indexOf(it.id) }
            )
            publicSlots.forEachIndexed { i, slotIdx ->
                rules[slotIdx] = sortedPublic[i]
            }
            optimizeRulePriorities(context)
            reorderedCount = rules.indices.count { rules[it].id != beforeOrder.getOrNull(it) }
        }

        // 合并重复文件后缀与同目标子规则
        val claimedExts = hashSetOf<String>()
        val mergedExts = hashSetOf<String>()

        for (i in rules.indices) {
            val r = rules[i]
            if (!r.enabled || r.action == RouteAction.IGNORE) continue
            val byTarget = linkedMapOf<String, ExtensionSubRule>()
            for (sub in r.subRules) {
                val cleanTarget = DefaultRuleRepository.ensureTrailingSlash(sub.targetDir)
                val kept = mutableListOf<String>()
                for (rawExt in sub.extensions) {
                    val ext = rawExt.trim().lowercase().removePrefix(".")
                    if (ext.isEmpty()) continue
                    if (claimedExts.add(ext)) {
                        kept.add(ext)
                    } else {
                        mergedExts.add(ext)
                    }
                }
                if (kept.isNotEmpty()) {
                    val key = cleanTarget.lowercase()
                    val prev = byTarget[key]
                    if (prev == null) {
                        byTarget[key] = sub.copy(targetDir = cleanTarget, extensions = kept)
                    } else {
                        val combinedLabel = if (prev.label.contains(sub.label)) prev.label else "${prev.label} / ${sub.label}"
                        byTarget[key] = prev.copy(
                            label = combinedLabel,
                            extensions = (prev.extensions + kept).distinct()
                        )
                    }
                }
            }
            rules[i] = r.copy(subRules = byTarget.values.toList())
        }

        if (reorderedCount > 0 || mergedExts.isNotEmpty()) {
            saveCustomRules(context)
            appendLog("规则健康度一键修复完成：重排 $reorderedCount 条优先级 · 合并 ${mergedExts.size} 个重复后缀")
        }
        return reorderedCount to mergedExts.size
    }

    fun moveRule(context: Context, index: Int, delta: Int): Boolean {
        ensureInit(context)
        val target = index + delta
        if (index !in rules.indices || target !in rules.indices) return false
        val item = rules.removeAt(index)
        rules.add(target, item)
        saveCustomRules(context)
        appendLog("已调整规则优先级：【${item.appName}】移至第 ${target + 1} 位")
        return true
    }

    fun resetDefaults(context: Context) {
        ensureInit(context)
        rules.clear()
        rules.addAll(DefaultRuleRepository.initialRules)
        quickFolders.clear()
        quickFolders.addAll(DefaultRuleRepository.quickFolders)
        saveCustomRules(context)
        saveQuickFolders(context)
        updateSettings(
            context = context,
            universal = true,
            smartClassify = true,
            groupByApp = false,
            eventTriggered = true,
            powerMode = "event_triggered",
            writeCheck = true,
            coalesceBatch = true,
            verifyDelayMs = 1500L
        )
        appendLog("已恢复默认预设规则与触发式唤醒配置")
    }

    fun updateRule(context: Context, updated: AppRouteRule) {
        ensureInit(context)
        val idx = rules.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            rules[idx] = updated
        } else {
            rules.add(updated)
        }
        saveCustomRules(context)
    }

    /**
     * 将本机探测到的真实目录一键绑定到对应规则（若无匹配规则则自动新建）
     */
    fun bindDiscoveredPathToRule(
        context: Context,
        dirPath: String,
        inferredAppName: String,
        matchedRuleId: String?
    ): String {
        ensureInit(context)
        val cleanPath = DefaultRuleRepository.ensureTrailingSlash(dirPath)
        val targetIdx = if (matchedRuleId != null) {
            rules.indexOfFirst { it.id == matchedRuleId }
        } else {
            -1
        }

        if (targetIdx >= 0) {
            val base = rules[targetIdx]
            val merged = (base.sourcePaths.map { DefaultRuleRepository.ensureTrailingSlash(it) } + cleanPath)
                .distinctBy { it.lowercase(Locale.ROOT) }
            rules[targetIdx] = base.copy(
                enabled = true,
                channel = ChannelMode.PUBLIC_OBSERVER,
                sourcePaths = merged
            )
            saveCustomRules(context)
            appendLog("已将真实目录绑定至【${base.appName}】：$cleanPath")
            return "已绑定至「${base.appName}」规则"
        }

        val alreadyRule = rules.firstOrNull { r ->
            r.sourcePaths.any { DefaultRuleRepository.ensureTrailingSlash(it).equals(cleanPath, ignoreCase = true) }
        }
        if (alreadyRule != null) {
            return "该目录已在「${alreadyRule.appName}」监控中"
        }

        val folderLabel = cleanPath.trimEnd('/').substringAfterLast('/').ifBlank { inferredAppName }
        val newRule = AppRouteRule(
            id = "rule_auto_${System.currentTimeMillis()}",
            appName = if (inferredAppName.isNotBlank()) inferredAppName else folderLabel,
            packageName = "custom.discovered",
            enabled = true,
            passiveWakeEnabled = true,
            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
            channel = ChannelMode.PUBLIC_OBSERVER,
            action = RouteAction.PROMPT,
            sourcePaths = listOf(cleanPath),
            defaultTargetDir = DefaultRuleRepository.ensureTrailingSlash("/storage/emulated/0/Documents/${folderLabel}归档/"),
            renamePattern = "{name}",
            subRules = emptyList()
        )
        rules.add(0, newRule)
        saveCustomRules(context)
        appendLog("已为本机目录新建监控规则【${newRule.appName}】：$cleanPath")
        return "已新建「${newRule.appName}」监控规则"
    }

    /**
     * 一键校准全部监控规则：用本机真实存在的目录替换不存在的预设路径，并补齐新发现的应用目录
     */
    fun autoCalibrateAllRules(
        context: Context,
        discovered: List<DiscoveredDeviceDir>
    ): Pair<Int, Int> {
        ensureInit(context)
        if (discovered.isEmpty()) return 0 to 0

        val byRuleId = discovered
            .filter { it.matchedRuleId != null }
            .groupBy { it.matchedRuleId!! }

        var updatedRuleCount = 0
        var totalBoundPaths = 0

        byRuleId.forEach { (ruleId, dirs) ->
            val idx = rules.indexOfFirst { it.id == ruleId }
            if (idx >= 0) {
                val base = rules[idx]
                val existingValidPaths = base.sourcePaths
                    .map { DefaultRuleRepository.ensureTrailingSlash(it) }
                    .filter { p ->
                        val f = File(p)
                        f.exists() && f.isDirectory
                    }
                val discoveredPaths = dirs.map { DefaultRuleRepository.ensureTrailingSlash(it.path) }
                val finalPaths = (existingValidPaths + discoveredPaths)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                if (finalPaths.isNotEmpty()) {
                    rules[idx] = base.copy(
                        enabled = true,
                        sourcePaths = finalPaths
                    )
                    updatedRuleCount++
                    totalBoundPaths += discoveredPaths.size
                }
            }
        }

        if (updatedRuleCount > 0) {
            saveCustomRules(context)
            appendLog("一键路径校准完成：已校准 $updatedRuleCount 条规则，绑定 $totalBoundPaths 个本机真实目录")
        }
        return updatedRuleCount to totalBoundPaths
    }

    private fun saveHistory(context: Context) {
        val arr = JSONArray()
        historyItems.forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id)
                put("appName", h.appName)
                put("fileName", h.fileName)
                put("fromPath", h.fromPath)
                put("toPath", h.toPath)
                put("fileSizeBytes", h.fileSizeBytes)
                put("copyOnly", h.copyOnly)
                put("restored", h.restored)
                put("timeText", h.timeText)
                put("mode", h.mode)
                put("status", h.status)
            })
        }
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }

    private fun loadHistory(raw: String?) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val restored = o.optBoolean("restored")
                historyItems.add(
                    TransferHistoryItem(
                        id = o.optString("id"),
                        appName = o.optString("appName"),
                        fileName = o.optString("fileName"),
                        fromPath = o.optString("fromPath"),
                        toPath = o.optString("toPath"),
                        fileSizeBytes = o.optLong("fileSizeBytes"),
                        copyOnly = o.optBoolean("copyOnly"),
                        restored = restored,
                        timeText = o.optString("timeText"),
                        mode = o.optString("mode", "prompt_confirmed"),
                        status = o.optString("status", if (restored) "restored" else "completed")
                    )
                )
            }
        }
    }

    private fun saveCustomRules(context: Context) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("appName", r.appName)
                put("packageName", r.packageName)
                put("enabled", r.enabled)
                put("action", r.action.name)
                put("defaultTargetDir", r.defaultTargetDir)
                put("sourcePaths", JSONArray(r.sourcePaths))
                put("cleanupStrategy", r.cleanupStrategy)
                put("cleanupDelayDays", r.cleanupDelayDays)
            })
        }
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_RULES_JSON, arr.toString())
            .apply()
    }

    private fun loadCustomRules(raw: String?) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val srcArr = o.optJSONArray("sourcePaths")
                val paths = mutableListOf<String>()
                if (srcArr != null) {
                    for (j in 0 until srcArr.length()) paths.add(srcArr.optString(j))
                }
                val action = runCatching { RouteAction.valueOf(o.optString("action")) }
                    .getOrDefault(RouteAction.PROMPT)
                val cleanupStrategy = o.optString("cleanupStrategy", "keep")
                val cleanupDelayDays = o.optInt("cleanupDelayDays", 7).coerceIn(1, 90)
                val existingIdx = rules.indexOfFirst { it.id == id }
                if (existingIdx >= 0) {
                    val base = rules[existingIdx]
                    rules[existingIdx] = base.copy(
                        enabled = o.optBoolean("enabled", base.enabled),
                        action = action,
                        defaultTargetDir = o.optString("defaultTargetDir", base.defaultTargetDir),
                        sourcePaths = if (paths.isNotEmpty()) paths else base.sourcePaths,
                        cleanupStrategy = cleanupStrategy,
                        cleanupDelayDays = cleanupDelayDays
                    )
                } else if (id.isNotBlank()) {
                    rules.add(
                        AppRouteRule(
                            id = id,
                            appName = o.optString("appName", "自定义目录"),
                            packageName = o.optString("packageName", "custom.app"),
                            enabled = o.optBoolean("enabled", true),
                            passiveWakeEnabled = true,
                            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
                            channel = ChannelMode.PUBLIC_OBSERVER,
                            action = action,
                            sourcePaths = paths,
                            defaultTargetDir = o.optString("defaultTargetDir", "/storage/emulated/0/Documents/"),
                            renamePattern = "{name}",
                            subRules = emptyList(),
                            cleanupStrategy = cleanupStrategy,
                            cleanupDelayDays = cleanupDelayDays
                        )
                    )
                }
            }
        }
    }
}
