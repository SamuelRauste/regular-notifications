# Manual Testing

This checklist will be completed as implementation phases land. Tests requiring
a real notification or reboot should be run on a physical Android phone.

## Setup

- [ ] Install the debug APK with `adb install -r`.
- [ ] Confirm the app has no network permission and the notification channel exists.
- [ ] On Android 13+, verify the app shows a non-blocking notification-permission banner and only opens the permission dialog after its action is tapped.
- [ ] Grant notification permission; repeat the suite with it denied and verify reminder CRUD still works.
- [ ] With an outstanding due reminder and notification permission denied, grant permission from the banner or Android Settings and verify the reminder can appear without restarting the app or changing the clock. Verify this does not create a duplicate notification.
- [ ] On Android 12+, verify the separate non-blocking `Alarms & reminders`
  banner appears when exact access is unavailable. Tap `Allow alarms & reminders`
  and confirm the app opens its package-specific special-access screen only as a
  result of that tap.
- [ ] Grant exact-alarm access and verify the banner disappears after returning
  to the app. Revoke it later and verify the banner returns while CRUD remains
  usable and reminders use the inexact fallback.
- [ ] Verify notification permission and exact-alarm access are independent:
  test granted/granted, granted/denied, denied/granted, and denied/denied.

## Global reminder delivery switch

- [ ] Verify `All reminders` is visible near the top of the list and has
  `Pause all reminders` / `Resume all reminders` accessibility semantics.
- [ ] With the master switch ON and an individual reminder ON, verify DUE and
  eligible TOMORROW alarms are scheduled.
- [ ] With the master switch ON and an individual reminder OFF, verify no alarm
  or notification is delivered and the reminder remains visibly disabled.
- [ ] With the master switch OFF and an individual reminder ON, verify the
  individual switch still visibly remains ON while the card says delivery is
  globally paused. Verify no DUE or TOMORROW alarm/notification is delivered.
- [ ] With both the master switch and an individual reminder OFF, verify no
  delivery occurs and both switches retain their separate states.
- [ ] Turn the master switch OFF while a DUE or TOMORROW notification is
  visible. Verify both alarm kinds and visible reminder notifications are
  removed, with no Done/Dismiss history event created.
- [ ] Turn the master switch OFF before one or several anchored occurrences
  pass, leave the app/process stopped, then turn it ON later. Verify those
  occurrences are skipped without an overdue notification backlog and the
  original recurrence anchor remains unchanged.
- [ ] Verify a future TOMORROW preview is recomputed only when its target is
  still future after resume; an expired preview must not replay.
- [ ] Turn the master switch back ON and verify an individually disabled
  reminder remains disabled.
- [ ] Kill/reopen the app and repeat the master switch test. Verify the setting
  persists. Repeat across reboot, clock change, and time-zone change when a
  physical device is available.

## Phase 3 notification foundation

- [ ] With the debug APK installed, show a DUE notification:
  `adb shell am broadcast -n com.samuel.regularnotifications/.notifications.DebugNotificationReceiver -a com.samuel.regularnotifications.debug.SHOW_DUE --el reminderId 42 --es title "Take out trash" --es description "Bins by the door"`.
- [ ] Verify the DUE notification title, description, and exactly `Done`, `Dismiss`, and `+1 day` actions.
- [ ] Repeat the same SHOW_DUE command and verify it replaces the existing notification instead of adding a duplicate.
- [ ] Show a TOMORROW notification with `--ei intervalDays 7` and verify its title starts `Tomorrow:`, with only the `Seen` action.
- [ ] Run the TOMORROW command with `--ei intervalDays 1` and verify no Tomorrow notification is posted.
- [ ] Verify DUE and TOMORROW notifications for reminder ID 42 can coexist and can be cancelled independently with the documented CANCEL commands.
- [ ] Tap a Phase 3 debug notification action only to confirm that the debug
  presentation path is not an end-to-end Room reminder test. Test real action
  behavior with a reminder created in the app in the Phase 5 section below.
- [ ] Remember that these commands inspect presentation only; normal app AlarmManager delivery is covered in the Phase 4 section below.

