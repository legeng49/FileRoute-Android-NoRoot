package app.fileroute.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import app.fileroute.service.DownloadMonitorService
import java.io.File

class OpenSourceFolderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawPath = intent?.getStringExtra(DownloadMonitorService.EXTRA_SOURCE_PATH)
            ?: "/storage/emulated/0/Download"
        val targetDir = File(rawPath).let { f ->
            if (f.isDirectory) f.absolutePath else (f.parent ?: "/storage/emulated/0/Download")
        }
        openFolderInSystemFileManager(targetDir)
        finish()
    }

    private fun openFolderInSystemFileManager(dirPath: String) {
        val relPath = dirPath
            .removePrefix("/storage/emulated/0")
            .trim('/')
        val docId = if (relPath.isEmpty()) "primary:" else "primary:$relPath"
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            docId
        )

        // 1. 优先调用 Android 标准 DocumentsUI（Android 11-17 通用）定位精确子目录
        val primaryIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { startActivity(primaryIntent); true }.getOrDefault(false)) {
            return
        }

        // 2. 兼容部分国产 ROM 文件管理器（HyperOS / ColorOS / HarmonyOS 等）
        val oemIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode(docId)),
                "resource/folder"
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(oemIntent); true }.getOrDefault(false)) {
            return
        }

        // 3. 保底跳转系统「下载内容」目录
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(downloadsIntent); true }.getOrDefault(false)) {
            return
        }

        Toast.makeText(
            this,
            "未找到可用的系统文件管理器，源目录位置：$dirPath",
            Toast.LENGTH_LONG
        ).show()
    }
}
