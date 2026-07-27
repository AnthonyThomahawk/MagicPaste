package com.tonyt.magicpaste.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.tonyt.magicpaste.MainActivity
import com.tonyt.magicpaste.R
import com.tonyt.magicpaste.magicPaste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

/**
 * Keeps the clipboard server alive while the app is not on screen.
 *
 * The service does not own the server — [ServerController] does — it owns the
 * *right to keep running*, plus the notification that Android requires in
 * exchange for it.
 */
class MagicPasteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: ServerController by lazy { magicPaste.serverController }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch {
            // The leading Stopped is the state we are being created to leave;
            // reacting to it would tear the service down before it ever started.
            controller.status.dropWhile { it is ServerStatus.Stopped }.collect { status ->
                when (status) {
                    is ServerStatus.Failed, is ServerStatus.Stopped -> stopSelf()
                    else -> notify(notification(status))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives us seconds to post a notification, so do it before anything else.
        startInForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopSelf()
            }

            else -> {
                val settings = magicPaste.settings
                val port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 } ?: settings.port
                controller.start(port, settings.pin, settings.shareClipboard, settings.shareFiles)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() = ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification(controller.status.value),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        },
    )

    private fun notify(notification: Notification) {
        // Only reaches the shade once the user has granted notifications; the
        // service keeps running either way.
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification)
    }

    private fun notification(status: ServerStatus): Notification {
        val text = when (status) {
            is ServerStatus.Running -> status.urls.firstOrNull()
                ?: getString(R.string.notification_no_network)

            is ServerStatus.Failed -> status.reason
            else -> getString(R.string.notification_starting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0,
                getString(R.string.action_stop),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, MagicPasteService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "magicpaste_server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tonyt.magicpaste.action.STOP"
        private const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int) {
            val intent = Intent(context, MagicPasteService::class.java).putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MagicPasteService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
