package app.fileroute.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fileroute.data.RuntimeStateStore
import app.fileroute.util.DownloadDiscoveryEngine
import app.fileroute.util.NotificationHelper
import java.util.concurrent.TimeUnit

/**
 * 触发式唤醒优化引擎（WorkManager + MediaStore ContentUriTrigger · Android 17 适配）：
 * 1. 平时完全休眠不常驻前台，空闲唤醒频率 0 次/分
 * 2. 唤醒后首先注册通知通道，防止系统静默丢弃顶部横幅
 * 3. 调用 DownloadDiscoveryEngine 同时扫描 MediaStore.Downloads 与所有下载目录，不受手机路径差异限制
 */
class DownloadTriggerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        RuntimeStateStore.ensureInit(applicationContext)
        NotificationHelper.ensureChannels(applicationContext)

        if (REQUIRE_WRITE_PERMISSION_CHECK && !DownloadDiscoveryEngine.hasAllFilesAccess()) {
            RuntimeStateStore.appendLog("触发式唤醒跳过：尚未授予「所有文件访问权限」")
            scheduleNextTrigger(applicationContext)
            return Result.success()
        }

        val triggeredCount = triggeredContentUris.size
        RuntimeStateStore.appendLog("MediaStore 触发唤醒（收到 $triggeredCount 个系统内容变更）")

        DownloadDiscoveryEngine.scanAndProcessRecent(
            context = applicationContext,
            lookbackMinutes = 10,
            notifyUser = true
        )

        // 每次唤醒处理完成后自动重新挂载下一次触发监听
        scheduleNextTrigger(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "fileroute_mediastore_trigger"
        const val REQUIRE_WRITE_PERMISSION_CHECK = true
        const val WRITE_VERIFY_DELAY_MS = 1500L
        const val BATCH_MAX_DELAY_MS = 3000L

        fun scheduleNextTrigger(context: Context) {
            NotificationHelper.ensureChannels(context)
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
            RuntimeStateStore.appendLog("已挂载系统 MediaStore 触发器（延迟 ${WRITE_VERIFY_DELAY_MS}ms）")
        }

        fun cancelTrigger(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            RuntimeStateStore.appendLog("已取消 MediaStore 触发器")
        }
    }
}

class BootTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RuntimeStateStore.ensureInit(context)
            if (RuntimeStateStore.guardArmed) {
                DownloadTriggerWorker.scheduleNextTrigger(context)
            }
        }
    }
}
