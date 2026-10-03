package co.camilo.transmicrone.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
            delay(1_000)
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
fun FreshnessLine(freshness: Freshness, modifier: Modifier = Modifier) {
    val text = when (freshness) {
        Freshness.Idle, Freshness.Loading -> stringResource(R.string.freshness_updating)
        is Freshness.Updated -> stringResource(R.string.freshness_updated, relativeTime(freshness.at))
        is Freshness.Cached -> stringResource(R.string.freshness_stale, relativeTime(freshness.cachedAt))
        is Freshness.UpdatingCached -> stringResource(R.string.freshness_stale, relativeTime(freshness.cachedAt))
        is Freshness.FailedCached -> stringResource(
            R.string.freshness_failed_cached,
            failureKindLabel(freshness.kind),
            relativeTime(freshness.cachedAt),
        )
        is Freshness.Failed -> stringResource(R.string.freshness_failed_kind, failureKindLabel(freshness.kind))
        is Freshness.Empty -> stringResource(R.string.freshness_empty)
        Freshness.Unsupported -> stringResource(R.string.freshness_unsupported)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
