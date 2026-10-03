package co.camilo.transmicrone

import androidx.test.platform.app.InstrumentationRegistry
import co.camilo.transmicrone.data.local.InstallationId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class InstallationIdTest {
    @Test
    fun installationIdIsPersistedAndReusedAcrossContexts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = InstallationId.getOrCreate(context)
        assertEquals(first, UUID.fromString(first).toString())
        val reopened = context.createPackageContext(context.packageName, 0)
        assertEquals(first, InstallationId.getOrCreate(reopened))
        assertEquals(first, reopened.getSharedPreferences("feed-installation", 0).getString("uuid", null))
    }
}
