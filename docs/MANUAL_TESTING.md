# Manual Testing

This checklist will be completed as implementation phases land. Tests requiring
a real notification or reboot should be run on a physical Android phone.

## Setup

- [ ] Install the debug APK with `adb install -r`.
- [ ] Confirm the app has no network permission and the notification channel exists.
- [ ] Grant notification permission when prompted; repeat the suite once with it denied.

## Reminder lifecycle

- [ ] Create reminders using minutes, hours, days, and weeks.
- [ ] Verify validation for blank title, invalid interval, and invalid date/time.
- [ ] Edit an enabled reminder and confirm only its alarm changes.
- [ ] Disable and re-enable a reminder.
- [ ] Delete a reminder and confirm the deletion prompt, alarm cancellation, and notification cancellation.
- [ ] Verify empty, loading, permission-denied, and error states.

## Phase 1 state semantics

- [ ] Verify that a reminder has at most one outstanding actionable due notification.
- [ ] Verify that repeated recovery after downtime does not create duplicate due notifications.
- [ ] Verify that missed occurrences collapse to one due state per reminder.
- [ ] Verify that +1 day changes only the outstanding occurrence and leaves the normal recurrence anchor unchanged.
- [ ] Verify repeated +1 day actions update one postponement rather than creating additional due states.
- [ ] Verify a newer normal occurrence collapses an older/postponed due state into one outstanding due state.
- [ ] Verify event history records Done, Dismiss, +1 day, and Tomorrow Seen independently of the recurrence definition.

## Notifications and actions

- [ ] Test with the app open and closed.
- [ ] Test while the screen is locked.
- [ ] Trigger multiple simultaneous reminders and verify each remains independent.
- [ ] Verify Done removes the notification, records completion, and preserves recurrence.
- [ ] Verify Dismiss removes the notification, records dismissal, and preserves recurrence.
- [ ] Verify “+1 day” removes the notification, postpones only that displayed occurrence, and leaves the recurrence anchor and normal schedule unchanged.
- [ ] Verify the Tomorrow preview says “Tomorrow: …”, has only one `Seen` action, and does not count as Done or Dismiss.
- [ ] Verify acknowledging a Tomorrow preview prevents it from returning after process death, reboot, or rescheduling.
- [ ] Verify an overdue/outstanding reminder suppresses its Tomorrow preview.
- [ ] Swipe a notification away and verify the documented dismissal behavior.

## Recovery and timing

- [ ] Reboot the device and verify enabled reminders are rescheduled.
- [ ] Change the device time zone and verify the intended local wall-clock behavior.
- [ ] Verify a daily 09:00 reminder remains 09:00 after changing from Finland to Japan time.
- [ ] Verify minute/hour reminders remain duration-based across a time-zone change.
- [ ] Verify only relevant future Tomorrow previews remain after recovery; obsolete previews are not replayed.
- [ ] Test a day/week reminder across a daylight-saving transition when available.
- [ ] Test battery saver/doze and record normal Android timing delays.
- [ ] Close/reopen the app after process death and verify reminders remain correct.
- [ ] Explicitly force-stop the app, document the alarm suppression behavior, then reopen it.
