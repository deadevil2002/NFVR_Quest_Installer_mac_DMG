# Desktop workflow boundaries in 2.3.0

The desktop layer now treats the ADB serial as a workflow boundary. Installed
app snapshots are kept per serial, scan callbacks carry the captured serial,
and a serial change invalidates the selected game, analysis, and operation
binding. A cached snapshot is only restored for the same serial and is labelled
as stale while it refreshes. A newly connected serial requires an explicit
application scan.

Activity and readiness timestamps are persisted as ISO `Instant` values and
rendered through `ZoneId.systemDefault()`. The formatter accepts an injected
`Clock` for timezone and midnight-rollover tests.

The Windows picker uses isolated per-attempt STA workers, an explicit
`IDLE → OPENING → OPEN → RESULT/CANCEL/ERROR → DISPOSING → IDLE` lifecycle, and
an initialization-only watchdog. The watchdog revokes the atomic native
`Show()` permit and quarantines a timed-out initialization so a new attempt can
proceed. It never times out or releases an active modal dialog. Attempt IDs
prevent late callbacks from changing the next attempt's state.
The JNA mappings are intentionally visible to JNA reflection and the
owner HWND is passed through unchanged.

ZIP and game-folder drops are handled by one AWT `DropTarget` attached to the
Compose host. A ZIP drop enters the same orchestration as a successful picker
selection; a folder drop enters the same `inspectGameFolder` path. Drops outside
the highlighted regions or with the wrong type are rejected.

Copyable diagnostics redact local user/temp/drive paths while retaining remote
Quest destinations and technical package information. Mod operation bindings
include serial, package, game version, archive SHA-256, and a deterministic
analysis plan ID.