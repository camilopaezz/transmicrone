package co.camilo.transmicrone.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.domain.FailureKind

/** Keep arrivals in view; explanations are available on demand. */
@Composable
fun RequestFailureNotice(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    details: (@Composable ColumnScope.() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    compactTitle: String = title,
    supportingText: String? = null,
    retrying: Boolean = false,
) {
    var showingDetails by rememberSaveable(title) { mutableStateOf(false) }
    val updatingLabel = stringResource(R.string.freshness_updating)
    FloatingControlSurface(
        modifier = modifier.fillMaxWidth().testTag("request-failure-notice"),
        shape = MaterialTheme.shapes.large,
        // This row is inside the backdrop source, like inline action panels.
        blurred = false,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { showingDetails = true },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Error, stringResource(R.string.request_failure_details),
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(compactTitle, style = MaterialTheme.typography.bodyMedium)
                    if (supportingText != null) Text(supportingText, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
            if (onRetry != null) FilledTonalButton(
                onClick = onRetry, enabled = !retrying,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    if (retrying) contentDescription = updatingLabel
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.retry_refresh),
                        if (retrying) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)
                    if (retrying) CircularProgressIndicator(Modifier.size(20.dp).testTag("retry-progress"), strokeWidth = 2.dp)
                }
            }
        }
    }
    if (showingDetails) AlertDialog(
        onDismissRequest = { showingDetails = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                details?.invoke(this)
            }
        },
        confirmButton = {
            if (onRetry != null) FilledTonalButton(
                onClick = { showingDetails = false; onRetry() }, enabled = !retrying,
            ) { Text(stringResource(R.string.retry_refresh)) }
            else TextButton(onClick = { showingDetails = false }) { Text(stringResource(R.string.close_details)) }
        },
        dismissButton = if (onRetry != null) ({
            TextButton(onClick = { showingDetails = false }) { Text(stringResource(R.string.close_details)) }
        }) else null,
    )
}

@Composable
fun requestFailureMessage(kind: FailureKind?): String = stringResource(when (kind) {
    FailureKind.TRANSPORT -> R.string.request_failure_transport
    FailureKind.TIMEOUT -> R.string.request_failure_timeout
    FailureKind.UNAUTHORIZED -> R.string.request_failure_unauthorized
    FailureKind.HOST_REJECTED -> R.string.request_failure_rejected
    FailureKind.MALFORMED -> R.string.request_failure_malformed
    null -> R.string.request_failure_generic
})
