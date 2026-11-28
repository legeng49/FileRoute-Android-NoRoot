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
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.fileroute.service.DownloadMonitorService
import app.fileroute.ui.OpenSourceFolderActivity
import app.fileroute.ui.ShareReceiverActivity
import java.io.File

object NotificationHelper {
    const val CHANNEL_KEEPALIVE = "fileroute_keepalive"
    const val CHANNEL_ALERT = "fileroute_new_file_alert"

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        } else {
            true
        }
    }

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
        suggestedDir: String,
        autoPopupIfAllowed: Boolean = true
    ): Boolean {
        ensureChannels(context)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DownloadMonitorService.EXTRA_SOURCE_PATH, file.absolutePath)
            putExtra(DownloadMonitorService.EXTRA_APP_NAME, appName)
            putExtra(DownloadMonitorService.EXTRA_SUGGESTED_DIR, suggestedDir)
        }

        // 若已授予「悬浮窗/显示在其他应用上层」权限，Android 10-17 允许从后台直接弹出透明底部目录选择框，
        // 在微信聊天页点击下载后无需下拉通知栏即可直接修改转存地址！
        var poppedDirectly = false
        if (autoPopupIfAllowed && canDrawOverlays(context)) {
            poppedDirectly = runCatching {
                context.applicationContext.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        if (!hasNotificationPermission(context)) return poppedDirectly

        val requestCode = file.absolutePath.hashCode()
        val pi = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sourceDirPath = file.parent ?: "/storage/emulated/0/Download"
        val openFolderIntent = Intent(context, OpenSourceFolderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
            putExtra(DownloadMonitorService.EXTRA_SOURCE_PATH, sourceDirPath)
        }
        val openFolderPi = PendingIntent.getActivity(
            context,
            (file.absolutePath + "#open_folder").hashCode(),
            openFolderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("检测到 $appName 新下载文件")
            .setContentText("${file.name} · 点击修改转存目录")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${file.name}\n源目录：${sourceDirPath}\n建议归位 → $suggestedDir")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, false)
            .addAction(0, "修改转存地址", pi)
            .addAction(0, "打开源文件夹", openFolderPi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return poppedDirectly
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

    /**
     * 文件归位确认成功后的轻微双振反馈：
     * 使用 VibrationAttributes.USAGE_NOTIFICATION，保证熄屏状态下同样可轻微震动确认。
     */
    fun vibrateOnArchiveSuccess(context: Context) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return

            val effect = VibrationEffect.createWaveform(
                longArrayOf(0L, 20L, 40L, 28L),
                intArrayOf(0, 96, 0, 148),
                -1
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                    .build()
                vibrator.vibrate(effect, attrs)
            } else {
                vibrator.vibrate(effect)
            }
        }
    }
}
