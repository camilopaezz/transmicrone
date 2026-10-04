package co.camilo.transmicrone.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import co.camilo.transmicrone.R
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.ui.board.ServicioBadge
import co.camilo.transmicrone.ui.key

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    query: String, results: List<Place>, place: Place?, selected: Set<String>,
    catalogReady: Boolean, searching: Boolean,
    onQuery: (String) -> Unit, onPick: (Place) -> Unit, onToggle: (String) -> Unit,
    onSave: () -> Unit, onBack: () -> Unit,
    isEditing: Boolean = false, onChangePlace: () -> Unit = onBack,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val repeatedNames = remember(results) { results.groupingBy { it.name }.eachCount() }
    val repeatedLocations = remember(results) {
        results.groupingBy { Triple(it.name, it.kind, it.disambiguator?.trim().orEmpty()) }.eachCount()
    }
    LaunchedEffect(place == null) {
        if (place == null) {
            searchFocus.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isEditing) R.string.edit_board else R.string.add_board), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = if (place == null) onBack else onChangePlace) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        bottomBar = {
            if (place != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (selected.isEmpty()) stringResource(R.string.need_servicio)
                        else pluralStringResource(R.plurals.setup_selected_services, selected.size, selected.size),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSave, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.save_board))
                    }
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
            if (place == null) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.setup_step_place), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.setup_place_title), style = MaterialTheme.typography.headlineMedium)
                            TextField(
                                value = query, onValueChange = onQuery,
                                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                shape = CircleShape,
                                label = { Text(stringResource(R.string.search_hint)) },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.clear_search)) } }) else null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    onQuery(query)
                                    keyboard?.hide()
                                }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                ),
                            )
                            if (query.length < 2) Text(
                                stringResource(R.string.search_example),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (query.length >= 2 && (!catalogReady || searching || results.isEmpty())) item {
                        val loading = !catalogReady || searching
                        val status = when {
                            !catalogReady -> stringResource(R.string.catalog_loading)
                            searching -> stringResource(R.string.searching_places)
                            else -> stringResource(R.string.no_place_results)
                        }
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (catalogReady && !searching) {
                        results.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal }).forEach { (kind, places) ->
                            item(key = "group-${kind.name}") {
                                Text(stringResource(if (kind == PlaceKind.ESTACION) R.string.search_stations else R.string.search_stops),
                                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                            }
                            items(places, key = { it.placeId }) { item ->
                                PlaceRow(
                                    item,
                                    leadWithAddress = repeatedNames.getOrDefault(item.name, 0) > 1,
                                    showCode = repeatedLocations.getOrDefault(Triple(item.name, item.kind, item.disambiguator?.trim().orEmpty()), 0) > 1,
                                ) {
                                    keyboard?.hide()
                                    onPick(item)
                                }
                            }
                        }
                    }
                }
            } else {
                // Snapshot the edit selection so toggling a row never moves it under the user's finger.
                val initiallySelected = remember(place.placeId, isEditing) { if (isEditing) selected.toSet() else emptySet() }
                val orderedServices = remember(place.servicios, initiallySelected) {
                    place.servicios.sortedBy { if (it.key() in initiallySelected) 0 else 1 }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.setup_step_services), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.choose_services_title), style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.setup_services_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(place.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(stringResource(if (place.kind == PlaceKind.ESTACION) R.string.kind_estacion else R.string.kind_paradero), place.disambiguator).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onChangePlace, modifier = Modifier.align(Alignment.End)) {
                                    Text(stringResource(R.string.setup_change_place))
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.choose_servicios), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp)) }
                    items(orderedServices, key = { it.key() }) { option ->
                        val key = option.key()
                        val checked = key in selected
                        val container by animateColorAsState(
                            if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            label = "service selection",
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(key) }),
                            shape = MaterialTheme.shapes.medium,
                            color = container,
                            border = if (checked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ServicioBadge(option.painted, option.color)
                                Text(option.destino, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, leadWithAddress: Boolean = false, showCode: Boolean = false, onClick: () -> Unit) {
    val kind = stringResource(if (place.kind == PlaceKind.ESTACION) R.string.kind_estacion else R.string.kind_paradero)
    val address = place.disambiguator?.takeIf { it.isNotBlank() }
    val useAddress = leadWithAddress && address != null
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Icon(if (place.kind == PlaceKind.ESTACION) Icons.Default.Tram else Icons.Default.DirectionsBus, null,
                    Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (useAddress) address.orEmpty() else place.name, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(if (useAddress) place.name else null, kind, if (useAddress) null else address).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showCode) Text(stringResource(R.string.setup_place_code, place.queryParadero),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
