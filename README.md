# Regular Notifications

An offline Android reminder app for recurring notifications with Done, Dismiss,
and “+1 day” actions. The app will keep reminders and their history locally on
the device. It will not use accounts, a server, analytics, advertisements, or
network access.

## Status

Phase 0 is complete: the project has a verified single-module Compose
application scaffold. The debug APK assembles, the JVM unit test passes, and
lint passes. Reminder functionality is intentionally not implemented yet.

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

The current scaffold uses minSdk 26, compileSdk/targetSdk 37, Android Gradle
Plugin 9.1.1, Gradle 9.3.1, built-in Kotlin, and Jetpack Compose Material 3.

## Run on a physical phone

With USB debugging enabled and the phone connected:

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app will request `POST_NOTIFICATIONS` at runtime on Android 13 and newer.
Notification delivery also depends on Android's alarm and battery-management
policies. The initial scheduler uses reasonably punctual inexact one-shot
`AlarmManager` alarms and does not request exact-alarm special access.

Day/week recurrences preserve local wall-clock time across daylight-saving
changes; minute/hour recurrences are duration-based. Android may delay alarms,
especially in battery saver or doze modes. If the user force-stops the app,
Android may suppress its alarms and receivers until the app is opened again.

## Documentation

- [Project plan](docs/PLAN.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Manual testing](docs/MANUAL_TESTING.md)
