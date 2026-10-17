package com.soar.tunnel

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val GITHUB_REPO = "mahdigholamipak/soar-tunnel"

    private fun getAppVersion(activity: Activity): String {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pInfo.versionName ?: "1.0.3"
        } catch (e: Exception) {
            "1.0.3"
        }
    }

    fun checkForUpdates(activity: Activity, silent: Boolean = false) {
        val currentVersion = getAppVersion(activity)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "SoarAndroidApp/1.0")

                if (conn.responseCode != 200) {
                    if (!silent) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Failed to check updates (HTTP ${conn.responseCode})", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val tagName = json.optString("tag_name", "").trim()
                val body = json.optString("body", "Bug fixes and performance improvements.")
                val remoteVer = tagName.removePrefix("v").trim()

                // Find APK asset
                var apkDownloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(remoteVer, currentVersion)

                withContext(Dispatchers.Main) {
                    if (hasUpdate && apkDownloadUrl.isNotBlank()) {
                        showUpdateDialog(activity, tagName, body, apkDownloadUrl)
                    } else if (!silent) {
                        Toast.makeText(activity, "You have the latest version (v$currentVersion)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Check update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val cParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (e: Exception) { }
        return false
    }

    private fun showUpdateDialog(
        activity: Activity,
        newVersion: String,
        notes: String,
        downloadUrl: String
    ) {
        AlertDialog.Builder(activity)
            .setTitle("New Update Available ($newVersion)")
            .setMessage("A new release of Soar Tunnel is available on GitHub.\n\n$notes\n\nWould you like to download and install it?")
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallApk(activity, downloadUrl, newVersion)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstallApk(activity: Activity, downloadUrl: String, version: String) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("Downloading Soar Tunnel $version...")
            isIndeterminate = false
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val cacheDir = activity.cacheDir
            val apkFile = File(cacheDir, "soar-update.apk")
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()

                val fileLength = conn.contentLength
                val input = conn.inputStream
                val output = FileOutputStream(apkFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = ((total * 100) / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            progressDialog.progress = progress
                        }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    launchInstallIntent(activity, apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchInstallIntent(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
