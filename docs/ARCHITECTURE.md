# Architecture

## Shape of the application

The application will be a single Android application module with a small,
feature-oriented structure:

```text
Application-scoped AppContainer
        |
UI (Compose screens)
        |
ViewModels and UI state (StateFlow)
        |
Repository
   /              \
Room database       ReminderScheduler
                      |
                 AlarmManager
                      |
             Alarm/boot/time receivers
                      |
                Notification actions
```

Room remains the source of truth. Android alarms and visible notifications are
derived state and must be safe to cancel and recreate from stored reminders.
The same database also contains one application-settings row for the persisted
global reminder-delivery switch.

Phase 2 adds the Compose reminder-management UI on top of the persisted,
pure-Kotlin recurrence/state model. Phase 3 adds notification presentation,
permission UX, and action contracts. Phase 4 adds Room-derived AlarmManager
scheduling and delivery. Phase 5 adds Room-backed notification action
processing and notification-swipe handling. Phase 6 adds the read-only,
repository-backed global history destination and completes recovery coverage.

## Planned packages

- `data`: Room entities, DAOs, database, and repository.
- `domain`: reminder models, validation, event semantics, and pure recurrence calculation.
- `scheduling`: `ReminderScheduler`, the `AlarmManager` implementation, and
  alarm/recovery receivers.
- `notifications`: channel, notification factory, and action handling.
- `ui`: reminder list/editor/history models, ViewModels, screens, and Compose
  navigation.

`RegularNotificationsApplication` owns one lazy `AppContainer` per app
process. The container owns the Room database and repository. Future UI,
alarm, boot/time, and notification-action components must obtain this shared
container instead of opening their own Room database instances.

The UI maps Room entities to small presentation models before rendering. A
list ViewModel owns the `StateFlow` for loading, content, and errors. A separate
editor ViewModel owns the short form and its validation state. Navigation has
three destinations: the reminder list, a create/edit editor, and global
history. Popping the editor after Save or Cancel clears its destination-scoped
ViewModel, preventing stale form state on a later edit. History is read-only
and has its own destination-scoped ViewModel.

## Reminder and recurrence decisions

Each reminder stores a stable ID, title, optional description, enabled state,
the first local date/time, a positive `intervalDays`, next scheduled occurrence,
and creation/modification timestamps. A recurrence calculator is a pure Kotlin
component so it can be tested without Android.

Every reminder is an Every-X-days local calendar schedule. The calculator
advances from the original local date/time anchor by `intervalDays` while
preserving the intended wall-clock time, including daylight-saving transitions.
Normal recurrence is anchored to the original schedule to avoid drift. If
multiple occurrences were missed, delivery records the relevant current
occurrence and schedules the first future occurrence instead of emitting a
backlog of notifications.

Disabling a reminder pauses delivery completely. Occurrences that become due
while it is disabled are skipped, not recovered later as overdue work. When the
reminder is disabled, re-enabled, or reconciled after a restart, the skipped
cursor advances to the latest due logical occurrence and the next future
occurrence is calculated from the original anchor. The skipped occurrences do
not create Done, Dismiss, or other history events.

The “+1 day” action postpones only the displayed occurrence. It means tomorrow
at the same local wall-clock time as the current effective DUE occurrence: it
uses the action's current local date, not the time of day when the button was
pressed. It does not change the reminder's recurrence anchor or normal recurring
schedule. Done and Dismiss record an event and continue the normal schedule.
Notification swipe dismissal will map to Dismiss only where Android exposes that
reliably.

Every-X-days recurrences are local wall-clock schedules. They store a local
anchor date/time and recalculate in the device's current `ZoneId`, so a 09:00
Every 7 days reminder follows 09:00 after a Finland-to-Japan time-zone change.
Java time's normal `LocalDateTime.atZone()` rules handle DST gaps and overlaps.

The scheduler uses one-shot alarms and prefers exact delivery. When
`canScheduleExactAlarms()` is true, both DUE and TOMORROW use
`setExactAndAllowWhileIdle`; otherwise both use `setAndAllowWhileIdle` as a
graceful fallback. A recovery calculation finds the latest normal occurrence
that is due and the first future normal occurrence, collapsing all missed
normal occurrences for a reminder into one outstanding due state.

## Phase 1 persisted state model

Room is the source of truth for both schedule state and deduplication state.
The schema is deliberately limited to one settings table and four schedule/
history tables:

