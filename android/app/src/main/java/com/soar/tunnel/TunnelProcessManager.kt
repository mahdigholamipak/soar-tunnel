package com.soar.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

object TunnelProcessManager {
    private const val TAG = "TunnelProcessManager"
    private var process: Process? = null
    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + supervisorJob)

    var onLogReceived: ((String) -> Unit)? = null
    var onStatusChanged: ((Boolean) -> Unit)? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    fun start(
        context: Context,
        serverKey: String,
        domains: String,
        port: Int,
        resolvers: String
    ): Boolean {
        if (isRunning) return true

        val filesDir = context.filesDir
        val configFile = File(filesDir, "client_config.toml")
        val resolversFile = File(filesDir, "client_resolvers.txt")

        // 1. Prepare resolvers file
        if (resolvers.isNotBlank()) {
            resolversFile.writeText(resolvers)
        } else {
            // Write default resolvers if missing
            if (!resolversFile.exists()) {
                resolversFile.writeText(
                    "1.1.1.1:53\n1.0.0.1:53\n8.8.8.8:53\n8.8.4.4:53\n" +
                    "178.22.122.100:53\n185.51.200.2:53\n10.202.10.202:53\n"
                )
            }
        }

        // 2. Prepare TOML config
        val domainList = domains.split(",", ";").map { "\"${it.trim()}\"" }.joinToString(", ")
        val tomlContent = """
            DOMAINS = [$domainList]
            SERVER_PUBLIC_KEY = "${serverKey.trim()}"
            LISTEN_IP = "127.0.0.1"
            LISTEN_PORT = $port
            RESOLVERS_FILE = "${resolversFile.absolutePath}"
            STREAM_IDLE_TIMEOUT_SECONDS = 60.0
            LOG_LEVEL = "INFO"
            COUNTRY = "IR"
        """.trimIndent()
        configFile.writeText(tomlContent)

        // 3. Locate or extract binary
        val binaryFile = getOrExtractBinary(context)
        if (binaryFile == null || !binaryFile.exists()) {
            log("[ERROR] soar-client binary could not be found or extracted.")
            return false
        }

        try {
            binaryFile.setExecutable(true, false)
        } catch (e: Exception) {
            log("[WARN] Could not chmod binary: ${e.message}")
        }

        log("[START] Launching Soar tunnel process...")
        try {
            val pb = ProcessBuilder(
                binaryFile.absolutePath,
                "-config", configFile.absolutePath
            )
            pb.directory(filesDir)
            pb.redirectErrorStream(true)

            process = pb.start()
            onStatusChanged?.invoke(true)

            // Start log reading coroutine
            scope.launch {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { log(it) }
                    }
                } catch (e: Exception) {
                    log("[PROCESS] Stream closed: ${e.message}")
                } finally {
                    reader.close()
                    onStatusChanged?.invoke(false)
                }
            }

            return true
        } catch (e: Exception) {
            log("[ERROR] Failed to start tunnel process: ${e.message}")
            stop()
            return false
        }
    }

    fun stop() {
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
        onStatusChanged?.invoke(false)
        log("[STOP] Tunnel process terminated.")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLogReceived?.invoke(msg)
    }

    private fun getOrExtractBinary(context: Context): File? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val archName = when {
            abi.contains("arm64") -> "soar-client-arm64"
            abi.contains("armeabi") -> "soar-client-arm"
            abi.contains("x86_64") -> "soar-client-amd64"
            else -> "soar-client-arm64"
        }

        val targetFile = File(context.filesDir, "soar-client")
        // Check if already extracted
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }

        // Try extracting from assets
        try {
            context.assets.open(archName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setExecutable(true, false)
            return targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Asset $archName not found, checking lib directory...")
        }

        // Check native library dir (if packaged as .so)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libFile = File(nativeDir, "libsoar.so")
        if (libFile.exists()) {
            return libFile
        }

        return targetFile
    }
}
