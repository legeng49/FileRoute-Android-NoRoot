package app.fileroute.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        if (RuntimeStateStore.shizukuEnabled) {
            app.fileroute.shizuku.ShizukuPrivateDirBridge.init(applicationContext)
        }

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
        val referrerHost = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.host.orEmpty()
        } else ""
        val uriSignature = (sharedUris.firstOrNull()?.toString().orEmpty() + " " + referrerHost).lowercase()
        val matchedShareRule = RuntimeStateStore.rules.firstOrNull { r ->
            when {
                uriSignature.contains("tencent.mm") || uriSignature.contains("micromsg") || uriSignature.contains("weixin") ->
                    r.packageName == "com.tencent.mm"
                uriSignature.contains("mobileqq") || uriSignature.contains("qqfile") ->
                    r.packageName == "com.tencent.mobileqq"
                uriSignature.contains("tencent.tim") ->
                    r.packageName == "com.tencent.tim"
                uriSignature.contains("rimet") || uriSignature.contains("dingtalk") ->
                    r.packageName == "com.alibaba.android.rimet"
                uriSignature.contains("lark") || uriSignature.contains("feishu") ->
                    r.packageName == "com.ss.android.lark"
                else -> false
            }
        }
        val sourceApp = intent.getStringExtra(DownloadMonitorService.EXTRA_APP_NAME)
            ?: when {
                uriSignature.contains("tencent.mm") || uriSignature.contains("micromsg") || uriSignature.contains("weixin") -> "微信（聊天文件）"
                uriSignature.contains("mobileqq") || uriSignature.contains("qqfile") -> "QQ（聊天文件）"
                uriSignature.contains("tencent.tim") -> "TIM（聊天文件）"
                uriSignature.contains("rimet") || uriSignature.contains("dingtalk") -> "钉钉"
                uriSignature.contains("lark") || uriSignature.contains("feishu") -> "飞书"
                sharedUris.isNotEmpty() -> "系统分享 / 打开传入"
                else -> "未知来源"
            }
        val primaryName = directFilePath?.let { File(it).name }
            ?: sharedUris.firstOrNull()?.let { resolveDisplayName(it) }
            ?: "未命名文件"
        val displayTitle = if (sharedUris.size > 1) {
            "$primaryName 等 ${sharedUris.size} 个文件"
        } else {
            primaryName
        }

        val autoTarget = DefaultRuleRepository.resolveTargetDir(
            rule = matchedShareRule,
            fileName = primaryName,
            smartClassifyEnabled = RuntimeStateStore.smartClassifyEnabled,
            smartGroupByApp = RuntimeStateStore.smartGroupByApp
        ).first
        val initialSuggested = intent.getStringExtra(DownloadMonitorService.EXTRA_SUGGESTED_DIR)
            ?: autoTarget

        // 若已开启「微信/QQ「用其他应用打开」直接静默归位」，零点击直接按后缀归位并返回聊天窗口
        if (directFilePath == null && sharedUris.isNotEmpty() && RuntimeStateStore.quickSilentShareArchive) {
            var successCount = 0
            var lastTarget = initialSuggested
            for (uri in sharedUris) {
                val rawName = resolveDisplayName(uri)
                val targetDir = DefaultRuleRepository.resolveTargetDir(
                    rule = matchedShareRule,
                    fileName = rawName,
                    smartClassifyEnabled = RuntimeStateStore.smartClassifyEnabled,
                    smartGroupByApp = RuntimeStateStore.smartGroupByApp
                ).first
                val finalName = DefaultRuleRepository.formatFileName(
                    matchedShareRule?.renamePattern ?: "{name}",
                    sourceApp,
                    rawName
                )
                val ok = DownloadDiscoveryEngine.relocateFile(
                    context = applicationContext,
                    sourcePath = null,
                    sharedUri = uri,
                    appName = sourceApp,
                    fileName = finalName,
                    targetDirPath = targetDir,
                    copyOnly = true,
                    conflictStrategy = "rename_timestamp",
                    mode = "share_saved"
                )
                if (ok) {
                    successCount++
                    lastTarget = targetDir
                }
            }
            Toast.makeText(
                this,
                if (successCount > 0) "已一键静默归位 $successCount 个${sourceApp}至 $lastTarget"
                else "保存失败，请先授予所有文件访问权限",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        setContent {
            MaterialTheme {
                var selectedDir by remember { mutableStateOf(initialSuggested) }
                var showFolderBrowser by remember { mutableStateOf(false) }
                var copyOnly by remember { mutableStateOf(sharedUris.isNotEmpty()) }
                var conflictStrategy by remember { mutableStateOf("rename_timestamp") }
                val systemFolderLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { treeUri ->
                    val resolved = safTreeUriToRealPath(treeUri)
                    if (!resolved.isNullOrBlank()) {
                        selectedDir = resolved
                        Toast.makeText(this@ShareReceiverActivity, "已选择目录：$resolved", Toast.LENGTH_SHORT).show()
                    }
                }
                val existingConflict = remember(selectedDir, primaryName) {
                    DownloadDiscoveryEngine.findExistingConflict(selectedDir, primaryName)
                }
                val isExactDuplicate = remember(
                    selectedDir,
                    primaryName,
                    RuntimeStateStore.integrityCheckEnabled
                ) {
                    if (existingConflict != null && RuntimeStateStore.integrityCheckEnabled) {
                        when {
                            directFilePath != null -> DownloadDiscoveryEngine.isIdenticalFile(File(directFilePath), existingConflict)
                            sharedUris.size == 1 -> DownloadDiscoveryEngine.isIdenticalUriToFile(applicationContext, sharedUris.first(), existingConflict)
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                val timestampPreviewName = remember(selectedDir, primaryName) {
                    DownloadDiscoveryEngine.buildTimestampedFileName(primaryName)
                }

                if (showFolderBrowser) {
                    FolderBrowserDialog(
                        initialPath = selectedDir,
                        onDismiss = { showFolderBrowser = false },
                        onConfirm = { picked ->
                            selectedDir = picked
                            showFolderBrowser = false
                        },
                        onOpenSystemPicker = { systemFolderLauncher.launch(null) }
                    )
                }

                ModalBottomSheet(onDismissRequest = { finish() }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "选择文件归位目录", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "$sourceApp · $displayTitle",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { showFolderBrowser = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("浏览文件夹...")
                            }
                            OutlinedButton(
                                onClick = { systemFolderLauncher.launch(null) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("系统文件管理器")
                            }
                        }

                        OutlinedTextField(
                            value = selectedDir,
                            onValueChange = { selectedDir = it },
                            label = { Text("目标存储目录（支持点击上方直接选取）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        if (isExactDuplicate && existingConflict != null) {
                            Surface(
                                color = androidx.compose.ui.graphics.Color(0xFFF0FDFA),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    androidx.compose.ui.graphics.Color(0xFF99F6E4)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "完整性校验一致（大小与 SHA-256 相同）",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = androidx.compose.ui.graphics.Color(0xFF0F766E)
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
                                color = androidx.compose.ui.graphics.Color(0xFFFFFBEB),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    androidx.compose.ui.graphics.Color(0xFFFDE68A)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "目标目录已存在同名文件（${DefaultRuleRepository.formatSize(existingConflict.length())}）",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = androidx.compose.ui.graphics.Color(0xFF92400E)
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("文件完整性校验（一致自动跳过）")
                            Switch(
                                checked = RuntimeStateStore.integrityCheckEnabled,
                                onCheckedChange = {
                                    RuntimeStateStore.updateSettings(context = applicationContext, integrityCheck = it)
                                }
                            )
                        }

                        if (sharedUris.isNotEmpty() && directFilePath == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("下次从微信/QQ打开时直接静默归位")
                                Switch(
                                    checked = RuntimeStateStore.quickSilentShareArchive,
                                    onCheckedChange = {
                                        RuntimeStateStore.updateSettings(context = applicationContext, quickSilentShare = it)
                                    }
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
                                val strategy = if (existingConflict != null) conflictStrategy else "rename_timestamp"
                                val ok = if (directFilePath != null) {
                                    DownloadDiscoveryEngine.relocateFile(
                                        context = applicationContext,
                                        sourcePath = directFilePath,
                                        sharedUri = null,
                                        appName = sourceApp,
                                        fileName = primaryName,
                                        targetDirPath = selectedDir,
                                        copyOnly = copyOnly,
                                        conflictStrategy = strategy
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
                                            copyOnly = true,
                                            conflictStrategy = strategy
                                        )
                                    }
                                } else {
                                    false
                                }
                                Toast.makeText(
                                    this@ShareReceiverActivity,
                                    when {
                                        !ok -> "保存失败，请先在首页授予所有文件访问权限"
                                        existingConflict != null && strategy == "skip" -> "已跳过同名文件"
                                        existingConflict != null && strategy == "overwrite" -> "已覆盖同名文件并归位至 $selectedDir"
                                        existingConflict != null -> "已添加时间戳归位至 $selectedDir"
                                        else -> "已归位至 $selectedDir"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
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
