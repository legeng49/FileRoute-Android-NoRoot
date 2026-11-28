package app.fileroute.ui

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fileroute.data.DefaultRuleRepository
import app.fileroute.util.NativeDirectoryPicker
import java.io.File

/**
 * 将 Android 系统文件管理器（ACTION_OPEN_DOCUMENT_TREE）返回的 SAF treeUri
 * 还原为真实的外部存储规范绝对路径（如 /storage/emulated/0/Documents/课件/）。
 */
fun safTreeUriToRealPath(treeUri: Uri?): String? {
    return NativeDirectoryPicker.resolveTreeUri(treeUri)
}

/**
 * 可视化文件夹浏览选择框（类似电脑选择保存路径）：
 * - 直接浏览设备内部存储真实目录树，无需手写绝对路径
 * - 支持顶部常用目录一键跳转、逐级点进子目录、返回上一级、原地新建文件夹
 * - 支持点击「系统文件管理器」调用 Android 原生目录选择器
 */
@Composable
fun FolderBrowserDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onOpenSystemPicker: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val extRoot = remember {
        runCatching { Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') }
            .getOrDefault("/storage/emulated/0")
    }

    fun resolveExistingStartDir(raw: String): File {
        val cleaned = raw.trim().replace("\\", "/")
        val mapped = when {
            cleaned.lowercase() == "/sdcard" || cleaned.lowercase().startsWith("/sdcard/") ->
                extRoot + cleaned.substring("/sdcard".length)
            cleaned.isNotEmpty() -> cleaned
            else -> "$extRoot/Documents"
        }
        var f = File(mapped)
        while (!f.exists() || !f.isDirectory) {
            val parent = f.parentFile ?: break
            f = parent
        }
        if (!f.exists() || !f.isDirectory || !f.absolutePath.startsWith(extRoot)) {
            val docs = File(extRoot, "Documents")
            return if (docs.exists() && docs.isDirectory) docs else File(extRoot)
        }
        return f
    }

    var currentDir by remember(initialPath) { mutableStateOf(resolveExistingStartDir(initialPath)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showCreateBox by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val subDirs = remember(currentDir.absolutePath, refreshTick) {
        runCatching {
            currentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    val relPath = currentDir.absolutePath.removePrefix(extRoot).trim('/')
    val sdcardDisplay = if (relPath.isEmpty()) "/sdcard/" else "/sdcard/$relPath/"
    val breadcrumbLabel = if (relPath.isEmpty()) "内部存储" else "内部存储 / ${relPath.replace("/", " / ")}"
    val canGoUp = currentDir.absolutePath.trimEnd('/') != extRoot

    val quickRoots = remember(extRoot) {
        listOf(
            "内部存储" to extRoot,
            "Documents" to "$extRoot/Documents",
            "Download" to "$extRoot/Download",
            "Pictures" to "$extRoot/Pictures",
            "Movies" to "$extRoot/Movies",
            "Music" to "$extRoot/Music",
            "DCIM" to "$extRoot/DCIM"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("选择目标文件夹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    breadcrumbLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 常用系统根目录快捷切换
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickRoots.forEach { (label, path) ->
                        val active = currentDir.absolutePath.trimEnd('/').equals(path.trimEnd('/'), ignoreCase = true)
                        FilterChip(
                            selected = active,
                            onClick = {
                                val target = File(path)
                                if (!target.exists()) runCatching { target.mkdirs() }
                                if (target.exists() && target.isDirectory) {
                                    currentDir = target
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // 当前真实挂载点 + 新建文件夹操作栏
                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前挂载路径", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                sdcardDisplay,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F766E)
                            )
                        }
                        TextButton(
                            onClick = { showCreateBox = !showCreateBox },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("新建文件夹", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (showCreateBox) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = { Text("输入新文件夹名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val clean = newFolderName.trim().replace(Regex("""[/\\:*?"<>|]"""), "_")
                                if (clean.isEmpty()) return@Button
                                val created = File(currentDir, clean)
                                val ok = created.exists() || runCatching { created.mkdirs() }.getOrDefault(false)
                                if (ok && created.isDirectory) {
                                    currentDir = created
                                    newFolderName = ""
                                    showCreateBox = false
                                    refreshTick++
                                } else {
                                    Toast.makeText(context, "创建失败，请检查所有文件访问权限", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) { Text("创建") }
                    }
                }

                // 子目录列表
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        if (canGoUp) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentDir.parentFile?.let { parent ->
                                                if (parent.absolutePath.length >= extRoot.length) {
                                                    currentDir = parent
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "返回上一级目录",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                HorizontalDivider(color = Color(0xFFF1F5F9))
                            }
                        }
                        if (subDirs.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "当前目录没有更多子文件夹，可直接点击下方「选择当前目录」",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(subDirs, key = { it.absolutePath }) { folder ->
                                val childCount = remember(folder.absolutePath) {
                                    runCatching {
                                        folder.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0
                                    }.getOrDefault(0)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentDir = folder }
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(Color(0xFFEFF6FF), RoundedCornerShape(7.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "目录",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF1D4ED8),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Text(
                                            folder.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        if (childCount > 0) "$childCount 项 →" else "进入 →",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HorizontalDivider(color = Color(0xFFF1F5F9))
                            }
                        }
                    }
                }

                if (onOpenSystemPicker != null) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenSystemPicker()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("调用系统文件管理器选取文件夹")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(DefaultRuleRepository.ensureTrailingSlash(currentDir.absolutePath))
                }
            ) {
                Text("选择当前目录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
