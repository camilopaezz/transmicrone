# Android UI, Material 3 refresh

Updated 2026-10-02. Material 3 with the Expressive design direction: clear type hierarchy, rounded tonal containers, prominent primary actions, and motion that reflects a change of state. Spanish UI, system font, edge-to-edge layouts. Domain behavior remains in [app-scope.md](app-scope.md).

References: [Material 3](https://m3.material.io/), [Compose Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3). The app uses the current stable Material 3 library, 1.4.0, with Compose BOM 2025.08.01. Expressive-only APIs that are still alpha are not required for this design.

## Theme

Android 12 and newer use the system wallpaper's dynamic light/dark color scheme. Earlier devices use a complete warm transit-red scheme, including surface-container, primary-container, secondary-container, and outline roles. Typography uses Android's system font with medium-weight station headings and stronger ETA phrases. Shapes range from 12 to 32 dp; buttons and selectors use pill shapes.

Servicio badges retain the reported route color. Black or white badge text is selected by luminance for contrast, rather than trusting an incompatible or missing upstream text color. JVM tests check foreground/background contrast for fallback themes and the tonal containers used by arrivals and service selection.

## Home

- Small app bar with a labeled “Tableros” action for saved-board management. The station name has its own large heading and room to wrap.
- Saved-board filter chips, swiping, and named previous/next controls provide equivalent ways to switch boards.
- Selected servicios appear below the place heading. The station header scrolls with its arrivals. Once it leaves view, the station name moves into the flat app bar. A 24 dp fading edge softens content passing beneath the app bar or board tabs. Each board keeps its own list scroll position.
- Refresh status sits beneath the station title alongside the corridor and shows elapsed time, updated every second. Status text wraps for cached data and failures. Pull-to-refresh is the only manual refresh action and supplies the loading indicator.
- Combined arrivals use separate rounded containers. Each shows servicio, destination, the exact ETA phrase, and occupancy. Station vehicle cards also show fleet label and distance. The update timestamp appears in the station header; individual report timestamps and ages are omitted from cards. Reports older than two minutes show “Reporte antiguo” above the last estimate. ETA text sits below the destination so neither competes for a narrow trailing column.
- The add-board action is an extended FAB. Arrival-list padding leaves room for it without covering content.
- The unofficial-feed notice follows the list instead of taking up a fixed bottom bar.
- Startup shows a loading state until the saved-board snapshot arrives, avoiding the previous empty-board flash.

## Setup

Two steps: find a place, then choose servicios. Search uses a pill-shaped Material text field with search and clear controls. Result containers show place type, disambiguator, and a directional affordance.

Service selection shows the selected place, explanation, and individual service rows. Each row is one accessible toggle; its checkbox does not create a second competing tap target. Selected rows animate to the secondary-container color. A bottom save button shows the selected-service count and is enabled only when a selection exists.

## Board management

Saved places use rounded containers with name, corridor, and selected servicios. Each board's three-dot menu contains “Editar tablero”, move up/down, and delete actions. Deletion uses a Material confirmation dialog. Android system Back returns from setup/management to home.

## Behavior and accessibility

Interactive targets remain at least 48 dp. Text and containers grow with font scale. Service ETA phrases remain verbatim; no precise countdown is derived. Cache, loading, successful empty results, unsupported queries, and failures retain distinct messages. Missing occupancy remains unknown. TalkBack arrival descriptions preserve servicio, destination, phrase, occupancy, and the appropriate vehicle or service estimate caveat.

Polling remains 25 seconds for the visible board while started, with an 8-second manual-refresh throttle. This UI change does not alter feed identifiers, installation IDs, or request semantics.
