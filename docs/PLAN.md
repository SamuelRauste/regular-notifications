# Development Plan

This is the living checklist for the Regular Notifications Android app. Tasks
are marked complete only after the relevant checks have been run.

## Phase 0 — Environment and scaffold

- [x] Inspect repository, Git state, operating system, Java, Android SDK, adb, and relevant environment variables.
- [x] Add Android/Gradle `.gitignore` rules.
- [x] Document the product, prerequisites, build, adb installation, tests, APK path, and platform limitations in `README.md`.
- [x] Write initial architecture and key decisions.
- [x] Create this complete phased plan.
- [x] Install or expose JDK 17 and the Android SDK command-line/build components.
- [x] Generate and verify the smallest Compose application project.
- [x] Run `assembleDebug`, `test`, and `lint` after scaffolding.
- [x] Commit Phase 0 once verification passes and the worktree has no unrelated changes.

## Phase 1 — Persistence and recurrence

- [x] Document the reminder definition, normal occurrence, outstanding due, postponement, event history, Tomorrow preview, identity, and deduplication model.
- [x] Configure the single application module using Kotlin, Compose, Material 3, Room, coroutines, and Java time.
- [x] Implement reminder and reminder-event entities, DAOs, database, repository, and Flow access. StateFlow remains a Phase 2 ViewModel concern.
- [x] Simplify the recurrence model to a positive `intervalDays` value and implement a pure Kotlin local-wall-clock calculator for Every X days.
- [x] Define anchoring, missed-occurrence, time-zone, and postponement semantics.
- [x] Implement pure Kotlin state transitions for recovery, due-state merging, repeated +1 day, resolution, and the full Tomorrow preview lifecycle.
- [x] Add unit tests for Every 1, 2, 7, and larger day intervals; past/future starts; missed occurrences; date boundaries; leap years; DST; time-zone changes; drift; anchoring; postponement; validation; Tomorrow lifecycle; and idempotency.
- [x] Add Room DAO/database and repository instrumentation tests for persistence, one-row-per-reminder invariants, event history, revision state, and application-scoped ownership. They compile; execution requires an attached device or emulator.

## Phase 2 — Reminder management UI

- [x] Implement an application-container-backed list ViewModel and Material 3 reminder list.
- [x] Implement destination-scoped create/edit ViewModel state and a short form with inline validation.
- [x] Add direct enable/disable and delete-confirmation flows.
- [x] Add loading, empty, error, and accessibility states with standard touch targets and labels.
- [x] Add presentation, ViewModel/Room, and meaningful Compose UI tests. They compile; execution requires an attached device or emulator.
- [x] Complete the pre-Phase 3 corrective pass: show disabled reminders as paused, make card controls reminder-specific to accessibility services, and skip occurrences that pass while delivery is disabled.

## Phase 3 — Notification foundation

- [x] Create an idempotent notification channel and factory for both normal due notifications and eligible `Tomorrow: [reminder title]` previews.
- [x] Give a normal due notification Done, Dismiss, and +1 day action contracts; give a Tomorrow notification only a Seen action.
- [x] Ensure every-1-day reminders never create a Tomorrow notification.
- [x] Request `POST_NOTIFICATIONS` on supported Android versions through a user-initiated, non-blocking UI action.
- [x] Handle denied permission without breaking reminder management.
- [x] Add a debug-only adb notification path, then cover notification presentation, identity, permission policy, and PendingIntent contracts with tests and documentation.

## Phase 4 — Alarm scheduling and delivery

- [x] Define `ReminderScheduler` and implement one-shot `AlarmManager` scheduling for applicable `DUE` and `TOMORROW` states.
- [x] Use stable `(reminderId, notification kind)` identities in unique immutable `PendingIntent`s and notification IDs.
- [x] Implement alarm delivery receiver and database-backed next-occurrence reconciliation.
- [x] Make scheduling/cancellation idempotent and safe when a reminder was deleted before delivery; never schedule Tomorrow for every-1-day reminders.
- [x] Reconstruct both applicable alarm kinds from Room-derived state after boot, time, and time-zone recovery.
- [x] Add pure scheduling/delivery tests and Android PendingIntent/content-intent tests.

## Phase 5 — Notification actions

- [x] Implement Done, Dismiss, +1 day, and Tomorrow Seen action receivers.
- [x] Record each event and remove the displayed notification.
- [x] Preserve normal schedule for Done/Dismiss.
- [x] Postpone only the displayed occurrence by one calendar day; do not change the reminder's recurrence anchor or normal schedule.
- [x] Make Seen acknowledge only its Tomorrow preview, remove that preview notification, preserve recurrence and actual-reminder status, and reject stale revisions.
- [x] Implement notification swipe dismissal where Android reliably exposes it.
- [x] Add action-processing tests.

