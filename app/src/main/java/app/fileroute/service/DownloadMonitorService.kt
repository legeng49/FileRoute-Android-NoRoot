package app.fileroute.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.fileroute.data.AppRouteRule
import app.fileroute.data.ChannelMode
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.data.RouteAction
import app.fileroute.ui.ShareReceiverActivity
import java.io.File

/**
 * 公共存储目录实时检测前台服务（免 Root / 免 Shizuku）
 * 覆盖：微信 (Download/WeiXin)、新版 QQ (Download/QQ)、钉钉、飞书、浏览器等
 */
class DownloadMonitorService : Service() {

    companion object {
        const val CHANNEL_KEEPALIVE = "fileroute_keepalive"
        const val CHANNEL_ALERT = "fileroute_new_file_alert"
        const val EXTRA_SOURCE_PATH = "extra_source_path"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_SUGGESTED_DIR = "extra_suggested_dir"
        const val POWER_MODE = "HIGH_PERFORMANCE"
        const val EVENT_DEBOUNCE_MS = 180L
    }

    private val observers = mutableListOf<FileObserver>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(1001, buildKeepAliveNotification())
        startPublicDirectoryWatchers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startPublicDirectoryWatchers() {
        observers.forEach { it.stopWatching() }
        observers.clear()

        val activePublicRules = DefaultRuleRepository.initialRules.filter {
            it.enabled && it.channel == ChannelMode.PUBLIC_OBSERVER && it.action != RouteAction.IGNORE
        }

        for (rule in activePublicRules) {
            for (dirPath in rule.sourcePaths) {
                val dir = File(dirPath)
                if (!dir.exists()) continue

                val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            if (!path.isNullOrBlank() && !path.startsWith(".") && !path.endsWith(".tmp")) {
                                onNewDownloadDetected(rule, File(dir, path))
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(dirPath, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            if (!path.isNullOrBlank() && !path.startsWith(".") && !path.endsWith(".tmp")) {
                                onNewDownloadDetected(rule, File(dir, path))
                            }
                        }
                    }
                }
                observer.startWatching()
                observers.add(observer)
            }
        }
    }

    private fun onNewDownloadDetected(rule: AppRouteRule, file: File) {
        if (!file.exists() || !file.isFile) return
        val (targetDir, _) = DefaultRuleRepository.resolveTargetDir(rule, file.name)
        val finalName = DefaultRuleRepository.formatFileName(rule.renamePattern, rule.appName, file.name)

        when (rule.action) {
            RouteAction.AUTO_MOVE -> moveOrCopyFile(file, File(targetDir), finalName, copyOnly = false)
            RouteAction.AUTO_COPY -> moveOrCopyFile(file, File(targetDir), finalName, copyOnly = true)
            RouteAction.PROMPT -> postHeadsUpNotification(rule, file, targetDir)
            RouteAction.IGNORE -> Unit
        }
    }

    private fun postHeadsUpNotification(rule: AppRouteRule, file: File, suggestedDir: String) {
        val intent = Intent(this, ShareReceiverActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SOURCE_PATH, file.absolutePath)
            putExtra(EXTRA_APP_NAME, rule.appName)
            putExtra(EXTRA_SUGGESTED_DIR, suggestedDir)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            file.absolutePath.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("检测到 ${rule.appName} 新下载文件")
            .setContentText("${file.name} · 点击选择目标存储目录")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "选择目录归位", pendingIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(file.absolutePath.hashCode(), notification)
    }

    private fun moveOrCopyFile(src: File, dstDir: File, finalName: String, copyOnly: Boolean) {
        runCatching {
            if (!dstDir.exists()) dstDir.mkdirs()
            val dstFile = File(dstDir, finalName)
            src.copyTo(dstFile, overwrite = true)
            if (!copyOnly) src.delete()
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEPALIVE, "后台下载监测状态", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "新文件归位顶部提示", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "检测到微信、QQ、钉钉等新下载时在顶部弹出横幅提醒"
            }
        )
    }

    private fun buildKeepAliveNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_KEEPALIVE)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("归位 FileRoute 正在免 Root 守护下载目录")
            .setContentText("已启用公共目录监听 · QQ 文件可通过分享菜单一键归位")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        observers.forEach { it.stopWatching() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
