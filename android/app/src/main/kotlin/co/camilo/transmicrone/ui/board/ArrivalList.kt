package co.camilo.transmicrone.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.Duration
import co.camilo.transmicrone.domain.ArrivalIdentity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.Occupancy

@Composable
fun ArrivalList(
    arrivals: List<Arrival>, emptyMessage: String, modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(), header: (@Composable () -> Unit)? = null,
    bottomContentPadding: Dp = 104.dp,
    colorForArrival: (Arrival) -> String? = { it.colorRuta },
) {
    val now by produceState(Instant.now()) {
        while (true) { delay(5_000); value = Instant.now() }
    }
    val keys = remember(arrivals) { arrivalListKeys(arrivals) }
    LazyColumn(
        state = state,
        modifier = modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (header == null) 8.dp else 0.dp, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header?.let { item(key = "station-header", contentType = "header") { it() } }
        if (arrivals.isEmpty()) item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.DirectionsBus, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(emptyMessage, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        itemsIndexed(arrivals, key = { index, _ -> keys[index] }) { _, arrival ->
            ArrivalRow(arrival, now, colorForArrival(arrival))
        }
        item {
            Text(stringResource(R.string.unofficial_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}

@Composable
fun ArrivalRow(arrival: Arrival, now: Instant = Instant.now(), routeColor: String? = arrival.colorRuta) {
    val occupancy = occupancyLabel(arrival.occupancy)
    val display = arrivalDisplay(arrival)
    val phrase = display.phrase.ifBlank { stringResource(R.string.unknown_arrival_time) }
    val destination = arrival.destino.ifBlank { stringResource(R.string.unknown_destination) }
    val vehicle = arrival.identity == ArrivalIdentity.VEHICLE
    val estimate = stringResource(if (vehicle) R.string.vehicle_estimate_label else R.string.estimate_label)
    val age = arrival.sourceReportedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
    val stale = vehicle && age != null && age > 120
    val staleLabel = stringResource(R.string.old_vehicle_report)
    val desc = listOfNotNull("${arrival.painted} a $destination", arrival.label?.let { stringResource(R.string.bus_label, it) },
        if (stale) staleLabel else null,
        if (stale) stringResource(R.string.last_vehicle_estimate, phrase) else phrase,
        display.distance, occupancy, estimate).joinToString(", ")
    Surface(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = desc },
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ServicioBadge(arrival.painted, routeColor)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.destination_prefix), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(destination, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (stale) staleLabel else phrase, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (stale) Text(stringResource(R.string.last_vehicle_estimate, phrase), style = MaterialTheme.typography.bodyMedium)
                if (vehicle) {
                    Text(listOfNotNull(arrival.label?.let { stringResource(R.string.bus_label, it) }, display.distance).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else display.distance?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Groups, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(occupancy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ServicioBadge(code: String, color: String? = null) {
    val background = parseColor(color) ?: MaterialTheme.colorScheme.primary
    val foreground = if (background.luminance() > 0.179f) Color.Black else Color.White
    Text(
        code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = foreground,
        modifier = Modifier.background(background, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
fun occupancyLabel(occupancy: Occupancy): String = when (occupancy) {
    Occupancy.VACIO -> stringResource(R.string.occupancy_vacio)
    Occupancy.MEDIO -> stringResource(R.string.occupancy_medio)
    Occupancy.LLENO -> stringResource(R.string.occupancy_lleno)
    Occupancy.UNKNOWN -> stringResource(R.string.occupancy_unknown)
}

fun parseColor(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
