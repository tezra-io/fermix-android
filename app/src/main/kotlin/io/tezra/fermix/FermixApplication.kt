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

    private lateinit var sight: SightObserver

    /** The app's services, made once as the process starts. */
    protected open fun makeServices(): AppServices = AppServices(this)

    override fun onCreate() {
        super.onCreate()
        services = makeServices()
        services.start()
        sight = SightObserver(services.supervisor.inSight)
        ProcessLifecycleOwner.get().lifecycle.addObserver(sight)
    }

    /**
     * The process ends, which only an emulated one says: Robolectric makes a new application for each test in one
     * process and calls this as the test ends, before it resets the notification manager's state, which it keeps for
     * the whole process. Without it a test's services ran on into the next test and wrote into what that test reads,
     * a channel of the test before among them. The services end, and the process's lifecycle, which outlives the
     * application there, stops telling them its sight.
     */
    override fun onTerminate() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(sight)
        services.close()
        super.onTerminate()
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
