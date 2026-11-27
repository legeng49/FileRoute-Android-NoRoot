package app.fileroute.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.service.DownloadMonitorService
import java.io.File

/**
 * 透明底部的目录选择器弹窗：
 * 1. 当用户在 QQ / TIM 中点击「用其他应用打开」或「分享 → 归位」时直接弹出（无需 Shizuku）
 * 2. 当用户点击顶部系统通知横幅时直接弹出
 */
class ShareReceiverActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else if (intent.action == Intent.ACTION_VIEW) {
            intent.data
        } else {
            null
        }

        val directFilePath = intent.getStringExtra(DownloadMonitorService.EXTRA_SOURCE_PATH)
        val sourceApp = intent.getStringExtra(DownloadMonitorService.EXTRA_APP_NAME)
            ?: if (sharedUri != null) "QQ / 系统分享传入" else "未知来源"
        val fileName = directFilePath?.let { File(it).name }
            ?: sharedUri?.let { resolveDisplayName(it) }
            ?: "未命名文件"
        val initialSuggested = intent.getStringExtra(DownloadMonitorService.EXTRA_SUGGESTED_DIR)
            ?: DefaultRuleRepository.quickFolders.first().path

        setContent {
            MaterialTheme {
                var selectedDir by remember { mutableStateOf(initialSuggested) }
                var copyOnly by remember { mutableStateOf(sharedUri != null) }

                ModalBottomSheet(
                    onDismissRequest = { finish() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(text = "选择文件归位目录", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "$sourceApp · $fileName",
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
                            items(DefaultRuleRepository.quickFolders) { folder ->
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
                                val ok = performRelocate(
                                    directFilePath = directFilePath,
                                    sharedUri = sharedUri,
                                    fileName = fileName,
                                    targetDirPath = selectedDir,
                                    copyOnly = copyOnly
                                )
                                Toast.makeText(
                                    this@ShareReceiverActivity,
                                    if (ok) "已归位至 $selectedDir" else "保存失败，请检查存储权限",
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

    private fun performRelocate(
        directFilePath: String?,
        sharedUri: Uri?,
        fileName: String,
        targetDirPath: String,
        copyOnly: Boolean
    ): Boolean {
        return runCatching {
            val targetDir = File(targetDirPath)
            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, fileName)

            if (directFilePath != null) {
                val src = File(directFilePath)
                src.copyTo(targetFile, overwrite = true)
                if (!copyOnly) src.delete()
            } else if (sharedUri != null) {
                contentResolver.openInputStream(sharedUri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return false
            } else {
                return false
            }
            true
        }.getOrDefault(false)
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
