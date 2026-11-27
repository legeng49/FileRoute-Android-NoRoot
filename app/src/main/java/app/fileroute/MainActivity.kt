package app.fileroute

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.fileroute.data.AppRouteRule
import app.fileroute.data.ChannelMode
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.data.DetectedDownloadItem
import app.fileroute.data.DirectoryDiagItem
import app.fileroute.data.PassiveTriggerType
import app.fileroute.data.RouteAction
import app.fileroute.data.RuntimeStateStore
import app.fileroute.service.DownloadMonitorService
import app.fileroute.util.DownloadDiscoveryEngine
import app.fileroute.util.NotificationHelper
import app.fileroute.worker.DownloadTriggerWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RuntimeStateStore.ensureInit(applicationContext)
        NotificationHelper.ensureChannels(applicationContext)
        setContent {
            MaterialTheme {
                FileRouteMainApp()
            }
        }
    }
}

private data class NavTabItem(val index: Int, val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileRouteMainApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var hasAllFilesAccess by remember { mutableStateOf(DownloadDiscoveryEngine.hasAllFilesAccess()) }
    var hasNotifPerm by remember { mutableStateOf(NotificationHelper.hasNotificationPermission(context)) }
    var ignoringBattery by remember { mutableStateOf(isBatteryOptIgnored(context)) }
    var activePickerItem by remember { mutableStateOf<DetectedDownloadItem?>(null) }

    // 每次从系统设置返回 App 时自动刷新权限状态，并顺带扫描最近下载
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = DownloadDiscoveryEngine.hasAllFilesAccess()
                hasNotifPerm = NotificationHelper.hasNotificationPermission(context)
                ignoringBattery = isBatteryOptIgnored(context)
                if (hasAllFilesAccess) {
                    DownloadDiscoveryEngine.scanAndProcessRecent(
                        context = context,
                        lookbackMinutes = 60,
                        notifyUser = false,
                        includeAlreadyHandledInPending = false
                    )
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted || NotificationHelper.hasNotificationPermission(context)
        Toast.makeText(
            context,
            if (hasNotifPerm) "已获得顶部横幅通知权限" else "未开启通知权限，将无法弹出顶部横幅",
            Toast.LENGTH_SHORT
        ).show()
    }

    val tabs = remember {
        listOf(
            NavTabItem(0, "守护", Icons.Default.Home),
            NavTabItem(1, "规则", Icons.Default.List),
            NavTabItem(2, "记录", Icons.Default.Refresh),
            NavTabItem(3, "诊断", Icons.Default.Settings)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLargeScreen = maxWidth >= 600.dp

        val mainContent: @Composable (Modifier) -> Unit = { mod ->
            Box(modifier = mod) {
                when (selectedTab) {
                    0 -> MonitorHomeTab(
                        hasAllFilesAccess = hasAllFilesAccess,
                        hasNotifPerm = hasNotifPerm,
                        ignoringBattery = ignoringBattery,
                        onRequestNotif = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenPicker = { activePickerItem = it }
                    )
                    1 -> RulesManageTab()
                    2 -> HistoryLedgerTab()
                    3 -> DiagnosticsTab(
                        hasAllFilesAccess = hasAllFilesAccess,
                        hasNotifPerm = hasNotifPerm
                    )
                }
            }
        }

        if (isLargeScreen) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab.index,
                            onClick = { selectedTab = tab.index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("归位 FileRoute · Android 17") })
                    }
                ) { padding ->
                    mainContent(Modifier.fillMaxSize().padding(padding))
                }
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("归位 FileRoute · Android 17") }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab.index,
                                onClick = { selectedTab = tab.index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                mainContent(Modifier.fillMaxSize().padding(padding))
            }
        }
    }

    // 应用内底部目录选择器（直接点待归位文件即可真实移动/复制）
    activePickerItem?.let { item ->
        var selectedDir by remember(item.id) { mutableStateOf(item.suggestedTargetDir) }
        var copyOnly by remember(item.id) { mutableStateOf(false) }

        ModalBottomSheet(onDismissRequest = { activePickerItem = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("选择文件归位目录", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${item.appName} · ${item.fileName} (${DefaultRuleRepository.formatSize(item.fileSizeBytes)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = selectedDir,
                    onValueChange = { selectedDir = it },
                    label = { Text("目标目录路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 210.dp)
                ) {
                    items(RuntimeStateStore.quickFolders) { folder ->
                        FilterChip(
                            selected = selectedDir == folder.path,
                            onClick = { selectedDir = folder.path },
                            label = { Text(folder.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("保留原文件（仅复制）", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = copyOnly, onCheckedChange = { copyOnly = it })
                }
                Button(
                    onClick = {
                        val ok = DownloadDiscoveryEngine.relocateFile(
                            context = context,
                            sourcePath = item.sourcePath,
                            sharedUri = null,
                            appName = item.appName,
                            fileName = item.fileName,
                            targetDirPath = selectedDir,
                            copyOnly = copyOnly
                        )
                        Toast.makeText(
                            context,
                            if (ok) "已归位至 $selectedDir" else "移动失败，请检查所有文件访问权限",
                            Toast.LENGTH_SHORT
                        ).show()
                        activePickerItem = null
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (copyOnly) "确认复制到此目录" else "确认移动归位")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MonitorHomeTab(
    hasAllFilesAccess: Boolean,
    hasNotifPerm: Boolean,
    ignoringBattery: Boolean,
    onRequestNotif: () -> Unit,
    onOpenPicker: (DetectedDownloadItem) -> Unit
) {
    val context = LocalContext.current
    val pending = RuntimeStateStore.pendingItems
    val armed = RuntimeStateStore.guardArmed
    val useEventMode = RuntimeStateStore.useEventTriggeredMode

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 1. 权限自检卡片
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("权限与运行环境自检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    PermissionActionRow(
                        title = "1. 所有文件访问权限",
                        subtitle = if (hasAllFilesAccess) "已授权 · 可读写微信/QQ/下载目录" else "必须开启，否则无法检测和移动文件",
                        granted = hasAllFilesAccess,
                        actionText = "去授权",
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }.onFailure {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                    PermissionActionRow(
                        title = "2. 顶部横幅通知权限",
                        subtitle = if (hasNotifPerm) "已授权 · 下载完成后自动弹出提醒" else "未授权 · 检测到了文件也无法弹窗",
                        granted = hasNotifPerm,
                        actionText = "开启通知",
                        onClick = onRequestNotif
                    )
                    HorizontalDivider()
                    PermissionActionRow(
                        title = "3. 电池不受限（防后台被杀）",
                        subtitle = if (ignoringBattery) "已加入电池白名单" else "建议设为无限制，避免国产 ROM 杀后台",
                        granted = ignoringBattery,
                        actionText = "去设置",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }.onFailure {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val ok = NotificationHelper.postTestBanner(context)
                                Toast.makeText(
                                    context,
                                    if (ok) "已发送测试通知，请看手机顶部！" else "请先开启通知权限",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("发测试通知")
                        }
                        OutlinedButton(
                            onClick = {
                                if (!hasAllFilesAccess) {
                                    Toast.makeText(context, "请先授予「所有文件访问权限」", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                val count = DownloadDiscoveryEngine.scanAndProcessRecent(
                                    context = context,
                                    lookbackMinutes = 180,
                                    notifyUser = true,
                                    includeAlreadyHandledInPending = true
                                )
                                Toast.makeText(
                                    context,
                                    "扫描完成：待归位队列现有 ${RuntimeStateStore.pendingItems.size} 个文件（新增 $count）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即扫描最近下载")
                        }
                    }
                }
            }
        }

        // 2. 守护模式控制卡
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (armed) "守护运行中 · 全盘下载监测已就绪" else "点击下方开启下载守护",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (useEventMode)
                            "触发式唤醒：平时完全休眠不挂后台，由系统 MediaStore 在下载完成后拉起（极省电，响应约 1–3 秒）。"
                        else
                            "前台实时守护：同时监听 Download 全部子目录与 MediaStore 变动，约 0.5 秒秒弹顶部横幅（首次测试强烈推荐）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !useEventMode,
                            onClick = {
                                RuntimeStateStore.updateSettings(context, eventTriggered = false)
                            },
                            label = { Text("实时守护(推荐首测)") }
                        )
                        FilterChip(
                            selected = useEventMode,
                            onClick = {
                                RuntimeStateStore.updateSettings(context, eventTriggered = true)
                            },
                            label = { Text("触发式省电唤醒") }
                        )
                    }
                    Button(
                        onClick = {
                            if (!hasAllFilesAccess) {
                                Toast.makeText(context, "请先授予「所有文件访问权限」再开启守护", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (!hasNotifPerm) onRequestNotif()

                            val svcIntent = Intent(context, DownloadMonitorService::class.java)
                            if (armed) {
                                DownloadTriggerWorker.cancelTrigger(context)
                                context.stopService(svcIntent)
                                RuntimeStateStore.updateSettings(context, armed = false)
                                Toast.makeText(context, "已停止守护", Toast.LENGTH_SHORT).show()
                            } else {
                                if (useEventMode) {
                                    context.stopService(svcIntent)
                                    DownloadTriggerWorker.scheduleNextTrigger(context)
                                } else {
                                    runCatching {
                                        context.startForegroundService(svcIntent)
                                    }.onFailure {
                                        DownloadTriggerWorker.scheduleNextTrigger(context)
                                        Toast.makeText(context, "前台服务受限，已自动回退为触发式唤醒", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                RuntimeStateStore.updateSettings(context, armed = true)
                                Toast.makeText(context, "免 Root 守护已开启！现在去微信/QQ下载文件即可测试", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(if (armed) "停止下载守护" else "开启免 Root 下载守护")
                    }
                }
            }
        }

        // 3. 待归位队列
        item {
            Text(
                text = "待归位文件队列（${pending.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (pending.isEmpty()) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("暂无待归位文件", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "点击上方「立即扫描最近下载」可立刻拉取你手机最近 3 小时在微信/QQ/浏览器下载的文件；或去微信点击下载任意文件测试自动弹窗。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(pending, key = { it.sourcePath }) { item ->
                OutlinedCard(
                    onClick = { onOpenPicker(item) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    RuntimeStateStore.recordIgnored(context, item)
                                    Toast.makeText(context, "已忽略并记入历史记录", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "忽略此文件")
                            }
                        }
                        Text(
                            "${item.appName} · ${DefaultRuleRepository.formatSize(item.fileSizeBytes)} · ${item.detectedAtText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "建议归位 → ${item.suggestedTargetDir}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "原路径：${item.sourcePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesManageTab() {
    val context = LocalContext.current
    val rules = RuntimeStateStore.rules
    var editingRule by remember { mutableStateOf<AppRouteRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全盘下载自动发现（防路径差异）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("自动通过 MediaStore 与 /Download 递归捕获，不限特定微信/QQ路径", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = RuntimeStateStore.universalScanEnabled,
                            onCheckedChange = { RuntimeStateStore.updateSettings(context, universal = it) }
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("智能扩展名自动归类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("图片→/Pictures，文档→/Documents，APK→/Download/安装包", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = RuntimeStateStore.smartClassifyEnabled,
                            onCheckedChange = { RuntimeStateStore.updateSettings(context, smartClassify = it) }
                        )
                    }
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加自定义监控目录规则")
                    }
                }
            }
        }

        items(rules, key = { it.id }) { rule ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (rule.channel == ChannelMode.PUBLIC_OBSERVER) "公共目录自动检测" else "QQ/系统分享菜单归位",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { editingRule = rule }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑规则")
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = {
                                    RuntimeStateStore.updateRule(context, rule.copy(enabled = it))
                                }
                            )
                        }
                    }
                    Text(
                        "来源：${rule.sourcePaths.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "目标：${rule.defaultTargetDir} · 动作：${actionLabel(rule.action)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    editingRule?.let { target ->
        var srcText by remember(target.id) { mutableStateOf(target.sourcePaths.joinToString(",")) }
        var dstText by remember(target.id) { mutableStateOf(target.defaultTargetDir) }
        var selectedAction by remember(target.id) { mutableStateOf(target.action) }

        AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text("编辑 ${target.appName} 规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = srcText,
                        onValueChange = { srcText = it },
                        label = { Text("监控来源目录（逗号分隔）") },
                        singleLine = false
                    )
                    OutlinedTextField(
                        value = dstText,
                        onValueChange = { dstText = it },
                        label = { Text("默认归位目标目录") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(RouteAction.PROMPT, RouteAction.AUTO_MOVE, RouteAction.AUTO_COPY).forEach { act ->
                            FilterChip(
                                selected = selectedAction == act,
                                onClick = { selectedAction = act },
                                label = { Text(actionLabel(act)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val paths = srcText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    RuntimeStateStore.updateRule(
                        context,
                        target.copy(
                            sourcePaths = if (paths.isNotEmpty()) paths else target.sourcePaths,
                            defaultTargetDir = DefaultRuleRepository.ensureTrailingSlash(dstText),
                            action = selectedAction
                        )
                    )
                    editingRule = null
                    Toast.makeText(context, "规则已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingRule = null }) { Text("取消") }
            }
        )
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var srcDir by remember { mutableStateOf("/storage/emulated/0/Download/") }
        var dstDir by remember { mutableStateOf("/storage/emulated/0/Documents/自定义归档/") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增自定义监控目录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("应用或目录名称") }, singleLine = true)
                    OutlinedTextField(value = srcDir, onValueChange = { srcDir = it }, label = { Text("手机实际下载目录路径") }, singleLine = true)
                    OutlinedTextField(value = dstDir, onValueChange = { dstDir = it }, label = { Text("目标归位目录") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleanName = name.trim().ifEmpty { "自定义应用" }
                    RuntimeStateStore.updateRule(
                        context,
                        AppRouteRule(
                            id = "custom_${System.currentTimeMillis()}",
                            appName = cleanName,
                            packageName = "custom.dir",
                            enabled = true,
                            passiveWakeEnabled = true,
                            passiveTrigger = PassiveTriggerType.MEDIASTORE_TRIGGER,
                            channel = ChannelMode.PUBLIC_OBSERVER,
                            action = RouteAction.PROMPT,
                            sourcePaths = listOf(DefaultRuleRepository.ensureTrailingSlash(srcDir)),
                            defaultTargetDir = DefaultRuleRepository.ensureTrailingSlash(dstDir),
                            renamePattern = "{name}",
                            subRules = emptyList()
                        )
                    )
                    showAddDialog = false
                    Toast.makeText(context, "已添加「$cleanName」监控规则", Toast.LENGTH_SHORT).show()
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HistoryLedgerTab() {
    val context = LocalContext.current
    val history = RuntimeStateStore.historyItems
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val completedItems = history.filter { it.status == "completed" && !it.restored }
    val totalMovedBytes = completedItems.sumOf { it.fileSizeBytes }

    val filtered = remember(history.toList(), searchQuery, statusFilter) {
        val q = searchQuery.trim().lowercase()
        history.filter { item ->
            val st = if (item.restored) "restored" else item.status
            val matchStatus = statusFilter == "all" || st == statusFilter
            val matchQuery = q.isEmpty() ||
                item.fileName.lowercase().contains(q) ||
                item.appName.lowercase().contains(q) ||
                item.fromPath.lowercase().contains(q) ||
                item.toPath.lowercase().contains(q)
            matchStatus && matchQuery
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 历史统计概览卡片
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("累计已归位", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${completedItems.size} 次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("累计整理体积", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(DefaultRuleRepository.formatSize(totalMovedBytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("全部留存记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${history.size} 条", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val csv = RuntimeStateStore.exportHistoryCsv(context)
                                Toast.makeText(
                                    context,
                                    if (csv != null) "已导出至 Documents/${csv.name}" else "导出失败，请先授予所有文件访问权限",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            enabled = history.isNotEmpty()
                        ) {
                            Text("导出 CSV 备份")
                        }
                        if (history.isNotEmpty()) {
                            TextButton(onClick = { showClearConfirm = true }) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清空历史")
                            }
                        }
                    }
                }
            }
        }

        // 搜索框与状态筛选
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索文件名、来源应用或目录路径") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "all" to "全部(${history.size})",
                        "completed" to "已归位",
                        "restored" to "已撤销",
                        "ignored" to "已忽略"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = statusFilter == key,
                            onClick = { statusFilter = key },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (history.isEmpty())
                            "尚无归位历史。完成文件移动、复制、自动整理或忽略后，全部记录都会自动持久化保存在本机，并支持搜索、导出与一键撤销还原。"
                        else
                            "没有匹配当前搜索或筛选条件的历史记录。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { rec ->
                val effectiveStatus = if (rec.restored) "restored" else rec.status
                val statusLabel = when (effectiveStatus) {
                    "completed" -> "已归位"
                    "restored" -> "已撤销还原"
                    "ignored" -> "已忽略"
                    else -> "已归位"
                }
                val modeLabel = when (rec.mode) {
                    "auto_rule" -> if (rec.copyOnly) "规则自动复制" else "规则自动移动"
                    "share_saved" -> "分享菜单另存"
                    "ignored" -> "保留原目录"
                    else -> if (rec.copyOnly) "手动复制归位" else "手动移动归位"
                }

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                rec.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (effectiveStatus == "completed")
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${rec.appName} · $modeLabel · ${DefaultRuleRepository.formatSize(rec.fileSizeBytes)} · ${rec.timeText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "原：${rec.fromPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (effectiveStatus != "ignored") {
                            Text(
                                "新：${rec.toPath}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (effectiveStatus == "completed") {
                                TextButton(onClick = {
                                    val ok = DownloadDiscoveryEngine.undoTransfer(context, rec)
                                    Toast.makeText(
                                        context,
                                        if (ok) "已撤销并将文件还原回原目录" else "目标文件不存在或已被移走，无法撤销",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) {
                                    Text("撤销还原")
                                }
                            }
                            TextButton(onClick = {
                                RuntimeStateStore.deleteHistoryItem(context, rec.id)
                                Toast.makeText(context, "已删除此条历史记录", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("删除记录")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空全部历史记录？") },
            text = { Text("仅清空 App 内的历史台账，不会删除已归位到手机目录中的真实文件。") },
            confirmButton = {
                Button(onClick = {
                    RuntimeStateStore.clearHistory(context)
                    showClearConfirm = false
                    Toast.makeText(context, "已清空全部历史记录", Toast.LENGTH_SHORT).show()
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DiagnosticsTab(hasAllFilesAccess: Boolean, hasNotifPerm: Boolean) {
    val context = LocalContext.current
    var diagPair by remember { mutableStateOf(DownloadDiscoveryEngine.diagnosePhonePaths(context)) }
    val logs = RuntimeStateStore.runtimeLogs

    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            val result = if (!text.isNullOrBlank()) RuntimeStateStore.importConfigJson(context, text) else null
            Toast.makeText(
                context,
                if (result != null)
                    "导入成功：${result.first} 条规则 · ${result.second} 个常用目录"
                else
                    "导入失败，请选择有效的归位 JSON 配置文件",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 跨设备 JSON 配置导出与导入卡片
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("跨设备规则与常用目录同步（JSON）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "当前共 ${RuntimeStateStore.rules.size} 条监控规则 · ${RuntimeStateStore.quickFolders.size} 个常用目录。支持导出为 JSON 文件或在换机时直接导入网页端/旧手机导出的配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val file = RuntimeStateStore.exportConfigJson(context)
                                Toast.makeText(
                                    context,
                                    if (file != null) "已导出至 Documents/${file.name}" else "导出失败，请先授予所有文件访问权限",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        ) {
                            Text("导出配置 JSON")
                        }
                        OutlinedButton(
                            onClick = {
                                importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }
                        ) {
                            Text("导入配置 JSON")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("本机实际路径诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { diagPair = DownloadDiscoveryEngine.diagnosePhonePaths(context) }) {
                            Text("刷新检测")
                        }
                    }
                    Text(
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · 存储权限=${if (hasAllFilesAccess) "正常" else "未授权"} · 通知=${if (hasNotifPerm) "正常" else "未授权"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(diagPair.first) { d: DirectoryDiagItem ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${d.label} · ${if (d.exists) (if (d.readable) "存在且可读(${d.fileCount}个文件)" else "存在但无权读取") else "此路径不存在"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(d.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!d.latestFileName.isNullOrBlank()) {
                        Text("最新文件：${d.latestFileName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("系统 MediaStore.Downloads 最新记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (diagPair.second.isEmpty()) {
                        Text("暂未读取到 MediaStore 下载记录（可能尚未下载文件或未授予存储权限）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        diagPair.second.forEach { row ->
                            Text(row, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Text("实时运行与唤醒日志（${logs.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        items(logs) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Icon(Icons.Default.Check, contentDescription = "已授权", tint = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onClick) { Text(actionText) }
        }
    }
}

private fun actionLabel(action: RouteAction): String = when (action) {
    RouteAction.PROMPT -> "通知询问"
    RouteAction.AUTO_MOVE -> "自动移动"
    RouteAction.AUTO_COPY -> "自动复制"
    RouteAction.IGNORE -> "已暂停"
}

private fun isBatteryOptIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
