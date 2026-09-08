# Manual Testing

This checklist will be completed as implementation phases land. Tests requiring
a real notification or reboot should be run on a physical Android phone.

## Setup

- [ ] Install the debug APK with `adb install -r`.
- [ ] Confirm the app has no network permission and the notification channel exists.
- [ ] On Android 13+, verify the app shows a non-blocking notification-permission banner and only opens the permission dialog after its action is tapped.
- [ ] Grant notification permission; repeat the suite with it denied and verify reminder CRUD still works.

## Phase 3 notification foundation

- [ ] With the debug APK installed, show a DUE notification:
  `adb shell am broadcast -n com.samuel.regularnotifications/.notifications.DebugNotificationReceiver -a com.samuel.regularnotifications.debug.SHOW_DUE --el reminderId 42 --es title "Take out trash" --es description "Bins by the door"`.
- [ ] Verify the DUE notification title, description, and exactly `Done`, `Dismiss`, and `+1 day` actions.
- [ ] Repeat the same SHOW_DUE command and verify it replaces the existing notification instead of adding a duplicate.
- [ ] Show a TOMORROW notification with `--ei intervalDays 7` and verify its title starts `Tomorrow:`, with only the `Seen` action.
- [ ] Run the TOMORROW command with `--ei intervalDays 1` and verify no Tomorrow notification is posted.
- [ ] Verify DUE and TOMORROW notifications for reminder ID 42 can coexist and can be cancelled independently with the documented CANCEL commands.
- [ ] Tap a Phase 3 action and verify it is intentionally inert; final Done, Dismiss, +1 day, and Seen behavior is deferred to Phase 5.
- [ ] Remember that these commands inspect presentation only; recurring AlarmManager delivery is deferred to Phase 4.

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
- [ ] Verify empty, loading, permission-denied, and error states.

## Phase 1 state semantics

- [ ] Verify that a reminder has at most one outstanding actionable due notification.
- [ ] Verify that repeated recovery after downtime does not create duplicate due notifications.
- [ ] Verify that missed occurrences collapse to one due state per reminder.
- [ ] Verify that +1 day changes only the outstanding occurrence and leaves the normal recurrence anchor unchanged.
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
- [ ] Verify “+1 day” removes the notification, postpones only that displayed occurrence, and leaves the recurrence anchor and normal schedule unchanged.
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
- [ ] Explicitly force-stop the app, document the alarm suppression behavior, then reopen it.
