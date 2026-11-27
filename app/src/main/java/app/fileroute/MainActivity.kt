package app.fileroute

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fileroute.data.ChannelMode
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.service.DownloadMonitorService
import app.fileroute.worker.DownloadTriggerWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FileRouteAdaptiveScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileRouteAdaptiveScreen() {
    val context = LocalContext.current
    var triggerArmed by remember { mutableStateOf(false) }
    var useEventTriggeredMode by remember { mutableStateOf(false) }
    val hasAllFilesAccess = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("归位 FileRoute · Android 17") })
        }
    ) { padding ->
        // 平板 / 折叠屏大屏自适应：宽度 >= 600dp 自动切换为左右双栏布局
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            val isTabletLayout = maxWidth >= 600.dp

            val controlPanel: @Composable () -> Unit = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (useEventTriggeredMode) "下载后按需唤醒模式（极低功耗 · 防杀）" else "常驻实时监听模式",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (useEventTriggeredMode)
                                "平时休眠不挂后台，微信/QQ 下载新文档后由 Android 系统 MediaStore 触发唤醒并弹出归位提示。"
                            else
                                "前台常驻监听微信、钉钉、飞书与新版 QQ 公共目录，约 0.18 秒极速弹出横幅。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = useEventTriggeredMode,
                                onClick = { useEventTriggeredMode = true },
                                label = { Text("下载后唤醒(推荐)") }
                            )
                            FilterChip(
                                selected = !useEventTriggeredMode,
                                onClick = { useEventTriggeredMode = false },
                                label = { Text("常驻秒弹") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                OutlinedButton(onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }) {
                                    Text("授予存储权限")
                                }
                            }
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                val svcIntent = Intent(context, DownloadMonitorService::class.java)
                                if (triggerArmed) {
                                    DownloadTriggerWorker.cancelTrigger(context)
                                    context.stopService(svcIntent)
                                    triggerArmed = false
                                } else {
                                    if (useEventTriggeredMode) {
                                        DownloadTriggerWorker.scheduleNextTrigger(context)
                                    } else {
                                        runCatching {
                                            context.startForegroundService(svcIntent)
                                        }.onFailure {
                                            // Android 17 前台服务启动受限时自动回退至 MediaStore 触发式唤醒
                                            DownloadTriggerWorker.scheduleNextTrigger(context)
                                            Toast.makeText(
                                                context,
                                                "系统限制前台服务启动，已自动切换为 MediaStore 触发式唤醒",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    triggerArmed = true
                                }
                            }) {
                                Text(if (triggerArmed) "停止守护" else "开启免 Root 守护")
                            }
                        }
                    }
                }
            }

            if (isTabletLayout) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(0.45f)) { controlPanel() }
                    LazyColumn(
                        modifier = Modifier.weight(0.55f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text("已同步规则（${DefaultRuleRepository.initialRules.size}）", style = MaterialTheme.typography.titleSmall)
                        }
                        items(DefaultRuleRepository.initialRules) { rule ->
                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rule.appName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (rule.channel == ChannelMode.PUBLIC_OBSERVER) "公共目录触发" else "QQ/系统分享菜单归位",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text("默认目标：${rule.defaultTargetDir}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { controlPanel() }
                    item {
                        Text("已内置归位规则（${DefaultRuleRepository.initialRules.size}）", style = MaterialTheme.typography.titleSmall)
                    }
                    items(DefaultRuleRepository.initialRules) { rule ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(rule.appName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (rule.channel == ChannelMode.PUBLIC_OBSERVER) "公共目录触发" else "QQ/系统分享菜单归位",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("默认目标：${rule.defaultTargetDir}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
