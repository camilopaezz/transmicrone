# Transmicrone

Live TransMilenio / SITP buses: what a vehicle is, which service it is on, and where that report sits in the official catalogs.

## System

**SITP**:
Bogotá's integrated public transport as a whole.
_Avoid_: using SITP to mean only street buses

**TMSA**:
The operator company TRANSMILENIO S.A.
_Avoid_: TransMilenio when you mean the company

**Troncal**:
BRT trunk service on dedicated lanes. Live feed tags these `TransMilenio`.
_Avoid_: TM, TransMilenio (as a bus type)

**Zonal**:
Street-bus service. Live feed tags these `TransMiZonal`.
_Avoid_: SITP (that is the whole system)

**Alimentador**:
Feeder from a neighborhood to a portal. Live feed also tags these `TransMiZonal`.

**Dual**:
Bus with doors on both sides; mixes trunk and street. Live feed tags these `TransMilenio`.

**TransMiCable**:
The cable line. Not on the bus position feed.

## Service

**Servicio**:
The code painted on the bus and the maps (`B23`, `Z8`, `166`, `1`). This is what you query. Case-sensitive. Not unique across the system.
_Avoid_: route, línea, GTFS route_id

**Línea**:
The bidirectional pair (`BK23` = `B23` + `K23`). You do not query this.
_Avoid_: servicio (that is one direction)

**Variante**:
One scheduled shape under a servicio, identified by GTFS `route_id` (B23 Alcalá = `12819`). One servicio can return several variantes at once.

**Destino**:
Cleaned headsign (`Alcalá`). Wire encoding is NFD. GTFS trips do not carry this.

## Vehicle

**Bus**:
One reporting vehicle in a live response. No duplicates of the same live `id` inside one servicio.

**Label**:
Fleet number painted on the body (`D0032`, `N0008`, `Z10-2258`). Official flota calls this `codigo_bus` and may drop zeros (`D032`).
_Avoid_: plate, matrícula, bus number, `id`

**Live id**:
Internal vehicle record (`"14008"`). Zonal: digits of the label minus the hyphen. Troncal: older numeric fleet id, not the raw digits of the label.
_Avoid_: label

**Accesibilidad**:
How a rider boards: `PLATAFORMA ALTA`, `CON ELEVADOR`, `ENTRADA BAJA`, `NINGUNA`. Flota says `NO ACCESIBLE` for that last one.

**Ocupación**:
Load hint: `VACIO`, `MEDIO`, `LLENO`, or empty. Empty is the usual value, not "unknown enum".

## Place

**Estación**:
A troncal BRT station. App codes look like `TM0002`. Has vagones.
_Avoid_: paradero, stop

**Vagón**:
One platform face of an estación. Arrivals at an estación can list several nodos.

**Paradero**:
A zonal street stop. What you stand at.
_Avoid_: estación, stop (when you mean this)

**Cenefa**:
The code on the stop sign (`080A01`). That is the `paradero` you send. GTFS adds a `_TM` suffix; the live host does not want it.

**Nodo**:
GTFS `stop_id` as returned on the arrivals board (`49841` for cenefa `080A01`).

## Report

**Fix**:
One GNSS report: coordinates, lasttime, ángulo, posición. About a minute old.

**Lasttime**:
Bogotá wall clock of that fix (`09:05:31 AM`). No date, no timezone in the field.

**Posición**:
Metres along the variante's shape from the origin. `0` is the first shape point, not "not reporting".
_Avoid_: stop sequence, percent, GPS accuracy

**Ángulo**:
Heading in degrees.

**Board**:
Arrivals at a paradero or estación: servicio, destino, distance label, ETA phrase. No label, no live id.

**Horario teórico**:
Scheduled remaining times at a paradero, in seconds from midnight Bogotá. Not a fix.
