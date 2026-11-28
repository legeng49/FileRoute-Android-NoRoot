package app.fileroute.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fileroute.data.RuntimeStateStore
import org.json.JSONArray
import rikka.shizuku.Shizuku

data class ShizukuFileEntry(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

object ShizukuPrivateDirBridge {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ_CODE_SHIZUKU = 41007

    private val mainHandler = Handler(Looper.getMainLooper())
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
                statusText = if (granted) "Shizuku 已授权，正在绑定私有目录服务..." else "您拒绝了 Shizuku 授权"
            }
            if (granted) {
                appContext?.let { ctx ->
                    RuntimeStateStore.updateSettings(ctx, shizuku = true)
                    bindService(ctx)
                    RuntimeStateStore.appendLog("Shizuku 授权成功，已启用微信/QQ 聊天私有目录直采通道")
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
                    statusText = "Shizuku 已就绪 · 微信/QQ 聊天私有目录实时监听可用"
                }
                RuntimeStateStore.appendLog("Shizuku UserService 绑定成功（可实时读取 /Android/data/ 聊天文件）")
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
        val bound = alive && granted && remoteService != null
        val summary = when {
            !installed -> "未安装 Shizuku（当前自动运行零 Shizuku 公共目录模式）"
            !alive -> "已安装 Shizuku，但服务未启动（请先在 Shizuku 中开启无线调试）"
            !granted -> "Shizuku 运行中 · 点击「授权 Shizuku」即可开启微信聊天直采"
            bound -> "Shizuku 已就绪 · 微信/QQ 聊天私有目录实时监听可用"
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
            val args = userServiceArgs ?: Shizuku.UserServiceArgs(
                ComponentName(app.packageName, PrivateDirUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku_fs")
                .debuggable(false)
                .version(7)
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
                    .filter { it.contains("/Android/data/", ignoreCase = true) }
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
        }.getOrElse {
            emptyList()
        }
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