## Phase 4 alarm scheduling and delivery

- [ ] Create an enabled reminder whose first occurrence is a few minutes in the future and verify a DUE notification appears after the alarm fires.
- [ ] Create an enabled Every 7 days reminder with a future first occurrence and verify both its future DUE alarm and its Tomorrow preview are represented by the correct notifications at their times.
- [ ] Create an Every 1 day reminder and verify no Tomorrow alarm or Tomorrow notification is produced.
- [ ] Disable a reminder before its scheduled time and verify no notification appears; re-enable it and verify the next future anchored occurrence is scheduled.
- [ ] Edit a scheduled reminder and verify the old time no longer produces a notification and the new time does.
- [ ] Delete a scheduled reminder and verify neither its DUE nor TOMORROW alarm can post afterward.
- [ ] Tap the notification body and verify the existing reminder list opens; verify this is separate from the action buttons.
- [ ] Repeat app startup/reopen and verify scheduling does not create duplicate notifications or duplicate visible reminders.
- [ ] Close the app, remove it from Recents, and allow Android to terminate its
  process. Verify an AlarmManager reminder can still start delivery; no
  foreground service or persistent "app is running" notification is required.
- [ ] Reboot the phone and verify enabled reminders are reconstructed from the database.
- [ ] Change the device clock and verify future scheduling is rebuilt without resurrecting disabled occurrences.
- [ ] Change the time zone and verify a 09:00 reminder remains 09:00 local time and old trigger times are replaced.
- [ ] Deliver a stale alarm after editing, disabling, or deleting a reminder and verify it produces no notification.
- [ ] Test battery saver/Doze and record any Android timing delay. Exact alarms
  are preferred but are not a mathematical zero-delay guarantee.
- [ ] Confirm the real app notification actions are handled by the Phase 5
  action processor; the debug receiver remains presentation-only.

### Samsung / physical-device exact-alarm procedure

- [ ] **A.** Grant `POST_NOTIFICATIONS` when Android requests it, or use the
  notification-permission banner. Confirm that notifications are enabled for
  the app.
- [ ] **B.** Grant the app's `Alarms & reminders` special access from the
  exact-alarm banner. Confirm that the banner disappears when the app resumes.
- [ ] **C.** Create an enabled reminder 5-10 minutes in the future. Keep the
  selected local date, time, and interval visible for comparison.
- [ ] **D.** If useful, confirm an alarm is registered with:
  `adb shell dumpsys alarm | findstr /i regular-notifications`.
  The output is diagnostic only; do not treat its vendor-specific formatting
  as an app contract.
- [ ] **E.** Swipe the app away from Recents.
- [ ] **F.** Lock the phone.
- [ ] **G.** Do **not** reopen the app while waiting.
- [ ] **H.** Verify that the notification arrives around the requested time,
  then repeat with the screen on and with battery saver/Doze enabled if
  practical. Exact alarms can still be affected by Android and Samsung timing
  policies.
- [ ] Repeat the procedure after revoking exact access. Verify the reminder
  remains enabled, the special-access explanation returns, and the fallback
  alarm still delivers. Grant access again and verify one reconciliation
  rebuilds the exact schedule without a duplicate notification.
- [ ] Repeat with the master switch OFF and with the individual reminder OFF;
  neither state may deliver an alarm. Turning either state back on must rebuild
  only the appropriate future anchored work.
- [ ] Reboot the phone, change the time zone, and revoke/re-grant notification
  permission separately. Verify each recovery path preserves the independent
  permission and switch states.

## Phase 5 notification actions

Use reminders created in the app, not the presentation-only debug receiver.
For repeatable tests, choose a short interval and a first occurrence a few
minutes ahead. Confirm notification permission is granted before testing
delivery.

- [ ] With the app open, verify a DUE notification has exactly `Done`,
  `Dismiss`, and `+1 day`.
- [ ] Press `Done`. Verify the notification disappears, exactly one `DONE`
  event is recorded, the reminder remains enabled, and its next normal
  occurrence is still scheduled.
