package com.soar.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object TunnelProcessManager {
    private const val TAG = "TunnelProcessManager"
    private var process: Process? = null
    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + supervisorJob)

    var onLogReceived: ((String) -> Unit)? = null
    var onTelemetryReceived: ((String) -> Unit)? = null
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

        // Clean up legacy files from old versions if any
        val legacyBinary = File(filesDir, "soar-client")
        if (legacyBinary.exists()) {
            try { legacyBinary.delete() } catch (e: Exception) { }
        }

        val configFile = File(filesDir, "client_config.toml")
        val resolversFile = File(filesDir, "client_resolvers.txt")

        // 1. Prepare resolvers file
        val cleanResolvers = resolvers.split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        if (cleanResolvers.isNotBlank()) {
            resolversFile.writeText(cleanResolvers + "\n")
        } else {
            // Write default resolvers if missing
            resolversFile.writeText(
                "1.1.1.1:53\n1.0.0.1:53\n8.8.8.8:53\n8.8.4.4:53\n" +
                "178.22.122.100:53\n185.51.200.2:53\n10.202.10.202:53\n217.218.155.155:53\n"
            )
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

        // 3. Locate native library executable
        val binaryFile = getOrExtractBinary(context)
        if (binaryFile == null || !binaryFile.exists()) {
            log("[ERROR] soar native library could not be located in nativeLibraryDir.")
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
                "-config", configFile.absolutePath,
                "-zones", domains.trim(),
                "-pubkey", serverKey.trim(),
                "-resolvers", resolversFile.absolutePath,
                "-listen", "127.0.0.1:$port"
            )
            pb.directory(filesDir)
            pb.redirectErrorStream(true)

            process = pb.start()
            onStatusChanged?.invoke(true)

            // Start log and telemetry reading coroutine
            scope.launch {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { l ->
                            if (l.startsWith("[TELEMETRY]")) {
                                onTelemetryReceived?.invoke(l)
                            } else {
                                log(l)
                            }
                        }
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
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libFile = File(nativeDir, "libsoar.so")
        if (libFile.exists()) {
            try {
                libFile.setExecutable(true, false)
            } catch (e: Exception) { }
            log("[INIT] Located native library executable: ${libFile.absolutePath}")
            return libFile
        }

        // Secondary check: look for any candidate in nativeLibraryDir
        val filesInLib = nativeDir.listFiles()
        if (filesInLib != null) {
            for (f in filesInLib) {
                if (f.name.contains("soar") || f.name.endsWith(".so")) {
                    try { f.setExecutable(true, false) } catch (e: Exception) { }
                    log("[INIT] Located candidate in nativeLibraryDir: ${f.absolutePath}")
                    return f
                }
            }
        }

        log("[ERROR] Native executable libsoar.so not found in ${nativeDir.absolutePath}. Files present: ${filesInLib?.map { it.name } ?: "none"}")
        return null
    }
}
