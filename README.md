# Regular Notifications

An offline Android reminder app for recurring notifications with Done, Dismiss,
and “+1 day” actions. Eligible schedules will also provide a single
“Tomorrow: …” preview for the next upcoming occurrence; every-1-day reminders
intentionally do not receive one. The app will keep reminders and their
history locally on the device. It will not use accounts, a server, analytics,
advertisements, or network access.

## Status

Phase 6 implementation is complete, with corrective passes for global pause,
notification-permission recovery, exact-alarm scheduling, edit semantics, and
recovery/history. Enabled reminders use Room-derived, one-shot AlarmManager
scheduling, alarm delivery, startup/package-update reconciliation, and
boot/clock/time-zone recovery. The app can create, view, edit, enable, disable,
and permanently delete local reminders through a short Compose/Material 3
interface. Done, Dismiss, +1 day, Tomorrow Seen, and notification-swipe
handling record actions in Room and reconcile the next schedule. A global,
read-only History screen shows those actions with current reminder titles.
Android instrumentation tests compile but connected execution and physical
recovery testing still need a usable phone or emulator.

## Prerequisites

- JDK 17 (required by the planned Android Gradle Plugin toolchain)
- Android SDK with a current supported platform, platform-tools, and build-tools
- Gradle Wrapper included in this repository (no system Gradle install is needed)
- A physical Android phone with USB debugging enabled for device testing

The smallest practical setup on Windows is Android Studio, used only to install
the JDK/SDK components and optionally manage an emulator. A command-line setup
is also sufficient: install JDK 17, `cmdline-tools`, `platform-tools`, one
Android platform, and matching build-tools; then set `ANDROID_HOME` or
`ANDROID_SDK_ROOT` and accept the SDK licenses.

## Build and test

