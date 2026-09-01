package com.ownapps.app.uihider

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ownapps.app.R

/**
 * Thin foreground service whose only job is to host the "press to open picker" notification while a
 * Node Picker session is active. Tapping the notification broadcasts [NodePicker.ACTION_OPEN] to the
 * [com.ownapps.app.uihider.UiHiderService] process, which actually reads the accessibility tree
 * and draws the overlay.
 *
 * The heavy lifting lives in [NodePicker]; this service just keeps the entry point alive and
 * reachable after the user switches to the app they want to inspect.
 */
class NodePickerService : Service() {

    companion object {
        private const val CHANNEL_ID = "node_picker_channel"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_STOP_SERVICE = "com.ownapps.app.nodepicker.STOP_SERVICE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NodePickerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodePickerService::class.java))
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_SERVICE) stopSelf()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_STOP_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.node_picker_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getBroadcast(
            this, 0, Intent(NodePicker.ACTION_OPEN).setPackage(packageName), flags
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_STOP_SERVICE).setPackage(packageName), flags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.node_picker_notif_title))
            .setContentText(getString(R.string.node_picker_notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.node_picker_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        // Tell the picker (in the accessibility service process) to tear down its overlay.
        sendBroadcast(Intent(NodePicker.ACTION_STOP).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
