package co.camilo.transmicrone

import android.app.Application
import android.util.Log
import co.camilo.transmicrone.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransmicroneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.checkForCatalogUpdate()
            } catch (failure: Throwable) {
                // Includes OutOfMemoryError on small heaps; an optional update must not crash the app.
                Log.w("PublishedCatalog", "Catalog update unavailable", failure)
            }
        }
    }
}