| Concept | Persisted representation | Invariant |
| --- | --- | --- |
| Global delivery switch | `app_settings` singleton row with `masterEnabled` | The switch is durable and independent from every reminder's `enabled` flag. |
| Reminder definition / recurrence anchor | `reminders` row with stable ID, title, description, enabled flag, local anchor date/time, `intervalDays`, and timestamps | The anchor and interval are never changed by notification actions. |
| Current calculated next normal recurrence | Cached normal occurrence index, epoch instant, and last calculation zone on the reminder row | It is derived from the definition and recalculated after recovery/time-zone changes. |
| Resolved/skipped normal-occurrence cursor | Highest normal occurrence index resolved by Done/Dismiss or skipped while disabled on the reminder row | It prevents resolved or intentionally skipped occurrences from being recreated without changing the recurrence anchor or normal schedule. |
| Outstanding due state | One `outstanding_due_states` row keyed by reminder ID, containing the latest contributing normal occurrence index, due instant, optional postponed-until instant, postponement count, and revision | There is never more than one unresolved due state or normal actionable notification for a reminder. |
| Tomorrow preview state | One `tomorrow_previews` row keyed by reminder ID, containing the target normal occurrence index, occurrence/preview instants, zone, acknowledgement, and revision | There is never more than one preview for a reminder; it is suppressed while that reminder has an outstanding due state and is never created for an every-1-day reminder. |
| Reminder event/history | Append-only `reminder_events` rows with reminder ID, logical occurrence index, action, times, and optional postponement time | Done, Dismiss, +1 day, and Seen are auditable without changing the recurrence definition. |

The existing `lastResolvedNormalOccurrenceIndex` column is intentionally reused
as this resolved/skipped cursor. No additional cursor column is needed; the
name remains for compatibility with the Phase 1 schema.

An edit classifies as metadata-only when its anchor date/time, interval, and
enabled state are unchanged; changing only title or description preserves this
cursor and the persisted DUE/TOMORROW rows. An enabled-state-only edit uses the
same inactive-occurrence skip/resume transition as the direct list switch.
Changing the anchor date, anchor time, or interval creates a replacement logical
schedule: its old cursor and DUE/TOMORROW rows are discarded because their
indices belong to the old definition. The repository skips past occurrences on
the replacement schedule without history, then derives only current/future work.

The Room schema version is now 3. It adds one `app_settings` singleton row with
`masterEnabled`. The row is initialized to true when the repository first
opens/reconciles the database. Development databases still use the existing
destructive fallback for unsupported older schemas.

The normal occurrence index is a stable zero-based logical instance derived
from the original anchor. It is used to distinguish an occurrence in history
without using a time-zone-dependent epoch as its identity. The due row may
represent a postponed older occurrence or the latest normal occurrence. When a
new normal occurrence becomes due while a postponed/older due state exists, the
state is merged to the newer normal index, the auxiliary postponement is
dropped, and the single due notification remains.

Repeated +1 day actions update the same due row and increment its revision; they
never create another due row or move the normal pointer. The postponed time is
calculated in the current local zone from the next local calendar date after the
action and the current effective DUE wall-clock time. It never takes its clock
time from a late button press, and is always kept in the future.
Resolving the due state removes that row, records the action, and advances only
the resolved/skipped occurrence cursor; the normal pointer remains the
canonical first future occurrence. Disabling also removes due and preview rows
and advances this same cursor through occurrences already due at that point,
without recording history. Re-enabling then reconciles only the next future
anchored occurrence.

Stable notification identities are derived from `(reminderId, kind)`, with
separate namespaces for `DUE` and `TOMORROW`. Stable alarm/PendingIntent
identities use the same pair. Action PendingIntents carry three expected-state
tokens: the state revision, the logical normal-occurrence index, and the
reminder definition's monotonic modification timestamp. Room checks all three
inside the action transaction, so an old button cannot resolve a newer
occurrence or an edited reminder even if a revision number repeats. The
database primary keys, transaction boundaries, and these checks prevent
duplicate delivery rather than relying on cleanup after several notifications
have already been posted.

Room schema version 2 removed the old duration anchor and interval-unit fields
in favor of `intervalDays`; version 3 adds the settings row. This is a
pre-release app, so the app deliberately destructively recreates an old local
development database rather than carrying migrations for unsupported
recurrence types.

Tomorrow previews target only the next normal future occurrence, except that an
every-1-day reminder never has one. Eligible previews are scheduled one local
calendar day before the occurrence at its local wall-clock time. Their
lifecycle is derived from the persisted row: before `previewAt` it is
scheduled; from `previewAt` until the actual occurrence is due it is current
and remains valid for `Seen`; an acknowledged row remains acknowledged; and a
target that is due/past (or has an outstanding due state) is obsolete.

