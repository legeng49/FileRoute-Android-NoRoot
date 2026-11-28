package app.fileroute.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fileroute.data.RuntimeStateStore
import app.fileroute.service.DownloadMonitorService
import app.fileroute.util.DownloadDiscoveryEngine
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class ShizukuFileEntry(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

data class ShizukuDirDiag(
    val dirPath: String,
    val fileCount: Int,
    val latestFileName: String,
    val latestModified: Long
)

object ShizukuPrivateDirBridge {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ_CODE_SHIZUKU = 41007

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var listenersRegistered = false

    @Volatile
    private var remoteService: IPrivateDirService? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    var isInstalled by mutableStateOf(false)
        private set
    var binderAlive by mutableStateOf(false)
        private set
    var permissionGranted by mutableStateOf(false)
        private set
    var serviceBound by mutableStateOf(false)
        private set
    var statusText by mutableStateOf("未检测 Shizuku")
        private set

    fun isReady(): Boolean = binderAlive && permissionGranted && remoteService != null

    fun defaultPrivateChatDirs(): List<String> {
        val extRoot = Environment.getExternalStorageDirectory().absolutePath
        return listOf(
            "$extRoot/Android/data/com.tencent.mm/MicroMsg",
            "$extRoot/Android/data/com.tencent.mm/MicroMsg/Download",
            "$extRoot/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
            "$extRoot/Android/data/com.tencent.tim/Tencent/TIMfile_recv",
            "$extRoot/Android/data/com.alibaba.android.rimet/files/Download",
            "$extRoot/Android/data/com.ss.android.lark/files/Download"
        )
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appContext?.let { ctx ->
            refreshState(ctx)
            if (RuntimeStateStore.shizukuEnabled && permissionGranted) {
                bindService(ctx)
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remoteService = null
        mainHandler.post {
            binderAlive = false
            serviceBound = false
            statusText = "Shizuku 服务已断开（自动回退公共目录模式）"
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE_SHIZUKU) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            mainHandler.post {
                permissionGranted = granted
                statusText = if (granted) "Shizuku 已授权，正在连接微信私有目录服务..." else "您拒绝了 Shizuku 授权"
            }
            if (granted) {
                appContext?.let { ctx ->
                    RuntimeStateStore.updateSettings(ctx, shizuku = true)
                    bindService(ctx)
                    RuntimeStateStore.appendLog("Shizuku 授权成功，正在绑定微信 MicroMsg/attachment 私有目录直采通道")
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                remoteService = IPrivateDirService.Stub.asInterface(service)
                mainHandler.post {
                    serviceBound = true
                    statusText = "Shizuku 已就绪 · 微信聊天实时监听运行中"
                }
                RuntimeStateStore.appendLog("Shizuku UserService 绑定成功，已建立微信聊天目录实时快照监听")
                appContext?.let { ctx ->
                    // 先对已有旧文件建立基线快照，避免启动瞬间对历史旧文件误弹窗；同时将最近下载列入待归位队列
                    bgExecutor.execute {
                        val baselineCount = DownloadDiscoveryEngine.seedShizukuBaseline(ctx)
                        RuntimeStateStore.appendLog("Shizuku 微信目录基线已建立（已索引 $baselineCount 个既有文件），此后任何新点击下载将秒级触发弹窗")
                        DownloadDiscoveryEngine.scanShizukuPrivateDirs(
                            context = ctx,
                            cutoffMillis = System.currentTimeMillis() - 7L * 24 * 60 * 60_000L,
                            notifyUser = false,
                            includeAlreadyHandledInPending = true,
                            skipStabilityDebounce = true
                        )
                    }
                    // 只要已授予所有文件访问权限，绑定 Shizuku 后自动拉起前台实时守护，确保微信聊天点击下载即可秒级捕获
                    if (DownloadDiscoveryEngine.hasAllFilesAccess()) {
                        RuntimeStateStore.updateSettings(ctx, armed = true)
                        runCatching {
                            val svcIntent = Intent(ctx, DownloadMonitorService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                ctx.startForegroundService(svcIntent)
                            } else {
                                ctx.startService(svcIntent)
                            }
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            mainHandler.post {
                serviceBound = false
                statusText = if (binderAlive) "Shizuku 通道已断开，点击重连" else "Shizuku 未运行"
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!listenersRegistered) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                listenersRegistered = true
            }
        }
        refreshState(app)
        if (RuntimeStateStore.shizukuEnabled && binderAlive && permissionGranted && remoteService == null) {
            bindService(app)
        }
    }

    fun refreshState(context: Context) {
        val app = context.applicationContext
        val installed = runCatching {
            app.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false)
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (alive && !runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        } else {
            false
        }
        // 关键修复：若用户已在 Shizuku 管理器中直接手动打开了本应用授权，自动同步开启 shizukuEnabled 并立即绑定！
        if (alive && granted && !RuntimeStateStore.shizukuEnabled) {
            RuntimeStateStore.updateSettings(app, shizuku = true)
        }
        val bound = alive && granted && remoteService != null
        val summary = when {
            !installed -> "未安装 Shizuku（当前自动运行零 Shizuku 公共目录模式）"
            !alive -> "已安装 Shizuku，但服务未启动（请先在 Shizuku 中开启无线调试）"
            !granted -> "Shizuku 运行中 · 点击「授权 Shizuku」即可开启微信聊天直采"
            bound -> "Shizuku 已就绪 · 微信聊天实时监听运行中"
            else -> "Shizuku 已授权 · 正在连接私有目录通道"
        }
        mainHandler.post {
            isInstalled = installed
            binderAlive = alive
            permissionGranted = granted
            serviceBound = bound
            statusText = summary
        }
        if (RuntimeStateStore.shizukuEnabled && alive && granted && remoteService == null) {
            bindService(app)
        }
    }

    fun requestPermission(context: Context): Boolean {
        init(context)
        if (!binderAlive) return false
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return false
        if (runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            permissionGranted = true
            RuntimeStateStore.updateSettings(context, shizuku = true)
            bindService(context)
            return true
        }
        return runCatching {
            Shizuku.requestPermission(REQ_CODE_SHIZUKU)
            true
        }.getOrDefault(false)
    }

    fun bindService(context: Context) {
        val app = context.applicationContext
        if (!binderAlive || !permissionGranted) return
        if (remoteService != null) return
        runCatching {
            val isDebuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val verCode = runCatching {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
            }.getOrDefault(9)
            val args = userServiceArgs ?: Shizuku.UserServiceArgs(
                ComponentName(app.packageName, PrivateDirUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku_fs")
                .debuggable(isDebuggable)
                .version(verCode)
                .also { userServiceArgs = it }
            Shizuku.bindUserService(args, serviceConnection)
        }.onFailure { err ->
            RuntimeStateStore.appendLog("Shizuku 绑定失败：${err.message ?: "请重启 Shizuku"}")
        }
    }

    fun unbindService() {
        val args = userServiceArgs ?: return
        runCatching {
            Shizuku.unbindUserService(args, serviceConnection, true)
        }
        remoteService = null
        mainHandler.post {
            serviceBound = false
        }
    }

    fun openShizukuApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        } else {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/zh-hans/download/")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(webIntent) }
        }
    }

    fun scanPrivateChatDirs(context: Context, sinceEpochMs: Long): List<ShizukuFileEntry> {
        if (!RuntimeStateStore.shizukuEnabled) return emptyList()
        val svc = remoteService ?: return emptyList()
        val targetDirs = linkedSetOf<String>()
        targetDirs.addAll(defaultPrivateChatDirs())
        RuntimeStateStore.rules
            .filter { it.enabled }
            .forEach { rule ->
                rule.sourcePaths
                    .filter { it.contains("/Android/data/", ignoreCase = true) || it.contains("MicroMsg", ignoreCase = true) }
                    .forEach { targetDirs.add(it.trimEnd('/')) }
            }

        return runCatching {
            val jsonStr = svc.listRecentFilesJson(targetDirs.toTypedArray(), sinceEpochMs)
            val arr = JSONArray(jsonStr)
            val list = ArrayList<ShizukuFileEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ShizukuFileEntry(
                        path = obj.optString("path"),
                        name = obj.optString("name"),
                        size = obj.optLong("size", 0L),
                        lastModified = obj.optLong("lastModified", 0L)
                    )
                )
            }
            list
        }.onFailure { err ->
            RuntimeStateStore.appendLog("Shizuku 扫描异常：${err.message ?: "服务连接中断"}")
        }.getOrElse {
            emptyList()
        }
    }

    fun diagnoseWeChatDirs(): List<ShizukuDirDiag> {
        val svc = remoteService ?: return emptyList()
        return runCatching {
            val jsonStr = svc.diagnoseWeChatDirsJson(defaultPrivateChatDirs().toTypedArray())
            val arr = JSONArray(jsonStr)
            val list = ArrayList<ShizukuDirDiag>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ShizukuDirDiag(
                        dirPath = obj.optString("dirPath"),
                        fileCount = obj.optInt("fileCount", 0),
                        latestFileName = obj.optString("latestFileName", ""),
                        latestModified = obj.optLong("latestModified", 0L)
                    )
                )
            }
            list.sortedByDescending { it.latestModified }
        }.getOrElse { emptyList() }
    }

    fun openReadFd(sourcePath: String): ParcelFileDescriptor? {
        val svc = remoteService ?: return null
        return runCatching { svc.openReadFd(sourcePath) }.getOrNull()
    }

    fun deleteFile(filePath: String): Boolean {
        val svc = remoteService ?: return false
        return runCatching { svc.deleteFile(filePath) }.getOrDefault(false)
    }

    fun transferFile(sourcePath: String, targetPath: String, copyOnly: Boolean): Boolean {
        val svc = remoteService ?: return false
        return runCatching {
            svc.transferFile(sourcePath, targetPath, copyOnly)
        }.getOrDefault(false)
    }

    fun computeSha256(sourcePath: String): String? {
        val svc = remoteService ?: return null
        return runCatching {
            svc.computeFileSha256(sourcePath)
        }.getOrNull()
    }
}
