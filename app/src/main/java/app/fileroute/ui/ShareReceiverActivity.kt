package app.fileroute.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.data.RuntimeStateStore
import app.fileroute.service.DownloadMonitorService
import app.fileroute.util.DownloadDiscoveryEngine
import java.io.File

/**
 * 透明底部的目录选择器弹窗（已适配 Android 17 Edge-to-Edge 与 IntentCompat）：
 * 1. 当用户在 QQ / TIM 中点击「用其他应用打开」或「分享 → 归位」时直接弹出（无需 Shizuku）
 * 2. 当用户点击顶部系统通知横幅时直接弹出
 */
class ShareReceiverActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RuntimeStateStore.ensureInit(applicationContext)

        val sharedUris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.filterNotNull()
                    .orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }

        val directFilePath = intent.getStringExtra(DownloadMonitorService.EXTRA_SOURCE_PATH)
        val sourceApp = intent.getStringExtra(DownloadMonitorService.EXTRA_APP_NAME)
            ?: if (sharedUris.isNotEmpty()) "QQ / 系统分享传入" else "未知来源"
        val primaryName = directFilePath?.let { File(it).name }
            ?: sharedUris.firstOrNull()?.let { resolveDisplayName(it) }
            ?: "未命名文件"
        val displayTitle = if (sharedUris.size > 1) {
            "$primaryName 等 ${sharedUris.size} 个文件"
        } else {
            primaryName
        }

        val autoTarget = DefaultRuleRepository.resolveTargetDir(
            rule = null,
            fileName = primaryName,
            smartClassifyEnabled = RuntimeStateStore.smartClassifyEnabled
        ).first
        val initialSuggested = intent.getStringExtra(DownloadMonitorService.EXTRA_SUGGESTED_DIR)
            ?: autoTarget

        setContent {
            MaterialTheme {
                var selectedDir by remember { mutableStateOf(initialSuggested) }
                var copyOnly by remember { mutableStateOf(sharedUris.isNotEmpty()) }

                ModalBottomSheet(onDismissRequest = { finish() }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(text = "选择文件归位目录", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "$sourceApp · $displayTitle",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = selectedDir,
                            onValueChange = { selectedDir = it },
                            label = { Text("目标存储目录") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 220.dp)
                        ) {
                            items(RuntimeStateStore.quickFolders) { folder ->
                                FilterChip(
                                    selected = selectedDir == folder.path,
                                    onClick = { selectedDir = folder.path },
                                    label = { Text(folder.label) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        if (directFilePath != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("保留原目录副本（仅复制）")
                                Switch(checked = copyOnly, onCheckedChange = { copyOnly = it })
                            }
                        }

                        Button(
                            onClick = {
                                val ok = if (directFilePath != null) {
                                    DownloadDiscoveryEngine.relocateFile(
                                        context = applicationContext,
                                        sourcePath = directFilePath,
                                        sharedUri = null,
                                        appName = sourceApp,
                                        fileName = primaryName,
                                        targetDirPath = selectedDir,
                                        copyOnly = copyOnly
                                    )
                                } else if (sharedUris.isNotEmpty()) {
                                    sharedUris.all { uri ->
                                        DownloadDiscoveryEngine.relocateFile(
                                            context = applicationContext,
                                            sourcePath = null,
                                            sharedUri = uri,
                                            appName = sourceApp,
                                            fileName = resolveDisplayName(uri),
                                            targetDirPath = selectedDir,
                                            copyOnly = true
                                        )
                                    }
                                } else {
                                    false
                                }
                                Toast.makeText(
                                    this@ShareReceiverActivity,
                                    if (ok) "已归位至 $selectedDir" else "保存失败，请先在首页授予所有文件访问权限",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(if (copyOnly) "确认复制到此目录" else "确认移动归位")
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "共享文件"
    }
}
