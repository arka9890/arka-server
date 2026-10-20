package com.arkacode.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.arkacode.app.MainActivity
import com.arkacode.app.R

/**
 * Foreground service yang menjaga proses app tetap hidup saat background:
 * prioritas proses naik (tak mudah dibunuh sistem), server opencode — yang
 * merupakan child process — ikut selamat, plus notifikasi status bar
 * "server jalan · port …" dan partial wakelock agar CPU tidak tidur
 * (layar mati) saat turn AI sedang berjalan.
 *
 * Server TIDAK dikelola service ini — lifecycle tetap di [EngineManager];
 * service hanya menjaga proses + notifikasi + wakelock.
 */
class EngineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "?"
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW, // senyap, cukup di panel notifikasi
            ),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("server opencode v$version · port $port")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arkacode:server")
                .apply { acquire() } // dilepas di onDestroy
        }
        // NOT_STICKY: bila sistem membunuh proses, server (child) ikut mati —
        // restart service tanpa server hanya bikin notifikasi yang bohong.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIF_ID = 4096
        private const val EXTRA_PORT = "port"
        private const val EXTRA_VERSION = "version"

        /** Idempoten: start saat service sudah jalan hanya memperbarui notifikasi. */
        fun start(context: Context, port: Int, version: String) {
            val intent = Intent(context, EngineService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_VERSION, version)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
