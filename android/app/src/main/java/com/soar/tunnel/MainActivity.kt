package com.soar.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.soar.tunnel.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var profiles = mutableListOf<ServerProfile>()
    private var activeProfile: ServerProfile? = null
    private var scanJob: Job? = null
    private val scannedHealthyResults = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    private var scannedTotalCount = 0

    private val logBuffer = SpannableStringBuilder()
    private val maxLogLength = 100_000
    private var lastDetectedClipboard = ""

    private val importResolversFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleResolversFileImport(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadProfiles()
        setupTabs()
        setupListeners()
        observeTunnelEvents()
        handleIncomingIntent(intent)
        updateUIState(TunnelProcessManager.isRunning)

        UpdateManager.checkForUpdates(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForProfile()
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

    private fun checkClipboardForProfile() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if (clip.startsWith("soar://", ignoreCase = true) && clip != lastDetectedClipboard) {
                lastDetectedClipboard = clip
                AlertDialog.Builder(this)
                    .setTitle("Import Soar Profile")
                    .setMessage("Detected a Soar profile link in your clipboard:\n\n$clip\n\nWould you like to import it now?")
                    .setPositiveButton("Import") { _, _ ->
                        importFromUriString(clip)
                    }
                    .setNegativeButton("Ignore", null)
                    .show()
            }
        } catch (_: Exception) { }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.layoutTabDashboard.visibility = View.VISIBLE
                        binding.layoutTabResolvers.visibility = View.GONE
                        binding.layoutTabConfig.visibility = View.GONE
                    }
                    1 -> {
                        binding.layoutTabDashboard.visibility = View.GONE
                        binding.layoutTabResolvers.visibility = View.VISIBLE
                        binding.layoutTabConfig.visibility = View.GONE
                    }
                    2 -> {
                        binding.layoutTabDashboard.visibility = View.GONE
                        binding.layoutTabResolvers.visibility = View.GONE
                        binding.layoutTabConfig.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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
        binding.tvProfileSummary.text = "Profile: ${profile.name} ${if (profile.isLocked) "(Locked)" else ""}"
        binding.tvProxyPortBadge.text = ":${profile.port}"
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
                    appendColoredLog("[PROFILE] Created new server profile '${newProf.name}'")
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

            val options = arrayOf(
                "Standard Share (Visible Keys & Domains)",
                "Protected Share (Hide Keys & Domains)"
            )
            AlertDialog.Builder(this)
                .setTitle("Share '${p.name}'")
                .setItems(options) { _, which ->
                    val locked = (which == 1) || p.isLocked
                    val link = ProfileManager.exportProfileUri(p, locked)
                    showQrShareDialog(p.name, link, locked)
                }
                .show()
        }

        binding.btnDeleteProfile.setOnClickListener {
            val p = activeProfile ?: return@setOnClickListener
            if (profiles.size <= 1) {
                Toast.makeText(this, "Cannot delete the only remaining profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (TunnelProcessManager.isRunning) {
                Toast.makeText(this, "Disconnect tunnel before deleting active profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Delete Server Profile")
                .setMessage("Are you sure you want to delete '${p.name}'?")
                .setPositiveButton("Delete") { _, _ ->
                    val idx = profiles.indexOf(p)
                    profiles.remove(p)
                    activeProfile = profiles.getOrNull(idx) ?: profiles.firstOrNull()
                    activeProfile?.let {
                        ProfileManager.setActiveProfileId(this, it.id)
                        applyProfileToUI(it)
                    }
                    ProfileManager.saveProfiles(this, profiles)
                    refreshProfileSpinner()
                    Toast.makeText(this, "Deleted profile '${p.name}'", Toast.LENGTH_SHORT).show()
                    appendColoredLog("[PROFILE] Deleted server profile '${p.name}'")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnImportResolversFile.setOnClickListener {
            importResolversFileLauncher.launch("text/*")
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
            val port = binding.etPort.text.toString().trim().ifBlank { "10850" }
            val proxyUrl = "socks5://127.0.0.1:$port"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SOCKS5 Proxy", proxyUrl))
            Toast.makeText(this, "Copied: $proxyUrl", Toast.LENGTH_SHORT).show()
            appendColoredLog("[CLIPBOARD] Copied SOCKS5 link: $proxyUrl")
        }

        binding.btnOpenTelegram.setOnClickListener {
            val port = binding.etPort.text.toString().trim().ifBlank { "10850" }
            val uri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                appendColoredLog("[TELEGRAM] Launched Telegram with proxy: tg://socks?server=127.0.0.1&port=$port")
            } catch (_: Exception) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Telegram Proxy", uri.toString()))
                Toast.makeText(this, "Telegram not installed. Link copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvClearLogs.setOnClickListener {
            logBuffer.clear()
            binding.tvLogs.text = ""
        }

        binding.btnCopyLogs.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Console Logs", binding.tvLogs.text.toString()))
            Toast.makeText(this, "Copied all logs to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleResolversFileImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val lines = BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
                    .distinct()
                    .toList()
            }

            if (lines.isEmpty()) {
                Toast.makeText(this, "No valid resolver addresses found in file", Toast.LENGTH_SHORT).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle("Import Resolvers (.txt)")
                .setMessage("Found ${lines.size} resolvers in selected file.\n\nChoose import mode:")
                .setPositiveButton("Overwrite") { _, _ ->
                    binding.etResolvers.setText(lines.joinToString("\n"))
                    saveCurrentProfileFromUI()
                    appendColoredLog("[RESOLVER] Overwrote active resolvers with ${lines.size} candidate resolvers from file.")
                    Toast.makeText(this, "Loaded ${lines.size} resolvers", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Append") { _, _ ->
                    val existing = binding.etResolvers.text.toString().split("\n")
                        .map { it.trim() }.filter { it.isNotEmpty() }
                    val merged = (existing + lines).distinct()
                    binding.etResolvers.setText(merged.joinToString("\n"))
                    saveCurrentProfileFromUI()
                    appendColoredLog("[RESOLVER] Appended ${lines.size} candidate resolvers (Total: ${merged.size}).")
                    Toast.makeText(this, "Appended ${lines.size} resolvers", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            appendColoredLog("[ERROR] Failed to read resolvers file: ${e.message}")
        }
    }

    private fun showQrShareDialog(profileName: String, link: String, isLocked: Boolean) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(link, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(32, 24, 32, 16)
            }

            val ivQr = ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(480, 480).apply {
                    bottomMargin = 24
                }
            }
            layout.addView(ivQr)

            val tvNote = TextView(this).apply {
                text = if (isLocked) "🔒 Protected Profile (Keys & Domains hidden)" else "🌐 Standard Profile (Keys & Domains visible)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }
            layout.addView(tvNote)

            AlertDialog.Builder(this)
                .setTitle("Share Profile: $profileName")
                .setView(layout)
                .setPositiveButton("Copy Link") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Soar Profile", link))
                    Toast.makeText(this, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Share via App") { _, _ ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                        putExtra(Intent.EXTRA_SUBJECT, "Soar Tunnel Connection")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Soar Server"))
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Exception) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Soar Profile", link))
            Toast.makeText(this, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromUriString(uriStr: String) {
        val imported = ProfileManager.importProfileUri(uriStr)
        if (imported != null) {
            profiles.add(imported)
            activeProfile = imported
            ProfileManager.saveProfiles(this, profiles)
            refreshProfileSpinner()
            appendColoredLog("[PROFILE] Imported server profile '${imported.name}'")
            Toast.makeText(this, "Imported: ${imported.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Invalid soar:// link format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testResolversLatency() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            scanJob = null
            binding.btnTestResolvers.text = "⚡ Test Latency"
            appendColoredLog("[TEST] Scan stopped by user. Found ${scannedHealthyResults.size} healthy resolvers so far.")
            if (scannedHealthyResults.isNotEmpty()) {
                val sorted = synchronized(scannedHealthyResults) {
                    scannedHealthyResults.sortedBy { it.second }.toList()
                }
                showHealthyResolversDialog(sorted, scannedTotalCount)
            } else {
                Toast.makeText(this, "Scan stopped (no responsive resolvers found yet)", Toast.LENGTH_SHORT).show()
            }
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

        scannedHealthyResults.clear()
        scannedTotalCount = list.size

        val domainsText = binding.etDomains.text.toString().trim()
        val targetDomain = domainsText.split(",", ";", "\n")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("Protected") } ?: ""

        val probeTargetName = if (targetDomain.isNotEmpty()) targetDomain else "google.com"
        binding.btnTestResolvers.text = "⏹ Stop Test"
        Toast.makeText(this, "Testing ${list.size} resolvers for $probeTargetName...", Toast.LENGTH_SHORT).show()
        appendColoredLog("[TEST] Probing ${list.size} resolvers for $probeTargetName in parallel...")

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            val dnsQuery = if (targetDomain.isNotEmpty()) {
                val q = mutableListOf<Byte>(
                    0x12, 0x34,
                    0x01, 0x00,
                    0x00, 0x01,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                )
                for (part in targetDomain.split(".")) {
                    if (part.isNotEmpty()) {
                        q.add(part.length.toByte())
                        for (c in part) {
                            q.add(c.code.toByte())
                        }
                    }
                }
                q.add(0x00)
                q.add(0x00); q.add(0x06) // Type SOA
                q.add(0x00); q.add(0x01) // Class IN
                q.toByteArray()
            } else {
                byteArrayOf(
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
            }

            val chunks = list.chunked(12)

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

                            if (recvPacket.length < 12) {
                                withContext(Dispatchers.Main) {
                                    appendColoredLog("[BAD] $r (short response)")
                                }
                                return@async
                            }

                            val qr = (buf[2].toInt() and 0x80) != 0
                            val ra = (buf[3].toInt() and 0x80) != 0
                            val rcode = buf[3].toInt() and 0x0F
                            val an = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                            val ns = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)

                            if (!qr || !ra || rcode != 0 || (an == 0 && ns == 0)) {
                                withContext(Dispatchers.Main) {
                                    appendColoredLog("[REJECTED] $r (RA=$ra, RCODE=$rcode)")
                                }
                                return@async
                            }

                            scannedHealthyResults.add(Pair(r, elapsed))
                            withContext(Dispatchers.Main) {
                                appendColoredLog("[OK] $r -> ${elapsed}ms")
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                appendColoredLog("[TIMEOUT] $r")
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }

            withContext(Dispatchers.Main) {
                binding.btnTestResolvers.text = "⚡ Test Latency"
                scanJob = null

                if (scannedHealthyResults.isNotEmpty()) {
                    val sorted = synchronized(scannedHealthyResults) {
                        scannedHealthyResults.sortedBy { it.second }.toList()
                    }
                    showHealthyResolversDialog(sorted, scannedTotalCount)
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
                appendColoredLog("[RESOLVER] Saved top ${top5.size} fastest resolvers to profile.")
            }
            .setNeutralButton("Use All Working (${results.size})") { _, _ ->
                val allWorking = results.map { it.first }
                binding.etResolvers.setText(allWorking.joinToString("\n"))
                saveCurrentProfileFromUI()
                Toast.makeText(this, "Applied all ${allWorking.size} working resolvers!", Toast.LENGTH_SHORT).show()
                appendColoredLog("[RESOLVER] Saved all ${allWorking.size} working resolvers to profile.")
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
                "SOAR DNS TUNNEL v1.0.9\n\n" +
                "Attribution & Credits:\n" +
                "Built upon the core Soar DNS tunneling protocol developed and open-sourced by Lantern (github.com/getlantern/soar) under the Apache License 2.0.\n\n" +
                "Ecosystem Enhancements:\n" +
                "• MasterDnsVPN & TheFeed inspired UI/UX\n" +
                "• Hero Dashboard & Live Speedometer telemetry\n" +
                "• Candidate resolver TXT file import\n" +
                "• QR Code profile sharing & clipboard auto-detect\n" +
                "• Multi-domain failover & EWMA latency probing\n" +
                "• Privacy-preserving locked profiles (soar://locked)\n" +
                "• In-app GitHub release updates\n\n" +
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
        appendColoredLog("[START] Starting Soar tunnel service with profile '${p.name}'...")
    }

    private fun stopTunnelService() {
        val serviceIntent = Intent(this, SoarService::class.java).apply {
            action = SoarService.ACTION_STOP
        }
        startService(serviceIntent)
        updateUIState(false)
        appendColoredLog("[STOP] Tunnel service stopped.")
    }

    private fun observeTunnelEvents() {
        TunnelProcessManager.onLogReceived = { logLine ->
            runOnUiThread {
                appendColoredLog(logLine)
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
                    val tx = map["tx"]?.toLongOrNull() ?: 0L
                    val rx = map["rx"]?.toLongOrNull() ?: 0L
                    val total = tx + rx

                    binding.tvHeroRx.text = "$rxRate KB/s"
                    binding.tvHeroTx.text = "$txRate KB/s"
                    binding.tvHeroStreams.text = streams
                    binding.tvHeroTotal.text = formatBytes(total)
                    binding.tvLiveTelemetry.text = "↓ $rxRate KB/s  ↑ $txRate KB/s  ($streams streams)"
                } catch (_: Exception) { }
            }
        }

        TunnelProcessManager.onStatusChanged = { running ->
            runOnUiThread {
                updateUIState(running)
            }
        }
    }

    private fun appendColoredLog(line: String) {
        val color = when {
            line.startsWith("[OK]", true) || line.startsWith("[STATUS]", true) || line.startsWith("[TUNNEL]", true) ->
                ContextCompat.getColor(this, R.color.terminal_green)
            line.startsWith("[TIMEOUT]", true) || line.startsWith("[REJECTED]", true) || line.startsWith("[BAD]", true) || line.startsWith("[ERROR]", true) ->
                ContextCompat.getColor(this, R.color.terminal_red)
            line.startsWith("[WARN]", true) ->
                ContextCompat.getColor(this, R.color.terminal_amber)
            line.startsWith("[TEST]", true) || line.startsWith("[START]", true) || line.startsWith("[TELEMETRY]", true) || line.startsWith("[PROFILE]", true) ->
                ContextCompat.getColor(this, R.color.terminal_cyan)
            line.startsWith("[BANK]", true) || line.startsWith("[RESOLVER]", true) ->
                ContextCompat.getColor(this, R.color.terminal_purple)
            else ->
                ContextCompat.getColor(this, R.color.text_light)
        }

        val start = logBuffer.length
        logBuffer.append(line).append("\n")
        val end = logBuffer.length
        logBuffer.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (logBuffer.length > maxLogLength) {
            logBuffer.delete(0, 20_000)
        }

        binding.tvLogs.text = logBuffer
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun updateUIState(running: Boolean) {
        if (running) {
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnConnect.text = getString(R.string.disconnect)
            binding.btnConnect.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
        } else {
            binding.tvStatus.text = getString(R.string.status_disconnected)
            binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            binding.btnConnect.text = getString(R.string.connect)
            binding.btnConnect.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_accent))
            binding.tvLiveTelemetry.text = "LIVE CONSOLE & TELEMETRY"
            binding.tvHeroRx.text = "0.0 KB/s"
            binding.tvHeroTx.text = "0.0 KB/s"
            binding.tvHeroStreams.text = "0"
            binding.tvHeroTotal.text = "0 MB"
        }
    }
}
