package app.fileroute.shizuku

import android.content.Context
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
 * 直接使用标准 java.io.File 读取 /Android/data/com.tencent.mm/MicroMsg/Download 等聊天私有目录，
 * 避免命令行拼接导致的中文文件名/空格转义问题。
 */
class PrivateDirUserService : IPrivateDirService.Stub {

    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    private val ignoredExtensions = setOf("tmp", "crdownload", "part", "download", "pending", "bak", "nomedia")

    override fun destroy() {
        exitProcess(0)
    }

    override fun listRecentFilesJson(dirPaths: Array<out String>?, sinceEpochMs: Long): String {
        val result = JSONArray()
        if (dirPaths == null) return result.toString()
        val seen = hashSetOf<String>()

        for (rawDir in dirPaths) {
            if (rawDir.isNullOrBlank()) continue
            val root = File(rawDir)
            if (!root.exists() || !root.isDirectory) continue
            walkDir(root, sinceEpochMs, depth = 0, maxDepth = 2, seen = seen, out = result)
        }
        return result.toString()
    }

    private fun walkDir(
        dir: File,
        sinceEpochMs: Long,
        depth: Int,
        maxDepth: Int,
        seen: MutableSet<String>,
        out: JSONArray
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (depth < maxDepth) {
                    walkDir(child, sinceEpochMs, depth + 1, maxDepth, seen, out)
                }
            } else if (child.isFile) {
                val len = child.length()
                val mod = child.lastModified()
                if (len <= 0L || mod < sinceEpochMs) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in ignoredExtensions) continue
                val abs = child.absolutePath
                if (seen.add(abs)) {
                    out.put(
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
