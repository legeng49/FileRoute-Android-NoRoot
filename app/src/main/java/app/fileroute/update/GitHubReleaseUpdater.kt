package app.fileroute.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import app.fileroute.data.RuntimeStateStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val repo: String,
    val tagName: String,
    val releaseName: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String?,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long,
    val isNewer: Boolean
)

object GitHubReleaseUpdater {

    fun getCurrentVersionName(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "3.2.1-android17"
        }.getOrDefault("3.2.1-android17")
    }

    fun normalizeRepo(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "legeng49/FileRoute-Android-NoRoot"
        val regex = Regex("github\\.com/([^/\\s]+/[^/\\s#?]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].removeSuffix(".git").trimEnd('/')
        }
        return trimmed.trim('/')
    }

    private fun parseVersionTuple(versionStr: String): List<Int> {
        val cleaned = versionStr
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return cleaned.split('.').map { seg ->
            seg.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun isRemoteNewer(currentVersion: String, remoteTag: String): Boolean {
        val curr = parseVersionTuple(currentVersion)
        val remote = parseVersionTuple(remoteTag)
        val maxLen = maxOf(curr.size, remote.size, 3)
        for (i in 0 until maxLen) {
            val c = curr.getOrElse(i) { 0 }
            val r = remote.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun checkLatestRelease(
        context: Context,
        repoInput: String = RuntimeStateStore.githubUpdateRepo
    ): Result<GitHubReleaseInfo> {
        return runCatching {
            val repo = normalizeRepo(repoInput)
            val currentVer = getCurrentVersionName(context)
            val jsonObj = fetchReleaseJson("https://api.github.com/repos/$repo/releases/latest")
                ?: fetchFirstReleaseFromList("https://api.github.com/repos/$repo/releases?per_page=1")
                ?: error("仓库 $repo 暂未发布任何 Release 版本")

            val tagName = jsonObj.optString("tag_name").trim().ifBlank { "unknown" }
            val releaseName = jsonObj.optString("name").trim().ifBlank { tagName }
            val notes = jsonObj.optString("body").trim()
            val htmlUrl = jsonObj.optString("html_url").trim()
                .ifBlank { "https://github.com/$repo/releases" }

            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = 0L
            val assets = jsonObj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            GitHubReleaseInfo(
                repo = repo,
                tagName = tagName,
                releaseName = releaseName,
                notes = notes,
                htmlUrl = htmlUrl,
                apkName = apkName,
                apkDownloadUrl = apkUrl,
                apkSizeBytes = apkSize,
                isNewer = isRemoteNewer(currentVer, tagName)
            )
        }
    }

    private fun fetchReleaseJson(endpoint: String): JSONObject? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FileRoute-Android-Updater/3.1")
        }
        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) error("GitHub API 返回 HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFirstReleaseFromList(endpoint: String): JSONObject? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FileRoute-Android-Updater/3.1")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("GitHub Releases 列表返回 HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() > 0) arr.optJSONObject(0) else null
        } finally {
            conn.disconnect()
        }
    }

    fun downloadApk(
        context: Context,
        release: GitHubReleaseInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> {
        return runCatching {
            val downloadUrl = release.apkDownloadUrl ?: error("该 Release 暂未附带 .apk 安装包，请通过浏览器查看")
            val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            if (!outDir.exists()) outDir.mkdirs()
            val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = File(outDir, "FileRoute-$safeTag.apk")
            if (targetFile.exists()) targetFile.delete()

            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FileRoute-Android-Updater/3.1")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("APK 下载失败（HTTP $code）")
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                val buffer = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val progress = if (totalBytes > 0L) {
                                (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            onProgress(progress, downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }
            targetFile
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            return false
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }

    fun openReleaseInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
