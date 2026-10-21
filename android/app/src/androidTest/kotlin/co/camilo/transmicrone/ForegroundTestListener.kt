package co.camilo.transmicrone

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Opt-in workaround for physical devices that block background activity launches. */
class ForegroundTestListener : RunListener() {
    override fun testStarted(description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val command = "am start -W -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}
