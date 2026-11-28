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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.fileroute.data.DiscoveredDeviceDir
import app.fileroute.data.PassiveTriggerType
import app.fileroute.data.RecentMediaSample
import app.fileroute.data.RouteAction
import app.fileroute.data.RuntimeStateStore
import app.fileroute.service.DownloadMonitorService
import app.fileroute.util.DownloadDiscoveryEngine
import app.fileroute.util.NotificationHelper
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import app.fileroute.worker.DownloadTriggerWorker

private val FileRouteColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF1D4ED8),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0FDFA),
    onSecondaryContainer = Color(0xFF0F766E),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFE2E8F0)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RuntimeStateStore.ensureInit(applicationContext)
        NotificationHelper.ensureChannels(applicationContext)
        setContent {
            MaterialTheme(colorScheme = FileRouteColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FileRouteMainApp()
                }
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

    // 每次从系统设置返回 App 时自动刷新权限状态，并静默同步最近下载
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
            NavTabItem(0, "监听", Icons.Default.Home),
            NavTabItem(1, "规则", Icons.Default.List),
            NavTabItem(2, "记录", Icons.Default.Refresh),
            NavTabItem(3, "诊断", Icons.Default.Search),
            NavTabItem(4, "设置", Icons.Default.Settings)
        )
    }

    val tabTitle = when (selectedTab) {
        0 -> "首页监听"
        1 -> "归位规则"
        2 -> "历史记录"
        3 -> "路径诊断"
        else -> "应用设置"
    }

    var openHealthTrigger by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLargeScreen = maxWidth >= 600.dp

        val mainContent: @Composable (Modifier) -> Unit = { mod ->
            Box(modifier = mod.background(MaterialTheme.colorScheme.background)) {
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
                        onOpenPicker = { activePickerItem = it },
                        onOpenRules = { selectedTab = 1 }
                    )
                    1 -> RulesManageTab(
                        onOpenRuleHealth = {
                            openHealthTrigger += 1
                            selectedTab = 4
                        }
                    )
                    2 -> HistoryLedgerTab()
                    3 -> DiagnosticsTab(
                        hasAllFilesAccess = hasAllFilesAccess,
                        hasNotifPerm = hasNotifPerm
                    )
                    4 -> SettingsManageTab(
                        onOpenRules = { selectedTab = 1 },
                        onOpenDiagnostics = { selectedTab = 3 },
                        autoExpandHealthTrigger = openHealthTrigger
                    )
                }
            }
        }

        if (isLargeScreen) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = Color.White) {
                    Spacer(Modifier.height(12.dp))
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab.index,
                            onClick = { selectedTab = tab.index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Color(0xFF2563EB),
                                selectedTextColor = Color(0xFF1D4ED8),
                                indicatorColor = Color(0xFFEFF6FF)
                            )
                        )
                    }
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                            title = {
                                Text(
                                    "归位 FileRoute · $tabTitle",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                ) { padding ->
                    mainContent(Modifier.fillMaxSize().padding(padding))
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                        title = {
                            Text(
                                "归位 · $tabTitle",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab.index,
                                onClick = { selectedTab = tab.index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF2563EB),
                                    selectedTextColor = Color(0xFF1D4ED8),
                                    indicatorColor = Color(0xFFEFF6FF)
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                mainContent(Modifier.fillMaxSize().padding(padding))
            }
        }
    }

    // 应用内底部目录选择器（直接点待归位文件即可真实移动/复制，支持实时同名冲突检测）
    activePickerItem?.let { item ->
        var selectedDir by remember(item.id) { mutableStateOf(item.suggestedTargetDir) }
        var copyOnly by remember(item.id) { mutableStateOf(false) }
        var conflictStrategy by remember(item.id) { mutableStateOf("rename_timestamp") }
        val existingConflict = remember(selectedDir, item.fileName) {
            DownloadDiscoveryEngine.findExistingConflict(selectedDir, item.fileName)
        }
        val isExactDuplicate = remember(
            selectedDir,
            item.fileName,
            RuntimeStateStore.integrityCheckEnabled
        ) {
            if (existingConflict != null && RuntimeStateStore.integrityCheckEnabled) {
                DownloadDiscoveryEngine.isIdenticalFile(File(item.sourcePath), existingConflict)
            } else {
                false
            }
        }
        val timestampPreviewName = remember(selectedDir, item.fileName) {
            DownloadDiscoveryEngine.buildTimestampedFileName(item.fileName)
        }

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
                if (isExactDuplicate && existingConflict != null) {
                    Surface(
                        color = Color(0xFFF0FDFA),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF99F6E4))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "完整性校验一致（大小与 SHA-256 相同）",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF0F766E)
                            )
                            Text(
                                "目标目录已存在完全相同的文件，确认后将自动跳过归位，避免无效写入与空间浪费。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (existingConflict != null) {
                    Surface(
                        color = Color(0xFFFFFBEB),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFFDE68A))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "目标目录已存在同名文件（${DefaultRuleRepository.formatSize(existingConflict.length())}）",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF92400E)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "rename_timestamp" to "重命名(时间戳)",
                                    "overwrite" to "覆盖",
                                    "skip" to "跳过"
                                ).forEach { (key, lbl) ->
                                    FilterChip(
                                        selected = conflictStrategy == key,
                                        onClick = { conflictStrategy = key },
                                        label = { Text(lbl) }
                                    )
                                }
                            }
                            Text(
                                when (conflictStrategy) {
                                    "rename_timestamp" -> "将保存为：$timestampPreviewName"
                                    "overwrite" -> "将覆盖目标目录中的已有同名文件"
                                    else -> "将跳过此文件，原目录与目标文件均保持不变"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                    Text("文件完整性校验（一致自动跳过）", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = RuntimeStateStore.integrityCheckEnabled,
                        onCheckedChange = {
                            RuntimeStateStore.updateSettings(context = context, integrityCheck = it)
                        }
                    )
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
                        val strategy = if (existingConflict != null) conflictStrategy else "rename_timestamp"
                        val ok = DownloadDiscoveryEngine.relocateFile(
                            context = context,
                            sourcePath = item.sourcePath,
                            sharedUri = null,
                            appName = item.appName,
                            fileName = item.fileName,
                            targetDirPath = selectedDir,
                            copyOnly = copyOnly,
                            conflictStrategy = strategy
                        )
                        Toast.makeText(
                            context,
                            when {
                                !ok -> "移动失败，请检查所有文件访问权限"
                                existingConflict != null && strategy == "skip" -> "已跳过同名文件"
                                existingConflict != null && strategy == "overwrite" -> "已覆盖同名文件并归位至 $selectedDir"
                                existingConflict != null -> "已添加时间戳归位至 $selectedDir"
                                else -> "已归位至 $selectedDir"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        activePickerItem = null
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        when {
                            existingConflict != null && conflictStrategy == "skip" -> "确认跳过此文件"
                            existingConflict != null && conflictStrategy == "overwrite" -> "覆盖原文件并归位"
                            existingConflict != null -> "添加时间戳并归位"
                            copyOnly -> "确认复制到此目录"
                            else -> "确认移动归位"
                        }
                    )
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
    onOpenPicker: (DetectedDownloadItem) -> Unit,
    onOpenRules: () -> Unit
) {
    val context = LocalContext.current
    val pending = RuntimeStateStore.pendingItems
    val history = RuntimeStateStore.historyItems
    val rules = RuntimeStateStore.rules
    val armed = RuntimeStateStore.guardArmed
    val powerMode = RuntimeStateStore.powerModeName
    val allPermsReady = hasAllFilesAccess && hasNotifPerm && ignoringBattery
    var showPermDetails by remember { mutableStateOf(!allPermsReady) }

    val completedHistory = history.filter { it.status == "completed" }
    val totalMovedBytes = completedHistory.sumOf { it.fileSizeBytes }
    val activeRuleCount = rules.count { it.enabled && it.action != RouteAction.IGNORE }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 1. 顶部 4 栏运行概况 KPI 条（与网页端对齐）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KpiMetricCell("待选择目录", "${pending.size}", "个新文件", Modifier.weight(1f))
                        KpiMetricCell("已归位文件", "${completedHistory.size}", "次整理", Modifier.weight(1f))
                    }
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KpiMetricCell("累计整理体积", DefaultRuleRepository.formatSize(totalMovedBytes), "已归档", Modifier.weight(1f))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("零 Shizuku 规则", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("$activeRuleCount", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = onOpenRules,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("管理规则", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 权限与运行环境自检（全部就绪后自动收起为单行，不含任何测试按钮）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (allPermsReady) "运行权限已全部就绪" else "权限与运行环境自检",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (allPermsReady) "所有文件访问、横幅通知与电池白名单均已开启" else "请先开启下方必要系统权限以自动监测下载目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showPermDetails = !showPermDetails }) {
                            Text(if (showPermDetails) "收起" else "检查")
                        }
                    }

                    if (showPermDetails || !allPermsReady) {
                        HorizontalDivider(color = Color(0xFFF1F5F9))
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
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        PermissionActionRow(
                            title = "2. 顶部横幅通知权限",
                            subtitle = if (hasNotifPerm) "已授权 · 下载完成后自动弹出提醒" else "未授权 · 检测到了文件也无法弹窗",
                            granted = hasNotifPerm,
                            actionText = "开启通知",
                            onClick = onRequestNotif
                        )
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        PermissionActionRow(
                            title = "3. 电池不受限（防后台被杀）",
                            subtitle = if (ignoringBattery) "已加入电池白名单" else "建议设为无限制，避免系统杀后台",
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
                    }
                }
            }
        }

        // 3. 归位效率分析卡片（原生 Canvas 环形饼图，与网页端 D3 饼图视觉及维度一致）
        item {
            EfficiencyAnalyticsNativeCard(historyItems = completedHistory)
        }

        // 4. 能耗仪表盘与守护总控（与网页端三档模式保持一致：触发式唤醒 / 省电 / 高性能）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("能耗仪表盘与下载守护", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (armed) "守护运行中 · 全盘下载监测已就绪" else "尚未开启后台监听，点击下方启动守护",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (armed) Color(0xFF0F766E) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple("event_triggered", "触发式唤醒", true),
                            Triple("powersave", "省电模式", false),
                            Triple("performance", "高性能秒弹", false)
                        ).forEach { (modeKey, label, isEvent) ->
                            FilterChip(
                                selected = powerMode == modeKey,
                                onClick = {
                                    RuntimeStateStore.updateSettings(
                                        context = context,
                                        eventTriggered = isEvent,
                                        powerMode = modeKey
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    val powerMetrics = when (powerMode) {
                        "performance" -> Triple("约 2.1% / 天", "18 MB", "0.2s 响应")
                        "powersave" -> Triple("约 0.8% / 天", "11 MB", "1.5s 防抖")
                        else -> Triple("约 0.2% / 天", "0 MB 休眠", "下载后按需拉起")
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("预估耗电", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(powerMetrics.first, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("后台内存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(powerMetrics.second, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("唤醒机制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(powerMetrics.third, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF2563EB))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    Toast.makeText(context, "已停止下载守护", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (RuntimeStateStore.useEventTriggeredMode) {
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
                                    Toast.makeText(context, "免 Root 守护已开启", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            Text(if (armed) "停止守护" else "开启免 Root 守护")
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
                                    notifyUser = false,
                                    includeAlreadyHandledInPending = true
                                )
                                Toast.makeText(
                                    context,
                                    "扫描完成：待归位队列现有 ${RuntimeStateStore.pendingItems.size} 个文件（新增 $count）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("扫描最近下载")
                        }
                    }
                }
            }
        }

        // 5. 待归位队列
        item {
            Text(
                text = "待归位检测队列（${pending.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (pending.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("所有新下载文件均已归位", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            "当微信、钉钉、飞书或浏览器写入公共目录，或在 QQ 中点击「用其他应用打开 → 归位」时，文件会自动出现在这里并弹出顶部横幅。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(pending, key = { it.sourcePath }) { item ->
                Card(
                    onClick = { onOpenPicker(item) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
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
private fun KpiMetricCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

private data class NativeAppSlice(
    val name: String,
    val color: Color,
    val count: Int,
    val savedBytes: Long
)

@Composable
private fun EfficiencyAnalyticsNativeCard(historyItems: List<app.fileroute.data.TransferHistoryItem>) {
    var bySpace by remember { mutableStateOf(false) }

    val slices = remember(historyItems.size, bySpace) {
        val grouped = linkedMapOf<String, Pair<Int, Long>>()
        historyItems.forEach { h ->
            val key = when {
                h.appName.contains("微信") -> "微信"
                h.appName.contains("QQ") || h.appName.contains("TIM") -> "QQ / TIM"
                h.appName.contains("钉钉") -> "钉钉"
                h.appName.contains("飞书") -> "飞书"
                else -> "浏览器 / 其他"
            }
            val saved = if (h.copyOnly) (h.fileSizeBytes * 35L) / 100L else (h.fileSizeBytes * 85L) / 100L
            val prev = grouped[key] ?: (0 to 0L)
            grouped[key] = (prev.first + 1) to (prev.second + saved)
        }
        val palette = mapOf(
            "微信" to Color(0xFF07C160),
            "QQ / TIM" to Color(0xFF0EA5E9),
            "钉钉" to Color(0xFF2563EB),
            "飞书" to Color(0xFF0D9488),
            "浏览器 / 其他" to Color(0xFF6366F1)
        )
        grouped.entries.map { (k, v) ->
            NativeAppSlice(k, palette[k] ?: Color(0xFF6366F1), v.first, v.second)
        }.sortedByDescending { if (bySpace) it.savedBytes else it.count.toLong() }
    }

    val totalCount = slices.sumOf { it.count }
    val totalSaved = slices.sumOf { it.savedBytes }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("归位效率分析", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (totalCount > 0) "累计归位 $totalCount 次 · 释放空间 ${DefaultRuleRepository.formatSize(totalSaved)}"
                        else "完成首笔真实归位后自动生成各应用效率分布图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !bySpace, onClick = { bySpace = false }, label = { Text("次数") })
                    FilterChip(selected = bySpace, onClick = { bySpace = true }, label = { Text("空间") })
                }
            }

            if (slices.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.size(124.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                            val totalVal = slices.sumOf { if (bySpace) it.savedBytes.coerceAtLeast(1L) else it.count.toLong() }.toFloat().coerceAtLeast(1f)
                            var startAngle = -90f
                            val strokeW = 22.dp.toPx()
                            slices.forEach { slice ->
                                val v = if (bySpace) slice.savedBytes.coerceAtLeast(1L).toFloat() else slice.count.toFloat()
                                val sweep = (v / totalVal) * 360f
                                drawArc(
                                    color = slice.color,
                                    startAngle = startAngle,
                                    sweepAngle = (sweep - 2f).coerceAtLeast(2f),
                                    useCenter = false,
                                    topLeft = Offset(strokeW / 2f, strokeW / 2f),
                                    size = Size(size.width - strokeW, size.height - strokeW),
                                    style = Stroke(width = strokeW, cap = StrokeCap.Butt)
                                )
                                startAngle += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (bySpace) DefaultRuleRepository.formatSize(totalSaved) else "${totalCount}次",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text("已整理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        slices.forEach { slice ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(modifier = Modifier.size(8.dp).background(slice.color, RoundedCornerShape(2.dp)))
                                    Text(slice.name, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "${slice.count}次 · ${DefaultRuleRepository.formatSize(slice.savedBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesManageTab(
    onOpenRuleHealth: () -> Unit
) {
    val context = LocalContext.current
    val rules = RuntimeStateStore.rules
    var editingRule by remember { mutableStateOf<AppRouteRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportRuleSetDialog by remember { mutableStateOf(false) }
    var cloudRuleUrl by remember { mutableStateOf("") }
    var isFetchingCloudRules by remember { mutableStateOf(false) }

    val importLocalJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            val result = if (!text.isNullOrBlank()) RuntimeStateStore.importConfigJson(context, text) else null
            if (result != null) {
                DownloadMonitorService.reloadIfRunning(context)
            }
            Toast.makeText(
                context,
                if (result != null) "已从本地恢复 ${result.first} 条规则 · ${result.second} 个常用目录"
                else "导入失败，请选择有效的归位 JSON 规则集文件",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val resolved = DownloadDiscoveryEngine.resolveTreeUriToPath(treeUri)
            if (resolved != null) {
                val (matchedId, appName) = DefaultRuleRepository.resolveRuleIdForDiscoveredDir(
                    RuntimeStateStore.rules,
                    resolved,
                    emptyList()
                )
                val msg = RuntimeStateStore.bindDiscoveredPathToRule(context, resolved, appName, matchedId)
                DownloadMonitorService.reloadIfRunning(context)
                Toast.makeText(context, "$msg（$resolved）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "未能解析该目录路径，请选择手机内部存储中的文件夹", Toast.LENGTH_LONG).show()
            }
        }
    }

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
                            Text("智能后缀自动归类（总开关）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("开启后自动按 .pdf / .jpg / .zip / .apk 等后缀归位至预设目录，无需手动建子规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = RuntimeStateStore.smartClassifyEnabled,
                            onCheckedChange = { RuntimeStateStore.updateSettings(context, smartClassify = it) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("系统选目录绑定")
                        }
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("手动输入规则")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val file = RuntimeStateStore.exportConfigJson(context)
                                Toast.makeText(
                                    context,
                                    if (file != null) "已导出规则集至 Documents/${file.name}"
                                    else "导出失败，请先授予所有文件访问权限",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("导出规则集 JSON")
                        }
                        OutlinedButton(
                            onClick = { showImportRuleSetDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("云端 / 本地导入")
                        }
                    }
                }
            }
        }

        // 路径重叠与优先级冲突自动检查工具
        item {
            val allConflicts = RuntimeStateStore.analyzePathConflicts()
            val shadowedConflicts = allConflicts.filter { it.severity == "shadowed" }
            val duplicateConflicts = allConflicts.filter { it.severity == "duplicate" }
            val safeConflicts = allConflicts.filter { it.severity == "safe" }
            val highRiskCount = shadowedConflicts.size + duplicateConflicts.size

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(
                    1.dp,
                    if (highRiskCount > 0) Color(0xFFF59E0B) else Color(0xFFE2E8F0)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("路径重叠与冲突检查", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    highRiskCount > 0 -> "发现 $highRiskCount 项高风险路径冲突，建议立即调整优先级"
                                    safeConflicts.isNotEmpty() -> "存在 ${safeConflicts.size} 组父子重叠路径 · 当前优先级安全无拦截"
                                    else -> "全部启用规则监控路径互不重叠，无逻辑冲突"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (highRiskCount > 0) Color(0xFFB91C1C) else Color(0xFF0F766E)
                            )
                        }
                        if (shadowedConflicts.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val moved = RuntimeStateStore.optimizeRulePriorities(context)
                                    DownloadMonitorService.reloadIfRunning(context)
                                    Toast.makeText(
                                        context,
                                        if (moved > 0) "已自动优化优先级，上移 $moved 条子目录规则" else "当前优先级已安全",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            ) {
                                Text("一键修复")
                            }
                        }
                    }

                    (shadowedConflicts + duplicateConflicts).take(4).forEach { c ->
                        Surface(
                            color = if (c.severity == "shadowed") Color(0xFFFEF2F2) else Color(0xFFFFFBEB),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.summary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "优先：${c.higherPath}  /  靠后：${c.lowerPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        itemsIndexed(rules, key = { _, r -> r.id }) { idx, rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "#${String.format("%02d", idx + 1)}  ${rule.appName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (rule.channel == ChannelMode.PUBLIC_OBSERVER) "公共目录自动检测 · 越靠前越优先" else "QQ/系统分享菜单归位",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (RuntimeStateStore.moveRule(context, idx, -1)) {
                                        Toast.makeText(context, "已提高「${rule.appName}」优先级", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = idx > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "提高优先级")
                            }
                            IconButton(
                                onClick = {
                                    if (RuntimeStateStore.moveRule(context, idx, 1)) {
                                        Toast.makeText(context, "已降低「${rule.appName}」优先级", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = idx < rules.lastIndex
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "降低优先级")
                            }
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
                    val cleanupDesc = when (rule.cleanupStrategy) {
                        "immediate" -> "归位后立即清理原残留"
                        "delayed_days" -> "归位 ${rule.cleanupDelayDays} 天后清理原临时文件"
                        else -> "不自动清理"
                    }
                    Text(
                        "目标：${rule.defaultTargetDir} · 动作：${actionLabel(rule.action)} · 清理：$cleanupDesc",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val sampleFileName = "2026项目方案.pdf"
                    val previewSrcBase = DefaultRuleRepository.ensureTrailingSlash(
                        rule.sourcePaths.firstOrNull()?.takeIf { it.isNotBlank() }
                            ?: "/storage/emulated/0/Download/"
                    )
                    val (previewDstDir, _) = DefaultRuleRepository.resolveTargetDir(
                        rule = rule,
                        fileName = sampleFileName,
                        smartClassifyEnabled = RuntimeStateStore.smartClassifyEnabled,
                        smartGroupByApp = RuntimeStateStore.smartGroupByApp
                    )
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "实时归位预览（假设有新文件：$sampleFileName）",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${previewSrcBase}$sampleFileName -> ${previewDstDir}$sampleFileName",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF1D4ED8)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onOpenRuleHealth,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("规则健康度检查 →", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    editingRule?.let { target ->
        var srcText by remember(target.id) { mutableStateOf(target.sourcePaths.joinToString(",")) }
        var dstText by remember(target.id) { mutableStateOf(target.defaultTargetDir) }
        var selectedAction by remember(target.id) { mutableStateOf(target.action) }
        var selectedCleanup by remember(target.id) { mutableStateOf(target.cleanupStrategy) }
        var selectedCleanupDays by remember(target.id) { mutableStateOf(target.cleanupDelayDays) }

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
                    val liveSrcBase = DefaultRuleRepository.ensureTrailingSlash(
                        srcText.split(",").firstOrNull { it.trim().isNotEmpty() }?.trim()
                            ?: target.sourcePaths.firstOrNull()
                            ?: "/storage/emulated/0/Download/"
                    )
                    val liveDstBase = DefaultRuleRepository.ensureTrailingSlash(
                        dstText.trim().ifEmpty { "/storage/emulated/0/Documents/" }
                    )
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "实时归位预览",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "假设有新文件：${liveSrcBase}示例文件.dat -> ${liveDstBase}示例文件.dat",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF1D4ED8)
                            )
                            TextButton(
                                onClick = {
                                    editingRule = null
                                    onOpenRuleHealth()
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("前往规则健康度检查 →", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(RouteAction.PROMPT, RouteAction.AUTO_MOVE, RouteAction.AUTO_COPY).forEach { act ->
                            FilterChip(
                                selected = selectedAction == act,
                                onClick = { selectedAction = act },
                                label = { Text(actionLabel(act)) }
                            )
                        }
                    }
                    Text("归位后自动清理策略", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("keep" to "不清理", "delayed_days" to "延迟清理", "immediate" to "立即清理").forEach { (key, lbl) ->
                            FilterChip(
                                selected = selectedCleanup == key,
                                onClick = { selectedCleanup = key },
                                label = { Text(lbl) }
                            )
                        }
                    }
                    if (selectedCleanup == "delayed_days") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3, 7, 14, 30).forEach { days ->
                                FilterChip(
                                    selected = selectedCleanupDays == days,
                                    onClick = { selectedCleanupDays = days },
                                    label = { Text("${days}天后") }
                                )
                            }
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
                            action = selectedAction,
                            cleanupStrategy = selectedCleanup,
                            cleanupDelayDays = selectedCleanupDays
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
        var showCustomPaths by remember { mutableStateOf(false) }
        var srcDir by remember { mutableStateOf("") }
        var dstDir by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增应用规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("应用名称（如：企业微信）") },
                        singleLine = true
                    )
                    Text(
                        "无需手动填写路径，程序将自动匹配公共下载目录并按文件类型智能归位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showCustomPaths = !showCustomPaths },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(if (showCustomPaths) "收起自定义路径" else "自定义存储路径（选填）")
                    }
                    if (showCustomPaths) {
                        OutlinedTextField(
                            value = srcDir,
                            onValueChange = { srcDir = it },
                            label = { Text("来源目录（留空自动识别）") },
                            placeholder = { Text("/storage/emulated/0/Download/") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = dstDir,
                            onValueChange = { dstDir = it },
                            label = { Text("归位目录（留空自动归类）") },
                            placeholder = { Text("/storage/emulated/0/Documents/") },
                            singleLine = true
                        )
                        val addSrcPreview = DefaultRuleRepository.ensureTrailingSlash(
                            srcDir.trim().ifEmpty { "/storage/emulated/0/Download/" }
                        )
                        val addDstPreview = DefaultRuleRepository.ensureTrailingSlash(
                            dstDir.trim().ifEmpty { "/storage/emulated/0/Documents/${name.trim().ifEmpty { "自定义应用" }}归档/" }
                        )
                        Text(
                            "假设有新文件：${addSrcPreview}2026项目方案.pdf -> ${addDstPreview}2026项目方案.pdf",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1D4ED8)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleanName = name.trim().ifEmpty { "自定义应用" }
                    val resolvedSrc = srcDir.trim().ifEmpty { "/storage/emulated/0/Download/" }
                    val resolvedDst = dstDir.trim().ifEmpty { "/storage/emulated/0/Documents/${cleanName}归档/" }
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
                            sourcePaths = listOf(DefaultRuleRepository.ensureTrailingSlash(resolvedSrc)),
                            defaultTargetDir = DefaultRuleRepository.ensureTrailingSlash(resolvedDst),
                            renamePattern = "{name}",
                            subRules = emptyList()
                        )
                    )
                    showAddDialog = false
                    Toast.makeText(context, "已添加「$cleanName」，无需指定路径即可自动归位", Toast.LENGTH_SHORT).show()
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }

    if (showImportRuleSetDialog) {
        AlertDialog(
            onDismissRequest = { if (!isFetchingCloudRules) showImportRuleSetDialog = false },
            title = { Text("从云端 / 本地导入规则集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "支持选取本地导出的 JSON 规则文件，或填入云端 JSON 直链一键恢复全部归位策略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            showImportRuleSetDialog = false
                            importLocalJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("从本地选择 .json 文件导入")
                    }
                    HorizontalDivider()
                    OutlinedTextField(
                        value = cloudRuleUrl,
                        onValueChange = { cloudRuleUrl = it },
                        label = { Text("云端 JSON 链接（https://...）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        enabled = !isFetchingCloudRules && cloudRuleUrl.trim().startsWith("http"),
                        onClick = {
                            val targetUrl = cloudRuleUrl.trim()
                            isFetchingCloudRules = true
                            val executor = Executors.newSingleThreadExecutor()
                            val mainHandler = Handler(Looper.getMainLooper())
                            executor.execute {
                                val text = runCatching {
                                    val conn = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 8000
                                    conn.readTimeout = 10000
                                    conn.requestMethod = "GET"
                                    if (conn.responseCode in 200..299) {
                                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                    } else null
                                }.getOrNull()
                                mainHandler.post {
                                    isFetchingCloudRules = false
                                    val imported = if (!text.isNullOrBlank()) {
                                        RuntimeStateStore.importConfigJson(context, text)
                                    } else null
                                    if (imported != null) {
                                        DownloadMonitorService.reloadIfRunning(context)
                                        showImportRuleSetDialog = false
                                        Toast.makeText(
                                            context,
                                            "云端恢复成功：${imported.first} 条规则 · ${imported.second} 个常用目录",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "云端拉取或 JSON 校验失败，请检查链接是否可访问",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                executor.shutdown()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isFetchingCloudRules) "正在拉取云端规则..." else "从云端链接一键恢复")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isFetchingCloudRules,
                    onClick = { showImportRuleSetDialog = false }
                ) { Text("关闭") }
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
    var discoveredDirs by remember { mutableStateOf<List<DiscoveredDeviceDir>>(emptyList()) }
    var isProbing by remember { mutableStateOf(false) }
    var hasProbedOnce by remember { mutableStateOf(false) }
    var showPresetPaths by remember { mutableStateOf(false) }
    val logs = RuntimeStateStore.runtimeLogs

    fun runSmartProbe() {
        if (isProbing) return
        isProbing = true
        val executor = Executors.newSingleThreadExecutor()
        val mainHandler = Handler(Looper.getMainLooper())
        executor.execute {
            val found = DownloadDiscoveryEngine.discoverRealDeviceDirs(context)
            val refreshedDiag = DownloadDiscoveryEngine.diagnosePhonePaths(context)
            mainHandler.post {
                discoveredDirs = found
                diagPair = refreshedDiag
                isProbing = false
                hasProbedOnce = true
                Toast.makeText(context, "探测完成：发现 ${found.size} 个本机真实目录", Toast.LENGTH_SHORT).show()
            }
            executor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        runSmartProbe()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val resolved = DownloadDiscoveryEngine.resolveTreeUriToPath(treeUri)
            if (resolved != null) {
                val (matchedId, appName) = DefaultRuleRepository.resolveRuleIdForDiscoveredDir(
                    RuntimeStateStore.rules,
                    resolved,
                    emptyList()
                )
                val msg = RuntimeStateStore.bindDiscoveredPathToRule(context, resolved, appName, matchedId)
                DownloadMonitorService.reloadIfRunning(context)
                runSmartProbe()
                Toast.makeText(context, "$msg（$resolved）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "无法识别此目录，请选择本机内部存储下的文件夹", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            val result = if (!text.isNullOrBlank()) RuntimeStateStore.importConfigJson(context, text) else null
            if (result != null) {
                DownloadMonitorService.reloadIfRunning(context)
                runSmartProbe()
            }
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
        // 1. 智能路径探测与一键校准卡片（解决不同品牌/平板目录不一致，免慢速全盘遍历）
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("智能本机路径探测与一键校准", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "无需慢速全盘遍历：通过系统 MediaStore 反查 + 2 层浅层探测，毫秒级定位本机真实目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isProbing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }

                    Text(
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · 存储权限=${if (hasAllFilesAccess) "正常" else "未授权"} · 通知=${if (hasNotifPerm) "正常" else "未授权"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { runSmartProbe() },
                            enabled = !isProbing
                        ) {
                            Text(if (isProbing) "探测中..." else "重新智能探测")
                        }
                        FilledTonalButton(
                            onClick = {
                                val (ruleCount, pathCount) = RuntimeStateStore.autoCalibrateAllRules(context, discoveredDirs)
                                DownloadMonitorService.reloadIfRunning(context)
                                runSmartProbe()
                                Toast.makeText(
                                    context,
                                    if (ruleCount > 0) "已自动校准 $ruleCount 条规则，绑定 $pathCount 个本机真实目录"
                                    else "当前已有规则已全部对齐本机路径",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            enabled = discoveredDirs.isNotEmpty() && !isProbing
                        ) {
                            Text("一键校准全部规则")
                        }
                        OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text("手动选系统目录")
                        }
                    }
                }
            }
        }

        // 2. 本机自动探测到的真实活跃目录（支持单条一键绑定）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已在本机发现的真实目录（${discoveredDirs.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (hasProbedOnce && discoveredDirs.isEmpty()) {
                    Text("请先授予所有文件访问权限", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        items(discoveredDirs, key = { it.path }) { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${item.inferredAppName} · ${item.fileCount} 个文件 · ${item.discoverySource}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            item.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!item.latestFileName.isNullOrBlank()) {
                            Text(
                                "最新文件：${item.latestFileName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.ownerPackages.isNotEmpty()) {
                            Text(
                                "写入来源包：${item.ownerPackages.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (item.alreadyBound) {
                        Text(
                            "已监控",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        FilledTonalButton(
                            onClick = {
                                val msg = RuntimeStateStore.bindDiscoveredPathToRule(
                                    context,
                                    item.path,
                                    item.inferredAppName,
                                    item.matchedRuleId
                                )
                                DownloadMonitorService.reloadIfRunning(context)
                                runSmartProbe()
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("绑定")
                        }
                    }
                }
            }
        }

        // 3. 实时抓路径：下载 1 个文件即可立刻看到真实写入路径并一键绑定
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("实时抓路径：系统最新写入（全公共目录）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { diagPair = DownloadDiscoveryEngine.diagnosePhonePaths(context) }) {
                            Text("刷新")
                        }
                    }
                    Text(
                        "不确定某个应用把文件存哪了？去该应用下载 1 个文件后点「刷新」，即可直接捕获真实路径并绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (diagPair.second.isEmpty()) {
                        Text("暂未读取到 MediaStore 公共目录记录（请先授予存储权限或下载测试文件）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        diagPair.second.forEach { sample: RecentMediaSample ->
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(sample.fileName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${sample.dirPath} · ${sample.ownerPackage}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    onClick = {
                                        val (matchedId, appName) = DefaultRuleRepository.resolveRuleIdForDiscoveredDir(
                                            RuntimeStateStore.rules,
                                            sample.dirPath,
                                            listOf(sample.ownerPackage)
                                        )
                                        val msg = RuntimeStateStore.bindDiscoveredPathToRule(context, sample.dirPath, appName, matchedId)
                                        DownloadMonitorService.reloadIfRunning(context)
                                        runSmartProbe()
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("绑定目录")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. 跨设备 JSON 配置导出与导入卡片
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

        // 5. 常见预设路径对照（默认折叠，避免未安装应用误导用户）
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("常见应用预设路径对照（${diagPair.first.count { it.exists }}/${diagPair.first.size} 存在）", style = MaterialTheme.typography.titleSmall)
                        Text("未安装的应用或尚未点「保存到手机」的目录显示不存在属正常现象", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showPresetPaths = !showPresetPaths }) {
                        Text(if (showPresetPaths) "收起" else "展开查看")
                    }
                }
            }
        }

        if (showPresetPaths) {
            items(diagPair.first) { d: DirectoryDiagItem ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${d.label} · ${if (d.exists) (if (d.readable) "存在且可读(${d.fileCount}个文件)" else "存在但无权读取") else "尚未创建/未安装"}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(d.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!d.latestFileName.isNullOrBlank()) {
                            Text("最新文件：${d.latestFileName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        } else if (d.hint.isNotBlank()) {
                            Text("说明：${d.hint}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SettingsManageTab(
    onOpenRules: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    autoExpandHealthTrigger: Int = 0
) {
    val context = LocalContext.current
    var isScanningStorage by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            val result = if (!text.isNullOrBlank()) RuntimeStateStore.importConfigJson(context, text) else null
            if (result != null) {
                DownloadMonitorService.reloadIfRunning(context)
            }
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

    var showHealthPanel by remember { mutableStateOf(autoExpandHealthTrigger > 0) }
    var showBackupPanel by remember { mutableStateOf(false) }
    var showAdvancedPanel by remember { mutableStateOf(false) }

    LaunchedEffect(autoExpandHealthTrigger) {
        if (autoExpandHealthTrigger > 0) {
            showHealthPanel = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 1. 智能自动优化（日常一键托管，免去繁琐配置与手填路径）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("智能自动优化（推荐）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "自动扫描本机真实目录、校准应用规则并开启省电触发式唤醒，日常使用无需手动填写存储路径或调整底层参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        enabled = !isScanningStorage,
                        onClick = {
                            isScanningStorage = true
                            val executor = Executors.newSingleThreadExecutor()
                            val mainHandler = Handler(Looper.getMainLooper())
                            executor.execute {
                                val found = DownloadDiscoveryEngine.discoverRealDeviceDirs(context)
                                mainHandler.post {
                                    val (ruleCount, pathCount) = RuntimeStateStore.autoCalibrateAllRules(context, found)
                                    RuntimeStateStore.updateSettings(
                                        context = context,
                                        eventTriggered = true,
                                        powerMode = "event_triggered",
                                        writeCheck = true,
                                        coalesceBatch = true,
                                        verifyDelayMs = 1500L
                                    )
                                    DownloadMonitorService.reloadIfRunning(context)
                                    isScanningStorage = false
                                    Toast.makeText(
                                        context,
                                        if (ruleCount > 0) "已自动完成优化：校准 $ruleCount 条规则（$pathCount 个路径）并启用省电唤醒"
                                        else "所有目录路径与省电唤醒均已处于最佳状态",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                executor.shutdown()
                            }
                        }
                    ) {
                        Text(if (isScanningStorage) "正在自动优化..." else "一键智能优化")
                    }
                }
            }
        }

        // 2. 折叠面板：规则健康度与目标存储分析
        item {
            var healthChecked by remember { mutableStateOf(false) }
            var isAuditingHealth by remember { mutableStateOf(false) }
            var missingPaths by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var dupExts by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
            var totalPathsChecked by remember { mutableStateOf(0) }
            var targetDirCount by remember { mutableStateOf(0) }
            var targetTotalBytes by remember { mutableStateOf(0L) }
            var targetTotalFiles by remember { mutableStateOf(0) }
            var extStorageStats by remember { mutableStateOf<List<Triple<String, Int, Long>>>(emptyList()) }

            fun runNativeHealthAudit() {
                if (isAuditingHealth) return
                isAuditingHealth = true
                val snapshotRules = RuntimeStateStore.rules.toList()
                val executor = Executors.newSingleThreadExecutor()
                val mainHandler = Handler(Looper.getMainLooper())
                executor.execute {
                    val missing = mutableListOf<Pair<String, String>>()
                    var totalPaths = 0
                    val extOwners = linkedMapOf<String, MutableList<String>>()
                    val targetDirs = linkedSetOf<String>()

                    snapshotRules.forEach { rule ->
                        if (rule.channel == ChannelMode.PUBLIC_OBSERVER) {
                            rule.sourcePaths.forEach { sp ->
                                totalPaths++
                                val exists = runCatching { java.io.File(sp).exists() }.getOrDefault(false)
                                if (!exists) missing.add(rule.appName to sp)
                            }
                        }
                        if (rule.defaultTargetDir.isNotBlank()) {
                            targetDirs.add(DefaultRuleRepository.ensureTrailingSlash(rule.defaultTargetDir))
                        }
                        rule.subRules.forEach { sub ->
                            if (sub.targetDir.isNotBlank()) {
                                targetDirs.add(DefaultRuleRepository.ensureTrailingSlash(sub.targetDir))
                            }
                        }
                        if (rule.enabled && rule.action != RouteAction.IGNORE) {
                            rule.subRules.forEach { sub ->
                                sub.extensions.forEach { ext ->
                                    val clean = ext.trim().lowercase().removePrefix(".")
                                    if (clean.isNotEmpty()) {
                                        val list = extOwners.getOrPut(clean) { mutableListOf() }
                                        list.add("${rule.appName}→${sub.targetDir}")
                                    }
                                }
                            }
                        }
                    }

                    var sumBytes = 0L
                    var sumFiles = 0
                    val extSizeMap = linkedMapOf<String, Pair<Int, Long>>()
                    val visitedFiles = hashSetOf<String>()

                    targetDirs.forEach { dirPath ->
                        val dir = java.io.File(dirPath)
                        if (dir.exists() && dir.isDirectory) {
                            runCatching {
                                dir.walkTopDown().maxDepth(2).filter { it.isFile }.forEach { f ->
                                    if (visitedFiles.add(f.absolutePath)) {
                                        val len = f.length().coerceAtLeast(0L)
                                        sumBytes += len
                                        sumFiles++
                                        val ext = f.extension.lowercase().ifBlank { "other" }
                                        val prev = extSizeMap[ext] ?: (0 to 0L)
                                        extSizeMap[ext] = (prev.first + 1) to (prev.second + len)
                                    }
                                }
                            }
                        }
                    }

                    val sortedExtStats = extSizeMap.entries
                        .map { Triple(it.key, it.value.first, it.value.second) }
                        .sortedByDescending { it.third }

                    mainHandler.post {
                        totalPathsChecked = totalPaths
                        missingPaths = missing
                        dupExts = extOwners.entries.filter { it.value.size > 1 }.map { it.key to it.value }
                        targetDirCount = targetDirs.size
                        targetTotalBytes = sumBytes
                        targetTotalFiles = sumFiles
                        extStorageStats = sortedExtStats
                        healthChecked = true
                        isAuditingHealth = false
                    }
                    executor.shutdown()
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showHealthPanel = !showHealthPanel },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("规则健康度与存储空间分析", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (!healthChecked) "展开查看路径存在性、重复后缀与目标目录体积占比"
                                else "目标已存 ${DefaultRuleRepository.formatSize(targetTotalBytes)} · ${missingPaths.size} 缺失 · ${dupExts.size} 重复后缀",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (showHealthPanel) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showHealthPanel) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showHealthPanel) {
                        val pathConflictsNow = RuntimeStateStore.analyzePathConflicts()
                            .filter { it.severity == "shadowed" || it.severity == "exact_duplicate" }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pathConflictsNow.isNotEmpty() || dupExts.isNotEmpty()) {
                                Button(
                                    enabled = !isAuditingHealth,
                                    onClick = {
                                        val (reordered, mergedExtCount) = RuntimeStateStore.autoFixRuleHealthIssues(context)
                                        DownloadMonitorService.reloadIfRunning(context)
                                        runNativeHealthAudit()
                                        Toast.makeText(
                                            context,
                                            if (reordered > 0 || mergedExtCount > 0)
                                                "一键修复完成：按路径深度重排 $reordered 条 · 合并 $mergedExtCount 个重复后缀"
                                            else
                                                "所有规则优先级与后缀均已健康",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                ) {
                                    Text("一键修复")
                                }
                            }
                            OutlinedButton(
                                enabled = !isAuditingHealth,
                                onClick = { runNativeHealthAudit() }
                            ) {
                                Text(if (isAuditingHealth) "扫描中..." else if (healthChecked) "重新体检与统计" else "立即体检与统计")
                            }
                        }
                        if (pathConflictsNow.isNotEmpty()) {
                            Surface(color = Color(0xFFFFFBEB), shape = RoundedCornerShape(10.dp)) {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "发现 ${pathConflictsNow.size} 项路径深度优先级冲突",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF92400E)
                                    )
                                    Text(
                                        "点击上方「一键修复」将按路径深度从深到浅自动重排优先级。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (healthChecked) {
                            Surface(color = Color(0xFFF8FAFC), shape = RoundedCornerShape(12.dp)) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "目标目录已存总量：${DefaultRuleRepository.formatSize(targetTotalBytes)}（$targetDirCount 目录 · $targetTotalFiles 文件）",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (extStorageStats.isEmpty()) {
                                        Text("目标目录暂未扫描到已归位文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        extStorageStats.take(5).forEach { (ext, cnt, bytes) ->
                                            val pct = if (targetTotalBytes > 0L) ((bytes * 1000L) / targetTotalBytes) / 10.0 else 0.0
                                            Text(
                                                ".$ext  ·  ${cnt}个  ·  ${DefaultRuleRepository.formatSize(bytes)}（${pct}%）",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            missingPaths.take(4).forEach { (app, path) ->
                                Surface(color = Color(0xFFFEF2F2), shape = RoundedCornerShape(10.dp)) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("路径不存在 · $app", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFB91C1C))
                                        Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            dupExts.take(4).forEach { (ext, owners) ->
                                Surface(color = Color(0xFFFFFBEB), shape = RoundedCornerShape(10.dp)) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("重复后缀 .${ext}（${owners.size} 条子规则）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF92400E))
                                        Text("建议合并或增加筛选：${owners.joinToString(" / ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (missingPaths.isEmpty() && dupExts.isEmpty()) {
                                Text("全部 $totalPathsChecked 条路径有效，且无重复后缀冲突。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F766E))
                            }
                        }
                    }
                }
            }
        }

        // 3. 折叠面板：跨设备 JSON 备份（导出 / 导入）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showBackupPanel = !showBackupPanel },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("跨设备配置备份（导出 / 导入 JSON）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${RuntimeStateStore.rules.size} 条规则 · ${RuntimeStateStore.quickFolders.size} 个常用目录 · 换机时展开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (showBackupPanel) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showBackupPanel) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showBackupPanel) {
                        HorizontalDivider(color = Color(0xFFF1F5F9))
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
                                Text("导出 JSON")
                            }
                            OutlinedButton(
                                onClick = { importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                            ) {
                                Text("导入 JSON")
                            }
                            TextButton(onClick = onOpenRules) {
                                Text("查看规则")
                            }
                        }
                    }
                }
            }
        }

        // 4. 折叠面板：高级设置（唤醒参数、路径诊断与恢复默认）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showAdvancedPanel = !showAdvancedPanel },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("高级设置（唤醒参数、诊断与重置）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "已由程序自动托管 · 仅在需要精细排查时展开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (showAdvancedPanel) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showAdvancedPanel) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showAdvancedPanel) {
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("触发式唤醒省电优化", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = RuntimeStateStore.useEventTriggeredMode,
                                onCheckedChange = { enabled ->
                                    RuntimeStateStore.updateSettings(
                                        context = context,
                                        eventTriggered = enabled,
                                        powerMode = if (enabled) "event_triggered" else "powersave"
                                    )
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("写入权限前置校验", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = RuntimeStateStore.requireWritePermissionCheck,
                                onCheckedChange = { RuntimeStateStore.updateSettings(context, writeCheck = it) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("连续下载合并唤醒", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = RuntimeStateStore.coalesceBatchDownloads,
                                onCheckedChange = { RuntimeStateStore.updateSettings(context, coalesceBatch = it) }
                            )
                        }
                        Text("下载落盘确认延迟", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(800L to "0.8s", 1500L to "1.5s 推荐", 3000L to "3.0s").forEach { (ms, label) ->
                                FilterChip(
                                    selected = RuntimeStateStore.writeVerifyDelayMs == ms,
                                    onClick = { RuntimeStateStore.updateSettings(context, verifyDelayMs = ms) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenDiagnostics) {
                                Text("查看目录诊断")
                            }
                            OutlinedButton(onClick = { confirmReset = true }) {
                                Text("恢复默认设置")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("确认恢复默认设置？") },
            text = { Text("将重置自定义规则与常用目录为初始预设（历史归位记录不会被清空）。") },
            confirmButton = {
                Button(onClick = {
                    RuntimeStateStore.resetDefaults(context)
                    DownloadMonitorService.reloadIfRunning(context)
                    confirmReset = false
                    Toast.makeText(context, "已恢复默认预设", Toast.LENGTH_SHORT).show()
                }) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
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
