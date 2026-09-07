# Regular Notifications

An offline Android reminder app for recurring notifications with Done, Dismiss,
and “+1 day” actions. Eligible schedules will also provide a single
“Tomorrow: …” preview for the next upcoming occurrence; every-1-day reminders
intentionally do not receive one. The app will keep reminders and their
history locally on the device. It will not use accounts, a server, analytics,
advertisements, or network access.

## Status

Phase 2 is complete: the app can create, view, edit, enable, disable, and
delete local reminders through a short Compose/Material 3 interface. Every
reminder uses **Every X days**. AlarmManager scheduling, notification delivery,
and action receivers are intentionally not implemented yet. Android
instrumentation tests compile but still need a usable phone or emulator to run.

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
```

The debug APK will be under `app/build/outputs/apk/debug/`.

The current project uses minSdk 26, compileSdk/targetSdk 37, Android Gradle
Plugin 9.2.1, Gradle 9.4.1, built-in Kotlin, and Jetpack Compose Material 3.

The reminder model treats the normal recurrence as canonical. Every reminder
uses an original local date/time anchor and a positive interval in days, such
as **Every 7 days**. A displayed occurrence can be postponed by one calendar
day without changing that anchor or normal future schedule. There is at most
one unresolved due state and one Tomorrow preview per reminder. Every 1 day
does not create a Tomorrow preview; every 2 or more days can. A preview remains
acknowledgeable after its delivery time until its actual reminder becomes due.
All reminders follow the device's current local time zone.

Disabling a reminder pauses delivery completely. Its card shows `Paused`
instead of a possibly stale `Next:` date. Occurrences that pass while it is
disabled are ignored without history events; re-enabling resumes at the next
future occurrence on the original Every-X-days anchor rather than creating a
backlog or restarting the schedule.

## Using the app

The main screen is a simple reminder list. Each card shows its title, repeat
schedule, next occurrence, status, and direct controls for enabling, editing,
and deleting it. The `+ Add` button opens a short form: title, optional
description, first date/time, and `Every [X] days`. New reminders default to
Every 1 day. When notification delivery is added, Tomorrow previews will be
automatic for intervals of 2 or more days; there is no preview setting in the
form.

## Run on a physical phone

With USB debugging enabled and the phone connected:

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Later notification work will request `POST_NOTIFICATIONS` at runtime on Android
13 and newer. Notification delivery will also depend on Android's alarm and
battery-management policies. The planned scheduler uses reasonably punctual
inexact one-shot `AlarmManager` alarms and does not request exact-alarm special
access.

Every-X-days recurrences preserve local wall-clock time across daylight-saving
and time-zone changes. Android may delay alarms, especially in battery saver or
doze modes. If the user force-stops the app, Android may suppress its alarms
and receivers until the app is opened again.

## Documentation

- [Project plan](docs/PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Manual testing](docs/MANUAL_TESTING.md)
