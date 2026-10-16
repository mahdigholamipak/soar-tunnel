package com.soar.tunnel

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SoarService : Service() {

    companion object {
        const val CHANNEL_ID = "soar_tunnel_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.soar.tunnel.ACTION_START"
        const val ACTION_STOP = "com.soar.tunnel.ACTION_STOP"
        const val EXTRA_KEY = "server_key"
        const val EXTRA_DOMAINS = "domains"
        const val EXTRA_PORT = "port"
        const val EXTRA_RESOLVERS = "resolvers"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            TunnelProcessManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val key = intent?.getStringExtra(EXTRA_KEY) ?: ""
        val domains = intent?.getStringExtra(EXTRA_DOMAINS) ?: ""
        val port = intent?.getIntExtra(EXTRA_PORT, 10850) ?: 10850
        val resolvers = intent?.getStringExtra(EXTRA_RESOLVERS) ?: ""

        val notification = buildNotification(port)
        startForeground(NOTIFICATION_ID, notification)

        TunnelProcessManager.start(this, key, domains, port, resolvers)

        return START_STICKY
    }

    override fun onDestroy() {
        TunnelProcessManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Soar Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of active Soar DNS Tunnel"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val stopIntent = Intent(this, SoarService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, port))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