A missing preview is not recreated after its scheduled preview time during
recovery, avoiding old notification replay. In contrast, an existing current
preview is retained so a slightly late AlarmManager delivery can still be
acknowledged. `Seen` transactionally checks the persisted/reconciled preview
and revision, records only `TOMORROW_SEEN`, and never changes the recurrence or
normal due state. A same-target preview retains its revision only when its
occurrence instant, preview instant, and zone are unchanged; any of those
scheduling changes increments the revision.

## History and audit trail

History is a global read-only destination reached from the Reminders top app
bar. Its data flow is deliberately small:

```text
Room reminder_events + reminders
              |
      ReminderRepository
              |
       HistoryViewModel
       (combine + map)
              |
        HistoryScreen
```

`ReminderEventDao.observeAll()` already orders events by `occurredAt` descending
and then event ID descending. `ReminderRepository.observeAllEvents()` exposes
that Flow without giving Compose direct DAO access. `HistoryViewModel` joins
each event to the current reminder row and maps storage action strings to the
friendly labels Done, Dismissed, Postponed, and Tomorrow preview seen. An
unknown stored action is displayed as a neutral Activity label rather than
leaking a database enum name.

The join intentionally uses the current reminder title, not a title snapshot
in the event row. A title edit therefore updates older history entries
reactively. Reminder deletion remains permanent: the existing Room foreign-key
cascade deletes its event rows, so those entries disappear from History without
an additional schema table or migration. History never adds rows for create,
edit, delete, pause, resume, recovery, or stale notifications.

Event timestamps are persisted Instants. The Compose screen formats them with
Android's locale-aware date and time formatters, which follow the device's
current time zone and 12/24-hour preference. A postponed event shows both its
action time and its persisted new reminder time; it does not imply that the
normal recurrence was moved. The existing `+1 day` rule remains unchanged:
only the displayed occurrence is postponed, while the recurrence anchor and
normal schedule stay canonical.

The screen has explicit loading, empty, error/retry, and populated states. A
back action returns to the Reminders destination, and cards expose reminder
title/action semantics for accessibility.

## Phase 2 user interface

The main screen is one direct list. A card shows a reminder's title, optional
description, `Every X days` label, next occurrence, enabled state, and Edit or
Delete controls. The primary `+ Add` action and the empty-state button both
open the same editor. Delete always requires a confirmation.

The editor intentionally contains only title, optional description, first
date/time, enabled state, and `Every [X] days`. New reminders begin at Every 1
day with the next rounded hour as the initial first occurrence. It uses the
platform date/time pickers and inline plain-language errors. Tomorrow previews
need no configuration: the state model makes them eligible only for
`intervalDays >= 2`.

## Scheduling and identity

`ReminderService` coordinates Room mutations with `ReminderScheduler`, so create,
edit, enable, disable, and delete operations update derived alarms without
making the repository Android-aware. The application container owns one
`AlarmManagerReminderScheduler` and one repository; receivers reuse that same
container.

Editing through the existing editor can correct a title/description typo or
change the schedule. `ReminderRepository` owns the metadata-versus-schedule
classification described above; Compose does not make recurrence decisions.
After any successful repository edit, `ReminderService` cancels both old alarm
identities and both visible notification identities, then asks the scheduler to
reconcile the Room-current state. A current DUE or unacknowledged current
TOMORROW therefore receives an immediate one-shot re-delivery using the edited
text, while future work is merely rescheduled. The delivery path skips its own
kind after posting, so this refresh does not form an alarm loop or duplicate
visible notification. The monotonic reminder modification timestamp changes on
every edit, so actions from the cancelled old presentation remain stale.

Delete remains permanent. The service cancels both alarm identities and both
visible notification identities before the Room row is removed; Room foreign
keys then cascade its outstanding state and history. A stale delivery after
deletion reloads Room, finds no reminder, and safely cancels without posting.

`AlarmManagerReminderScheduler` calls
`AlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
triggerAtMillis, pendingIntent)` for individually scheduled, one-shot alarms
when `AlarmManager.canScheduleExactAlarms()` allows it. If the special access is
unavailable, it calls `setAndAllowWhileIdle` with the same PendingIntent
identity. The exact call is guarded for API 31+, and a `SecurityException`
race falls back to the inexact call instead of crashing. Both APIs remain
subject to Android timing and battery-management behavior; exact does not mean
mathematically zero-delay delivery. The app declares `SCHEDULE_EXACT_ALARM`,
not `USE_EXACT_ALARM`, and does not use WorkManager as the reminder timer.

