package app.fileroute.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import app.fileroute.data.ChannelMode
import app.fileroute.data.RouteAction
import app.fileroute.data.RuntimeStateStore
import app.fileroute.util.DownloadDiscoveryEngine
import app.fileroute.util.NotificationHelper
import app.fileroute.worker.DownloadTriggerWorker
import java.io.File
import java.util.concurrent.Executors

/**
 * 实时守护前台服务（Android 17 / API 37 适配 · 免 Root / 免 Shizuku）
 * 1. 同时挂载 Download 及所有子目录 FileObserver + MediaStore ContentObserver
 * 2. 即使不同手机 ROM 的微信/QQ路径不同，MediaStore 变动也会在 0.8 秒内触发扫描并弹出顶部横幅
 * 3. 达到 Android 15–17 dataSync 6 小时限额时自动无缝移交 WorkManager 触发器，永不崩溃
 */
class DownloadMonitorService : Service() {

    companion object {
        const val EXTRA_SOURCE_PATH = "extra_source_path"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_SUGGESTED_DIR = "extra_suggested_dir"
        const val POWER_MODE = "HIGH_PERFORMANCE"
        const val EVENT_DEBOUNCE_MS = 400L
    }

    private val observers = mutableListOf<FileObserver>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var mediaObserver: ContentObserver? = null

    private val debouncedScanTask = Runnable {
        ioExecutor.execute {
            DownloadDiscoveryEngine.scanAndProcessRecent(
                context = applicationContext,
                lookbackMinutes = 5,
                notifyUser = true
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeStateStore.ensureInit(applicationContext)
        NotificationHelper.ensureChannels(applicationContext)

        val keepAliveNotif = NotificationHelper.buildKeepAliveNotification(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, keepAliveNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1001, keepAliveNotif)
        }

        startDirectoryAndMediaWatchers()
        RuntimeStateStore.appendLog("前台实时守护已启动（已监听 ${observers.size} 个目录 + MediaStore）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleDebouncedScan()
        return START_STICKY
    }

    private fun scheduleDebouncedScan() {
        mainHandler.removeCallbacks(debouncedScanTask)
        mainHandler.postDelayed(debouncedScanTask, EVENT_DEBOUNCE_MS)
    }

    private fun startDirectoryAndMediaWatchers() {
        observers.forEach { it.stopWatching() }
        observers.clear()

        val watchDirs = linkedSetOf<File>()
        val extRoot = Environment.getExternalStorageDirectory()
        val downloadRoot = File(extRoot, "Download")
        if (downloadRoot.exists() && downloadRoot.isDirectory) {
            watchDirs.add(downloadRoot)
            downloadRoot.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach {
                watchDirs.add(it)
            }
        }
        watchDirs.add(File(extRoot, "Pictures/WeiXin"))
        watchDirs.add(File(extRoot, "Pictures/QQ"))
        watchDirs.add(File(extRoot, "Documents/WeiXin"))

        RuntimeStateStore.rules
            .filter { it.enabled && it.channel == ChannelMode.PUBLIC_OBSERVER && it.action != RouteAction.IGNORE }
            .forEach { rule ->
                rule.sourcePaths.forEach { watchDirs.add(File(it)) }
            }

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        for (dir in watchDirs) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
            val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        if (!path.isNullOrBlank() && !path.startsWith(".")) {
                            scheduleDebouncedScan()
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        if (!path.isNullOrBlank() && !path.startsWith(".")) {
                            scheduleDebouncedScan()
                        }
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }

        // 双保险：实时监听系统 MediaStore 变动，彻底解决不同手机路径差异问题
        val co = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleDebouncedScan()
            }
        }
        mediaObserver = co
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, co)
        }
        contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, co)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RuntimeStateStore.appendLog("达到系统前台服务时长上限，已自动切换为 MediaStore 触发式唤醒")
        DownloadTriggerWorker.scheduleNextTrigger(applicationContext)
        stopSelf(startId)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(debouncedScanTask)
        observers.forEach { it.stopWatching() }
        observers.clear()
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        ioExecutor.shutdownNow()
        RuntimeStateStore.appendLog("前台实时守护已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
