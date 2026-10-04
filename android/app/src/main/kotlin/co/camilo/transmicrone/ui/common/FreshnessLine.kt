package co.camilo.transmicrone.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.camilo.transmicrone.R
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.ui.Freshness
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
fun relativeTime(at: Instant): String {
    val now by produceState(Instant.now(), at) {
        while (true) {
            val seconds = Duration.between(at, Instant.now()).seconds.coerceAtLeast(0)
            val interval = if (seconds < 60) 60L else if (seconds < 3_600) 60L else if (seconds < 86_400) 3_600L else 86_400L
            delay((interval - seconds % interval) * 1_000)
            value = Instant.now()
        }
    }
    val seconds = Duration.between(at, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(R.string.freshness_less_than_minute)
        seconds < 3_600 -> stringResource(R.string.report_age_minutes, seconds / 60)
        seconds < 86_400 -> stringResource(R.string.freshness_hours, seconds / 3_600)
        else -> stringResource(R.string.freshness_days, seconds / 86_400)
    }
}

@Composable
fun failureKindLabel(kind: FailureKind): String = stringResource(
    when (kind) {
        FailureKind.HOST_REJECTED -> R.string.failure_host_rejected
        FailureKind.UNAUTHORIZED -> R.string.failure_unauthorized
        FailureKind.TRANSPORT -> R.string.failure_transport
        FailureKind.MALFORMED -> R.string.failure_malformed
        FailureKind.TIMEOUT -> R.string.failure_timeout
    },
)

@Composable
fun FreshnessLine(freshness: Freshness, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val text = when (freshness) {
        Freshness.Idle, Freshness.Loading -> stringResource(R.string.freshness_updating)
        is Freshness.Updated -> stringResource(R.string.freshness_updated, relativeTime(freshness.at))
        is Freshness.Cached -> stringResource(R.string.freshness_stale, relativeTime(freshness.cachedAt))
        is Freshness.UpdatingCached -> stringResource(R.string.freshness_updating_cached, relativeTime(freshness.cachedAt))
        is Freshness.FailedCached -> stringResource(
            R.string.freshness_failed_cached,
            failureKindLabel(freshness.kind),
            relativeTime(freshness.cachedAt),
        )
        is Freshness.Failed -> stringResource(R.string.freshness_failed_kind, failureKindLabel(freshness.kind))
        is Freshness.Empty -> stringResource(R.string.freshness_empty)
        Freshness.Unsupported -> stringResource(R.string.freshness_unsupported)
    }
    val failed = freshness is Freshness.Failed || freshness is Freshness.FailedCached
    Column(modifier) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failed) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
        }
        if (failed && onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_refresh)) }
    }
}
