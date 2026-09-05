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

The Phase 0 scaffold currently contains only the application entry point, a
Material 3 Compose placeholder screen, and a JVM smoke test. It deliberately
does not contain persistence, scheduling, notifications, or reminder behavior;
those begin in later phases.

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

## Scheduling and identity

Every reminder gets individually derived request codes and/or intent data from
its stable database ID. Saving an edit first cancels the old alarm for that ID,
then schedules the current enabled state. Disable cancels it; delete cancels
the alarm and associated visible notifications before deleting the row.

The scheduler uses one-shot inexact `AlarmManager` alarms behind an interface.
It must be idempotent, avoid long-running services, and tolerate a receiver
running after the reminder was deleted. Boot and relevant clock/time-zone
receivers query enabled reminders and reconstruct alarms.

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

## Testing strategy

Pure recurrence and validation tests are ordinary JUnit tests. Room DAO tests
cover persistence and event history. Scheduling tests verify stable identifiers,
cancel/reschedule behavior, editing, deletion, and missed-occurrence handling.
Action tests verify Done, Dismiss, postponement, and deletion races. AndroidX
instrumentation tests will be added only where notification or lifecycle
behavior cannot be meaningfully tested as pure Kotlin.
