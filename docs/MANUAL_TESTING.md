# Manual testing

This is the real-device QA checklist for Regular Notifications. The automated
test suite is useful evidence, but notification delivery, reboot behavior,
power management, and system permission screens must be checked on an Android
phone or emulator.

## Status and test-environment notes

Automated verification completed in the agent environment:

- `test`, `lint`, `assembleDebug`, `assembleAndroidTest`, `testDebugUnitTest`,
  `assembleRelease`, and `bundleRelease` passed.
- Lint reported 0 errors and 25 warnings. The warnings are documented in the
  Phase 7 record in `docs/PLAN.md`; they did not fail the build.
- The agent's `adb devices` could not enumerate devices because this environment
  could not create adb's Android user directory. The agent therefore did not
  run `connectedDebugAndroidTest`.

Separately, the user ran `connectedDebugAndroidTest` on a physical Samsung
SM-S931B. All 60 instrumentation tests passed while the phone was unlocked.
A previous locked-screen run produced `No compose hierarchies found in the
app`. That is a device/test-environment limitation: Compose UI tests need an
authorized, unlocked phone with the screen on and no blocking dialogs. Do not
add wake locks, keyguard bypasses, or production behavior to work around it.

Every checkbox in the sections below is intentionally still unchecked. The
external 60/60 instrumentation result does not prove that the full physical
recovery and timing checklist has been performed.

## 1. Prerequisites and setup

- [ ] Install JDK 17 and verify `java -version` reports Java 17.
- [ ] Install the Android SDK platform and build-tools required by the project
  (`compileSdk`/`targetSdk` 37), plus platform-tools.
- [ ] From the repository root, run `adb devices`.
- [ ] Enable Developer options and USB debugging on the phone if using a
  physical device. Accept the computer authorization prompt.
- [ ] Confirm the device appears as `device`, not `unauthorized` or `offline`.
- [ ] Keep the phone unlocked, the screen on, and free of system dialogs while
  running connected Compose tests.

Build and install the debug app:

