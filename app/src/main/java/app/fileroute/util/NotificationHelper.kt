package app.fileroute.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.fileroute.service.DownloadMonitorService
import app.fileroute.ui.ShareReceiverActivity
import java.io.File

object NotificationHelper {
    const val CHANNEL_KEEPALIVE = "fileroute_keepalive"
    const val CHANNEL_ALERT = "fileroute_new_file_alert"

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_KEEPALIVE,
                "后台下载守护状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "常驻守护模式运行时在通知栏显示的轻量状态"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "新下载文件归位提醒（顶部横幅）",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "检测到微信、QQ、钉钉等新下载文件时弹出顶部横幅提醒"
                enableVibration(true)
            }
        )
    }

    fun buildKeepAliveNotification(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("归位 FileRoute 正在免 Root 守护下载")
            .setContentText("已开启全盘下载 + MediaStore 实时监测 · 适配 Android 17")
            .setOngoing(true)
            .build()
    }

    fun postNewFileHeadsUp(
        context: Context,
        appName: String,
        file: File,
        suggestedDir: String
    ): Boolean {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return false

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DownloadMonitorService.EXTRA_SOURCE_PATH, file.absolutePath)
            putExtra(DownloadMonitorService.EXTRA_APP_NAME, appName)
            putExtra(DownloadMonitorService.EXTRA_SUGGESTED_DIR, suggestedDir)
        }

        val requestCode = file.absolutePath.hashCode()
        val pi = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("检测到 $appName 新下载文件")
            .setContentText("${file.name} · 点击选择目标目录归位")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${file.name}\n建议归位 → $suggestedDir")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "选择目录归位", pi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.notify(requestCode, notification)
        return true
    }

    fun postTestBanner(context: Context): Boolean {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("归位 FileRoute · 通知通道正常")
            .setContentText("顶部横幅工作正常！下次在微信/QQ下载文件后将弹出类似提醒。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(99001, notification)
        return true
    }
}
