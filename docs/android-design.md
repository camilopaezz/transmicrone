# Android UI

The current app uses Material 3 Expressive, bundled Adwaita Sans, a Spanish UI, and edge-to-edge layouts. Product boundaries are in [app-scope.md](app-scope.md); builds and verification are in [the Android README](../android/README.md).

## Theme

System, light, and dark modes are available in Ajustes. Android 12+ wallpaper colors are enabled by default and can be disabled. Other devices, and devices with wallpaper colors disabled, use the transit-red palette. Settings persist separately from saved boards.

`MaterialExpressiveTheme` supplies the motion scheme. App shapes range from 12 to 32 dp. Medium-weight headings and emphasized destination and operating-time styles establish the text hierarchy. Font licenses are bundled with the app.

Service chips and arrival badges use the saved catalog route color, falling back to the live color. Badge text is black or white according to luminance. Theme tests cover text contrast for the fallback palettes and tonal containers.

## Home and board selection

The home screen uses separate floating capsules for the brand and search/settings actions over the base surface. A centered floating dock holds edit and add-board actions. Capsules use a Material tonal tint over a clipped 20 dp backdrop blur, with a subtle outline. Android 11 and earlier use a stronger translucent tint when backdrop blur is unavailable. The header wraps at large font scales, and the arrivals list has bottom clearance so its last item can scroll above the dock. In windows shorter than 480 dp, or with less than 360 dp of effective height after font scaling, add/edit move into the header and the board title scrolls with the arrivals, leaving the list clear of the dock. An empty installation shows an add-board action; startup waits for saved boards to load.

The station title stays above the arrivals and moves with the horizontal board pager. Names slide, scale, and fade during gestures; long names ellipsize. Neighboring title hints, a progress line, a text page count, and a temporary swipe hint make additional boards visible. Tap or hold the title, or tap the page count, to open a frosted Material bottom sheet with full board names, locations, selected services, and an add action. The selected board retains a solid secondary-container fill. The sheet shares the arrivals backdrop and keeps Material dismissal and scrolling behavior.

Paging persists selection only after settling. Board IDs preserve each board's vertical scroll position. Selected services scroll with arrivals while the title selector remains visible. A fading edge softens content passing beneath it.

Pull-to-refresh requires a new downward gesture that starts at the top of the list, even if the preceding scroll animation is still settling. Fling momentum and the remainder of a return-to-top drag cannot arm refresh. Scrolling back to the top does not refresh. It uses a reserved 4 dp progress strip. Active request progress takes precedence over pull-distance feedback and stays visible while scrolling. Switching to a cached board fetches quietly; an uncached first fetch shows decorative skeleton cards. The home header omits fetch age and a retry action. Empty, unsupported, and failed requests have distinct empty-list messages. Cached arrivals remain visible after a failure, without a separate visible failure banner. The unofficial-feed notice follows the list.

Arrival cards show service, destination, the cleaned server ETA phrase, and occupancy. Vehicle cards also show fleet label and distance. Reports older than two minutes show `Reporte antiguo` above the last estimate. Individual source timestamps and ages are omitted. Missing occupancy remains unknown; spacing and metric-unit cleanup preserve the stored feed phrase.

Search, place/service details, setup, and settings share the home capsule treatment. All floating header labels, including the home brand, use the same Material title-medium typography. Headers have 16 dp of space above their controls and 4 dp below, in addition to the system status inset. The home board title has an additional 8 dp top inset. Back navigation has its own 52 dp circle beside a title capsule that grows for wrapped text. Search replaces the title capsule with a glass search field beside the back button, both in Buscar and in the place step of board setup. The field stays available while results scroll and preserves clear, keyboard search, and automatic focus behavior. Search, setup, settings, and detail lists scroll behind the transparent header; its initial clearance is list content padding rather than a fixed opaque strip. Place details and board editing use detached glass action panels. Their lists extend behind the panels and the transparent surrounding area; bottom clearance is scrollable content padding so the last row can move above the actions. Save-panel status text has an additional 12 dp horizontal and 4 dp vertical inset inside the panel. Board actions join the scrollable list when the keyboard opens or the window is shorter than 480 dp, so the name field, services, save, and delete remain reachable. These inline panels use a solid tonal fill, avoiding a blur that would read its own source. Shared safe insets protect headers, lists, and panels from side cutouts. Search consumes the navigation inset before applying keyboard padding.

## Setup, editing, and deletion

Setup has two steps: choose a place, then select services. Search focuses automatically and supports the keyboard search action. Results show place type and disambiguating information; stops sharing name and address also show catalog codes.

When adding, back from service selection returns to the retained search query. When editing, back returns home without saving. Choosing the same place restores the draft selection; a different place resets it. An optional board name is trimmed, and a blank name uses the station or stop name.

Each service row is one accessible toggle. Selected rows use the secondary-container color. Editing initially lists saved services first and preserves that order while toggling. The bottom save action names the selected-service count and requires at least one selection.

The pencil opens the current board's editor. `Cambiar` opens place search with the edit draft retained. Saving returns home with the updated board selected. `Eliminar tablero` sits beneath save and confirms the saved board's name. Deletion works even when the catalog no longer contains its place, selects a remaining neighbor, and offers undo. Undo restores that board while retaining subsequent edits and selection changes. Ajustes can reorder boards with accessible up/down controls.

## Catalog details

Place details list serving services and can start a board. Service details show a direction selector, service badge and stop count, destination and origin, published hours, and ordered route stops. Hours and routes come from the packaged catalog and work offline.

For two route variants in windows with at least 480 dp of effective height after font scaling, the direction selector sits in a glass panel below the floating header and remains available while stops scroll. In shorter windows, at larger text sizes, or with more variants, it stays inline with a solid tonal fill. Selection retains Material toggle motion and radio-button semantics.

The direction selector uses one shared track with 4 dp padding and gaps, 18 dp outer corners, and stable 14 dp segment corners. Two equal-width choices fit each row. `Hacia` sits above the destination; rows share a height of at least 64 dp and grow for wrapped text. Selection uses the secondary-container fill and radio-button semantics. Duplicate destination names include a variant number. Switching variants updates the header, hours, and route together.

## Accessibility and freshness

Interactive targets are at least 48 dp. Containers grow with font scale. Board sheets and accessibility labels expose full names when visible titles ellipsize. Arrival descriptions include service, destination, ETA, occupancy, and the vehicle or service estimate caveat.

The visible board polls every 25 seconds. Successful manual refreshes have an eight-second throttle; failed requests can retry immediately, and an in-flight guard prevents duplicate requests. Fetch time and vehicle source time remain separate. The UI marks known vehicle reports older than two minutes as stale and does not derive a precise countdown.

Ajustes also explains arrival ranges, old reports, and unknown occupancy, and shows the app version and unofficial data-source notice. There is no in-app credential or sample-data toggle.
