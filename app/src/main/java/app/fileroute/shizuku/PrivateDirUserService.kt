package app.fileroute.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 特权进程（ADB Shell UID 2000）中的文件服务：
 * 1. 自动展开微信 8.0+ 全部真实下载位置：
 *    - /Android/data/com.tencent.mm/MicroMsg/Download
 *    - /Android/data/com.tencent.mm/MicroMsg/<32位账号哈希>/attachment （微信聊天点击 PDF/文档的真实落盘目录！）
 *    - /Android/data/com.tencent.mm/MicroMsg/<32位账号哈希>/openapi & record & Download
 *    - 兼容小米/HyperOS 双开空间 (/storage/emulated/999)
 * 2. 通过 ParcelFileDescriptor 将私有文件句柄安全递交给主 App 进程写入 /storage/emulated/0/Documents/，
 *    彻底避免 UID 2000 直接写公共目录导致文件管理器不可见的问题。
 */
class PrivateDirUserService : IPrivateDirService.Stub {

    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    private val ignoredExtensions = setOf(
        "tmp", "crdownload", "part", "download", "pending", "bak", "nomedia",
        "log", "xlog", "db", "db-wal", "db-shm", "dat", "cfg", "bin", "so",
        "dex", "odex", "vdex", "art", "ini", "lock", "crypt", "msg", "thumb",
        "amr", "silk", "slk", "aud", "idx", "index", "pb", "mmkv", "crc"
    )

    private val skipDirNames = setOf(
        "image", "image2", "avatar", "voice", "voice2", "video", "sfs", "sns",
        "emoji", "webview", "xweb", "wxacache", "appbrand", "brandicon",
        "checkresupdate", "crash", "xlog", "tinker", "fts", "tmp", "temp",
        "finder", "live", "bizimg", "favorite", "vusericon", "wallet",
        "package", "game", "miniprogram", "webcompt", "flutter", "matrix",
        "voip", "ilink", "wxa", "pag", "res", "dex", "oat"
    )

