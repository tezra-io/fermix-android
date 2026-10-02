package io.tezra.fermix

import android.app.Application

/** The process: it makes the app's services once, and starts reading the network. */
class FermixApplication : Application() {
    lateinit var services: AppServices
        private set

    override fun onCreate() {
        super.onCreate()
        services = AppServices(this)
        services.start()
    }
}
