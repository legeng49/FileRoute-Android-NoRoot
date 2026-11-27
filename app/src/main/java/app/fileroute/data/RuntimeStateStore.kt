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
    private const val PREF_NAME = "fileroute_runtime_v21"
    private const val KEY_UNIVERSAL_SCAN = "universal_scan"
    private const val KEY_SMART_CLASSIFY = "smart_classify"
    private const val KEY_EVENT_TRIGGERED = "event_triggered"
    private const val KEY_GUARD_ARMED = "guard_armed"
    private const val KEY_HANDLED_SET = "handled_files"
    private const val KEY_HISTORY_JSON = "history_json"
    private const val KEY_CUSTOM_RULES_JSON = "custom_rules_json"
    private const val KEY_QUICK_FOLDERS_JSON = "quick_folders_json"

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

    var universalScanEnabled by mutableStateOf(true)
    var smartClassifyEnabled by mutableStateOf(true)
    var useEventTriggeredMode by mutableStateOf(false)
    var guardArmed by mutableStateOf(false)

    private val handledKeys = linkedSetOf<String>()

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        universalScanEnabled = prefs.getBoolean(KEY_UNIVERSAL_SCAN, true)
        smartClassifyEnabled = prefs.getBoolean(KEY_SMART_CLASSIFY, true)
        useEventTriggeredMode = prefs.getBoolean(KEY_EVENT_TRIGGERED, false)
        guardArmed = prefs.getBoolean(KEY_GUARD_ARMED, false)

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
            sb.append("﻿时间,来源应用,文件名,大小,操作方式,状态,原路径,目标路径
")
            historyItems.forEach { h ->
                fun csvEsc(s: String) = ""${s.replace(""", """")}""
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
                ).append("
")
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
        eventTriggered: Boolean = useEventTriggeredMode,
        armed: Boolean = guardArmed
    ) {
        ensureInit(context)
        universalScanEnabled = universal
        smartClassifyEnabled = smartClassify
        useEventTriggeredMode = eventTriggered
        guardArmed = armed
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UNIVERSAL_SCAN, universal)
            .putBoolean(KEY_SMART_CLASSIFY, smartClassify)
            .putBoolean(KEY_EVENT_TRIGGERED, eventTriggered)
            .putBoolean(KEY_GUARD_ARMED, armed)
            .apply()
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
                val existingIdx = rules.indexOfFirst { it.id == id }
                if (existingIdx >= 0) {
                    val base = rules[existingIdx]
                    rules[existingIdx] = base.copy(
                        enabled = o.optBoolean("enabled", base.enabled),
                        action = action,
                        defaultTargetDir = o.optString("defaultTargetDir", base.defaultTargetDir),
                        sourcePaths = if (paths.isNotEmpty()) paths else base.sourcePaths
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
                            subRules = emptyList()
                        )
                    )
                }
            }
        }
    }
}
