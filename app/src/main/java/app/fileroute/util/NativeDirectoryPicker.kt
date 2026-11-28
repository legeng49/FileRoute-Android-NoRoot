package app.fileroute.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.net.URLDecoder

/**
 * Android 原生系统「文档选择器 / 文件管理器」FilePicker 工具：
 * 1) 通过 ACTION_OPEN_DOCUMENT_TREE 拉起系统级图形化目录选择界面，并自动定位到当前目录；
 * 2) 自动将系统返回的 SAF content:// treeUri（含 primary:、home:、downloads:、raw:、外置 SD 卡）
 *    以及 /sdcard/、/storage/self/primary/ 等别名路径统一转换为规范格式：
 *    /storage/emulated/0/.../（始终以斜杠结尾，杜绝手写绝对路径导致的识别失败）。
 */
object NativeDirectoryPicker {

    fun externalRoot(): String {
        return runCatching {
            Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0"
    }

    /**
     * 将任意路径输入统一规范化为 /storage/emulated/0/.../（或外置卡 /storage/<vol>/.../）
     */
    fun normalizeCanonicalPath(raw: String?): String {
        val extRoot = externalRoot()
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "$extRoot/Documents/"

        var cleaned = trimmed.replace('\\', '/')
        when {
            cleaned.startsWith("primary:", ignoreCase = true) -> {
                val rel = cleaned.substringAfter(':').trim('/')
                cleaned = if (rel.isEmpty()) extRoot else "$extRoot/$rel"
            }
            cleaned.startsWith("home:", ignoreCase = true) -> {
                val rel = cleaned.substringAfter(':').trim('/')
                cleaned = if (rel.isEmpty()) "$extRoot/Documents" else "$extRoot/Documents/$rel"
            }
            cleaned.startsWith("downloads:", ignoreCase = true) -> {
                val rel = cleaned.substringAfter(':').trim('/')
                cleaned = if (rel.isEmpty()) "$extRoot/Download" else "$extRoot/Download/$rel"
            }
            cleaned.startsWith("raw:", ignoreCase = true) -> {
                cleaned = cleaned.substringAfter(':')
            }
        }

        cleaned = cleaned.replace(Regex("/+"), "/")

        cleaned = when {
            cleaned == "/sdcard" || cleaned == "sdcard" -> extRoot
            cleaned.startsWith("/sdcard/", ignoreCase = true) ->
                "$extRoot/" + cleaned.substring("/sdcard/".length)
            cleaned.startsWith("sdcard/", ignoreCase = true) ->
                "$extRoot/" + cleaned.substring("sdcard/".length)
            cleaned.startsWith("/mnt/sdcard/", ignoreCase = true) ->
                "$extRoot/" + cleaned.substring("/mnt/sdcard/".length)
            cleaned.startsWith("/storage/self/primary", ignoreCase = true) ->
                extRoot + cleaned.substring("/storage/self/primary".length)
            cleaned.startsWith("/storage/sdcard0", ignoreCase = true) ->
                extRoot + cleaned.substring("/storage/sdcard0".length)
            !cleaned.startsWith("/") -> "$extRoot/$cleaned"
            else -> cleaned
        }

        val stack = ArrayDeque<String>()
        cleaned.split('/').forEach { seg ->
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(seg)
            }
        }

        val normalized = if (stack.isEmpty()) {
            "$extRoot/"
        } else {
            "/" + stack.joinToString("/") + "/"
        }
        return if (normalized == "/") "$extRoot/" else normalized
    }

    /**
     * 生成用户易读的 /sdcard/.../ 挂载点预览路径
     */
    fun toDisplayMountPath(canonicalPath: String): String {
        val norm = normalizeCanonicalPath(canonicalPath)
        val extRoot = externalRoot()
        return if (norm.startsWith("$extRoot/", ignoreCase = true)) {
            "/sdcard/" + norm.substring(extRoot.length + 1)
        } else {
            norm
        }
    }

    /**
     * 构造 Android 原生系统「文档选择器（Document Picker）」Intent
     */
    fun buildDocumentTreeIntent(initialCanonicalPath: String? = null): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra("android.content.extra.FANCY", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !initialCanonicalPath.isNullOrBlank()) {
            buildInitialDocumentUri(initialCanonicalPath)?.let { initialUri ->
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        return intent
    }

    private fun buildInitialDocumentUri(canonicalPath: String): Uri? {
        return runCatching {
            val norm = normalizeCanonicalPath(canonicalPath).trimEnd('/')
            val extRoot = externalRoot()
            val docId = when {
                norm.equals(extRoot, ignoreCase = true) -> "primary:"
                norm.startsWith("$extRoot/", ignoreCase = true) -> {
                    val rel = norm.substring(extRoot.length + 1)
                    "primary:$rel"
                }
                norm.startsWith("/storage/", ignoreCase = true) -> {
                    val rest = norm.substring("/storage/".length)
                    val vol = rest.substringBefore('/')
                    val rel = rest.substringAfter('/', "")
                    if (vol.isNotBlank() && vol != "emulated" && vol != "self") {
                        "$vol:$rel"
                    } else {
                        null
                    }
                }
                else -> null
            } ?: return null
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        }.getOrNull()
    }

    /**
     * 解析并持久化系统文档选择器返回的 treeUri，转化为规范统一路径 /storage/emulated/0/.../
     */
    fun normalizePickedTreeUri(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) return null
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        return resolveTreeUri(treeUri)
    }

