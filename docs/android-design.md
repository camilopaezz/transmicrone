# Android UI, Material 3 refresh

Updated 2026-10-04. Material 3 with the Expressive design direction: clear type hierarchy, rounded tonal containers, prominent primary actions, and motion that reflects a change of state. Spanish UI, bundled Adwaita Sans typography, edge-to-edge layouts. Domain behavior remains in [app-scope.md](app-scope.md).

References: [Material 3](https://m3.material.io/), [Compose Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3). The app uses the current stable Material 3 library, 1.4.0, with Compose BOM 2025.08.01. Expressive-only APIs that are still alpha are not required for this design.

## Theme

Android 12 and newer use the system wallpaper's dynamic light/dark color scheme. Earlier devices use a complete warm transit-red scheme, including surface-container, primary-container, secondary-container, and outline roles. Typography uses MoonMode’s Adwaita Sans regular and medium fonts with medium-weight station headings and stronger ETA phrases. Font licenses are bundled in the app assets. Shapes range from 12 to 32 dp; buttons and selectors use pill shapes.

Home service chips and arrival badges share the saved catalog route color, falling back to the live route color when needed. Black or white badge text is selected by luminance for contrast, rather than trusting an incompatible or missing upstream text color. JVM tests check foreground/background contrast for fallback themes and the tonal containers used by arrivals and service selection.

## Home

- The app bar pencil edits the current board directly, with the accessible name “Editar tablero”. The station name has its own large heading and room to wrap.
- Saved-board filter chips remain visible with one or more boards, followed by “+ Agregar”. Chips and horizontal swiping switch boards. Tiny page dots share the service-badge row. A brief “Desliza para cambiar de tablero” hint overlays the arrival viewport with multiple boards and disappears on drag or after five seconds without moving the list. Dismissal survives screen navigation and rotation for this session. There are no arrow controls or numeric page counter.
- Selected servicios appear below the place heading. The station header scrolls with its arrivals. Once it leaves view, the station name moves into the flat app bar. A 24 dp fading edge softens content passing beneath the app bar or board tabs. Each board keeps its own list scroll position.
- Refresh status sits beneath the station title alongside the corridor and shows elapsed time, updated at the next minute, hour, or day boundary. Status text wraps for cached data and failures. Failures use an error icon and color with a “Reintentar” action. Pull-to-refresh uses a thin indicator in a reserved strip above the list, so it never covers an arrival or moves the list when refresh starts.
- Combined arrivals use separate rounded containers. Each shows servicio, destination, the cleaned display ETA phrase, and occupancy. Station vehicle cards also show fleet label and distance. The update timestamp appears in the station header; individual report timestamps and ages are omitted from cards. Reports older than two minutes show “Reporte antiguo” above the last estimate. ETA text sits below the destination so neither competes for a narrow trailing column.
- Adding lives in the board-chip row, leaving the arrival viewport free of floating buttons and their reserved clearance. Header service chips retain their route colors.
- The unofficial-feed notice follows the list instead of taking up a fixed bottom bar.
- Startup shows a loading state until the saved-board snapshot arrives, avoiding the previous empty-board flash.

## Setup

Two steps: find a place, then choose servicios. Search focuses automatically and supports the keyboard search action. When adding, back from service selection returns to search with its query retained. When editing, both system back and app-bar back return home without saving. Choosing the same place restores the draft service selection; choosing another place resets it. Editing uses “Editar tablero” in the app bar. Search uses a pill-shaped Material text field with search and clear controls. Result containers show place type, disambiguator, and a directional affordance. Stops sharing both name and address also show their distinct catalog codes.

Service selection shows the selected place in a neutral card with a “Cambiar” action, explanation, and individual service rows. Each row is one accessible toggle; its checkbox does not create a second competing tap target. Selected rows animate to the secondary-container color. Editing places initially selected services first and keeps that order while toggling. A bottom save button shows the pluralized selected-service count, or explains that at least one service is required. It is enabled only when a selection exists.

## Editing and deleting

The pencil opens the current board's service selection. “Cambiar” opens station search while retaining the edit draft. Saving returns home with the updated board selected. “Eliminar tablero” lives beneath the save action and names the saved board in a Material confirmation dialog, even if the draft station has changed. Deleting selects the neighbor at the removed board’s index, clamped to the remaining range. Editing still offers deletion if the catalog no longer contains its place. Deleting returns home and offers “Deshacer”, including after deleting the final board. Undo restores only the deleted board, retaining later additions, edits and selection changes. The management screen is removed; reordering is deferred to a future settings page.

## Behavior and accessibility

Interactive targets remain at least 48 dp. Text and containers grow with font scale. Presentation repairs missing spaces and standardizes metric units without changing the stored feed phrase. It removes only equivalent duplicate distance suffixes; no precise countdown is derived. Cache, loading, successful empty results, unsupported queries, and failures retain distinct messages. Missing occupancy remains unknown. TalkBack arrival descriptions preserve servicio, destination, phrase, occupancy, and the appropriate vehicle or service estimate caveat.

Polling remains 25 seconds for the visible board while started, with an 8-second manual-refresh throttle after successful refreshes. Failed requests can retry immediately, while an in-flight guard prevents duplicate requests. This UI change does not alter feed identifiers, installation IDs, or request semantics.
