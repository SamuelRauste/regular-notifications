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

Phase 2 adds the Compose reminder-management UI on top of the persisted,
pure-Kotlin recurrence/state model. Phase 3 adds notification presentation,
permission UX, and action contracts. Phase 4 adds Room-derived AlarmManager
scheduling and delivery. Phase 5 will add notification action business logic.

## Planned packages

- `data`: Room entities, DAOs, database, and repository.
- `domain`: reminder models, validation, event semantics, and pure recurrence calculation.
- `scheduling`: `ReminderScheduler`, the `AlarmManager` implementation, and
  alarm/recovery receivers.
- `notifications`: channel, notification factory, and action handling.
- `ui`: reminder list/editor models, ViewModels, screens, and Compose navigation.

`RegularNotificationsApplication` owns one lazy `AppContainer` per app
process. The container owns the Room database and repository. Future UI,
alarm, boot/time, and notification-action components must obtain this shared
container instead of opening their own Room database instances.

The UI maps Room entities to small presentation models before rendering. A
list ViewModel owns the `StateFlow` for loading, content, and errors. A separate
editor ViewModel owns the short form and its validation state. Navigation has
only two destinations: the reminder list and a create/edit editor. Popping the
editor after Save or Cancel clears its destination-scoped ViewModel, preventing
stale form state on a later edit.

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

The “+1 day” action postpones only the displayed occurrence. It does not change
the reminder's recurrence anchor or normal recurring schedule. Done and Dismiss
record an event and continue the normal schedule. Notification swipe dismissal
will map to Dismiss only where Android exposes that reliably.

Every-X-days recurrences are local wall-clock schedules. They store a local
anchor date/time and recalculate in the device's current `ZoneId`, so a 09:00
Every 7 days reminder follows 09:00 after a Finland-to-Japan time-zone change.
Java time's normal `LocalDateTime.atZone()` rules handle DST gaps and overlaps.

The scheduler uses inexact one-shot alarms. Exact-alarm permission is not part
of the design. A recovery calculation finds the latest normal occurrence that
is due and the first future normal occurrence, collapsing all missed normal
occurrences for a reminder into one outstanding due state.

## Phase 1 persisted state model

Room is the source of truth for both schedule state and deduplication state.
The schema is deliberately limited to four conceptual tables:

| Concept | Persisted representation | Invariant |
| --- | --- | --- |
| Reminder definition / recurrence anchor | `reminders` row with stable ID, title, description, enabled flag, local anchor date/time, `intervalDays`, and timestamps | The anchor and interval are never changed by notification actions. |
| Current calculated next normal recurrence | Cached normal occurrence index, epoch instant, and last calculation zone on the reminder row | It is derived from the definition and recalculated after recovery/time-zone changes. |
| Resolved/skipped normal-occurrence cursor | Highest normal occurrence index resolved by Done/Dismiss or skipped while disabled on the reminder row | It prevents resolved or intentionally skipped occurrences from being recreated without changing the recurrence anchor or normal schedule. |
| Outstanding due state | One `outstanding_due_states` row keyed by reminder ID, containing the latest contributing normal occurrence index, due instant, optional postponed-until instant, postponement count, and revision | There is never more than one unresolved due state or normal actionable notification for a reminder. |
| Tomorrow preview state | One `tomorrow_previews` row keyed by reminder ID, containing the target normal occurrence index, occurrence/preview instants, zone, acknowledgement, and revision | There is never more than one preview for a reminder; it is suppressed while that reminder has an outstanding due state and is never created for an every-1-day reminder. |
| Reminder event/history | Append-only `reminder_events` rows with reminder ID, logical occurrence index, action, times, and optional postponement time | Done, Dismiss, +1 day, and Seen are auditable without changing the recurrence definition. |

The existing `lastResolvedNormalOccurrenceIndex` column is intentionally reused
as this resolved/skipped cursor. No new Room field or schema version is needed;
the name remains for compatibility with the Phase 1 schema.

The normal occurrence index is a stable zero-based logical instance derived
from the original anchor. It is used to distinguish an occurrence in history
without using a time-zone-dependent epoch as its identity. The due row may
represent a postponed older occurrence or the latest normal occurrence. When a
new normal occurrence becomes due while a postponed/older due state exists, the
state is merged to the newer normal index, the auxiliary postponement is
dropped, and the single due notification remains.

