# Android arrivals app scope

Agreed with the user on 2026-10-01. An Android prototype now exists under `android/`; build and validation results are in [its README](../android/README.md). Live-feed capabilities still need validation against the intended experience.

## Purpose

Show the approaching buses a person would actually take, together, without inspecting a map or checking each servicio separately.

The user currently finds TransMiApp slow and its one-servicio-at-a-time map unhelpful for comparing alternatives. The app supplies information. The person decides which bus to take based on their circumstances, including how late they are.

The first tester is the user. Their commute is a test case, not a hard-coded configuration. Other people should be able to configure the app around their own places and servicios.

## First version

Android, with a personalized home screen of saved arrivals boards.

### Setup

1. Search for an estación or paradero by name.
2. Select one place for the board.
3. Choose the servicios to watch from those serving that place.
4. Save the board and repeat for other places.

People already know which servicios they want. Destination-based discovery and journey planning are outside this version. Users should not need internal place codes, variante IDs, or exact vagón names to configure a board.

### Daily use

- Display one saved board at a time.
- Swipe left or right to switch between boards.
- Reopen the last-used board.
- Combine the selected servicios into one list, approximately soonest arrival first. Do not group the list by servicio.
- Identify each arrival by servicio and destino.
- Show the available arrival range or phrase. Do not turn coarse phrases into precise countdowns.
- Refresh automatically while the board is open.
- Show when the board last refreshed and whether a request is loading or has failed.
- Display cached content promptly while fetching an update, clearly marked as old. Cached content is not evidence that a bus is still approaching.
- Show ocupación when reported. Missing ocupación means unknown, never empty.

Multiple approaching buses per servicio are desired, but depend on feed validation. Never present one servicio-level estimate as several identified buses. Overlapping ETA ranges do not establish an exact ordering.

A board has exactly one estación or paradero. Combining different places into the same board is outside this version. Relevant vagones may need internal handling for correct results, even though their names are not required during setup.

## Exclusions

- Maps on the main screen.
- Journey planning or destination search.
- Recommendations about which bus to take.
- Predictions or instructions about when to leave home.
- Account requirement.
- Background tracking and arrival notifications.
- Dependable crowding predictions.

Alerts are a candidate for later. Decide their scope after using the board and measuring whether the data provides useful warning.

## First test case

| Board | Selected servicios | Direction |
| --- | --- | --- |
| León XIII | E48, K43 | Toward Ricaurte |
| Ricaurte | S48, S43 | Toward León XIII |

The user describes alternative boarding points about 20 metres apart, with the exact location to confirm during testing. They sometimes prefer E48 for a better chance of space, but may choose K43 when in a hurry. No fixed crowding-versus-speed preference belongs in the app.

Walking time is about 15 minutes from home to León XIII and 10 minutes from work to Ricaurte. The user estimates León XIII is about 5 minutes from the servicio's origin. This limits how useful tracking an already-running bus would be for deciding when to leave home. It does not prevent the board from helping at the estación.

## Data constraints

Read [feed.md](feed.md), [arrivals.md](arrivals.md), and [join.md](join.md) before integrating or testing the live endpoints.

Documented observations are not a current availability guarantee:

- The unofficial feed may change or stop answering.
- The live paths require a Colombian IP and an app-level header. Native Android networking does not require a proxy solely for browser CORS.
- The basic arrivals board supplies servicio-level ETA phrases, without vehicle identity.
- The nearby-vehicle endpoint supplies vehicle identities, but returned at most three vehicles in the documented test case.
- Live position reports were roughly 30–56 seconds old in the documented checks.
- Servicio codes alone are not unique identifiers for variantes.
- Ocupación is usually missing. The user still considers the app useful without it.

Distinguish the time of a successful fetch from the age of a vehicle's position report. A recently refreshed screen may contain older source observations. Do not describe a successful empty result, an unsupported query, and a failed request as the same state.

## Validation before UI implementation

1. Verify that catalog search can resolve the intended estaciones and paraderos, with their correct selected servicios and variantes.
2. Check arrivals and nearby vehicles for the four test servicios at the appropriate estaciones.
3. Determine whether the feeds can support a combined list of distinct approaching buses, including more than one bus per servicio, without duplicates or invented precision.
4. Observe whether the data gives the user enough warning to switch between the nearby boarding points.
5. Observe missing occupancy, stale reports, request failures, and missing arrivals so the board can represent them honestly.

The practical success test is whether the user can notice an approaching alternative without switching servicio views and avoid missing it at the other boarding point. A responsive interface alone does not establish that the feed is timely enough.

## Still to decide

- Android implementation technology.
- Polling cadence and stale-data thresholds, based on observation and responsible feed use.
- Whether direct requests are sufficient for the prototype or a controlled backend is warranted.
- Detailed board-management interactions and visual design.
- Any data recording for research, including consent and retention. This scope does not authorize automatic collection of personal location or trip history.
