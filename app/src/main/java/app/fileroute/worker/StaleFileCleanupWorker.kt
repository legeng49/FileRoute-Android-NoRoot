package app.fileroute.worker

import android.content.Context
import android.media.MediaScannerConnection
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.data.RuntimeStateStore
import app.fileroute.util.NativeDirectoryPicker
import java.io.File
import java.util.concurrent.TimeUnit

data class AnalyzedRuleFileItem(
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val ageDays: Int,
    val isStale: Boolean
)

data class RuleStorageCardReport(
    val ruleId: String,
    val appName: String,
    val targetDirs: List<String>,
    val totalBytes: Long,
    val staleBytes: Long,
    val fileCount: Int,
    val staleCount: Int,
    val topFiles: List<AnalyzedRuleFileItem>
)

data class StorageDeepScanReport(
    val cards: List<RuleStorageCardReport>,
    val totalBytes: Long,
    val totalFiles: Int,
    val staleBytes: Long,
    val staleFiles: Int,
    val scannedDirCount: Int
)

object StorageDeepAnalyzer {
    const val STALE_THRESHOLD_DAYS = 30

    private fun collectFiles(dir: File, maxDepth: Int, out: MutableList<File>) {
        if (maxDepth < 0 || !dir.exists() || !dir.isDirectory || !dir.canRead()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isFile) {
                out.add(child)
            } else if (child.isDirectory && maxDepth > 0) {
                collectFiles(child, maxDepth - 1, out)
            }
        }
    }

    fun scanAllRuleTargets(
        context: Context,
        staleDays: Int = STALE_THRESHOLD_DAYS
    ): StorageDeepScanReport {
        RuntimeStateStore.ensureInit(context)
        val now = System.currentTimeMillis()
        val staleThresholdMs = staleDays * 24L * 60L * 60L * 1000L
        val uniqueDirs = linkedSetOf<String>()

        val cards = RuntimeStateStore.rules.map { rule ->
            val dirs = buildList {
                add(NativeDirectoryPicker.normalizeCanonicalPath(rule.defaultTargetDir))
                rule.subRules.forEach { sub ->
                    add(NativeDirectoryPicker.normalizeCanonicalPath(sub.targetDir))
                }
            }.distinctBy { it.lowercase() }

            dirs.forEach { uniqueDirs.add(it.lowercase()) }

            val foundFiles = mutableListOf<File>()
            val seenPaths = hashSetOf<String>()
            dirs.forEach { dirPath ->
                val rawList = mutableListOf<File>()
                collectFiles(File(dirPath), maxDepth = 2, out = rawList)
                rawList.forEach { f ->
                    if (seenPaths.add(f.absolutePath.lowercase())) {
                        foundFiles.add(f)
                    }
                }
            }

            val analyzed = foundFiles.map { f ->
                val ageMs = (now - f.lastModified()).coerceAtLeast(0L)
                val ageDays = (ageMs / (24L * 60L * 60L * 1000L)).toInt()
                AnalyzedRuleFileItem(
                    name = f.name,
                    absolutePath = f.absolutePath,
                    sizeBytes = f.length().coerceAtLeast(0L),
                    ageDays = ageDays,
                    isStale = ageMs > staleThresholdMs
                )
            }.sortedByDescending { it.sizeBytes }

            val totalBytes = analyzed.sumOf { it.sizeBytes }
            val staleItems = analyzed.filter { it.isStale }
            RuleStorageCardReport(
                ruleId = rule.id,
                appName = rule.appName,
                targetDirs = dirs,
                totalBytes = totalBytes,
                staleBytes = staleItems.sumOf { it.sizeBytes },
                fileCount = analyzed.size,
                staleCount = staleItems.size,
                topFiles = analyzed.take(8)
            )
        }.sortedByDescending { it.totalBytes }

        return StorageDeepScanReport(
            cards = cards,
            totalBytes = cards.sumOf { it.totalBytes },
            totalFiles = cards.sumOf { it.fileCount },
            staleBytes = cards.sumOf { it.staleBytes },
            staleFiles = cards.sumOf { it.staleCount },
            scannedDirCount = uniqueDirs.size
        )
    }

    fun deleteStaleFiles(
        context: Context,
        ruleId: String? = null,
        staleDays: Int = STALE_THRESHOLD_DAYS,
        onProgress: ((processed: Int, total: Int, freedBytes: Long, currentFileName: String) -> Unit)? = null
    ): Pair<Int, Long> {
        RuntimeStateStore.ensureInit(context)
        val now = System.currentTimeMillis()
        val staleThresholdMs = staleDays * 24L * 60L * 60L * 1000L

        val targetRules = if (ruleId != null) {
            RuntimeStateStore.rules.filter { it.id == ruleId }
        } else {
            RuntimeStateStore.rules.toList()
        }

        val targetDirs = targetRules.flatMap { rule ->
            listOf(NativeDirectoryPicker.normalizeCanonicalPath(rule.defaultTargetDir)) +
                rule.subRules.map { NativeDirectoryPicker.normalizeCanonicalPath(it.targetDir) }
        }.distinctBy { it.lowercase() }

        val staleCandidates = mutableListOf<File>()
        val seenPaths = hashSetOf<String>()
        targetDirs.forEach { dirPath ->
            val files = mutableListOf<File>()
            collectFiles(File(dirPath), maxDepth = 2, out = files)
            files.forEach { f ->
                val ageMs = (now - f.lastModified()).coerceAtLeast(0L)
                if (ageMs > staleThresholdMs && seenPaths.add(f.absolutePath.lowercase())) {
                    staleCandidates.add(f)
                }
            }
        }

        var deletedCount = 0
        var freedBytes = 0L
        val deletedPaths = mutableListOf<String>()
        val total = staleCandidates.size

        staleCandidates.forEachIndexed { idx, f ->
            val size = f.length().coerceAtLeast(0L)
            if (runCatching { f.delete() }.getOrDefault(false)) {
                deletedCount += 1
                freedBytes += size
                deletedPaths.add(f.absolutePath)
            }
            onProgress?.invoke(idx + 1, total, freedBytes, f.name)
        }

        if (deletedPaths.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    deletedPaths.toTypedArray(),
                    null,
                    null
                )
            }
            RuntimeStateStore.appendLog(
                "存储空间深度清理完成：删除 $deletedCount 个超过 ${staleDays} 天的旧文件，释放 ${DefaultRuleRepository.formatSize(freedBytes)}"
            )
        }
        return deletedCount to freedBytes
    }
}

class StaleFileCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        RuntimeStateStore.ensureInit(applicationContext)
        if (!RuntimeStateStore.staleCleanup30dEnabled) return Result.success()
        StorageDeepAnalyzer.deleteStaleFiles(
            context = applicationContext,
            ruleId = null,
            staleDays = StorageDeepAnalyzer.STALE_THRESHOLD_DAYS
        )
        return Result.success()
    }

    companion object {
        private const val UNIQUE_CLEANUP_WORK = "fileroute_stale_30d_cleanup_worker"

        fun syncSchedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (enabled) {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
                val request = PeriodicWorkRequestBuilder<StaleFileCleanupWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                wm.enqueueUniquePeriodicWork(
                    UNIQUE_CLEANUP_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                RuntimeStateStore.appendLog("已开启 30 天过时旧文件定时清理任务（WorkManager 每日自动巡检）")
            } else {
                wm.cancelUniqueWork(UNIQUE_CLEANUP_WORK)
                RuntimeStateStore.appendLog("已关闭 30 天过时旧文件定时清理任务")
            }
        }
    }
}