```powershell
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For release verification, run:

```powershell
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
```

The expected local artifacts are
`app/build/outputs/apk/release/app-release-unsigned.apk` and
`app/build/outputs/bundle/release/app-release.aab`. They are unsigned because
this repository contains no release keystore or signing credentials. They are
not Play-ready until signed by a separately configured release process.

## 2. Basic reminder CRUD

- [ ] Open the app and confirm the list has a useful empty state.
- [ ] Create a reminder with a title, description, first date/time in the
  future, and a positive interval.
- [ ] Confirm the list shows the title, recurrence, enabled status, and a
  human-readable next occurrence.
- [ ] Try to save an empty title, a blank date/time, and a non-positive
  interval. Confirm understandable inline validation and no invalid reminder.
- [ ] Edit only the title or description. Confirm the current schedule and any
  current notification state remain intact, with no history event caused by the
  edit.
- [ ] Edit the first date, first time, or interval. Confirm the old derived
  state is replaced, old alarms/notifications do not return, and past
  occurrences on the replacement schedule are skipped without fake history.
- [ ] Disable a reminder from the list. Confirm it remains visible, shows
  `Paused` rather than a stale next date, and does not deliver.
- [ ] Re-enable it. Confirm it resumes at the next future point on its original
  anchored schedule rather than restarting from the enable time.
- [ ] Choose Delete and cancel the confirmation. Confirm nothing changes.
- [ ] Choose Delete and confirm. Confirm the reminder, its alarms, visible
  notifications, and its history are removed.
- [ ] Create at least two reminders and verify editing, enabling, disabling,
  and deleting one never changes the other.

## 3. Permissions

Test notification and exact-alarm access independently. Android 13 and newer
may require `POST_NOTIFICATIONS`; Android 12 and newer may show the separate
`SCHEDULE_EXACT_ALARM` access. The app must not request `INTERNET` or
`USE_EXACT_ALARM`.

- [ ] With notification permission granted and exact-alarm access granted,
  verify normal delivery.
- [ ] With notification permission granted and exact-alarm access denied,
  verify the app remains usable and delivery uses the inexact fallback.
- [ ] With notification permission denied and exact-alarm access granted,
  verify reminders can still be created, edited, paused, resumed, deleted, and
  viewed in History without a crash.
- [ ] With both permissions denied, verify reminder management and History
  still work and the UI explains how to grant access.
- [ ] Deny `POST_NOTIFICATIONS`, let a reminder become due, then grant the
  permission. Confirm valid outstanding work can reconcile and produce at most
  one current notification, not a duplicate.
- [ ] Revoke and re-grant exact-alarm access. Confirm the banner opens the
  appropriate Settings screen, the reminder definition is unchanged, and the
  schedule is rebuilt when access returns.
- [ ] Confirm the global `All reminders` switch does not silently change either
  Android permission.

## 4. Normal alarm delivery

Use a first occurrence close enough to test without waiting unnecessarily. The
current product uses anchored Every-X-days schedules; use an interval of one
day for a normal due test and an interval of two or more days when testing a
Tomorrow preview.

- [ ] With the app open, let an enabled reminder become due. Confirm the
  notification contains the expected title/description and the correct action
  buttons.
- [ ] Close the app normally and remove it from Recents. Confirm AlarmManager
  can still deliver an enabled reminder without a foreground service.
- [ ] Lock the phone and confirm delivery behavior, noting any Android or
  manufacturer delay.
- [ ] Create two reminders that become due close together. Confirm both
  notifications remain distinct and their actions affect only their own
  reminder.
- [ ] For an interval of two or more days, verify a `Tomorrow: <title>` preview
  can appear for the next eligible occurrence and is separate from the DUE
  notification.
- [ ] For an interval of one day, confirm no Tomorrow preview is created.
- [ ] If exact-alarm access is denied, confirm the inexact notification still
  eventually arrives without assuming a precise delivery second.

## 5. Notification actions

Test actions on a DUE notification:

- [ ] Press **Done**. Confirm the notification disappears, exactly one Done
  history event is recorded, the reminder stays enabled, and its normal
  recurrence continues.
- [ ] Press **Dismiss**. Confirm the notification disappears, exactly one
  Dismissed history event is recorded, and the normal recurrence continues.
- [ ] Swipe the DUE notification away. Where Android delivers the delete
  intent reliably, confirm it has the same recorded meaning as Dismiss.
- [ ] Press **+1 day**. Confirm the notification disappears and exactly one
  Postponed history event is recorded.
- [ ] For a Tomorrow notification, press **Seen**. Confirm only the preview is
  acknowledged, the actual reminder is not completed, and one preview-seen
  event is recorded.
- [ ] Swipe a Tomorrow notification away and confirm it is treated as Seen
  where Android supplies the delete intent.
- [ ] Repeat the same action or tap a stale notification after it has been
  edited, disabled, deleted, or superseded. Confirm it cannot mutate newer
  state or cancel a newer notification.
- [ ] Trigger duplicate action delivery if practical. Confirm it creates at
  most one event and does not advance the schedule twice.
- [ ] Verify a notification body tap opens the app without changing reminder
  state.

### The exact +1 day rule

`+1 day` postpones only the displayed occurrence. It does not move the normal
recurrence anchor or the normal future schedule, and it is not a fixed 24-hour
offset from the time the button is pressed.

Use this concrete scenario:

```text
Displayed DUE occurrence: Monday 08:00
Press +1 day:             Monday 23:00
Expected postponed time:  Tuesday 08:00
```

If that postponed occurrence is still outstanding at Tuesday 23:00, the next
postponed occurrence should be Wednesday 08:00. It must not become Tuesday
23:00 or Wednesday 23:00. Confirm the original recurrence anchor and normal
schedule remain unchanged in the list and after reopening the app.

## 6. Global and individual pause

- [ ] Turn off the persisted `All reminders` switch. Confirm all visible DUE
  and Tomorrow notifications disappear and future delivery stops.
- [ ] Leave the app paused while an occurrence passes, then reopen it. Confirm
  no fake Done/Dismiss history is created and no old notification storm is
  emitted.
- [ ] Close and reopen the app while globally paused. Confirm the paused state
  persists and the app does not create overdue work during the pause.
- [ ] Turn the global switch back on. Confirm each enabled reminder resumes at
  the next future occurrence on its original anchor.
- [ ] Confirm an individually disabled reminder remains disabled after global
  pause/resume, while an individually enabled reminder resumes normally.
- [ ] Disable one reminder while another remains enabled. Confirm only the
  disabled reminder is quiet and its passed occurrences do not accumulate.
- [ ] Re-enable it after time has passed. Confirm it does not replay a backlog
  and does not create history for skipped occurrences.

## 7. History

- [ ] Open History from the main screen and verify the empty state before any
  action has been recorded.
- [ ] Generate Done, Dismissed, Postponed, and Tomorrow-seen events. Confirm
  each has the correct plain-language label and local timestamp.
- [ ] Confirm postponed history shows both the action time and the new
  postponed reminder time.
- [ ] Rename a reminder and confirm older history uses the current reminder
  title.
- [ ] Delete a reminder and confirm its cascaded history disappears.
- [ ] Confirm create, edit, pause, resume, recovery, and skipped occurrences do
  not create fake history rows.
- [ ] Reopen History after process recreation and confirm ordering and content
  remain correct.
- [ ] Check the History screen's loading/empty/error/retry and back-navigation
  behavior where those states can be induced safely.

## 8. App and process lifecycle

- [ ] Create an enabled reminder, open another screen if needed, then swipe the
  app away from Recents. Confirm this ordinary process removal does not cancel
  its AlarmManager schedule.
- [ ] Reopen the app after process removal. Confirm startup reconciliation does
  not duplicate alarms, notifications, or history.
- [ ] Let a reminder become overdue while the process is unavailable. Confirm
  recovery collapses missed occurrences to the first future schedule and emits
  at most one current DUE notification per reminder.
- [ ] Postpone a DUE occurrence, remove the app from Recents, and reopen it.
  Confirm the postponed state survives without moving the normal anchor.
- [ ] Repeat the basic checks with notification permission denied. Room state,
  CRUD, and History must remain usable.

## 9. Reboot and package-update recovery

These checks change device state. Save the test reminder details first and
restore normal settings afterward.

- [ ] Schedule an enabled reminder, reboot the phone, unlock it, and confirm
  the enabled reminder is reconstructed from Room and can deliver.
- [ ] Reboot with an individually disabled reminder and with global pause on.
  Confirm neither produces a notification or backlog.
- [ ] Install the debug APK over the existing app with `adb install -r`.
  Confirm package-update recovery rebuilds derived alarms without duplicating
  notifications or changing reminder data.
- [ ] Delete a reminder before a pending alarm can run, then reboot or update
  if practical. Confirm the stale alarm is harmless and no notification is
  recreated.

## 10. Clock, time zone, and daylight-saving behavior

Do not perform these tests on a phone that must remain available. Record the
original clock and time-zone settings and restore them immediately afterward.
Automated tests must never change the real phone clock.

- [ ] Move the clock forward past several anchored occurrences. Confirm missed
  occurrences collapse to one first-future schedule and do not create a
  historical notification storm.
- [ ] Move the clock backward across a resolved occurrence. Confirm the
  resolved/skipped occurrence does not resurrect.
- [ ] Change the time zone, for example between Helsinki and another zone.
  Confirm the reminder preserves its intended local wall-clock time, old
  alarms become stale, and future scheduling is rebuilt.
- [ ] Verify a stale Tomorrow preview is not replayed after a time-zone or
  clock change.
- [ ] Verify a postponed occurrence is not duplicated or resurrected after a
  time-zone change or restart.
- [ ] If performing a controlled daylight-saving test, confirm day/week-style
  wall-clock recurrence follows Java time-zone calendar semantics. Do not
  expect fixed 24-hour elapsed spacing across a DST boundary.
- [ ] Restore the original clock and time zone and confirm the app reconciles
  once without duplicate notifications.

## 11. Battery saver and Doze

Android and phone manufacturers may delay alarms even when the app is correct.
Record observed timing rather than treating a small delay as a data error.

- [ ] With exact-alarm access granted, test an enabled reminder while the
  screen is locked and the app is removed from Recents.
- [ ] If safe on the test phone, repeat with battery saver enabled and note any
  vendor-specific delay.
- [ ] If safe and understood, test a Doze-like idle period and record the
  result. Do not change unrelated power-management settings permanently.
- [ ] Revoke exact-alarm access and confirm the inexact fallback eventually
  delivers, without promising exact timing.
- [ ] Confirm that no long-running service or persistent "app is running"
  notification is required.

## 12. Force Stop limitation

Force Stop is different from closing the app, removing it from Recents, or
normal process death.

- [ ] Schedule a reminder and explicitly use Android Settings to Force Stop the
  app. Confirm and record that Android may suppress its alarms and receivers
  until the app is manually opened again.
- [ ] Open the app again. Confirm startup reconciliation rebuilds valid state
  without a missed-occurrence notification storm or duplicate event.
- [ ] Do not treat Force Stop behavior as a bug to bypass, and do not add code
  intended to defeat Android's Force Stop restriction.

## 13. Final smoke test

- [ ] Install the current debug APK on a clean test installation or a test
  device whose old test data has been intentionally reviewed.
- [ ] Create one short-term enabled reminder and one disabled reminder.
- [ ] Verify the permission banners and the notification/exact-alarm states
  needed for the test.
- [ ] Receive one DUE notification, exercise one action, and verify its History
  row.
- [ ] Confirm the normal schedule remains active after Done, Dismiss, or
  +1 day according to the exact rule above.
- [ ] Reopen the app, check the list and History, then delete both reminders
  through the confirmation flow.
- [ ] Confirm no test notification remains visible and no unrelated device
  setting was left changed.
