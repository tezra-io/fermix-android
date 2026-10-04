package io.tezra.fermix

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The process: it makes the app's services once ([makeServices], which a test's application makes with a fake
 * of its own), starts reading the network and the records, and tells the sessions' supervisor when the process
 * comes into and goes out of sight.
 */
open class FermixApplication : Application() {
    lateinit var services: AppServices
        private set

    /** The app's services, made once as the process starts. */
    protected open fun makeServices(): AppServices = AppServices(this)

    override fun onCreate() {
        super.onCreate()
        services = makeServices()
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