- [ ] Press `Dismiss` on another occurrence. Verify the notification
  disappears, exactly one `DISMISSED` event is recorded, and the normal
  recurrence continues.
- [ ] Press `+1 day`. Verify the notification disappears and exactly one
  `POSTPONED` event is recorded. Verify it moves the displayed occurrence to
  tomorrow at the same local wall-clock time as the current DUE occurrence, rather
  than the time the button was pressed; the recurrence anchor, interval, and
  normal next occurrence do not move.
- [ ] Repeat `+1 day` on the same visible occurrence and verify it updates one
  postponed due state rather than creating duplicate notifications or events
  for a new normal occurrence.
- [ ] For an 08:00 DUE notification, press `+1 day` late in the evening and
  verify the replacement is at 08:00 tomorrow. Leave an 08:00 occurrence
  outstanding until the next evening, then press `+1 day`; verify the target is
  08:00 on the following day, never a past time.
- [ ] On an eligible Every 2+ days reminder, verify the TOMORROW notification
  has only `Seen`. Press `Seen` and verify the preview disappears, one
  `TOMORROW_SEEN` event is recorded, and no actual DUE occurrence is marked
  complete or dismissed.
- [ ] Swipe a DUE notification away and verify it has the same recorded
  meaning as `Dismiss`. Swipe a TOMORROW notification away and verify it has
  the same recorded meaning as `Seen`.
- [ ] Test every action with the app open, closed, removed from Recents, and
  while the screen is locked. Confirm each reminder's notification and state
  remain independent when multiple reminders are due together.
- [ ] Edit a reminder after its notification is shown, then press the old
  action. Verify the old action is ignored as stale and does not cancel or
  mutate the current edited reminder state.
- [ ] Deliver or repeat an old action after the same notification was already
  handled. Verify no duplicate event is recorded and the current notification
  is not cancelled incorrectly.
- [ ] Delete a reminder while its notification is visible, then use any old
  action if Android still exposes it. Verify no reminder or event is
  recreated and all associated alarms/notifications remain cancelled.

### Edit and stale-notification corrective checks

- [ ] Create an enabled reminder and let it become due. While its DUE
  notification is visible, edit only the title and save.
- [ ] Verify the old DUE notification disappears, the reminder remains due,
  and the replacement notification appears or updates with the new title.
- [ ] Verify the edit did not complete, dismiss, postpone, or otherwise advance
  the occurrence. If an old action can still be invoked, it must do nothing.
- [ ] Repeat the title-only check with a description-only edit.
- [ ] Create an eligible future Tomorrow preview. Edit only title/description,
  then verify the stale preview is removed and the current unacknowledged
  preview is rebuilt with edited text. Seen status must remain unchanged.
- [ ] Change the first date/time or interval while an old DUE/TOMORROW alarm or
  notification exists. Verify it is cancelled, does not fire later, and only
  valid future work on the replacement schedule remains.
- [ ] Edit enabled to disabled. Verify DUE/TOMORROW alarms and visible
  notifications are removed, no fake Done/Dismiss history is created, and the
  original anchor stays intact.
- [ ] Edit disabled to enabled after one or more occurrences have passed.
  Verify it resumes at the first future anchored occurrence without a backlog.
- [ ] Repeat a title or schedule edit while `All reminders` is paused and while
  the individual reminder is disabled. Verify no notification or alarm appears
  until the appropriate delivery control is re-enabled.

## Reminder lifecycle

- [ ] Open the app with no reminders and verify the empty state and both Add actions are obvious.
- [ ] Create reminders for Every 1 day, Every 2 days, Every 7 days, and Every 30 days.
- [ ] Verify the editor shows only `Every [X] days`, with no interval-unit selector or Tomorrow setting.
- [ ] Verify the first date/time defaults are sensible, platform pickers open, and Cancel does not save changes.
- [ ] Verify validation for blank title, an interval below 1 day, and invalid date/time.
- [ ] Edit an enabled reminder and confirm only that reminder's displayed definition and next occurrence change.
- [ ] Disable and re-enable a reminder.
- [ ] Disable a reminder before an occurrence, wait until it has passed, and verify the disabled card says `Paused` instead of showing a stale `Next:` date.
- [ ] Re-enable it after one or several anchored occurrences have passed and verify no old notification/due state appears; the next occurrence is the next future date on the original schedule.
- [ ] Delete a reminder and confirm the deletion prompt and removal from the list. When scheduling exists, also verify alarm and notification cancellation.
- [ ] Edit a reminder title/description to correct a typo and verify a later
  notification uses the new text. Edit schedule fields and verify obsolete
  alarms are replaced by the edited schedule.