    private val strictCacheUserExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "wps", "et", "dps",
        "txt", "md", "csv", "rtf", "epub", "mobi", "azw3", "caj", "ofd", "pages",
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "apks", "xapk",
        "mp4", "mkv", "mov", "avi", "mp3", "m4a", "wav", "flac", "dwg", "psd", "ai"
    )

    private val hexAccountRegex = Regex("^[0-9a-fA-F]{16,40}$")

    override fun destroy() {
        exitProcess(0)
    }

    private fun expandWeChatAndChatDirs(inputDirs: Array<out String>?): List<Pair<File, Boolean>> {
        val expanded = linkedMapOf<String, Pair<File, Boolean>>()

        fun addIfDir(dir: File, strictDocOnly: Boolean = false) {
            runCatching {
                if (dir.exists() && dir.isDirectory) {
                    val existing = expanded[dir.absolutePath]
                    if (existing == null || (existing.second && !strictDocOnly)) {
                        expanded[dir.absolutePath] = dir to strictDocOnly
                    }
                }
            }
        }

        fun expandMicroMsgRoot(microMsgDir: File) {
            if (!microMsgDir.exists() || !microMsgDir.isDirectory) return
            addIfDir(File(microMsgDir, "Download"))
            addIfDir(File(microMsgDir, "WeiXin"))
            addIfDir(File(microMsgDir, "attachment"))
            addIfDir(File(microMsgDir, "attachment2"))

            val children = runCatching { microMsgDir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (!child.isDirectory) continue
                val lower = child.name.lowercase(Locale.ROOT)
                if (lower in skipDirNames || lower.startsWith(".")) continue
                // 微信 8.0+ / 平板 HD 版在 32 位账号哈希目录下使用 attachment / attachment2 / Download / openapi / record / file
                if (hexAccountRegex.matches(child.name) || child.name.length >= 16) {
                    addIfDir(File(child, "attachment"))
                    addIfDir(File(child, "attachment2"))
                    addIfDir(File(child, "openapi"))
                    addIfDir(File(child, "record"))
                    addIfDir(File(child, "Download"))
                    addIfDir(File(child, "file"))
                    addIfDir(File(child, "files"))
                }
            }
        }

        val storageRoots = linkedSetOf("/storage/emulated/0")
        runCatching {
            val emulatedBase = File("/storage/emulated")
            emulatedBase.listFiles()?.forEach { userDir ->
                if (userDir.isDirectory && userDir.name.all { it.isDigit() }) {
                    storageRoots.add(userDir.absolutePath)
                }
            }
        }
        val dualAppRoot = File("/storage/emulated/999")
        if (dualAppRoot.exists() && dualAppRoot.isDirectory) {
            storageRoots.add("/storage/emulated/999")
        }

        for (root in storageRoots) {
            expandMicroMsgRoot(File("$root/Android/data/com.tencent.mm/MicroMsg"))
            addIfDir(File("$root/Android/data/com.tencent.mm/files/Download"))
            addIfDir(File("$root/Android/data/com.tencent.mm/files/attachment"))
            addIfDir(File("$root/Android/data/com.tencent.mm/files/WeiXin"))
            addIfDir(File("$root/Android/data/com.tencent.mm/files"), strictDocOnly = true)
            addIfDir(File("$root/Android/data/com.tencent.mm/cache"), strictDocOnly = true)
            addIfDir(File("$root/Download/WeiXin"))
            addIfDir(File("$root/Documents/WeiXin"))
            addIfDir(File("$root/Tencent/MicroMsg/Download"))
            addIfDir(File("$root/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"))
            addIfDir(File("$root/Android/data/com.tencent.mobileqq/files/QQfile_recv"))
            addIfDir(File("$root/Android/data/com.tencent.tim/Tencent/TIMfile_recv"))
            addIfDir(File("$root/Android/data/com.alibaba.android.rimet/files/Download"))
            addIfDir(File("$root/Android/data/com.ss.android.lark/files/Download"))
        }

        if (inputDirs != null) {
            for (rawDir in inputDirs) {
                if (rawDir.isNullOrBlank()) continue
                val f = File(rawDir.trimEnd('/'))
                if (f.name.equals("MicroMsg", ignoreCase = true)) {
                    expandMicroMsgRoot(f)
                } else {
                    addIfDir(f)
                }
            }
        }
        return expanded.values.toList()
    }

    override fun listRecentFilesJson(dirPaths: Array<out String>?, sinceEpochMs: Long): String {
        val dirs = expandWeChatAndChatDirs(dirPaths)
        val seen = hashSetOf<String>()
        val items = ArrayList<JSONObject>()

        for ((root, strictDocOnly) in dirs) {
            walkDir(
                dir = root,
                sinceEpochMs = sinceEpochMs,
                depth = 0,
                maxDepth = 5,
                strictDocOnly = strictDocOnly,
                seen = seen,
                out = items
            )
        }

        items.sortByDescending { it.optLong("lastModified", 0L) }
        val result = JSONArray()
        val limit = items.size.coerceAtMost(400)
        for (i in 0 until limit) {
            result.put(items[i])
        }
        return result.toString()
    }

    override fun diagnoseWeChatDirsJson(baseRoots: Array<out String>?): String {
        val dirs = expandWeChatAndChatDirs(baseRoots)
        val arr = JSONArray()
        for ((dir, strictDocOnly) in dirs) {
            var newestName = ""
            var newestMod = 0L
            val seen = hashSetOf<String>()
            val bucket = ArrayList<JSONObject>()
            walkDir(
                dir = dir,
                sinceEpochMs = 0L,
                depth = 0,
                maxDepth = 5,
                strictDocOnly = strictDocOnly,
                seen = seen,
                out = bucket
            )
            for (obj in bucket) {
                val mod = obj.optLong("lastModified", 0L)
                if (mod > newestMod) {
                    newestMod = mod
                    newestName = obj.optString("name", "")
                }
            }
            arr.put(
                JSONObject().apply {
                    put("dirPath", dir.absolutePath)
                    put("fileCount", bucket.size)
                    put("latestFileName", newestName)
                    put("latestModified", newestMod)
                }
            )
        }
        return arr.toString()
    }

    private fun walkDir(
        dir: File,
        sinceEpochMs: Long,
        depth: Int,
        maxDepth: Int,
        strictDocOnly: Boolean,
        seen: MutableSet<String>,
        out: MutableList<JSONObject>
    ) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (name.lowercase(Locale.ROOT) in skipDirNames) continue
                if (depth < maxDepth) {
                    walkDir(child, sinceEpochMs, depth + 1, maxDepth, strictDocOnly, seen, out)
                }
            } else if (child.isFile) {
                val len = child.length()
                val mod = child.lastModified()
                if (len <= 0L || (sinceEpochMs > 0L && mod < sinceEpochMs)) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                // 微信缓存里无后缀的内部数据库/缩略图文件直接跳过，仅保留带正常文件后缀的用户文档/压缩包/媒体/安装包
                if (ext.isEmpty() || ext in ignoredExtensions) continue
                if (strictDocOnly && ext !in strictCacheUserExtensions) continue
                val abs = child.absolutePath
                if (seen.add(abs)) {
                    out.add(
                        JSONObject().apply {
                            put("path", abs)
                            put("name", name)
                            put("size", len)
                            put("lastModified", mod)
                        }
                    )
                }
            }
        }
    }

    override fun openReadFd(sourcePath: String?): ParcelFileDescriptor? {
        if (sourcePath.isNullOrBlank()) return null
        return runCatching {
            val src = File(sourcePath)
            if (!src.exists() || !src.isFile) return null
            ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    override fun deleteFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return runCatching {
            val f = File(filePath)
            if (!f.exists()) true else f.delete()
        }.getOrDefault(false)
    }

    override fun transferFile(sourcePath: String?, targetPath: String?, copyOnly: Boolean): Boolean {
        if (sourcePath.isNullOrBlank() || targetPath.isNullOrBlank()) return false
        return runCatching {
            val src = File(sourcePath)
            if (!src.exists() || !src.isFile) return false
            val expectedLen = src.length()
            val dst = File(targetPath)
            dst.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            var copied = 0L
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        copied += read
                    }
                    output.fd.sync()
                }
            }
            if (copied != expectedLen || !dst.exists()) return false
            runCatching {
                dst.setReadable(true, false)
                dst.setWritable(true, false)
            }
            if (!copyOnly) {
                src.delete()
            }
            true
        }.getOrDefault(false)
    }

    override fun computeFileSha256(filePath: String?): String? {
        if (filePath.isNullOrBlank()) return null
        return runCatching {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return null
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    override fun pathExists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).exists() }.getOrDefault(false)
    }
}