Run from the repository root in PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleAndroidTest
```

The debug APK will be under `app/build/outputs/apk/debug/`.

The current project uses minSdk 26, compileSdk/targetSdk 37, Android Gradle
Plugin 9.2.1, Gradle 9.4.1, built-in Kotlin, and Jetpack Compose Material 3.

The reminder model treats the normal recurrence as canonical. Every reminder
uses an original local date/time anchor and a positive interval in days, such
as **Every 7 days**. `+1 day` means **tomorrow at the same local wall-clock time
as the current DUE occurrence**: it uses the date when the action is pressed,
not the button-press time of day. It changes only that displayed occurrence,
without changing the anchor or normal future schedule. There is at most one
unresolved due state and one Tomorrow preview per reminder. Every 1 day does
not create a Tomorrow preview; every 2 or more days can. A preview remains
acknowledgeable after its delivery time until its actual reminder becomes due.
All reminders follow the device's current local time zone.

Disabling a reminder pauses delivery completely. Its card shows `Paused`
instead of a possibly stale `Next:` date. Occurrences that pass while it is
disabled are ignored without history events; re-enabling resumes at the next
future occurrence on the original Every-X-days anchor rather than creating a
backlog or restarting the schedule.

The list also has a persisted `All reminders` switch. It is separate from each
reminder's individual enabled switch and from Android's notification permission.
When the master switch is off, all DUE and TOMORROW alarms and visible reminder
notifications are cancelled, but reminder definitions and individual switches
remain unchanged. Occurrences that pass during the global pause are skipped
without fake Done/Dismiss history; turning the switch back on resumes at the
next future occurrence on each original anchor, without an overdue backlog.
The individual switch still behaves independently, so an individually disabled
reminder remains disabled after a global pause/resume.

## Editing a reminder

A title- or description-only edit preserves recurrence progress: the resolved/
skipped cursor, a logically current DUE occurrence, and a logically current
Tomorrow preview stay intact. The new text is used when the current notification
is rebuilt. No completion, dismissal, postponement, or history event is created
by editing.

Changing the first date, first time, or interval replaces the logical schedule.
Its old DUE/Tomorrow state and cursor are not reused because their occurrence
indices belong to the old schedule. Past occurrences on the replacement
schedule are skipped without history, and only valid future work is rebuilt.

After every successful edit, the app cancels both old alarm kinds and both
visible notification kinds, then rebuilds derived work from Room. A currently
due reminder or current unacknowledged Tomorrow preview is queued immediately
with the edited text; the normal one-shot delivery path posts it without an
alarm loop. Old notification buttons and swipes carry the previous modification
token and are safely rejected.

## Using the app

The main screen is a simple reminder list. Each card shows its title, repeat
schedule, next occurrence, status, and direct controls for enabling, editing,
and deleting it. The `+ Add` button opens a short form: title, optional
description, first date/time, and `Every [X] days`. New reminders default to
Every 1 day. Tomorrow previews are automatic for intervals of 2 or more days;
there is no preview setting in the form. Enabled reminders are scheduled from
Room state using one-shot alarms. When Android's **Alarms & reminders** access
is available, the scheduler uses `setExactAndAllowWhileIdle`; otherwise it
gracefully falls back to `setAndAllowWhileIdle`. The list remains usable while
exact access is unavailable and shows a user-initiated link to the relevant
Settings screen. Exact-alarm access is independent of notification permission
and the `All reminders` switch. The app reconciles alarms at startup and after
reboot, clock changes, time-zone changes, and exact-access changes. It does not
need to remain visible, stay in Recents, keep a foreground service running, or
show an "app is running" notification. Android can terminate the process and
later start the alarm receiver when an alarm is due. Edit is the way to correct
a title/description typo or change a schedule. Delete is permanent:
confirmation removes the Room reminder/history and both derived alarm and
visible-notification kinds. Notification action buttons update Room history,
remove the corresponding visible notification, and reconcile the next derived
schedule. A notification swipe maps to Dismiss for DUE and Seen for TOMORROW
through the notification delete intent.

The Reminders top bar opens a read-only History screen. It is backed by the
Room `reminder_events` table through the repository and updates reactively.
History shows Done, Dismissed, Postponed, and Tomorrow preview seen with local,
locale-aware timestamps. A postponed entry shows both the action time and the
new reminder time. History uses the current reminder title, so renaming updates
older entries; deleting a reminder permanently removes its cascaded history.
Create, edit, delete, pause, resume, and recovery operations do not create
history rows. Recovery also listens for app package replacement so disposable
alarms can be reconstructed after an update.

## Inspect Phase 3 notifications

The debug APK contains a temporary, non-production `adb` broadcast receiver.
It exercises the factory without AlarmManager scheduling:

```powershell
adb shell am broadcast -n com.samuel.regularnotifications/.notifications.DebugNotificationReceiver -a com.samuel.regularnotifications.debug.SHOW_DUE --el reminderId 42 --es title "Take out trash" --es description "Bins by the door"
adb shell am broadcast -n com.samuel.regularnotifications/.notifications.DebugNotificationReceiver -a com.samuel.regularnotifications.debug.SHOW_TOMORROW --el reminderId 42 --ei intervalDays 7 --es title "Take out trash"
adb shell am broadcast -n com.samuel.regularnotifications/.notifications.DebugNotificationReceiver -a com.samuel.regularnotifications.debug.CANCEL_DUE --el reminderId 42
adb shell am broadcast -n com.samuel.regularnotifications/.notifications.DebugNotificationReceiver -a com.samuel.regularnotifications.debug.CANCEL_TOMORROW --el reminderId 42
```

Repeating a SHOW command with the same reminder ID replaces the same logical
notification. The debug receiver is excluded from release builds. These
commands inspect notification presentation only: they do not create a Room
reminder or schedule future alarms, so their sample action buttons are not an
end-to-end action test. Normal app reminders use the Room-backed action
processor and scheduler.

## Run on a physical phone

With USB debugging enabled and the phone connected:

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Android 13 and newer, the app asks for `POST_NOTIFICATIONS` only after the
user taps the non-blocking permission banner. Denial does not prevent reminder
management; the banner offers Android notification settings when another
permission prompt is no longer appropriate. On Android 12 and newer, the app
also shows a separate user-initiated banner for `SCHEDULE_EXACT_ALARM` access.
The two permissions are independent: granting or revoking one does not change
the other or the global reminder switch. If exact access is denied or later
revoked, scheduling uses the inexact fallback; when access is granted again,
the app reconciles Room state and rebuilds exact alarms without changing
reminder data. If a reminder becomes due while notification permission is
denied, its Room due state is retained and a later permission reconciliation
can deliver it. Delivery still depends on Android's alarm and battery
management policies.

Every-X-days recurrences preserve local wall-clock time across daylight-saving
and time-zone changes. Android may delay alarms, especially in battery saver or
doze modes. If the user explicitly force-stops the app from Android system
settings, Android may suppress its alarms and broadcast receivers until the app
is opened again. The app does not attempt to bypass that platform limitation.

## Documentation

- [Project plan](docs/PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Manual testing](docs/MANUAL_TESTING.md)