Implementation notes for this phase:

- The explicit `NotificationActionReceiver` parses and validates the complete
  action identity before starting work, uses the application-scoped container,
  calls `goAsync()`, and always finishes its `PendingResult`.
- `NotificationActionProcessor` serializes in-process actions, delegates the
  transactional event/state mutation to Room, cancels the corresponding alarm
  and visible notification after a valid or already-consumed action, and then
  reconciles from Room. A stale action does not cancel a newer notification.
- DUE notification delete intents use Dismiss semantics. TOMORROW delete
  intents use Seen semantics. These are best-effort because Android controls
  when it delivers notification delete intents.
- Action tokens include the state revision, normal occurrence index, and a
  monotonic reminder-definition modification timestamp. This closes the case
  where an old action's revision number could otherwise repeat after a later
  occurrence or edit.

## Corrective pass after Phase 5 — edit semantics and stale presentation

- [x] Preserve `lastResolvedNormalOccurrenceIndex` and logically current
  DUE/TOMORROW state for title- or description-only edits.
- [x] Treat anchor date/time or interval changes as a replacement schedule:
  invalidate old derived state, reset incompatible cursor progress, and skip
  past replacement-schedule occurrences without history.
- [x] Keep editor enabled-state changes on the same inactive skip/resume rules
  as the direct individual enable/disable switch.
- [x] After every successful edit, cancel old DUE/TOMORROW alarms and visible
  notifications, then reconcile only the current Room-derived state.
- [x] Keep old notification actions stale through the monotonic modification
  timestamp after every edit.
- [x] Add focused repository/service tests and update edit/manual-test docs.

## Corrective pass after Phase 5 — +1 day wall-clock semantics

- [x] Define `+1 day` as tomorrow at the current effective DUE occurrence's
  local wall-clock time, using the action's local calendar date rather than its
  time of day.
- [x] Preserve the recurrence anchor, normal next occurrence, action revision,
  stale-action protection, and one-event-per-successful-postponement behavior.
- [x] Cover late same-day, overdue, repeated, persistence, and DST cases with
  focused tests and update user-facing/manual documentation.

## Corrective/product pass between Phase 4 and Phase 5

- [x] Persist one global `masterEnabled` reminder-delivery setting in Room
  without changing individual reminder `enabled` values.
- [x] Make global pause and individual disable share the inactive-occurrence
  cursor operation: clear derived DUE/TOMORROW state, skip passed occurrences,
  preserve anchors, and record no fake history.
- [x] Reconcile globally paused reminders before resume so a process restart or
  long pause cannot create an overdue backlog.
- [x] Enforce `masterEnabled && reminder.enabled` in planning, alarm delivery,
  startup, boot/time/time-zone recovery, create/edit, and enable/disable paths.
- [x] Remove visible reminder notifications when global or individual delivery
  becomes inactive, while keeping stale alarm delivery safe.
- [x] Reconcile Room-derived outstanding work after notification permission
  changes from denied/unavailable to granted, without immediate retry loops.
- [x] Add the master switch UI, clear paused presentation, and accessibility
  semantics while preserving individual edit/enable/delete controls.
- [x] Document calendar-like background operation, Force Stop limitations,
  permission separation, global pause semantics, edit behavior, and permanent
  deletion cleanup.
- [x] Add JVM and AndroidX coverage for master persistence, four effective-state
  combinations, global pause/resume, permission transitions, and UI semantics.

## Exact-alarm corrective pass after Phase 4/global pause

- [x] Declare `SCHEDULE_EXACT_ALARM` and use `setExactAndAllowWhileIdle` for
  DUE and TOMORROW when Android grants exact-alarm access.
- [x] Fall back to `setAndAllowWhileIdle` when exact access is unavailable,
  including a safe fallback for a permission race, without changing Room state.
- [x] Add a non-blocking, user-initiated Alarms & reminders banner and refresh
  capability on lifecycle resume.
- [x] Reconcile after the exact-alarm grant broadcast, and after revocation via
  startup, resume, recovery, and CRUD without changing master or individual switches.
- [x] Add exact/inexact policy, permission-intent, and recovery tests without
  implementing Phase 5 notification actions.
- [x] Update the README, architecture, and physical-device checklist for exact
  alarms, fallback behavior, independent permissions, and timing limitations.

## Phase 6 — Recovery and history

