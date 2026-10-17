package com.soar.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.soar.tunnel.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var profiles = mutableListOf<ServerProfile>()
    private var activeProfile: ServerProfile? = null
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadProfiles()
        setupListeners()
        observeTunnelEvents()
        handleIncomingIntent(intent)
        updateUIState(TunnelProcessManager.isRunning)

        // Silent update check in background
        UpdateManager.checkForUpdates(this, silent = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && uri.scheme.equals("soar", ignoreCase = true)) {
                importFromUriString(uri.toString())
            }
        }
    }

    private fun loadProfiles() {
        profiles = ProfileManager.loadProfiles(this)
        val activeId = ProfileManager.getActiveProfileId(this)
        activeProfile = profiles.find { it.id == activeId } ?: profiles.firstOrNull()

        refreshProfileSpinner()
    }

    private fun refreshProfileSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles)
        binding.spnProfiles.adapter = adapter

        val idx = profiles.indexOf(activeProfile)
        if (idx >= 0) {
            binding.spnProfiles.setSelection(idx)
        }

        activeProfile?.let { applyProfileToUI(it) }
    }

    private fun applyProfileToUI(profile: ServerProfile) {
        if (profile.isLocked) {
            binding.etServerKey.setText("•••••••••••••••••••••••••••••••• (Protected)")
            binding.etServerKey.isEnabled = false
            binding.etDomains.setText("Protected Domains (Hidden)")
            binding.etDomains.isEnabled = false
        } else {
            binding.etServerKey.setText(profile.serverKey)
            binding.etServerKey.isEnabled = true
            binding.etDomains.setText(profile.domains)
            binding.etDomains.isEnabled = true
        }

        binding.etPort.setText(profile.port.toString())
        binding.etResolvers.setText(profile.resolvers.ifBlank { ProfileManager.DEFAULT_RESOLVERS })
    }

    private fun saveCurrentProfileFromUI() {
        val current = activeProfile ?: return

        if (!current.isLocked) {
            current.serverKey = binding.etServerKey.text.toString().trim()
            current.domains = binding.etDomains.text.toString().trim()
        }

        current.port = binding.etPort.text.toString().toIntOrNull() ?: 10850
        current.resolvers = binding.etResolvers.text.toString().trim()

        ProfileManager.saveProfiles(this, profiles)
        ProfileManager.setActiveProfileId(this, current.id)
    }

    private fun setupListeners() {
        binding.spnProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in profiles.indices) {
                    saveCurrentProfileFromUI()
                    activeProfile = profiles[position]
                    activeProfile?.let {
                        ProfileManager.setActiveProfileId(this@MainActivity, it.id)
                        applyProfileToUI(it)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnAddProfile.setOnClickListener {
            val input = EditText(this).apply { hint = "Server Name (e.g. My Server)" }
            AlertDialog.Builder(this)
                .setTitle("Add New Server Profile")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val name = input.text.toString().trim().ifBlank { "Server ${profiles.size + 1}" }
                    val newProf = ServerProfile(
                        name = name,
                        serverKey = "",
                        domains = "",
                        port = 10850,
                        resolvers = ProfileManager.DEFAULT_RESOLVERS,
                        isLocked = false
                    )
                    profiles.add(newProf)
                    activeProfile = newProf
                    ProfileManager.saveProfiles(this, profiles)
                    refreshProfileSpinner()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnImportLink.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""

            if (clipText.startsWith("soar://", ignoreCase = true)) {
                importFromUriString(clipText)
            } else {
                val input = EditText(this).apply { hint = "soar://profile?... or soar://locked?..." }
                AlertDialog.Builder(this)
                    .setTitle("Import Server Link")
                    .setMessage("Paste your soar:// link:")
                    .setView(input)
                    .setPositiveButton("Import") { _, _ ->
                        importFromUriString(input.text.toString().trim())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.btnShareProfile.setOnClickListener {
            val p = activeProfile ?: return@setOnClickListener
            saveCurrentProfileFromUI()

            val options = arrayOf("Standard Share (Visible Details)", "Protected Share (Hide Domains & Key)")
            AlertDialog.Builder(this)
                .setTitle("Share '${p.name}'")
                .setItems(options) { _, which ->
                    val locked = (which == 1) || p.isLocked
                    val link = ProfileManager.exportProfileUri(p, locked)

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                        putExtra(Intent.EXTRA_SUBJECT, "Soar Tunnel Connection")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Soar Server"))
                }
                .show()
        }

        binding.btnTestResolvers.setOnClickListener {
            testResolversLatency()
        }

        binding.btnCheckUpdate.setOnClickListener {
            UpdateManager.checkForUpdates(this, silent = false)
        }

        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        binding.btnConnect.setOnClickListener {
            if (TunnelProcessManager.isRunning) {
                stopTunnelService()
            } else {
                startTunnelService()
            }
        }

        binding.btnCopyProxy.setOnClickListener {
            val port = binding.etPort.text.toString().trim()
            val proxyUrl = "socks5://127.0.0.1:$port"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SOCKS5 Proxy", proxyUrl))
            Toast.makeText(this, "Copied: $proxyUrl", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenTelegram.setOnClickListener {
            val port = binding.etPort.text.toString().trim()
            val uri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                Toast.makeText(this, "Telegram is not installed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvClearLogs.setOnClickListener {
            binding.tvLogs.text = ""
        }
    }

    private fun importFromUriString(uriStr: String) {
        val imported = ProfileManager.importProfileUri(uriStr)
        if (imported != null) {
            profiles.add(imported)
            activeProfile = imported
            ProfileManager.saveProfiles(this, profiles)
            refreshProfileSpinner()
            Toast.makeText(this, "Imported: ${imported.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Invalid soar:// link format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testResolversLatency() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            scanJob = null
            binding.btnTestResolvers.text = "Test Resolvers"
            binding.tvLogs.append("\n[TEST] Scan stopped by user.\n")
            Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
            return
        }

        val rawResolvers = binding.etResolvers.text.toString().trim()
        val list = rawResolvers.split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (list.isEmpty()) {
            Toast.makeText(this, "No resolvers to test", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestResolvers.text = "Stop Test"
        Toast.makeText(this, "Testing ${list.size} resolvers in parallel...", Toast.LENGTH_SHORT).show()
        binding.tvLogs.append("\n[TEST] Probing ${list.size} resolvers concurrently...\n")

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            // Standard DNS Query for google.com (A record)
            val dnsQuery = byteArrayOf(
                0x12, 0x34,
                0x01, 0x00,
                0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x06, 'g'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'g'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0x00,
                0x00, 0x01,
                0x00, 0x01
            )

            val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
            val chunks = list.chunked(10)

            for (chunk in chunks) {
                if (!isActive) break
                val jobs = chunk.map { r ->
                    async {
                        if (!isActive) return@async
                        val host = if (r.contains(":")) r.substringBefore(":") else r
                        val port = if (r.contains(":")) r.substringAfter(":").toIntOrNull() ?: 53 else 53

                        val startTime = System.currentTimeMillis()
                        try {
                            val socket = DatagramSocket()
                            socket.soTimeout = 900
                            val address = InetAddress.getByName(host)
                            val packet = DatagramPacket(dnsQuery, dnsQuery.size, address, port)
                            socket.send(packet)

                            val buf = ByteArray(512)
                            val recvPacket = DatagramPacket(buf, buf.size)
                            socket.receive(recvPacket)
                            val elapsed = System.currentTimeMillis() - startTime
                            socket.close()

                            results.add(Pair(r, elapsed))
                            withContext(Dispatchers.Main) {
                                binding.tvLogs.append("[OK] $r -> ${elapsed}ms\n")
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                binding.tvLogs.append("[TIMEOUT] $r\n")
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }

            results.sortBy { it.second }

            withContext(Dispatchers.Main) {
                binding.btnTestResolvers.text = "Test Resolvers"
                scanJob = null

                if (results.isNotEmpty()) {
                    showHealthyResolversDialog(results, list.size)
                } else {
                    Toast.makeText(this@MainActivity, "No responsive resolvers found", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showHealthyResolversDialog(results: List<Pair<String, Long>>, totalTested: Int) {
        val messageBuilder = StringBuilder()
        messageBuilder.append("Found ${results.size} of $totalTested responsive resolvers:\n\n")

        for (i in 0 until minOf(results.size, 15)) {
            val (addr, ping) = results[i]
            val badge = when {
                ping < 120 -> "🟢"
                ping < 300 -> "🟡"
                else -> "🟠"
            }
            messageBuilder.append("$badge $addr (${ping}ms)\n")
        }
        if (results.size > 15) {
            messageBuilder.append("... and ${results.size - 15} more.\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Healthy Resolvers")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("Use Fastest 5 (Recommended)") { _, _ ->
                val top5 = results.take(5).map { it.first }
                binding.etResolvers.setText(top5.joinToString("\n"))
                saveCurrentProfileFromUI()
                Toast.makeText(this, "Applied top ${top5.size} fastest resolvers!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Use All Working (${results.size})") { _, _ ->
                val allWorking = results.map { it.first }
                binding.etResolvers.setText(allWorking.joinToString("\n"))
                saveCurrentProfileFromUI()
                Toast.makeText(this, "Applied all ${allWorking.size} working resolvers!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Copy List") { _, _ ->
                val allWorking = results.map { it.first }.joinToString("\n")
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Healthy Resolvers", allWorking))
                Toast.makeText(this, "Copied ${results.size} healthy resolvers to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Soar DNS Tunnel")
            .setMessage(
                "SOAR DNS TUNNEL v1.0.3\n\n" +
                "Protocol Attribution & Credits:\n" +
                "Built upon the core Soar DNS tunneling protocol developed and open-sourced by Lantern (github.com/getlantern/soar) under the Apache License 2.0.\n\n" +
                "Ecosystem Enhancements:\n" +
                "• Multi-domain racing and automatic failover\n" +
                "• Dynamic resolver latency testing\n" +
                "• Privacy-preserving locked profiles (soar://locked)\n" +
                "• In-app updates via GitHub Releases\n\n" +
                "Repository: github.com/mahdigholamipak/soar-tunnel"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startTunnelService() {
        val p = activeProfile ?: return
        saveCurrentProfileFromUI()

        if (p.serverKey.isEmpty()) {
            Toast.makeText(this, "Please enter Server Public Key", Toast.LENGTH_SHORT).show()
            return
        }
        if (p.domains.isEmpty()) {
            Toast.makeText(this, "Please enter at least one tunnel domain", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, SoarService::class.java).apply {
            action = SoarService.ACTION_START
            putExtra(SoarService.EXTRA_KEY, p.serverKey)
            putExtra(SoarService.EXTRA_DOMAINS, p.domains)
            putExtra(SoarService.EXTRA_PORT, p.port)
            putExtra(SoarService.EXTRA_RESOLVERS, p.resolvers)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUIState(true)
    }

    private fun stopTunnelService() {
        val serviceIntent = Intent(this, SoarService::class.java).apply {
            action = SoarService.ACTION_STOP
        }
        startService(serviceIntent)
        updateUIState(false)
    }

    private fun observeTunnelEvents() {
        TunnelProcessManager.onLogReceived = { logLine ->
            runOnUiThread {
                binding.tvLogs.append(logLine + "\n")
                binding.scrollLogs.post {
                    binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        TunnelProcessManager.onTelemetryReceived = { telemetryLine ->
            runOnUiThread {
                try {
                    val parts = telemetryLine.removePrefix("[TELEMETRY]").trim().split(" ")
                    val map = parts.associate {
                        val kv = it.split("=")
                        if (kv.size == 2) kv[0] to kv[1] else "" to ""
                    }
                    val txRate = map["tx_rate"] ?: "0.0"
                    val rxRate = map["rx_rate"] ?: "0.0"
                    val streams = map["streams"] ?: "0"
                    binding.tvLiveTelemetry.text = "↓ ${rxRate} KB/s  ↑ ${txRate} KB/s  (${streams} streams)"
                } catch (e: Exception) { }
            }
        }

        TunnelProcessManager.onStatusChanged = { running ->
            runOnUiThread {
                updateUIState(running)
            }
        }
    }

    private fun updateUIState(running: Boolean) {
        if (running) {
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.viewStatusDot.setBackgroundColor(getColor(R.color.status_green))
            binding.btnConnect.text = getString(R.string.disconnect)
            binding.btnConnect.setBackgroundColor(getColor(R.color.status_red))
        } else {
            binding.tvStatus.text = getString(R.string.status_disconnected)
            binding.viewStatusDot.setBackgroundColor(getColor(R.color.status_red))
            binding.btnConnect.text = getString(R.string.connect)
            binding.btnConnect.setBackgroundColor(getColor(R.color.primary_accent))
            binding.tvLiveTelemetry.text = "LIVE CONSOLE & TELEMETRY"
        }
    }
}
