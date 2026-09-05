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

## Notifications and actions

- [ ] Test with the app open and closed.
- [ ] Test while the screen is locked.
- [ ] Trigger multiple simultaneous reminders and verify each remains independent.
- [ ] Verify Done removes the notification, records completion, and preserves recurrence.
- [ ] Verify Dismiss removes the notification, records dismissal, and preserves recurrence.
- [ ] Verify “+1 day” removes the notification and does not shift the normal schedule.
- [ ] Swipe a notification away and verify the documented dismissal behavior.

## Recovery and timing

- [ ] Reboot the device and verify enabled reminders are rescheduled.
- [ ] Change the device time zone and verify the intended local wall-clock behavior.
- [ ] Test a day/week reminder across a daylight-saving transition when available.
- [ ] Test battery saver/doze and record normal Android timing delays.
- [ ] Close/reopen the app after process death and verify reminders remain correct.
- [ ] Explicitly force-stop the app, document the alarm suppression behavior, then reopen it.
