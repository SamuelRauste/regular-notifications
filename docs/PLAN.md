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

- [ ] Define `ReminderScheduler` and implement one-shot inexact `AlarmManager` scheduling for applicable `DUE` and `TOMORROW` states.
- [ ] Use stable `(reminderId, notification kind)` identities in unique `PendingIntent`s and notification IDs.
- [ ] Implement alarm delivery receiver and database-backed next-occurrence calculation.
- [ ] Make scheduling/cancellation idempotent and safe when a reminder was deleted before delivery; never schedule Tomorrow for every-1-day reminders.
- [ ] Reconstruct both applicable alarm kinds from Room-derived state after boot, time, and time-zone recovery.
- [ ] Add scheduling identifier and delivery tests.

## Phase 5 — Notification actions

- [ ] Implement Done, Dismiss, +1 day, and Tomorrow Seen action receivers.
- [ ] Record each event and remove the displayed notification.
- [ ] Preserve normal schedule for Done/Dismiss.
- [ ] Postpone only the displayed occurrence by one calendar day; do not change the reminder's recurrence anchor or normal schedule.
- [ ] Make Seen acknowledge only its Tomorrow preview, remove that preview notification, preserve recurrence and actual-reminder status, and reject stale revisions.
- [ ] Implement notification swipe dismissal where Android reliably exposes it.
- [ ] Add action-processing tests.

## Phase 6 — Recovery and history

- [ ] Reschedule enabled reminders after boot, time change, and time-zone change.
- [ ] Reconstruct alarms from Room as the source of truth.
- [ ] Add history screen/section and event display.
- [ ] Handle process death and missed alarms without notification storms.
- [ ] Add recovery and history tests.

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
- Inexact one-shot alarms are the initial timing mechanism; exact-alarm access is intentionally not requested.
- Phase 0 scaffold uses compileSdk/targetSdk 37, Android Gradle Plugin 9.2.1, Gradle 9.4.1, built-in Kotlin/Compose compiler plugin 2.3.21, and Compose BOM 2026.08.00.
- The newer Android CLI is useful and preferred for agent-driven workflows. Modern `sdkmanager` from the Android SDK Command-Line Tools package remains documented and supported for installing SDK packages; a deprecation warning may refer to the legacy SDK Tools package or an older `sdkmanager` earlier on PATH.
- Every-X-days schedules use the device's current time zone and preserve the original local calendar anchor and wall-clock time.
- There is one persisted outstanding due state and one persisted Tomorrow preview state per reminder. Normal recurrence remains canonical; postponed state is auxiliary and can be collapsed when a newer normal occurrence becomes due.
- The reminder stores one resolved/skipped normal-occurrence cursor so Done/Dismiss cannot recreate resolved occurrences and disabled-period occurrences cannot reappear after recovery or a time-zone change; this cursor does not alter the recurrence anchor and does not create history for skipped occurrences.
- Disabled reminders do not accumulate missed occurrences. Re-enabling resumes at the first future occurrence on the original Every-X-days schedule; it does not restart the schedule from the re-enable date.
- The existing `lastResolvedNormalOccurrenceIndex` column is reused as the resolved/skipped cursor; no new Room field or schema version is required.
- Disabled reminder cards show `Every X days · Paused` instead of a cached `Next:` date. Edit, delete, and enable/disable controls include the reminder title in their accessibility semantics while keeping the visible labels short.
- Phase 3 uses one `Reminders` notification channel. DUE has `Done`, `Dismiss`, and `+1 day`; TOMORROW has only `Seen`; Every 1 day never builds TOMORROW.
- NotificationManager identity is `(tag containing full reminder ID and kind, stable small kind ID)`, so notification tags prevent 64-bit-to-32-bit ID collisions. Action PendingIntents carry reminder ID, kind, action, and expected revision, use stable data/request identity, and are immutable.
- `POST_NOTIFICATIONS` is requested only after the user taps the permission banner on Android 13+; denied permission leaves CRUD usable and can link to app notification settings. The debug-only adb receiver exercises notifications before Phase 4.
- Phase 3 does not schedule alarms or mutate Room from notification actions. AlarmManager delivery is Phase 4; Done, Dismiss, +1 day, Seen, and swipe-action processing are Phase 5.
- Tomorrow previews are acknowledged per logical normal occurrence and use one `Seen` action. They are suppressed when the reminder is already due and are not replayed when obsolete after recovery.
- Every-1-day reminders never create Tomorrow preview state or Tomorrow notifications. For eligible schedules, an existing preview remains current after `previewAt` until Seen, supersession, or the actual occurrence becoming due; a missing past preview is not replayed during recovery.
- A Tomorrow revision changes whenever its occurrence instant, preview instant, or time zone changes, and stays stable for an unchanged reconciliation.
- `RegularNotificationsApplication` owns one lazy application-scoped container with the Room database and repository; future UI and receivers must use it rather than creating database instances.
- Room schema version 2 removes the obsolete duration anchor and interval unit/amount columns in favor of `intervalDays`. Because this is pre-release development data, opening an old local database deliberately uses destructive migration.
- Phase 2 uses a small two-destination Navigation Compose graph so a create/edit ViewModel is scoped to its editor and is cleared after Save or Cancel. There is no DI framework.
- The editor exposes only title, optional description, first date/time, enabled state, and `Every [X] days`; Tomorrow eligibility is automatic for `intervalDays >= 2` and is never a user setting.
- Any asynchronous receiver work must use a receiver lifecycle mechanism such as `goAsync()`/`PendingResult.finish()`; unmanaged `onReceive()` coroutines are prohibited.
- The main UI must be understandable in under one minute: direct controls, obvious labels, sensible defaults, few screens, and no unnecessary onboarding or advanced settings.

## Phase 1, Phase 2, and Phase 3 verification note

The simplified Every-X-days Phase 1 checkpoint passed the JVM suite, lint,
debug APK, and Android-test APK compilation before Phase 2 began. The final
Phase 2 verification passed the same checks. Instrumentation execution remains
pending a usable physical device or emulator; the available adb process is not
currently usable in this environment.

Phase 3 verification passed the JVM suite, lint, debug APK, and Android-test APK
compilation. Connected instrumentation remains pending a usable phone or
emulator; the current adb process cannot create its Android user directory.
