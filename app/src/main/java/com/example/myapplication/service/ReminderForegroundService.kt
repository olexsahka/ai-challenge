package com.example.myapplication.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.data.reminder.ReminderEvent
import com.example.myapplication.data.reminder.ReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ReminderForegroundService : Service() {

    private val reminderManager: ReminderManager by inject()

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var sseJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification())
        startSse()
        return START_STICKY
    }

    override fun onDestroy() {
        sseJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSse() {
        sseJob?.cancel()
        sseJob = serviceScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                android.util.Log.d("ReminderService", "SSE connect attempt #$attempt")
                updateForegroundNotification("Подключение... (попытка $attempt)")
                try {
                    reminderManager.connectFlow().collect { event ->
                        android.util.Log.d("ReminderService", "Event: ${event.type} ${event.symbol} ${event.price}")
                        updateForegroundNotification("Подключено — последнее: ${event.symbol} ${event.price}")
                        showReminderNotification(event)
                        reminderManager.emitReminder(event)
                    }
                    android.util.Log.d("ReminderService", "SSE flow ended, reconnecting...")
                } catch (e: Exception) {
                    android.util.Log.e("ReminderService", "SSE error: ${e.message}", e)
                    updateForegroundNotification("Ошибка соединения, повтор через 5с...")
                }
                delay(5_000)
            }
        }
    }

    private fun showReminderNotification(event: ReminderEvent) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${event.symbol} — ${event.type}")
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${event.message}\nЦена: ${event.price}"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun buildForegroundNotification(status: String = "Подключение..."): Notification {
        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Crypto Reminders")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun updateForegroundNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID_FOREGROUND, buildForegroundNotification(status))
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOREGROUND,
                "Crypto Reminders Service",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Crypto Price Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о ценах криптовалют"
            }
        )
    }

    companion object {
        private const val CHANNEL_FOREGROUND = "reminder_foreground"
        private const val CHANNEL_REMINDERS = "reminder_alerts"
        private const val NOTIF_ID_FOREGROUND = 1001

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ReminderForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, ReminderForegroundService::class.java)
            )
        }
    }
}