`AlarmSchedulePlanner` is pure Kotlin. For a reminder whose effective enabled
state is `masterEnabled && reminder.enabled`, it schedules a
DUE alarm at the outstanding due time, or at the next normal occurrence when
there is no outstanding due state. It schedules TOMORROW only when the
persisted preview is unacknowledged, still targets a future occurrence, and the
shared `supportsTomorrow(intervalDays)` rule allows it. An outstanding DUE
state suppresses TOMORROW. Globally paused and individually disabled reminders
produce no alarms or visible reminder notifications.

The scheduler first calls Room reconciliation and only then derives alarms from
the returned snapshot. Repeated reconciliation replaces the same PendingIntent
or cancels it, so editing, recovery, and startup are idempotent. A delivery
reconciles again using the current `ZoneId`, compares the alarm's expected
revision with the current due/preview revision, and ignores stale or early
work. Valid delivery posts through `ReminderNotificationManager`; the one-shot
alarm is not immediately recreated while its notification remains visible.

Alarm PendingIntents use explicit, immutable broadcasts to
`AlarmDeliveryReceiver`. Their data URI contains the full reminder ID and
notification kind; the hashed request code is only an additional lookup key,
not the sole identity. DUE and TOMORROW therefore remain distinct even if a
32-bit hash were ever to collide. Cancellation creates the same deterministic
identity.

