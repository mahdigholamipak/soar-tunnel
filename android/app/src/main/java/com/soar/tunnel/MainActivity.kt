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
    private var isSpinnerInitialized = false

    private var scanJob: Job? = null
    private val scannedHealthyResults = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    private var scannedTotalCount = 0

    // Ring-buffer for console logs to prevent UI thread lockups and memory bloat
    private val logLines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var isLogRenderPending = false
    companion object {
        private const val MAX_LOG_LINES = 250
    }
    private var lastDetectedClipboard = ""

    private var resolverItems = mutableListOf<ResolverItem>()
    private lateinit var resolverAdapter: ResolverAdapter
    private var currentResolverFilter = "ALL"

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

        setupResolversView()
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

    override fun onDestroy() {
        scanJob?.cancel()
        TunnelProcessManager.onLogReceived = null
        TunnelProcessManager.onTelemetryReceived = null
        TunnelProcessManager.onStatusChanged = null
        super.onDestroy()
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
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("⚡ Dashboard"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("🌐 Resolvers"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("⚙️ Config & Logs"))

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

        // Ensure Tab 0 is selected by default
        binding.tabLayout.getTabAt(0)?.select()
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
        val resText = profile.resolvers.ifBlank { ProfileManager.DEFAULT_RESOLVERS }
        binding.etResolvers.setText(resText)
        parseResolversFromText(resText)
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
        if (resolverItems.isNotEmpty()) {
            current.resolvers = resolverItems.joinToString("\n") { it.addr }
        } else {
            current.resolvers = binding.etResolvers.text.toString().trim()
        }
        binding.etResolvers.setText(current.resolvers)

        ProfileManager.saveProfiles(this, profiles)
        ProfileManager.setActiveProfileId(this, current.id)
    }

    private fun setupListeners() {
        binding.spnProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isSpinnerInitialized) {
                    isSpinnerInitialized = true
                    return
                }
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
                    appendThrottledLog("[PROFILE] Created new server profile '${newProf.name}'")
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
            if (TunnelProcessManager.isRunning) {
                Toast.makeText(this, "Disconnect tunnel before modifying active profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (profiles.size <= 1) {
                AlertDialog.Builder(this)
                    .setTitle("Reset Profile")
                    .setMessage("This is your only server profile. Would you like to reset it to default empty settings?")
                    .setPositiveButton("Reset") { _, _ ->
                        p.name = "Default Server"
                        p.serverKey = ""
                        p.domains = ""
                        p.port = 10850
                        p.resolvers = ProfileManager.DEFAULT_RESOLVERS
                        p.isLocked = false
                        ProfileManager.saveProfiles(this, profiles)
                        applyProfileToUI(p)
                        refreshProfileSpinner()
                        Toast.makeText(this, "Reset profile to default", Toast.LENGTH_SHORT).show()
                        appendThrottledLog("[PROFILE] Reset active profile to default settings.")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
                    appendThrottledLog("[PROFILE] Deleted server profile '${p.name}'")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnImportResolversFile.setOnClickListener {
            importResolversFileLauncher.launch("text/*")
        }

        binding.btnClearResolvers.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Resolvers")
                .setMessage("Clear candidate resolvers list?")
                .setPositiveButton("Clear") { _, _ ->
                    resolverItems.clear()
                    saveCurrentProfileFromUI()
                    applyResolverFilter()
                    Toast.makeText(this, "Cleared resolvers list", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnResetDefaultResolvers.setOnClickListener {
            parseResolversFromText(ProfileManager.DEFAULT_RESOLVERS)
            saveCurrentProfileFromUI()
            Toast.makeText(this, "Restored default resolvers", Toast.LENGTH_SHORT).show()
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
            appendThrottledLog("[CLIPBOARD] Copied SOCKS5 link: $proxyUrl")
        }

        binding.btnOpenTelegram.setOnClickListener {
            val port = binding.etPort.text.toString().trim().ifBlank { "10850" }
            val uri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                appendThrottledLog("[TELEGRAM] Launched Telegram with proxy: tg://socks?server=127.0.0.1&port=$port")
            } catch (_: Exception) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Telegram Proxy", uri.toString()))
                Toast.makeText(this, "Telegram not installed. Link copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvClearLogs.setOnClickListener {
            synchronized(logLines) { logLines.clear() }
            binding.tvLogs.text = ""
        }

        binding.btnCopyLogs.setOnClickListener {
            val fullLogs = synchronized(logLines) { logLines.joinToString("\n") }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Console Logs", fullLogs))
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
                    parseResolversFromText(lines.joinToString("\n"))
                    saveCurrentProfileFromUI()
                    appendThrottledLog("[RESOLVER] Overwrote active resolvers with ${lines.size} candidate resolvers from file.")
                    Toast.makeText(this, "Loaded ${lines.size} resolvers", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Append") { _, _ ->
                    val currentAddrs = resolverItems.map { it.addr }
                    val merged = (currentAddrs + lines).distinct()
                    parseResolversFromText(merged.joinToString("\n"))
                    saveCurrentProfileFromUI()
                    appendThrottledLog("[RESOLVER] Appended ${lines.size} candidate resolvers (Total: ${merged.size}).")
                    Toast.makeText(this, "Appended ${lines.size} resolvers", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            appendThrottledLog("[ERROR] Failed to read resolvers file: ${e.message}")
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
        } catch (_: Exception) {
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
            appendThrottledLog("[PROFILE] Imported server profile '${imported.name}'")
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
            appendThrottledLog("[TEST] Scan stopped by user. Found ${scannedHealthyResults.size} healthy resolvers so far.")
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
        appendThrottledLog("[TEST] Probing ${list.size} resolvers for $probeTargetName in parallel...")

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

                            val ep = if (r.contains(":")) r else "$r:53"
                            if (recvPacket.length < 12) {
                                val item = resolverItems.find { it.addr.equals(ep, true) }
                                item?.let { it.failCount++; withContext(Dispatchers.Main) { applyResolverFilter() } }
                                appendThrottledLog("[BAD] $r (short response)")
                                return@async
                            }

                            val qr = (buf[2].toInt() and 0x80) != 0
                            val ra = (buf[3].toInt() and 0x80) != 0
                            val rcode = buf[3].toInt() and 0x0F
                            val an = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                            val ns = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)

                            if (!qr || !ra || rcode != 0 || (an == 0 && ns == 0)) {
                                val item = resolverItems.find { it.addr.equals(ep, true) }
                                item?.let { it.failCount++; withContext(Dispatchers.Main) { applyResolverFilter() } }
                                appendThrottledLog("[REJECTED] $r (RA=$ra, RCODE=$rcode)")
                                return@async
                            }

                            scannedHealthyResults.add(Pair(ep, elapsed))
                            val item = resolverItems.find { it.addr.equals(ep, true) }
                            if (item != null) {
                                item.rttMs = elapsed.toDouble()
                                item.isTested = true
                                item.successCount++
                                item.score = 100.0 - (elapsed * 0.1).coerceAtMost(50.0)
                                withContext(Dispatchers.Main) { applyResolverFilter() }
                            }
                            appendThrottledLog("[OK] $ep -> ${elapsed}ms")
                        } catch (_: Exception) {
                            val ep = if (r.contains(":")) r else "$r:53"
                            val item = resolverItems.find { it.addr.equals(ep, true) }
                            item?.let { it.failCount++; withContext(Dispatchers.Main) { applyResolverFilter() } }
                            appendThrottledLog("[TIMEOUT] $r")
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
                parseResolversFromText(top5.joinToString("\n"))
                saveCurrentProfileFromUI()
                Toast.makeText(this, "Applied top ${top5.size} fastest resolvers!", Toast.LENGTH_SHORT).show()
                appendThrottledLog("[RESOLVER] Saved top ${top5.size} fastest resolvers to profile.")
            }
            .setNeutralButton("Use All Working (${results.size})") { _, _ ->
                val allWorking = results.map { it.first }
                parseResolversFromText(allWorking.joinToString("\n"))
                saveCurrentProfileFromUI()
                Toast.makeText(this, "Applied all ${allWorking.size} working resolvers!", Toast.LENGTH_SHORT).show()
                appendThrottledLog("[RESOLVER] Saved all ${allWorking.size} working resolvers to profile.")
            }
            .setNegativeButton("Copy List") { _, _ ->
                val allWorking = results.map { it.first }.joinToString("\n")
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Healthy Resolvers", allWorking))
                Toast.makeText(this, "Copied ${results.size} healthy resolvers to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Soar DNS Tunnel")
            .setMessage(
                "SOAR DNS TUNNEL v1.4.0\n\n" +
                "Attribution & Credits:\n" +
                "Built upon the core Soar DNS tunneling protocol developed and open-sourced by Lantern (github.com/getlantern/soar) under the Apache License 2.0.\n\n" +
                "Ecosystem Enhancements:\n" +
                "• MasterDnsVPN & TheFeed inspired UI/UX\n" +
                "• Smart Adaptive Real-Time Network Tuner (EWMA RTT & FEC scaling)\n" +
                "• Full Redaction Shield on locked profiles (soar://locked)\n" +
                "• Live card scoreboard with latency ranking, search, and region tags\n" +
                "• QR Code profile sharing & clipboard auto-detect\n" +
                "• Multi-domain failover & EWMA latency probing\n" +
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
            putExtra(SoarService.EXTRA_LOCKED, p.isLocked)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUIState(true)
        appendThrottledLog("[START] Starting Soar tunnel service with profile '${p.name}'...")
    }

    private fun stopTunnelService() {
        val serviceIntent = Intent(this, SoarService::class.java).apply {
            action = SoarService.ACTION_STOP
        }
        startService(serviceIntent)
        updateUIState(false)
        appendThrottledLog("[STOP] Tunnel service stopped.")
    }

    private fun observeTunnelEvents() {
        TunnelProcessManager.onLogReceived = { logLine ->
            if (logLine.startsWith("[TUNER]")) {
                runOnUiThread {
                    binding.tvStatus.text = "CONNECTED • ⚡ ${logLine.removePrefix("[TUNER]").trim()}"
                }
            }
            appendThrottledLog(logLine)
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

    // Thread-safe, throttled log aggregator that prevents Choreographer / UI thread lockups
    private fun appendThrottledLog(line: String) {
        var sanitized = line
        val p = activeProfile
        if (p != null && p.isLocked) {
            val dList = p.domains.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
            for ((idx, d) in dList.withIndex()) {
                sanitized = sanitized.replace(d, "[protected-domain-${idx + 1}]")
            }
            if (p.serverKey.isNotEmpty()) {
                sanitized = sanitized.replace(p.serverKey, "[protected-key]")
            }
        }

        synchronized(logLines) {
            logLines.add(sanitized)
            if (logLines.size > MAX_LOG_LINES) {
                logLines.removeAt(0)
            }
        }

        if (!isLogRenderPending) {
            isLogRenderPending = true
            binding.tvLogs.postDelayed({
                isLogRenderPending = false
                renderLogs()
            }, 150)
        }
    }

    private fun renderLogs() {
        val linesSnapshot = synchronized(logLines) { logLines.toList() }
        val sb = SpannableStringBuilder()
        for (l in linesSnapshot) {
            val color = when {
                l.startsWith("[OK]", true) || l.startsWith("[STATUS]", true) || l.startsWith("[TUNNEL]", true) ->
                    ContextCompat.getColor(this, R.color.terminal_green)
                l.startsWith("[TIMEOUT]", true) || l.startsWith("[REJECTED]", true) || l.startsWith("[BAD]", true) || l.startsWith("[ERROR]", true) ->
                    ContextCompat.getColor(this, R.color.terminal_red)
                l.startsWith("[WARN]", true) ->
                    ContextCompat.getColor(this, R.color.terminal_amber)
                l.startsWith("[TEST]", true) || l.startsWith("[START]", true) || l.startsWith("[TELEMETRY]", true) || l.startsWith("[PROFILE]", true) ->
                    ContextCompat.getColor(this, R.color.terminal_cyan)
                l.startsWith("[BANK]", true) || l.startsWith("[RESOLVER]", true) ->
                    ContextCompat.getColor(this, R.color.terminal_purple)
                else ->
                    ContextCompat.getColor(this, R.color.text_light)
            }
            val start = sb.length
            sb.append(l).append("\n")
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        binding.tvLogs.text = sb
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

    private fun setupResolversView() {
        resolverAdapter = ResolverAdapter(
            items = listOf(),
            onTestClick = { item -> testSingleResolver(item) },
            onEditClick = { item -> editSingleResolver(item) },
            onDeleteClick = { item -> deleteSingleResolver(item) }
        )
        binding.rvResolvers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvResolvers.adapter = resolverAdapter

        binding.etResolverSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                applyResolverFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnFilterAll.setOnClickListener { updateFilterSelection("ALL") }
        binding.btnFilterHealthy.setOnClickListener { updateFilterSelection("HEALTHY") }
        binding.btnFilterFast.setOnClickListener { updateFilterSelection("FAST") }
        binding.btnFilterIran.setOnClickListener { updateFilterSelection("IRAN") }
        binding.btnFilterGlobal.setOnClickListener { updateFilterSelection("GLOBAL") }

        binding.btnAddResolver.setOnClickListener { promptAddResolver() }
        binding.btnPruneDead.setOnClickListener { pruneDeadResolvers() }
        binding.btnToggleRaw.setOnClickListener {
            binding.layoutRawEditor.visibility = if (binding.layoutRawEditor.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnSaveRawResolvers.setOnClickListener {
            val text = binding.etResolvers.text.toString()
            parseResolversFromText(text)
            saveCurrentProfileFromUI()
            binding.layoutRawEditor.visibility = View.GONE
            Toast.makeText(this, "Saved and applied resolvers list!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseResolversFromText(rawText: String) {
        val lines = rawText.split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            .distinct()

        val existingMap = resolverItems.associateBy { it.addr }
        resolverItems = lines.map { addr ->
            val formatted = if (addr.contains(":")) addr else "$addr:53"
            existingMap[formatted] ?: existingMap[addr] ?: ResolverItem(addr = formatted)
        }.toMutableList()

        applyResolverFilter()
    }

    private fun updateFilterSelection(filter: String) {
        currentResolverFilter = filter
        val chips = listOf(
            Pair(binding.btnFilterAll, "ALL"),
            Pair(binding.btnFilterHealthy, "HEALTHY"),
            Pair(binding.btnFilterFast, "FAST"),
            Pair(binding.btnFilterIran, "IRAN"),
            Pair(binding.btnFilterGlobal, "GLOBAL")
        )
        for ((btn, key) in chips) {
            if (key == filter) {
                btn.setBackgroundResource(R.drawable.bg_chip_selected)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.setBackgroundResource(R.drawable.bg_chip_unselected)
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        }
        applyResolverFilter()
    }

    private fun applyResolverFilter() {
        val query = binding.etResolverSearch.text?.toString()?.trim()?.lowercase() ?: ""
        val filtered = resolverItems.filter { item ->
            val matchesQuery = query.isEmpty() || item.addr.lowercase().contains(query)
            val matchesCategory = when (currentResolverFilter) {
                "HEALTHY" -> item.isTested && item.rttMs > 0 && item.failCount == 0
                "FAST" -> item.isTested && item.rttMs in 1.0..120.0
                "IRAN" -> item.isIran
                "GLOBAL" -> !item.isIran
                else -> true
            }
            matchesQuery && matchesCategory
        }

        resolverAdapter.updateList(filtered)
        binding.tvResolverSummary.text = "${filtered.size}/${resolverItems.size} Resolvers"
    }

    private fun promptAddResolver() {
        val input = EditText(this).apply {
            hint = "IP address or IP:53 (e.g. 178.22.122.100)"
        }
        AlertDialog.Builder(this)
            .setTitle("Add DNS Resolver")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val ep = if (raw.contains(":")) raw else "$raw:53"
                    if (resolverItems.none { it.addr.equals(ep, true) }) {
                        resolverItems.add(0, ResolverItem(addr = ep))
                        saveCurrentProfileFromUI()
                        applyResolverFilter()
                        Toast.makeText(this, "Added $ep", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editSingleResolver(item: ResolverItem) {
        val input = EditText(this).apply {
            setText(item.addr)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit DNS Resolver")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newAddr = input.text.toString().trim()
                if (newAddr.isNotEmpty()) {
                    item.addr = if (newAddr.contains(":")) newAddr else "$newAddr:53"
                    saveCurrentProfileFromUI()
                    applyResolverFilter()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSingleResolver(item: ResolverItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Resolver")
            .setMessage("Remove ${item.addr} from candidate resolvers?")
            .setPositiveButton("Delete") { _, _ ->
                resolverItems.remove(item)
                saveCurrentProfileFromUI()
                applyResolverFilter()
                Toast.makeText(this, "Removed ${item.addr}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pruneDeadResolvers() {
        val dead = resolverItems.filter { it.failCount >= 3 && it.successCount == 0 }
        if (dead.isEmpty()) {
            Toast.makeText(this, "No dead resolvers found to prune (3+ failures, 0 success)", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Prune Dead Resolvers")
            .setMessage("Found ${dead.size} dead resolvers with 3+ failures. Remove them all?")
            .setPositiveButton("Prune") { _, _ ->
                resolverItems.removeAll(dead)
                saveCurrentProfileFromUI()
                applyResolverFilter()
                Toast.makeText(this, "Pruned ${dead.size} dead resolvers", Toast.LENGTH_SHORT).show()
                appendThrottledLog("[RESOLVER] Pruned ${dead.size} dead resolvers.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testSingleResolver(item: ResolverItem) {
        CoroutineScope(Dispatchers.IO).launch {
            val domainsText = binding.etDomains.text.toString().trim()
            val targetDomain = domainsText.split(",", ";", "\n")
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.contains("Protected") } ?: ""
            val probeTargetName = if (targetDomain.isNotEmpty()) targetDomain else "google.com"

            val dnsQuery = buildDnsQuery(probeTargetName)
            val host = item.addr.substringBefore(":")
            val port = item.addr.substringAfter(":").toIntOrNull() ?: 53

            val startTime = System.currentTimeMillis()
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 1200
                val address = InetAddress.getByName(host)
                val packet = DatagramPacket(dnsQuery, dnsQuery.size, address, port)
                socket.send(packet)

                val buf = ByteArray(512)
                val recvPacket = DatagramPacket(buf, buf.size)
                socket.receive(recvPacket)
                val elapsed = (System.currentTimeMillis() - startTime).toDouble()
                socket.close()

                if (recvPacket.length >= 12) {
                    val qr = (buf[2].toInt() and 0x80) != 0
                    val ra = (buf[3].toInt() and 0x80) != 0
                    val rcode = buf[3].toInt() and 0x0F
                    if (qr && ra && rcode == 0) {
                        item.rttMs = elapsed
                        item.isTested = true
                        item.successCount++
                        item.score = 100.0 - (elapsed * 0.1).coerceAtMost(50.0)
                        withContext(Dispatchers.Main) {
                            applyResolverFilter()
                            appendThrottledLog("[RESOLVER] ${item.addr} -> ${elapsed.toInt()}ms (OK)")
                        }
                        return@launch
                    }
                }
                item.failCount++
                withContext(Dispatchers.Main) {
                    applyResolverFilter()
                    appendThrottledLog("[RESOLVER] ${item.addr} (invalid response)")
                }
            } catch (e: Exception) {
                item.failCount++
                withContext(Dispatchers.Main) {
                    applyResolverFilter()
                    appendThrottledLog("[RESOLVER] ${item.addr} timeout")
                }
            }
        }
    }

    private fun buildDnsQuery(targetDomain: String): ByteArray {
        return if (targetDomain.isNotEmpty()) {
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
    }
}