- [x] Extend the lightweight boot/time/time-zone recovery with package-update
  recovery and retain one shared application-scoped reconciliation path.
- [x] Reconstruct Phase 4 alarms from Room as the source of truth, including
  process-death restart, missed-occurrence collapse, postponed state, preview
  lifecycle, disabled/global-paused state, and time-zone recalculation.
- [x] Add a global read-only History destination backed by a repository-wide
  Room event Flow, with current reminder-title joins, friendly action labels,
  local date/time formatting, postponed action/new-time details, and retry,
  empty, loading, and error states.
- [x] Keep deletion's existing Room foreign-key cascade so deleted reminders
  remove their history without a schema change; title edits continue to show
  the current title for older events.
- [x] Handle process death and missed alarms without notification storms;
  recovery records no synthetic history events.
- [x] Add repository/ViewModel/Compose recovery and history tests. Android-test
  APK compilation is verified; connected execution and physical-device
  validation remain pending a usable authorized device.
- [ ] Execute the physical recovery/history checklist on a real Android phone.

Phase 6 decisions:

- History is read-only and global. `HistoryViewModel` combines the repository's
  ordered `reminder_events` Flow with current reminder rows, so renaming a
  reminder changes the displayed title of older events and deleting a
  reminder removes its cascaded history.
- History displays Done, Dismissed, Postponed, and Tomorrow preview seen with
  plain-language labels. Instants are formatted in the device's current local
  time zone using the user's locale/time preference. Postponed rows show both
  the action time and the new reminder time.
- Recovery does not write history. It reconciles Room state into disposable
  alarms and preserves the existing rule that `+1 day` postpones only the
  displayed occurrence, without moving the recurrence anchor or normal
  schedule.
- `ACTION_MY_PACKAGE_REPLACED` uses the existing recovery receiver so an app
  update can rebuild derived alarms from Room without adding a broad package
  broadcast or a new persistence field.

## Phase 7 — Verification and documentation

- [ ] Complete automated tests, lint, and debug build checks.
- [ ] Add AndroidX instrumentation tests where they provide meaningful coverage.
- [ ] Complete `docs/MANUAL_TESTING.md` for permissions, lifecycle, actions, reboot, time zones, battery saver, and DST.
- [ ] Document release-build instructions without committing signing credentials.

## Phase 8 — Final review

- [ ] Review privacy, permissions, accessibility, scheduling identifiers, logging, and error handling.
- [ ] Remove temporary/debug-only features.
- [ ] Re-run relevant checks and inspect the final diff.
- [ ] Make the final focused local commit when requested/appropriate.

## Decisions recorded

- Single application module; no backend, network permission, DI framework, or WorkManager primary timer.
- Room is the source of truth; alarms are derived state and are reconstructed after recovery events.
- Minimum SDK target is 26 unless the installed toolchain gives a strong reason to change it.
- One-shot alarms are derived from Room state. Exact alarms are preferred when
  `SCHEDULE_EXACT_ALARM` access is available; `setAndAllowWhileIdle` is the
  graceful fallback when it is unavailable. Exact access is independent of
  notification permission and the global master switch.
- Phase 0 scaffold uses compileSdk/targetSdk 37, Android Gradle Plugin 9.2.1, Gradle 9.4.1, built-in Kotlin/Compose compiler plugin 2.3.21, and Compose BOM 2026.08.00.
- The newer Android CLI is useful and preferred for agent-driven workflows. Modern `sdkmanager` from the Android SDK Command-Line Tools package remains documented and supported for installing SDK packages; a deprecation warning may refer to the legacy SDK Tools package or an older `sdkmanager` earlier on PATH.
- Every-X-days schedules use the device's current time zone and preserve the original local calendar anchor and wall-clock time.
- There is one persisted outstanding due state and one persisted Tomorrow preview state per reminder. Normal recurrence remains canonical; postponed state is auxiliary and can be collapsed when a newer normal occurrence becomes due.
- `+1 day` uses the active zone's next local calendar date and the current
  effective DUE occurrence's local wall-clock time. A late action therefore
  never shifts tomorrow's reminder to the action time or uses fixed 24-hour
  arithmetic; it only changes the outstanding occurrence.
- The reminder stores one resolved/skipped normal-occurrence cursor so Done/Dismiss cannot recreate resolved occurrences and disabled-period occurrences cannot reappear after recovery or a time-zone change; this cursor does not alter the recurrence anchor and does not create history for skipped occurrences.
- Metadata-only edits (title/description with unchanged enabled state and
  schedule fields) preserve the resolved/skipped cursor and any logically
  current DUE/TOMORROW state. The changed monotonic modification timestamp
  invalidates old notification actions without creating history.