Repeated +1 day actions update the same due row and increment its revision; they
never create another due row or move the normal pointer. The postponed time is
calculated in the current local zone using a calendar-day advance, based on the
currently displayed due time (or now when an overdue state is being postponed).
Resolving the due state removes that row, records the action, and advances only
the resolved/skipped occurrence cursor; the normal pointer remains the
canonical first future occurrence. Disabling also removes due and preview rows
and advances this same cursor through occurrences already due at that point,
without recording history. Re-enabling then reconciles only the next future
anchored occurrence.

Stable notification identities are derived from `(reminderId, kind)`, with
separate namespaces for `DUE` and `TOMORROW`. Stable alarm/PendingIntent
identities use the same pair. State revisions are persisted so later receiver
work can reject stale actions. The database primary keys, transaction
boundaries, and revision checks prevent duplicate delivery rather than relying
on cleanup after several notifications have already been posted.

The current Room schema is version 2. It removes the old duration anchor and
interval-unit fields in favor of `intervalDays`. This is a pre-release app, so
the app deliberately destructively recreates an old local development database
rather than carrying a migration for unsupported recurrence types.

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

`AlarmManagerReminderScheduler` calls
`AlarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis,
pendingIntent)` for individually scheduled, one-shot inexact alarms. This API
can run during Doze but remains subject to Android batching and delay. The app
does not declare or request `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`, and it
does not use WorkManager as the reminder timer.

`AlarmSchedulePlanner` is pure Kotlin. For an enabled reminder it schedules a
DUE alarm at the outstanding due time, or at the next normal occurrence when
there is no outstanding due state. It schedules TOMORROW only when the
persisted preview is unacknowledged, still targets a future occurrence, and the
shared `supportsTomorrow(intervalDays)` rule allows it. An outstanding DUE
state suppresses TOMORROW. Disabled reminders produce no alarms.

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
`BOOT_COMPLETED`, `TIME_SET`, and `TIMEZONE_CHANGED`; application startup also
performs a full reconciliation. Reconciliation recalculates local-wall-clock
occurrences in the current zone, so a 09:00 reminder remains 09:00 after travel
and old alarm trigger times are replaced. Deleted or disabled reminders are
safe when a previously delivered alarm races with the database change.

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
carry the reminder ID, notification kind, action, and expected revision. Their
data URI contains the full reminder/kind/action identity, their request code is
stable, and they use `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`. The receiver is an
inert Phase 3 stub that only logs the action name; Phase 5 will add repository
mutations and notification cancellation.

The manifest declares `POST_NOTIFICATIONS`. On Android 13 and newer, the list
screen shows a small user-initiated permission banner. The first tap requests
permission; after a rejected request, the UI uses the rationale when Android
offers one and otherwise links to app notification settings. Older Android
versions do not show the banner, and denial never blocks reminder CRUD.

The debug variant includes a temporary exported `adb` receiver that posts or
cancels sample DUE/TOMORROW notifications. It is not part of release builds
and does not schedule alarms. Notification body taps now use a stable immutable
activity PendingIntent to open the existing main reminder list. Phase 5 will
implement the action behavior; the production action receiver remains inert.

## Reliability and privacy

Receivers do short database/scheduling work using `goAsync()` and the
application scope. The receiver never trusts alarm extras without reloading
Room state and checking the current revision. No network permission, accounts,
analytics, advertisements, or cloud sync are planned. Diagnostic logs may
include IDs and operation outcomes, but not title
or description text. Android can delay inexact alarms and can suppress alarms
after an explicit force-stop until the app is opened again; both limitations
will be documented and manually tested.

The primary user experience is intentionally low-friction: one main reminder
list, a prominent add action, direct enable/edit/delete controls, sensible
defaults, plain-language labels, few screens, and no onboarding or advanced
settings unless later testing proves they are necessary. The data API exposes
these simple operations directly so the UI does not need to teach users about
recurrence instances, revisions, or time zones.

## Testing strategy

Pure recurrence, validation, presentation, notification eligibility, identity,
and permission-policy tests are ordinary JUnit tests. AndroidX tests cover
NotificationCompat action sets, channel idempotency, content/action PendingIntent
identity, and alarm PendingIntent identity. Pure alarm planner/delivery tests
cover enabled/disabled schedules, daily eligibility, acknowledgement, due
precedence, stale revisions, and early delivery.
Room DAO tests cover persistence and event history. AndroidX tests cover
repository-backed list/editor ViewModels and the high-value empty-state Compose
path. Scheduling tests will verify stable identifiers, cancellation/reschedule
behavior, editing, deletion, and missed-occurrence handling. Action tests will
verify Done, Dismiss, postponement, and deletion races when those later phases
are implemented.
