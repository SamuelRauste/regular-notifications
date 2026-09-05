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

- [ ] Create the single application module using Kotlin, Compose, Material 3, Room, coroutines, and Java time.
- [ ] Implement reminder and reminder-event entities, DAOs, database, repository, and Flow/StateFlow access.
- [ ] Implement a pure Kotlin recurrence calculator for minute/hour duration recurrence and day/week wall-clock recurrence.
- [ ] Define anchoring, missed-occurrence, time-zone, and postponement semantics.
- [ ] Add unit tests for all recurrence units, past/future starts, missed occurrences, date boundaries, DST, drift, anchoring, postponement, and validation.

## Phase 2 — Reminder management UI

- [ ] Implement list ViewModel and Material 3 reminder list.
- [ ] Implement create/edit state and screen with validation.
- [ ] Add enable/disable and delete confirmation flows.
- [ ] Add loading, empty, error, and accessibility states.
- [ ] Add Room and ViewModel tests where valuable.

## Phase 3 — Notification foundation

- [ ] Create notification channel and notification factory.
- [ ] Request `POST_NOTIFICATIONS` on supported Android versions.
- [ ] Handle denied permission without breaking reminder management.
- [ ] Add a temporary/test notification path, then cover it with tests and documentation.

## Phase 4 — Alarm scheduling and delivery

- [ ] Define `ReminderScheduler` and implement one-shot inexact `AlarmManager` scheduling.
- [ ] Use stable reminder IDs in unique `PendingIntent`s.
- [ ] Implement alarm delivery receiver and database-backed next-occurrence calculation.
- [ ] Make scheduling idempotent and safe when a reminder was deleted before delivery.
- [ ] Add scheduling identifier and delivery tests.

## Phase 5 — Notification actions

- [ ] Implement Done, Dismiss, and +1 day action receivers.
- [ ] Record each event and remove the displayed notification.
- [ ] Preserve normal schedule for Done/Dismiss.
- [ ] Postpone only the displayed occurrence by one calendar day; do not change the reminder's recurrence anchor or normal schedule.
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
