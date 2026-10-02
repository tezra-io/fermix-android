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
import io.tezra.fermix.onboarding.PAIRING_COUNTDOWN_SECONDS
import io.tezra.fermix.onboarding.PairingWait
import io.tezra.fermix.onboarding.clock
import io.tezra.fermix.onboarding.millisToNextSecond
import io.tezra.fermix.onboarding.secondsUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val CHANNEL = "pairing_wait"
private const val NOTIFICATION_ID = 1

/**
 * What the pairing-wait notification shows: [wait], while the app is out of sight ([inBackground]), and
 * nothing otherwise, which is when the service is not started, or ends (design section 12.5). Onboarding
 * says what Verify waits for, and nothing once it no longer shows.
 */
internal fun pairingWaitShown(
    wait: PairingWait?,
    inBackground: Boolean,
): PairingWait? = wait?.takeIf { inBackground }

/**
 * Design section 12.5's short foreground service for the pairing wait: while Verify waits on the computer
 * and the app is out of sight, "Waiting for approval on suj-mbp · 1:42", title only, ticking each second.
 * It ends itself when the wait ends (approved, refused, cancelled or expired) or the app comes back, so
 * the activity never has to stop it; a `shortService`, it also ends at the platform's timeout, which the
 * 120 s window never reaches.
 */
class PairingWaitService : Service() {
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
        startForeground(
            NOTIFICATION_ID,
            notification(services.pairingWait.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
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

    /** The wait while the app is out of sight, ticking; nothing to show ends the service. */
    private suspend fun follow(services: AppServices) {
        combine(services.pairingWait, services.inBackground, ::pairingWaitShown)
            .collectLatest { wait -> if (wait == null) end() else tick(wait) }
    }

    /** One update a second, as the second turns, until the window closes. */
    private suspend fun tick(wait: PairingWait) {
        val manager = getSystemService(NotificationManager::class.java)
        repeat(PAIRING_COUNTDOWN_SECONDS + 1) {
            manager.notify(NOTIFICATION_ID, notification(wait))
            if (secondsUntil(wait.expiresAt) == 0) return
            delay(millisToNextSecond(wait.expiresAt))
        }
    }

    private fun end() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL, getString(R.string.pairing_wait_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Title only (section 13.9's copy); a tap brings the app back to Verify. */
    private fun notification(wait: PairingWait?): Notification {
        val title =
            if (wait == null) {
                getString(R.string.app_name)
            } else {
                getString(R.string.pairing_wait_title, wait.host, clock(secondsUntil(wait.expiresAt)))
            }
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
            context.startForegroundService(Intent(context, PairingWaitService::class.java))
        }
    }
}