- Schedule-defining edits (anchor date, anchor time, or interval) replace the
  old logical occurrence sequence. They intentionally reset incompatible cursor
  progress, discard old derived DUE/TOMORROW rows, skip past occurrences on the
  replacement schedule without history, and rebuild only valid future work.
- An editor enabled-state change uses the same inactive skip/resume semantics as
  the direct enable/disable control. Every successful edit then cancels old
  visible notifications and alarms before `ReminderService` reconciles the
  current Room state.
- Disabled reminders do not accumulate missed occurrences. Re-enabling resumes at the first future occurrence on the original Every-X-days schedule; it does not restart the schedule from the re-enable date.
- The existing `lastResolvedNormalOccurrenceIndex` column is reused as the resolved/skipped cursor; no additional cursor field is required. The separate `app_settings` row is Room schema version 3.
- Disabled reminder cards show `Every X days · Paused` instead of a cached `Next:` date. Edit, delete, and enable/disable controls include the reminder title in their accessibility semantics while keeping the visible labels short.
- Phase 3 uses one `Reminders` notification channel. DUE has `Done`, `Dismiss`, and `+1 day`; TOMORROW has only `Seen`; Every 1 day never builds TOMORROW.
- NotificationManager identity is `(tag containing full reminder ID and kind, stable small kind ID)`, so notification tags prevent 64-bit-to-32-bit ID collisions. Action PendingIntents carry reminder ID, kind, action, and expected revision, use stable data/request identity, and are immutable.
- `POST_NOTIFICATIONS` is requested only after the user taps the permission banner on Android 13+; denied permission leaves CRUD usable and can link to app notification settings. The debug-only adb receiver exercises notifications before Phase 4.
- Phase 3 does not schedule alarms or mutate Room from notification actions. AlarmManager delivery is Phase 4; Done, Dismiss, +1 day, Seen, and swipe-action processing are Phase 5.
- The exact-alarm corrective pass uses `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, ...)` for both DUE and TOMORROW when permitted, and `setAndAllowWhileIdle(RTC_WAKEUP, ...)` otherwise. It uses `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`, and does not use WorkManager as the primary timer.
- `ReminderService` coordinates CRUD with `ReminderScheduler`; the repository stays Android-free. The application container owns one scheduler and one Room repository, and startup plus narrow boot/time/time-zone receivers reconcile all rows.
- Alarm PendingIntent data contains the full reminder ID and notification kind. The integer request-code hash is not the sole identity. Alarm delivery re-reconciles Room state and accepts only current expected revisions; a DUE seed revision of zero is used only for the future next-normal alarm.
- Valid alarm delivery posts through the Phase 3 notification manager and does not immediately recreate the delivered one-shot alarm. A later reconciliation can safely rebuild it from Room if the visible notification is lost.
- Notification body taps use a stable immutable activity PendingIntent that opens the main list. Action PendingIntents are parsed by the explicit receiver and processed through the application-scoped Room/action processor; DUE and TOMORROW delete intents provide best-effort swipe semantics.
- `supportsTomorrow(intervalDays)` is the shared pure eligibility rule used by the state machine and notification/scheduling code.
- Tomorrow previews are acknowledged per logical normal occurrence and use one `Seen` action. They are suppressed when the reminder is already due and are not replayed when obsolete after recovery.
- Every-1-day reminders never create Tomorrow preview state or Tomorrow notifications. For eligible schedules, an existing preview remains current after `previewAt` until Seen, supersession, or the actual occurrence becoming due; a missing past preview is not replayed during recovery.
- A Tomorrow revision changes whenever its occurrence instant, preview instant, or time zone changes, and stays stable for an unchanged reconciliation.
- `RegularNotificationsApplication` owns one lazy application-scoped container with the Room database and repository; future UI and receivers must use it rather than creating database instances.
- Room schema version 2 removes the obsolete duration anchor and interval unit/amount columns in favor of `intervalDays`. Because this is pre-release development data, opening an old local database deliberately uses destructive migration.
- Phase 2 uses a small two-destination Navigation Compose graph so a create/edit ViewModel is scoped to its editor and is cleared after Save or Cancel. There is no DI framework.
- The editor exposes only title, optional description, first date/time, enabled state, and `Every [X] days`; Tomorrow eligibility is automatic for `intervalDays >= 2` and is never a user setting.
- Any asynchronous receiver work must use a receiver lifecycle mechanism such as `goAsync()`/`PendingResult.finish()`; unmanaged `onReceive()` coroutines are prohibited.
- The main UI must be understandable in under one minute: direct controls, obvious labels, sensible defaults, few screens, and no unnecessary onboarding or advanced settings.

