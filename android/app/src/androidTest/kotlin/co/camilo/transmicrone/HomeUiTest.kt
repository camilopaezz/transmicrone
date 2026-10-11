package co.camilo.transmicrone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.Occupancy
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.ui.BoardView
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.home.HomeScreen
import co.camilo.transmicrone.ui.setup.SetupScreen
import co.camilo.transmicrone.ui.theme.TransmicroneTheme
import co.camilo.transmicrone.ui.key
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oldBusReportDoesNotClaimArrivingAfterFreshFetch() {
        val now = Instant.parse("2026-10-02T13:19:03Z")
        compose.setContent {
            TransmicroneTheme {
                co.camilo.transmicrone.ui.board.ArrivalRow(sampleArrival().copy(
                    etaPhrase = "Servicio llegando",
                    identity = co.camilo.transmicrone.domain.ArrivalIdentity.VEHICLE,
                    vehicleId = "41405", label = "T10405", distanceLabel = "2594 m",
                    sourceReportedAt = now.minusSeconds(180),
                ), now)
            }
        }
        compose.onNodeWithText("Reporte antiguo", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Reporte 08:16:03", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Bus T10405 · 2594 m", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Última estimación: Servicio llegando", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun emptyHomeShowsAddBoard() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = emptyList(),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithText("Sin tableros").assertIsDisplayed()
        compose.onNodeWithText("Agregar tablero").assertIsDisplayed()
    }

    @Test
    fun loadingSavedBoardsDoesNotFlashTheEmptyState() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(emptyList(), 0, {}, {}, {}, {}, loadingBoards = true)
            }
        }
        compose.onNodeWithText("Cargando tus tableros…").assertIsDisplayed()
        compose.onAllNodesWithText("Sin tableros").assertCountEquals(0)
        compose.onAllNodesWithText("Agregar tablero").assertCountEquals(0)
    }

    @Test
    fun boardShowsPhraseNotCountdownAndUnknownOccupancy() {
        val board = Board(
            boardId = "1",
            placeId = "estacion:TM0101",
            kind = PlaceKind.ESTACION,
            queryParadero = "TM0101",
            displayName = "León XIII",
            disambiguator = "Soacha",
            servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
        )
        val arrival = Arrival(
            painted = "E48",
            destino = "CAD",
            routeId = 13266,
            nodo = 62282,
            etaPhrase = "Entre 2 y 4 min",
            distanceLabel = "200 mts",
            occupancy = Occupancy.UNKNOWN,
            colorRuta = "#AE6B00",
        )
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(
                        BoardView(
                            board = board,
                            arrivals = listOf(arrival),
                            freshness = Freshness.Updated(Instant.parse("2026-10-02T05:00:00Z")),
                        ),
                    ),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onAllNodesWithText("León XIII")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Soacha").assertCountEquals(0)
        compose.onAllNodesWithText("E48", useUnmergedTree = true)[0].assertIsDisplayed()
        compose.onNodeWithText("CAD", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Entre 2 y 4 min", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ocupación desconocida", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Tableros").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Editar tablero")[0].assertIsDisplayed()
        val rowName = compose.onAllNodesWithContentDescription(
            "E48 a CAD, Entre 2 y 4 min, 200 m, Ocupación desconocida",
            substring = true,
        ).fetchSemanticsNodes()
        assertTrue(rowName.isNotEmpty())
    }

    @Test
    fun homeOmitsRefreshStatusWhileArrivalsAreShown() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(
                        BoardView(
                            board = sampleBoard(),
                            arrivals = listOf(sampleArrival()),
                            freshness = Freshness.FailedCached(
                                Instant.parse("2026-10-02T05:00:00Z"),
                                FailureKind.HOST_REJECTED,
                            ),
                        ),
                    ),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onAllNodesWithText("el servidor rechazó la consulta", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Mostrando datos anteriores", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Actualizado", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Reintentar").assertCountEquals(0)
    }

    @Test
    fun updatingCachedDoesNotShowAStatusLine() {
        compose.setContent {
            TransmicroneTheme {
                HomeScreen(
                    boards = listOf(
                        BoardView(
                            board = sampleBoard(),
                            arrivals = listOf(sampleArrival()),
                            freshness = Freshness.UpdatingCached(Instant.parse("2026-10-02T05:00:00Z")),
                        ),
                    ),
                    pageIndex = 0,
                    onPage = {},
                    onAdd = {},
                    onEdit = {},
                    onRefresh = {},
                )
            }
        }
        compose.onAllNodesWithText("Mostrando datos anteriores", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Actualizado", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("el servidor rechazó la consulta", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("No se pudo actualizar", substring = true).assertCountEquals(0)
    }

    @Test
    fun setupDoesNotClaimEmptyWhileCatalogLoads() {
        compose.setContent {
            TransmicroneTheme {
                SetupScreen(
                    query = "leon",
                    results = emptyList(),
                    place = null,
                    selected = emptySet(),
                    catalogReady = false,
                    searching = true,
                    onQuery = {},
                    onPick = {},
                    onToggle = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Cargando catálogo…").assertIsDisplayed()
        compose.onAllNodesWithText("No hay lugares con ese nombre en el catálogo.").assertCountEquals(0)
    }

    @Test
    fun serviceSelectionEnablesSaveAndSavesTheSelectedService() {
        val option = ServicioOption("E48", "CAD", listOf(13266), null)
        val place = Place("estacion:TM0101", PlaceKind.ESTACION, "León XIII", "leon xiii", "Soacha", "TM0101", listOf(option))
        val selected = mutableStateOf(emptySet<String>())
        var saved = false
        compose.setContent {
            TransmicroneTheme {
                SetupScreen("", emptyList(), place, selected.value, true, false,
                    onQuery = {}, onPick = {}, onToggle = { key -> selected.value = if (key in selected.value) emptySet() else setOf(key) },
                    onSave = { saved = true }, onBack = {})
            }
        }
        compose.onNodeWithText("Guardar tablero").assertIsNotEnabled()
        compose.onNodeWithText("CAD").performClick()
        compose.onNodeWithText("Guardar tablero").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(saved)
            assertTrue(option.key() in selected.value)
        }
    }

    @Test
    fun arrivalPhraseAndOccupancyRemainVisibleWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                TransmicroneTheme {
                    co.camilo.transmicrone.ui.board.ArrivalRow(sampleArrival())
                }
            }
        }
        compose.onNodeWithText("Entre 2 y 4 min", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ocupación desconocida", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun sampleBoard() = Board(
        boardId = "1",
        placeId = "estacion:TM0101",
        kind = PlaceKind.ESTACION,
        queryParadero = "TM0101",
        displayName = "León XIII",
        disambiguator = "Soacha",
        servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
    )

    private fun sampleArrival() = Arrival(
        painted = "E48",
        destino = "CAD",
        routeId = 13266,
        nodo = 62282,
        etaPhrase = "Entre 2 y 4 min",
        distanceLabel = "200 mts",
        occupancy = Occupancy.UNKNOWN,
        colorRuta = "#AE6B00",
    )
}