`AlarmDeliveryReceiver` and `SchedulingRecoveryReceiver` call `goAsync()` and
run short Room/scheduling work on the application scope. Every path calls
`PendingResult.finish()`, including failures. The recovery receiver handles
`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, and
`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`; it re-checks the
capability before reconciling. `MY_PACKAGE_REPLACED` covers an app update,
which can invalidate disposable alarms, through the same Room-derived rebuild
path. Application startup and list-screen resume also reconcile when
appropriate. Reconciliation recalculates local-wall-clock occurrences in the
current zone, so a 09:00 reminder remains 09:00 after travel and old alarm
trigger times are replaced. Deleted or disabled reminders are safe when a
previously delivered alarm races with the database change.

## Global delivery switch and permission recovery

The main list exposes a plain-language `All reminders` switch backed by the
`app_settings` Room row. It is not implemented as transient Compose state and
does not rewrite any reminder's individual `enabled` value. The effective
delivery state is:

```text
masterEnabled && reminder.enabled
```

When the master switch changes from on to off, the repository transaction
advances every reminder through `ReminderStateMachine.skipInactiveOccurrences`,
clears outstanding DUE and TOMORROW state, and preserves the original anchor,
individual enabled flag, and history. The scheduler then cancels both alarm
kinds and both visible notification kinds. No Done, Dismiss, or other fake
history event is recorded. A stale alarm rechecks the persisted master state
before it can post anything.

If the app process is dead while the master switch is off, turning it back on
first applies the same inactive skip operation at the current time, then
reconciles only each reminder's next future anchored occurrence. This is why a
global pause does not create an overdue backlog or reset recurrence anchors.
An individually disabled reminder remains disabled after global resume and
continues to use the same inactive cursor rules. A future TOMORROW preview may
be recomputed for a still-future target after resume; an expired preview is not
replayed.

Android notification permission is a separate control. With permission denied,
reminders remain configured and their outstanding Room state is retained. If a
DUE or TOMORROW alarm fires while permission is unavailable, the scheduler
does not spin on immediate retries. When the UI observes a denied-to-granted
permission transition, it requests one scheduling reconciliation. Startup also
reconciles, so opening the app after granting permission is sufficient even if
the app was not running while the setting changed.

Exact-alarm access is a separate control as well. On Android 12 and newer the
manifest requests `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`. The list shows a
non-blocking explanation and opens `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` only
after the user taps it. On resume, the UI re-checks the capability; a change in
either direction triggers one Room-derived reconciliation. The permission
state-change receiver also uses `goAsync()`, verifies the current capability,
and reconciles all reminders. If access is revoked, Android may delete exact
alarms already scheduled by the app; the next startup, recovery broadcast, or
resume rebuilds the inexact fallback without changing Room state or the master
switch.

## Notifications and permissions

Phase 3 creates one idempotent `Reminders` notification channel with ordinary
default importance. `ReminderNotificationFactory` builds the two notification
shapes without reading Room or changing repository state. A DUE notification
shows the reminder title and optional description, with exactly `Done`,
`Dismiss`, and `+1 day`. A TOMORROW notification is titled
`Tomorrow: [title]` and has only `Seen`; the factory returns no notification for
an Every-1-day input, so this rule is not UI-only.

`ReminderNotificationManager` is the only posting/cancellation boundary. Its
target uses `NotificationManagerCompat.notify(tag, id, notification)`. The tag
contains the full reminder ID and notification kind, while the small integer ID
is stable per kind. This makes DUE and TOMORROW distinct and avoids reducing a
64-bit reminder ID to the only identity component. Reposting the same pair
replaces it; cancellation uses the same deterministic pair.

Action `PendingIntent`s target the explicit `NotificationActionReceiver` and
carry the reminder ID, notification kind, action, and the three expected-state
tokens described above. Their data URI contains the full
reminder/kind/action identity, their request code is stable, and they use
`FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`. The parser rejects missing, negative,
unknown, or kind/action-mismatched values and verifies the data URI before a
request reaches Room.

`NotificationActionProcessor` is the application-scoped boundary for Done,
Dismiss, +1 day, Tomorrow Seen, and notification delete intents. It serializes
actions within the process, lets the repository perform the transactional
revision/token check and event insert, cancels the corresponding alarm and
visible notification after an applied or already-resolved action, and then
reconciles the reminder from Room. A stale action is not allowed to cancel a
newer notification; it only triggers a safe reconciliation. A missing reminder
cancels all derived state. DUE delete intents map to Dismiss and TOMORROW delete
intents map to Seen, which gives notification swipes the same recorded meaning
where Android delivers the delete intent.

The manifest declares `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM`. On
Android 13 and newer, the list screen shows a small user-initiated notification
permission banner. On Android 12 and newer, it independently shows the
exact-alarm access banner when needed. The first notification tap requests
permission; after a rejected request, the UI uses the rationale when Android
offers one and otherwise links to app notification settings. The exact-alarm
button opens the special-access screen and is never launched automatically.
Older Android versions do not show the relevant banner, and neither permission
blocks reminder CRUD.

The debug variant includes a temporary exported `adb` receiver that posts or
cancels sample DUE/TOMORROW notifications. It is not part of release builds
and does not schedule alarms. Notification body taps use a stable immutable
activity PendingIntent to open the existing main reminder list. Debug sample
notifications exercise presentation only; production notifications use the
Room-backed action processor above.

## Reliability and privacy

Receivers do short database/scheduling work using `goAsync()` and the
application scope, and action receivers always call `PendingResult.finish()` in
a `finally` block. Receivers never trust alarm/action extras without reloading
Room state and checking the current revision/tokens. The app does not need to remain
open, stay in Recents, run a foreground service, or show a persistent process
notification: Android may terminate the process and later start the explicit
alarm receiver. No network permission, accounts,
analytics, advertisements, or cloud sync are planned. Diagnostic logs may
include IDs and operation outcomes, but not title
or description text. Android can still delay alarms, including exact alarms,
and can suppress alarms and receivers after an explicit Force Stop until the
app is opened again. The app does not attempt to bypass that platform behavior;
it is documented and manually tested.

The primary user experience is intentionally low-friction: one main reminder
list, a prominent add action, direct enable/edit/delete controls, sensible
defaults, plain-language labels, few screens, and no onboarding or advanced
settings unless later testing proves they are necessary. The data API exposes
these simple operations directly so the UI does not need to teach users about
recurrence instances, revisions, or time zones.

## Testing strategy

Pure recurrence, validation, presentation, notification eligibility, identity,
permission-transition policy, exact-alarm selection, and global alarm-planning tests are ordinary
JUnit tests. AndroidX tests cover
NotificationCompat action sets, channel idempotency, content/action PendingIntent
identity, and alarm PendingIntent identity. Pure alarm planner/delivery tests
cover enabled/disabled schedules, daily eligibility, acknowledgement, due
precedence, stale revisions, and early delivery.
Room DAO tests cover persistence, event history, and master-switch skip/resume
semantics. AndroidX tests cover
repository-backed list/editor ViewModels and the high-value empty-state Compose
path. Scheduling tests verify stable identifiers, cancellation/reschedule
behavior, editing, deletion, and missed-occurrence handling. Action tests verify
strict PendingIntent parsing, Done, Dismiss, postponement, Tomorrow Seen,
duplicate or stale actions, token protection after edits, event recording, and
reconciliation. Phase 6 adds AndroidX coverage for reactive global history,
current-title joins, delete cascades, friendly action/postponement display,
pause/recovery silence, missed-occurrence collapse, postponed reconstruction,
preview recovery, time-zone changes, and idempotent reconciliation. The
Android-test APK is compile-verified; connected execution still requires an
authorized phone or emulator.
