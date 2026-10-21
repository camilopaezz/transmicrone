package co.camilo.transmicrone.ui.search

import co.camilo.transmicrone.ui.common.floatingHorizontalPadding
import co.camilo.transmicrone.ui.common.FloatingScaffold
import co.camilo.transmicrone.ui.common.FloatingSearchHeader
import co.camilo.transmicrone.ui.common.FloatingScreenHeader
import co.camilo.transmicrone.ui.common.FloatingActionPanel
import co.camilo.transmicrone.ui.common.LocalScreenEntered

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.camilo.transmicrone.R
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.board.ServicioBadge
import co.camilo.transmicrone.ui.board.parseColor
import co.camilo.transmicrone.ui.common.ServiceChipSurface
import co.camilo.transmicrone.ui.RouteState
import co.camilo.transmicrone.ui.ServiceLookupState
import co.camilo.transmicrone.ui.ServiceDestinationList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSearchScreen(
    query: String, results: CatalogResults, searching: Boolean, failed: Boolean,
    onQuery: (String) -> Unit, onDetails: (CatalogEntry) -> Unit, onBack: () -> Unit,
    boards: List<Board> = emptyList(),
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val screenEntered = LocalScreenEntered.current
    LaunchedEffect(screenEntered) { if (screenEntered) focus.requestFocus() }
    val list = rememberLazyListState()
    FloatingScaffold(topEdgeVisible = { list.canScrollBackward }, topBar = {
        FloatingSearchHeader(query, onQuery, stringResource(R.string.catalog_search_hint), focus, onBack, sharedKey = "search-capsule") { keyboard?.hide() }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(bottom = insets.calculateBottomPadding()).consumeWindowInsets(PaddingValues(bottom = insets.calculateBottomPadding())).imePadding(),
            state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                query.isBlank() -> {
                    // Saved boards are the likeliest searches, so offer them before any typing.
                    val services = boards.flatMap { it.servicios }.distinctBy { it.painted }
                    val places = boards.distinctBy { it.placeId }
                    if (services.isNotEmpty()) item {
                        SectionTitle(stringResource(R.string.catalog_your_services))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            services.forEach { servicio ->
                                Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable { onQuery(servicio.painted) },
                                    contentAlignment = Alignment.Center) {
                                    ServiceChipSurface(parseColor(servicio.color) ?: MaterialTheme.colorScheme.primary) {
                                        Text(servicio.painted, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                    if (places.isNotEmpty()) item {
                        SectionTitle(stringResource(R.string.catalog_your_places))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            places.forEach { board ->
                                SuggestionChip(onClick = { onQuery(board.displayName) }, label = { Text(board.displayName) },
                                    shape = CircleShape)
                            }
                        }
                    }
                    item { Message(stringResource(R.string.catalog_search_example)) }
                }
                searching -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Message(stringResource(R.string.searching_places))
                    }
                }
                failed -> item {
                    Message(stringResource(R.string.catalog_search_failed))
                    TextButton(onClick = { onQuery(query) }) { Text(stringResource(R.string.retry_refresh)) }
                }
                results.services.isEmpty() && results.places.isEmpty() -> item { Message(stringResource(R.string.catalog_no_results)) }
                else -> {
                    if (results.services.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.catalog_services)) }
                        items(results.services, key = { "service:${it.option.painted}\u0001${it.option.destino}" }) { entry ->
                            ServiceRow(entry.option) { keyboard?.hide(); onDetails(entry) }
                        }
                    }
                    results.places.groupBy { it.kind }.forEach { (kind, places) ->
                        item { SectionTitle(stringResource(if (kind == PlaceKind.ESTACION) R.string.search_stations else R.string.search_stops)) }
                        items(places, key = { it.placeId }) { place ->
                            LocationRow(place) { keyboard?.hide(); onDetails(CatalogEntry.Location(place)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDetailsScreen(
    entry: CatalogEntry, onBack: () -> Unit, onPlace: (Place) -> Unit,
    onService: (ServicioOption) -> Unit, onAdd: (Place) -> Unit,
    routeState: RouteState? = null, onRetryRoute: () -> Unit = {},
    routeIndex: Int = 0, onSelectRoute: (Int) -> Unit = {},
    serviceLookup: ServiceLookupState? = null, onRetryService: () -> Unit = {},
) {
    val location = (entry as? CatalogEntry.Location)?.place
    val service = entry as? CatalogEntry.Service
    val routes = (routeState as? RouteState.Loaded)?.routes.orEmpty()
    val route = routes.getOrNull(routeIndex) ?: routes.firstOrNull()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Long variants and large text stay in the list rather than consuming its viewport.
        val pinnedDirections = service != null && routes.size == 2 &&
            maxHeight / maxOf(1f, LocalDensity.current.fontScale) >= 480.dp
        val edgeList = rememberLazyListState()
        FloatingScaffold(
            topEdgeVisible = { edgeList.canScrollBackward },
            bottomEdgeBlur = pinnedDirections || location != null,
            bottomEdgeVisible = { edgeList.canScrollForward },
            topBar = {
                FloatingScreenHeader(stringResource(if (location != null) {
                    if (location.kind == PlaceKind.ESTACION) R.string.kind_estacion else R.string.kind_paradero
                } else R.string.catalog_service), stringResource(R.string.back), onBack)
            },
            bottomBar = {
                if (pinnedDirections) Box(Modifier.floatingHorizontalPadding()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom))
                    .padding(vertical = 16.dp)) {
                    ServiceRouteSelector(service, routes, routeIndex, onSelectRoute, floating = true)
                }
                if (location != null) FloatingActionPanel {
                    Button(onClick = { onAdd(location) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_board))
                    }
                }
            },
        ) { insets ->
            LazyColumn(Modifier.fillMaxSize(), state = edgeList, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 12.dp, bottom = insets.calculateBottomPadding() + 20.dp), verticalArrangement = Arrangement.spacedBy(if (service != null) 0.dp else 10.dp)) {
                if (location != null) item {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (location.kind == PlaceKind.ESTACION) Icons.Default.Tram else Icons.Default.DirectionsBus, null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Text(location.name, style = MaterialTheme.typography.headlineLarge)
                            location.disambiguator?.let { Message(it) }
                            Message(stringResource(R.string.setup_place_code, location.queryParadero))
                        }
                    }
                }
                if (service != null) item {
                    ServiceDetailsHeader(service, routes, routeIndex, onSelectRoute, showDirections = !pinnedDirections)
                }
                serviceLookup?.let { lookup ->
                    item {
                        if (lookup.failed) {
                            Message(stringResource(R.string.service_lookup_failed, lookup.option.painted))
                            TextButton(onClick = onRetryService) { Text(stringResource(R.string.retry_refresh)) }
                        } else {
                            Message(stringResource(R.string.service_lookup_loading, lookup.option.painted))
                        }
                    }
                }
                if (location != null) {
                    item { SectionTitle(stringResource(R.string.choose_servicios)) }
                    items(location.servicios, key = { "${it.painted}\u0001${it.destino}" }) { option ->
                        ServiceRow(option) { onService(option) }
                    }
                } else if (service != null) {
                    item { SectionTitle(stringResource(R.string.route_title)) }
                    when (routeState) {
                        null, RouteState.Loading -> item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Message(stringResource(R.string.route_loading))
                            }
                        }
                        RouteState.Failed, RouteState.Unavailable -> item {
                            Message(stringResource(if (routeState == RouteState.Failed) R.string.route_failed else R.string.route_unavailable))
                            TextButton(onClick = onRetryRoute) { Text(stringResource(R.string.retry_refresh)) }
                        }
                        is RouteState.Loaded -> {
                            if (route != null) {
                                items(route.stops.size, key = { "${route.catalogId}:$it" }) { index ->
                                    RouteStopRow(route.stops[index], index, route.stops.lastIndex) { place -> onPlace(place) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteStopRow(stop: RouteStop, index: Int, lastIndex: Int, onPlace: (Place) -> Unit) {
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .then(if (stop.place != null) Modifier.clickable { onPlace(stop.place) } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(32.dp).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val center = 24.dp.toPx()
                if (index > 0) drawLine(lineColor, Offset(size.width / 2, 0f), Offset(size.width / 2, center), strokeWidth = 2.dp.toPx())
                if (index < lastIndex) drawLine(lineColor, Offset(size.width / 2, center), Offset(size.width / 2, size.height), strokeWidth = 2.dp.toPx())
            }
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(24.dp), shape = CircleShape,
                color = if (index == 0 || index == lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (index == 0 || index == lastIndex) Text(stringResource(if (index == 0) R.string.route_origin else R.string.route_destination),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(stop.name, style = MaterialTheme.typography.titleMedium)
            Message(listOfNotNull(stop.code.takeIf { it.isNotBlank() },
                stop.wagon?.let { stringResource(R.string.route_wagon, it) },
                if (!stop.stopsHere) stringResource(R.string.route_no_stop) else null).joinToString(" · "))
        }
        if (stop.place != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            modifier = Modifier.padding(top = 18.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServiceRow(option: ServicioOption, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ServicioBadge(option.painted, option.color, containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val destinations = option.destino.split("||").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (destinations.size <= 1) {
                    Text(stringResource(R.string.destination_prefix), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(destinations.firstOrNull().orEmpty(), style = MaterialTheme.typography.titleMedium)
                } else {
                    ServiceDestinationList(option.destino, R.string.destination_prefix, MaterialTheme.typography.titleMedium)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocationRow(place: Place, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (place.kind == PlaceKind.ESTACION) Icons.Default.Tram else Icons.Default.DirectionsBus, null,
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium)
                Message(listOfNotNull(stringResource(if (place.kind == PlaceKind.ESTACION) R.string.kind_estacion else R.string.kind_paradero),
                    place.disambiguator, place.queryParadero).joinToString(" · "))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}
