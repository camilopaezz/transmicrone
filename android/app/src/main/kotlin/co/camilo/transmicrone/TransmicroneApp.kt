package co.camilo.transmicrone

import android.app.Application
import co.camilo.transmicrone.di.AppContainer

class TransmicroneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
