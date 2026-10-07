package com.xeamum.puppyclicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(val versionName: String, val apkUrl: String)

/** Self-updater backed by the latest GitHub Release of [BuildConfig.UPDATE_REPO]. */
object Updater {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        ApiClient.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return@withContext null
            UpdateInfo(version, apk.getString("browser_download_url"))
        }
    }

    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val diff = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    suspend fun download(context: Context, update: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "update.apk")
            ApiClient.http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val body = response.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress(copied.toFloat() / total)
                        }
                    }
                }
            }
            file
        }

    /** Returns false if the user must first allow this app to install updates (settings screen is opened). */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }
}
