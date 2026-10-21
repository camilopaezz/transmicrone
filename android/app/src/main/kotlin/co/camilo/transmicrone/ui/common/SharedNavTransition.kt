package co.camilo.transmicrone.ui.common

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
internal fun rememberSharedMorphEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(true) }
    DisposableEffect(context) {
        fun update() {
            enabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { update() }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        update()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Lays out at the resting size so the morphing capsule clips its contents instead of reflowing them. */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MorphContent(modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalNavAnimatedScope.current
    val active = enabled && shared != null && visibility != null && rememberSharedMorphEnabled()
    // Outgoing icons leave first; incoming content waits until the capsule is ~40% of the way there.
    val morph = if (active) with(shared!!) { Modifier.skipToLookaheadSize() }.then(with(visibility!!) {
        Modifier.animateEnterExit(enter = fadeIn(tween(180, delayMillis = 170)), exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()))
    }) else Modifier
    Box(modifier.then(morph)) { content() }
}

/** Short crossfade so the home brand and the search back circle never linger over each other. */
@Composable
internal fun Modifier.navQuickEnterExit(): Modifier {
    val visibility = LocalNavAnimatedScope.current
    if (visibility == null || !rememberSharedMorphEnabled()) return this
    return with(visibility) {
        this@navQuickEnterExit.animateEnterExit(enter = fadeIn(tween(120, delayMillis = 90)), exit = fadeOut(tween(50)))
    }
}
