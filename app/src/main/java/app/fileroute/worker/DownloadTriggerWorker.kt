package app.fileroute.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fileroute.data.ChannelMode
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.data.PassiveTriggerType
import app.fileroute.data.RouteAction
import app.fileroute.service.DownloadMonitorService
import app.fileroute.ui.ShareReceiverActivity
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 触发式唤醒优化引擎（WorkManager + MediaStore ContentUriTrigger）：
 * 1. 平时完全休眠不常驻前台，空闲唤醒频率 0 次/分
 * 2. 仅针对已勾选「MediaStore 被动触发」的应用（如微信、新版QQ、钉钉、飞书）在文件下载完成后拉起
 * 3. 拉起前校验写入权限就绪状态（requireWritePermissionCheck = true）
 */
class DownloadTriggerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 前置写入权限校验：未获得写入权限时不扫描，直接结束避免无效唤醒
        if (REQUIRE_WRITE_PERMISSION_CHECK &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            scheduleNextTrigger(applicationContext)
            return Result.success()
        }

        val triggeredUris = triggeredContentUris
        if (triggeredUris.isNotEmpty()) {
            inspectRecentDownloads()
        }
        // 每次唤醒处理完成后自动重新挂载下一次触发监听，保证永不中断
        scheduleNextTrigger(applicationContext)
        return Result.success()
    }

    private fun inspectRecentDownloads() {
        val cutoffMillis = System.currentTimeMillis() - 90_000L
        val mediaStoreRules = DefaultRuleRepository.initialRules.filter {
            it.enabled &&
                it.passiveWakeEnabled &&
                it.passiveTrigger == PassiveTriggerType.MEDIASTORE_TRIGGER &&
                it.channel == ChannelMode.PUBLIC_OBSERVER &&
                it.action != RouteAction.IGNORE
        }

        for (rule in mediaStoreRules) {
            for (dirPath in rule.sourcePaths) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory || !dir.canWrite()) continue
                dir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") && it.lastModified() >= cutoffMillis }
                    ?.forEach { file ->
                        val (targetDir, _) = DefaultRuleRepository.resolveTargetDir(rule, file.name)
                        if (rule.action == RouteAction.AUTO_MOVE || rule.action == RouteAction.AUTO_COPY) {
                            runCatching {
                                val dstDir = File(targetDir).apply { if (!exists()) mkdirs() }
                                val finalName = DefaultRuleRepository.formatFileName(
                                    rule.renamePattern, rule.appName, file.name
                                )
                                file.copyTo(File(dstDir, finalName), overwrite = true)
                                if (rule.action == RouteAction.AUTO_MOVE) file.delete()
                            }
                        } else {
                            postPromptBanner(rule.appName, file, targetDir)
                        }
                    }
            }
        }
    }

    private fun postPromptBanner(appName: String, file: File, suggestedDir: String) {
        val intent = Intent(applicationContext, ShareReceiverActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DownloadMonitorService.EXTRA_SOURCE_PATH, file.absolutePath)
            putExtra(DownloadMonitorService.EXTRA_APP_NAME, appName)
            putExtra(DownloadMonitorService.EXTRA_SUGGESTED_DIR, suggestedDir)
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            file.absolutePath.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, DownloadMonitorService.CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("检测到 $appName 新下载文档")
            .setContentText("${file.name} · 点击选择目标存储目录")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "选择目录归位", pi)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(file.absolutePath.hashCode(), notification)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "fileroute_mediastore_trigger"
        const val REQUIRE_WRITE_PERMISSION_CHECK = true
        const val WRITE_VERIFY_DELAY_MS = 1500L
        const val BATCH_MAX_DELAY_MS = 3000L

        fun scheduleNextTrigger(context: Context) {
            val builder = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                .setTriggerContentUpdateDelay(WRITE_VERIFY_DELAY_MS, TimeUnit.MILLISECONDS)
                .setTriggerContentMaxDelay(BATCH_MAX_DELAY_MS, TimeUnit.MILLISECONDS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.addContentUriTrigger(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true)
            }

            val request = OneTimeWorkRequestBuilder<DownloadTriggerWorker>()
                .setConstraints(builder.build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelTrigger(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

class BootTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DownloadTriggerWorker.scheduleNextTrigger(context)
        }
    }
}
