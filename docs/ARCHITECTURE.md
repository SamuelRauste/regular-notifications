# Architecture

## Shape of the application

The application will be a single Android application module with a small,
feature-oriented structure:

```text
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

The Phase 1 implementation adds persistence and pure Kotlin recurrence/state
logic. It deliberately does not contain the Compose reminder UI, notification
delivery, AlarmManager scheduling, or BroadcastReceiver implementation; those
remain later phases.

## Planned packages

- `data`: Room entities, DAOs, database, and repository.
- `domain`: reminder models, validation, event semantics, and pure recurrence calculation.
- `scheduling`: `ReminderScheduler` and the `AlarmManager` implementation.
- `notifications`: channel, notification factory, and action handling.
- `receivers`: alarm delivery, notification actions, boot, and time/time-zone changes.
- `ui`: list, editor, history, ViewModels, and Compose theme/screens.

## Reminder and recurrence decisions

Each reminder stores a stable ID, title, optional description, enabled state,
the first local date/time, interval amount/unit, next scheduled occurrence,
creation/modification timestamps, and enough local-time/zone information to
recalculate after restart or time-zone changes. A recurrence calculator is a
pure Kotlin component so it can be tested without Android.

Minute and hour intervals use elapsed-duration arithmetic. Day and week
intervals advance local calendar dates while preserving the intended local wall
clock time, including daylight-saving transitions. Normal recurrence is
anchored to the original schedule to avoid drift. If multiple occurrences were
missed, delivery records the relevant current occurrence and schedules the
first future occurrence instead of emitting a backlog of notifications.

The “+1 day” action postpones only the displayed occurrence. It does not change
the reminder's recurrence anchor or normal recurring schedule. Done and Dismiss
record an event and continue the normal schedule. Notification swipe dismissal
will map to Dismiss only where Android exposes that reliably.

Day and week recurrences are local wall-clock schedules. They store a local
anchor date/time and recalculate in the device's current `ZoneId`, so a 09:00
reminder follows 09:00 after a Finland-to-Japan time-zone change. Java time's
normal `LocalDateTime.atZone()` rules handle DST gaps and overlaps. Minute and
hour recurrences are duration-based from their persisted anchor instant, so a
time-zone change changes their displayed local time but not their elapsed-time
schedule.

The scheduler uses inexact one-shot alarms. Exact-alarm permission is not part
of the design. A recovery calculation finds the latest normal occurrence that
is due and the first future normal occurrence, collapsing all missed normal
occurrences for a reminder into one outstanding due state.

## Phase 1 persisted state model

Room is the source of truth for both schedule state and deduplication state.
The schema is deliberately limited to four conceptual tables:

| Concept | Persisted representation | Invariant |
| --- | --- | --- |
| Reminder definition / recurrence anchor | `reminders` row with stable ID, title, description, enabled flag, local anchor date/time, duration anchor instant, interval amount/unit, timestamps | The anchor and interval are never changed by notification actions. |
| Current calculated next normal recurrence | Cached normal occurrence index, epoch instant, and last calculation zone on the reminder row | It is derived from the definition and recalculated after recovery/time-zone changes. |
| Resolved normal-occurrence cursor | Highest normal occurrence index already resolved by Done/Dismiss on the reminder row | It prevents an already resolved past occurrence from being recreated without changing the recurrence anchor or normal schedule. |
| Outstanding due state | One `outstanding_due_states` row keyed by reminder ID, containing the latest contributing normal occurrence index, due instant, optional postponed-until instant, postponement count, and revision | There is never more than one unresolved due state or normal actionable notification for a reminder. |
| Tomorrow preview state | One `tomorrow_previews` row keyed by reminder ID, containing the target normal occurrence index, occurrence/preview instants, zone, acknowledgement, and revision | There is never more than one preview for a reminder; it is suppressed while that reminder has an outstanding due state. |
| Reminder event/history | Append-only `reminder_events` rows with reminder ID, logical occurrence index, action, times, and optional postponement time | Done, Dismiss, +1 day, and Seen are auditable without changing the recurrence definition. |

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
the resolved-occurrence cursor; the normal pointer remains the canonical first
future occurrence.

Stable notification identities are derived from `(reminderId, kind)`, with
separate namespaces for `DUE` and `TOMORROW`. Stable alarm/PendingIntent
identities use the same pair. State revisions are persisted so later receiver
work can reject stale actions. The database primary keys, transaction
boundaries, and revision checks prevent duplicate delivery rather than relying
on cleanup after several notifications have already been posted.

Tomorrow previews target only the next normal future occurrence. The preview is
scheduled one local calendar day before that occurrence at its local wall-clock
time. `Seen` marks the target index acknowledged and does not alter any normal
schedule or due state. If the preview time is already past during recovery, or
the reminder is due, the preview is obsolete and is not recreated. A changed
target index starts a new unacknowledged preview row.

## Scheduling and identity

Every reminder gets individually derived request codes and/or intent data from
its stable database ID. Saving an edit first cancels the old alarm for that ID,
then schedules the current enabled state. Disable cancels it; delete cancels
the alarm and associated visible notifications before deleting the row.

The scheduler uses one-shot inexact `AlarmManager` alarms behind an interface.
It must be idempotent, avoid long-running services, and tolerate a receiver
running after the reminder was deleted. Boot and relevant clock/time-zone
receivers query enabled reminders and reconstruct alarms. A future receiver
that needs Room or scheduling work must use `goAsync()` and call
`PendingResult.finish()` in all completion/error paths, or use another Android
component with an equivalent lifecycle guarantee. Unmanaged coroutines started
directly from `onReceive()` are not acceptable.

## Notifications and permissions

Notifications use `NotificationCompat` with a dedicated channel and three
actions: Done, Dismiss, and +1 day. Android 13+ notification permission is
requested at runtime; a denial is shown as a useful UI state while reminders
remain editable. Action receivers update Room and cancel/update notification
state without depending on the app process remaining alive.

## Reliability and privacy

Receivers do short database/scheduling work using coroutines as appropriate.
No network permission, accounts, analytics, advertisements, or cloud sync are
planned. Diagnostic logs may include IDs and operation outcomes, but not title
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

Pure recurrence and validation tests are ordinary JUnit tests. Room DAO tests
cover persistence and event history. Scheduling tests verify stable identifiers,
cancel/reschedule behavior, editing, deletion, and missed-occurrence handling.
Action tests verify Done, Dismiss, postponement, and deletion races. AndroidX
instrumentation tests will be added only where notification or lifecycle
behavior cannot be meaningfully tested as pure Kotlin.
