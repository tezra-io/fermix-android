package io.tezra.fermix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.tezra.fermix.data.Instance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val CHANNEL = "upload"
private const val NOTIFICATION_ID = 2

/** What the upload notification shows: the computer it names, none while its record is not read yet. */
internal data class UploadShown(
    val host: String?,
)

/**
 * The upload notification while a session has an upload in flight ([uploading], by instance id) and the app is
 * out of sight ([inBackground]), naming the first such instance's computer among [records]; none otherwise,
 * which is when the service is not started, or ends (design sections 8.5 and 12.5).
 */
internal fun uploadShown(
    uploading: Set<String>,
    inBackground: Boolean,
    records: List<Instance>,
): UploadShown? {
    if (uploading.isEmpty() || !inBackground) return null
    return UploadShown(records.firstOrNull { it.id in uploading }?.host)
}

/**
 * Design section 12.5's `shortService` for an upload in flight: started as the app leaves the screen while a
 * session uploads an outbox item's attachments, it keeps the process in the foreground while the supervisor
 * keeps that session up (SessionSupervisor's upload hold), its notification naming the computer. It ends itself
 * when no upload is in flight any more, or the app comes back, so the activity never has to stop it, and at the
 * platform's timeout, after which the item waits in the outbox for the next connection.
 */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val services = (application as FermixApplication).services
        createChannel()
        // A service started in the foreground must say so at once, even one that has nothing left to show.
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        following?.cancel()
        following = scope.launch { follow(services) }
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) = end()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The upload while the app is out of sight; none ends the service. */
    private suspend fun follow(services: AppServices) {
        combine(services.supervisor.uploading, services.inBackground, services.instances.instances, ::uploadShown)
            .collectLatest { shown ->
                if (shown == null) {
                    end()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(shown))
                }
            }
    }

    private fun end() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL, getString(R.string.upload_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Title only, naming the computer once it is known; a tap brings the app back. */
    private fun notification(shown: UploadShown?): Notification {
        val title = shown?.host?.let { getString(R.string.upload_title, it) } ?: getString(R.string.app_name)
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        /** Starts the service from [context], the activity as it leaves the screen. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, UploadService::class.java))
        }
    }
}
