package com.soar.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.soar.tunnel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("soar_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedSettings()
        setupListeners()
        observeTunnelEvents()
        updateUIState(TunnelProcessManager.isRunning)
    }

    private fun loadSavedSettings() {
        binding.etServerKey.setText(prefs.getString("server_key", ""))
        binding.etDomains.setText(prefs.getString("domains", ""))
        binding.etPort.setText(prefs.getInt("port", 10850).toString())
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putString("server_key", binding.etServerKey.text.toString().trim())
            putString("domains", binding.etDomains.text.toString().trim())
            putInt("port", binding.etPort.text.toString().toIntOrNull() ?: 10850)
            apply()
        }
    }

    private fun setupListeners() {
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

    private fun startTunnelService() {
        val key = binding.etServerKey.text.toString().trim()
        val domains = binding.etDomains.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 10850

        if (key.isEmpty()) {
            Toast.makeText(this, "Please enter Server Public Key", Toast.LENGTH_SHORT).show()
            return
        }
        if (domains.isEmpty()) {
            Toast.makeText(this, "Please enter at least one tunnel domain", Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        val serviceIntent = Intent(this, SoarService::class.java).apply {
            action = SoarService.ACTION_START
            putExtra(SoarService.EXTRA_KEY, key)
            putExtra(SoarService.EXTRA_DOMAINS, domains)
            putExtra(SoarService.EXTRA_PORT, port)
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
                    binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN)
                }
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
        }
    }
}