- [ ] Verify empty, loading, permission-denied, and error states.

## Phase 1 state semantics

- [ ] Verify that a reminder has at most one outstanding actionable due notification.
- [ ] Verify that repeated recovery after downtime does not create duplicate due notifications.
- [ ] Verify that missed occurrences collapse to one due state per reminder.
- [ ] Verify that +1 day changes only the outstanding occurrence, uses tomorrow
  at that occurrence's local wall-clock time, and leaves the normal recurrence
  anchor unchanged.
- [ ] Verify repeated +1 day actions update one postponement rather than creating additional due states.
- [ ] Verify a newer normal occurrence collapses an older/postponed due state into one outstanding due state.
- [ ] Verify event history records Done, Dismiss, +1 day, and Tomorrow Seen independently of the recurrence definition.
- [ ] Verify an every-1-day reminder never creates a Tomorrow notification, even after restart, edit, or rescheduling.
- [ ] Verify an eligible non-daily reminder creates at most one Tomorrow preview.
- [ ] Verify disabled-period occurrences do not create fake Done/Dismiss history events and repeated reconciliation remains skipped.

## Notifications and actions

- [ ] Test with the app open and closed.
- [ ] Test while the screen is locked.
- [ ] Trigger multiple simultaneous reminders and verify each remains independent.
- [ ] Verify Done removes the notification, records completion, and preserves recurrence.
- [ ] Verify Dismiss removes the notification, records dismissal, and preserves recurrence.
- [ ] Verify “+1 day” removes the notification, postpones only that displayed
  occurrence to tomorrow at its local wall-clock time, and leaves the recurrence
  anchor and normal schedule unchanged.
- [ ] Verify the Tomorrow preview says “Tomorrow: …”, has only one `Seen` action, and does not count as Done or Dismiss.
- [ ] Deliver a Tomorrow notification slightly late, then press Seen; verify it is still acknowledged and records exactly one `TOMORROW_SEEN` event.
- [ ] Verify acknowledging a Tomorrow preview prevents it from returning after process death, reboot, or rescheduling.
- [ ] Verify an overdue/outstanding reminder suppresses its Tomorrow preview.
- [ ] Swipe a notification away and verify the documented dismissal behavior.

## Recovery and timing

- [ ] Reboot the device and verify enabled reminders are rescheduled.
- [ ] Change the device time zone and verify the intended local wall-clock behavior.
- [ ] Change the device time zone after re-enabling a reminder and verify skipped disabled-period occurrences do not return, while the original logical index/anchor remains intact.
- [ ] Verify a daily 09:00 reminder remains 09:00 after changing from Finland to Japan time.
- [ ] Verify Every 7 days remains anchored to the original local calendar date/time after a Finland-to-Japan time-zone change.
- [ ] Verify only relevant future Tomorrow previews remain after recovery; obsolete previews are not replayed.
- [ ] Verify a preview whose target reminder is now due/past is discarded during recovery, while a slightly late preview for a still-future target remains acknowledgeable.
- [ ] Change time zone while an eligible Tomorrow preview is pending and verify only the newly scheduled preview/action remains usable.
- [ ] Test an Every X days reminder across both daylight-saving transitions when available.
- [ ] Test battery saver/doze and record normal Android timing delays.
- [ ] Close/reopen the app after process death and verify reminders remain correct.
- [ ] Explicitly Force Stop the app from Android system settings. Verify and
  document that Android may suppress alarms/receivers until the app is opened;
  do not treat this as the normal "app closed" test or as an app bypass test.
