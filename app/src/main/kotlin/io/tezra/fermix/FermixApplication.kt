package io.tezra.fermix

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The process: it makes the app's services once, starts reading the network and the records, and tells the
 * sessions' supervisor when the process comes into and goes out of sight.
 */
class FermixApplication : Application() {
    lateinit var services: AppServices
        private set

    override fun onCreate() {
        super.onCreate()
        services = AppServices(this)
        services.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(SightObserver(services.supervisor.inSight))
    }
}

/** The process's sight, started or stopped as its activities are, as [inSight] says it. */
private class SightObserver(
    private val inSight: MutableStateFlow<Boolean>,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        inSight.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        inSight.value = false
    }
}