## Corrective/product decisions

- The persisted `app_settings.masterEnabled` singleton is the only global
  reminder-delivery setting. It defaults to true and is read by Room-backed
  reconciliation; Compose state is only a presentation of it.
- Effective delivery is `masterEnabled && reminder.enabled`. Turning the master
  switch off clears DUE/TOMORROW derived state and visible notifications and
  skips occurrences without fake history. Turning it on first skips all time
  elapsed while globally paused, then rebuilds only future anchored work.
- Global pause does not alter reminder definitions, recurrence anchors, or
  individual enabled switches. A reminder whose own switch is off remains off
  after global resume.
- AlarmManager and explicit receivers provide background behavior without a
  foreground service or polling. Android Force Stop may still suppress alarms
  and receivers until the app is opened again.
- Denied notification permission retains Room outstanding state. A denied to
  granted transition triggers one reconciliation; permission and the master
  switch never silently change one another.
- Exact-alarm access is independently user controlled. Granting or revoking it
  never changes notification permission, master pause, or individual reminder
  enabled state. Permission-state broadcasts and lifecycle resume re-check the
  capability and rebuild the Room-derived schedule; Android Force Stop remains
  an explicit platform limitation.

## Phase 1, Phase 2, Phase 3, and Phase 4 verification note

The simplified Every-X-days Phase 1 checkpoint passed the JVM suite, lint,
debug APK, and Android-test APK compilation before Phase 2 began. The final
Phase 2 verification passed the same checks. Instrumentation execution remains
pending a usable physical device or emulator; the available adb process is not
currently usable in this environment.

Phase 3 verification passed the JVM suite, lint, debug APK, and Android-test APK
compilation. Connected instrumentation remains pending a usable phone or
emulator; the current adb process cannot create its Android user directory.

Phase 4 verification passed `test`, `lint`, `assembleDebug`, and
`assembleAndroidTest`. Connected instrumentation was not run because no usable
device or emulator is available. Physical testing is still required for actual
AlarmManager timing, lock-screen delivery, battery saver/Doze behavior, reboot,
time-zone changes, notification body taps, and vendor-specific background
policies.

The corrective/product pass also passed `test`, `lint`, `assembleDebug`, and
`assembleAndroidTest`. Connected instrumentation was not run because no usable
device or emulator is available. Physical testing remains required for
permission-granted recovery, global pause/resume across process death and
reboot, visible notification cancellation, lock-screen delivery, battery
saver/Doze behavior, and vendor-specific background policies.

The exact-alarm corrective pass passed `test`, `lint`, `assembleDebug`, and
`assembleAndroidTest`. Connected instrumentation was not run because no usable
device or emulator is available. Physical testing remains required for exact
access grant/revocation fallback behavior, Samsung timing, lock-screen delivery,
battery saver/Doze behavior, reboot, time-zone changes, and vendor-specific
background policies.

Phase 5 verification passed `test`, `lint`, `assembleDebug`, and
`assembleAndroidTest`. The debug APK and Android-test APK were created. A
connected instrumentation run was not possible because `adb devices` failed
before listing devices: the available adb process could not create its Android
user directory. Physical testing remains required for real notification action
delivery, swipe delete intents, lock-screen behavior, multiple simultaneous
reminders, and vendor-specific notification policies.

The post-Phase-5 edit-semantics corrective pass passed `test`, `lint`,
`assembleDebug`, and `assembleAndroidTest`. Connected instrumentation was not
run because `adb devices` again failed before listing devices: the available adb
process could not create its Android user directory. Physical testing remains
required for visible-notification replacement after metadata edits, old-action
rejection after edits, replacement-schedule alarm cancellation, and paused
editing on a real device.

The post-Phase-5 `+1 day` wall-clock corrective pass passed `test`, `lint`,
`assembleDebug`, and `assembleAndroidTest`. Physical testing remains required
for late, repeated, overdue, and daylight-saving postponement behavior on a
real device.

Phase 6 verification passed `test`, `lint`, `assembleDebug`, and
`assembleAndroidTest` after adding the repository-backed History destination,
recovery tests, and package-update recovery action. Connected instrumentation
was not run because a usable authorized phone or emulator is not available;
`adb devices` must be checked before any future connected run. Physical testing
remains required for notification permission variants, app-open/closed and
lock-screen delivery, simultaneous reminders, notification swipes, editing and
deletion of scheduled reminders, reboot, time-zone/DST behavior, battery saver,
postponed recovery, and package-update recovery.
