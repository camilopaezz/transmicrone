package co.camilo.transmicrone.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.ui.ArrivalFailure
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.board.ServicioBadge
import co.camilo.transmicrone.ui.common.RequestFailureNotice
import co.camilo.transmicrone.ui.common.relativeTime
import co.camilo.transmicrone.ui.common.requestFailureMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ArrivalFailureNotice(problem: ArrivalFailure, view: BoardView, retrying: Boolean, onRetry: (() -> Unit)?) {
    RequestFailureNotice(
        title = stringResource(if (problem.partial) R.string.arrivals_partial_failure_short else R.string.freshness_failed),
        compactTitle = stringResource(if (problem.partial) R.string.arrivals_partial_failure_short else R.string.freshness_failed),
        message = requestFailureMessage(problem.kind),
        supportingText = problem.oldestFetchedAt?.let { stringResource(R.string.arrivals_cache_age, relativeTime(it)) },
        retrying = retrying,
        onRetry = onRetry,
        details = {
            if (problem.services.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.arrivals_failed_services), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.arrivals_service_fetch_times), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                problem.services.forEach { service ->
                    val selection = service.selection
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.widthIn(min = 52.dp)) {
                            ServicioBadge(selection.painted, routeColor(view, selection.painted, selection.routeIds.firstOrNull()), compact = true)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(selection.destino, style = MaterialTheme.typography.bodyMedium)
                            Text(service.fetchedAt?.let { savedFetchTime(it) }
                                ?: stringResource(R.string.arrivals_never_fetched),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else problem.oldestFetchedAt?.let {
                Text(stringResource(R.string.arrivals_last_success, savedFetchTime(it)), style = MaterialTheme.typography.bodySmall)
            }
            if (problem.hasPreviousArrivals) Text(stringResource(R.string.arrivals_failure_previous), style = MaterialTheme.typography.bodySmall)
        },
    )
}

private fun savedFetchTime(at: Instant): String = DateTimeFormatter
    .ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("es-CO"))
    .withZone(ZoneId.systemDefault()).format(at)
