package co.camilo.transmicrone.ui.home

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberDockCollapseEnabled(): Boolean {
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember(context) { mutableStateOf(false) }
    DisposableEffect(context, accessibility) {
        fun update() { enabled = !accessibility.isTouchExplorationEnabled && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { update() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { update() }
        }
        accessibility.addTouchExplorationStateChangeListener(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        update()
        onDispose {
            accessibility.removeTouchExplorationStateChangeListener(listener)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return enabled
}