    /**
     * 纯 URI -> 规范路径转换（供 FolderBrowserDialog 与 Document Picker 共用）
     */
    fun resolveTreeUri(treeUri: Uri?): String? {
        if (treeUri == null) return null
        if (treeUri.scheme.equals("file", ignoreCase = true)) {
            val path = treeUri.path ?: return null
            return normalizeCanonicalPath(path)
        }

        val docId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: runCatching {
            DocumentsContract.getDocumentId(treeUri)
        }.getOrNull()

        if (!docId.isNullOrBlank()) {
            val decoded = runCatching { URLDecoder.decode(docId, "UTF-8") }.getOrDefault(docId)
            val extRoot = externalRoot()
            return when {
                decoded.startsWith("primary:", ignoreCase = true) -> {
                    val rel = decoded.substringAfter(':').trim('/')
                    normalizeCanonicalPath(if (rel.isEmpty()) extRoot else "$extRoot/$rel")
                }
                decoded.equals("home", ignoreCase = true) || decoded.startsWith("home:", ignoreCase = true) -> {
                    val rel = decoded.substringAfter(':', "").trim('/')
                    normalizeCanonicalPath(if (rel.isEmpty()) "$extRoot/Documents" else "$extRoot/Documents/$rel")
                }
                decoded.equals("downloads", ignoreCase = true) || decoded.startsWith("downloads:", ignoreCase = true) -> {
                    val rel = decoded.substringAfter(':', "").trim('/')
                    normalizeCanonicalPath(if (rel.isEmpty()) "$extRoot/Download" else "$extRoot/Download/$rel")
                }
                decoded.startsWith("raw:", ignoreCase = true) -> {
                    normalizeCanonicalPath(decoded.substringAfter(':'))
                }
                decoded.contains(":") -> {
                    val volume = decoded.substringBefore(':').trim()
                    val rel = decoded.substringAfter(':').trim('/')
                    if (volume.equals("primary", ignoreCase = true)) {
                        normalizeCanonicalPath(if (rel.isEmpty()) extRoot else "$extRoot/$rel")
                    } else if (volume.isNotEmpty()) {
                        normalizeCanonicalPath(if (rel.isEmpty()) "/storage/$volume" else "/storage/$volume/$rel")
                    } else {
                        null
                    }
                }
                decoded.startsWith("/") -> normalizeCanonicalPath(decoded)
                else -> null
            }
        }

        val rawPath = runCatching { URLDecoder.decode(treeUri.path.orEmpty(), "UTF-8") }.getOrDefault("")
        val treeSeg = rawPath.substringAfter("/tree/", "")
        if (treeSeg.isNotBlank()) {
            return when {
                treeSeg.startsWith("primary:", ignoreCase = true) ->
                    normalizeCanonicalPath(externalRoot() + "/" + treeSeg.substringAfter(':'))
                treeSeg.startsWith("raw:", ignoreCase = true) ->
                    normalizeCanonicalPath(treeSeg.substringAfter(':'))
                treeSeg.startsWith("/") ->
                    normalizeCanonicalPath(treeSeg)
                else -> null
            }
        }
        return null
    }

    /**
     * 根据规范路径自动生成简短目录名称（用于添加到常用快捷目录）
     */
    fun suggestFolderLabel(canonicalPath: String): String {
        val norm = normalizeCanonicalPath(canonicalPath).trimEnd('/')
        val extRoot = externalRoot()
        if (norm.equals(extRoot, ignoreCase = true)) return "内部存储根目录"
        val seg = norm.substringAfterLast('/').trim()
        return when (seg.lowercase()) {
            "documents" -> "系统文档 (Documents)"
            "download", "downloads" -> "系统下载 (Download)"
            "pictures" -> "系统图片 (Pictures)"
            "movies" -> "系统视频 (Movies)"
            "music" -> "系统音频 (Music)"
            "dcim" -> "相册 (DCIM)"
            else -> seg.ifBlank { "自定义目录" }
        }
    }
}
