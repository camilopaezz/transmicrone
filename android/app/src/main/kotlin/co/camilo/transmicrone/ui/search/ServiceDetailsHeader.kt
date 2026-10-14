package co.camilo.transmicrone.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.ServiceDestinationList
import co.camilo.transmicrone.ui.board.ServicioBadge

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ServiceDetailsHeader(
    service: CatalogEntry.Service,
    routes: List<ServiceRoute>,
    routeIndex: Int,
    onSelectRoute: (Int) -> Unit,
) {
    val selectedIndex = routeIndex.takeIf { it in routes.indices } ?: 0
    val route = routes.getOrNull(selectedIndex)
    val hours = if (route == null) service.operatingHours else service.operatingHours.filter { it.catalogId == route.catalogId }
    val destinations = routes.map { variant ->
        service.operatingHours.firstOrNull { it.catalogId == variant.catalogId }?.destino ?: variant.stops.last().name
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (routes.size > 1) RouteVariantSelector(destinations, selectedIndex, onSelectRoute)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServicioBadge(service.option.painted, service.option.color, compact = true)
            if (route != null) Text(stringResource(R.string.route_stop_count, route.stops.count { it.stopsHere }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (route != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(destinations[selectedIndex], style = MaterialTheme.typography.headlineSmallEmphasized)
                val from = stringResource(R.string.route_from)
                Text(buildAnnotatedString {
                    append("$from ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(route.stops.first().name) }
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ServiceDestinationList(service.option.destino, R.string.route_direction_label, MaterialTheme.typography.headlineSmallEmphasized)
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hours.isEmpty()) {
                    Text(stringResource(R.string.service_hours_unavailable), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else hours.forEach { schedule ->
                    if (hours.size > 1) Text("${schedule.painted} · ${schedule.destino}", style = MaterialTheme.typography.titleSmall)
                    val groups = schedule.windows.groupBy { it.dayGroup }.entries.toList()
                    val columns = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2
                    groups.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { (day, windows) ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(operatingDayLabel(day), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    windows.forEach { window ->
                                        Text("${window.startTime} – ${window.endTime}", style = MaterialTheme.typography.titleSmallEmphasized)
                                    }
                                }
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Route variants share an inset track, with two equal-width choices per row.
 * Stable segment corners and separate direction/destination lines keep selection from shifting labels.
 * Equal heights per row come from [EqualHeightRow].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RouteVariantSelector(destinations: List<String>, selectedIndex: Int, onSelectRoute: (Int) -> Unit) {
    val segmentShape = RoundedCornerShape(14.dp)
    val rows = destinations.indices.chunked(2)
    val colors = ToggleButtonDefaults.toggleButtonColors(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { indices ->
                EqualHeightRow(columns = 2, gap = 4.dp) { probe ->
                    indices.forEach { index ->
                        val destination = destinations[index]
                        val duplicate = destinations.count { it == destination } > 1
                        val label = if (duplicate) stringResource(R.string.route_variant_destination, destination, index + 1)
                            else stringResource(R.string.route_direction, destination)
                        val isSelected = index == selectedIndex
                        ToggleButton(
                            checked = isSelected,
                            onCheckedChange = { onSelectRoute(index) },
                            shapes = ToggleButtonShapes(shape = segmentShape, pressedShape = segmentShape, checkedShape = segmentShape),
                            colors = colors,
                            modifier = Modifier.fillMaxSize().defaultMinSize(minHeight = 64.dp).then(if (probe) Modifier.clearAndSetSemantics {} else Modifier.semantics(mergeDescendants = true) {
                                role = Role.RadioButton
                                selected = isSelected
                                contentDescription = label
                            }),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(R.string.route_direction_label), style = MaterialTheme.typography.labelMedium)
                                Text(destination, style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center)
                                if (duplicate) Text(stringResource(R.string.route_variant_number, index + 1),
                                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays out up to [columns] equal-width cells, measuring each at its real width first and then pinning all of
 * them to the tallest. Probe copies must clear their semantics. Intrinsic sizing underestimates wrapped ToggleButton labels at large font scales.
 */
@Composable
private fun EqualHeightRow(columns: Int, gap: Dp, content: @Composable (probe: Boolean) -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val gapPx = gap.roundToPx()
        val cellWidth = ((constraints.maxWidth - gapPx * (columns - 1)) / columns).coerceAtLeast(0)
        val probe = subcompose("probe") { content(true) }.map { it.measure(Constraints(minWidth = cellWidth, maxWidth = cellWidth)) }
        val height = probe.maxOfOrNull { it.height } ?: 0
        val cells = subcompose("cells") { content(false) }.map { it.measure(Constraints.fixed(cellWidth, height)) }
        layout(constraints.maxWidth, height) {
            cells.forEachIndexed { i, cell -> cell.placeRelative(i * (cellWidth + gapPx), 0) }
        }
    }
}

@Composable
private fun operatingDayLabel(dayGroup: String): String = when (dayGroup) {
    "L-V" -> stringResource(R.string.service_days_weekdays)
    "L-S" -> stringResource(R.string.service_days_monday_saturday)
    "L-D" -> stringResource(R.string.service_days_every_day)
    "S" -> stringResource(R.string.service_days_saturday)
    "D" -> stringResource(R.string.service_days_sunday)
    "D-F" -> stringResource(R.string.service_days_sunday_holidays)
    else -> dayGroup
}
