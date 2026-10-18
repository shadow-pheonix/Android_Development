# FuelNote and Discipline

Implementation base: `1727c5f5705ce47b3bce3dc472db91f9090ad05e`, verified against GitHub main and all 650 tracked file blobs in both existing chat-local worktrees on 18 October 2026. Branch: `feature/fuelnote-discipline`.

The owner requested a launch choice between FuelNote and Discipline inside one installed app. Both sections share local data. FuelNote retains its green theme; Discipline uses the same dark glass design with a blue accent. Discipline manages user-created food, skin care, hair care and custom reminders.

Planned checkpoints:

1. Persistent reminder records, weekday scheduling, completion/snooze history and Android notification/alarm handling. Verify recurrence, time zones, interrupted writes and stale delivery/action protection.
2. Blue Discipline dashboard/editor and the two-option launcher. Preserve reminder drafts and provide contextual notification controls, completion, snooze and diagnostics.
3. Connect both sections to shared profile/diary/plan/reminder records and add navigation from FuelNote. Preserve existing onboarding and food logging. Validate the complete Android build and CI before merging and verify the signed release APK.

The [ingredient-first preference design](DRAFT_INGREDIENT_PREFERENCES.md) stays deferred. Existing planner behavior is outside this implementation.

Android scheduling follows the official [alarm guidance](https://developer.android.com/develop/background-work/services/alarms) and [notification permission guidance](https://developer.android.com/develop/ui/compose/notifications/notification-permission). Exact delivery uses user-granted alarm access where available; otherwise timing is approximate. Notification permission and channel/device settings affect delivery. Force-stopping an app prevents background delivery until it is opened again. Reminders are user-defined routine prompts; completing one does not log food or imply a medical recommendation.

Checkpoint 1: reminder identity, categories, validated weekday/time/meal links, bounded private storage and per-date completion are implemented. Five focused scheduling tests passed, covering weekday masks, completed dates, disabled reminders, midnight, time-zone changes and daylight-saving gaps/overlaps. The ingredient-first proposal is saved separately as a deferred draft.

Checkpoint 2: the launcher offers FuelNote and Discipline; Discipline has a blue glass dashboard, category filters, selected weekdays, a time picker, local notes and meal links, editable reminders, pause/delete, Done/Undo and snooze. Raw editor drafts survive leaving/rotation, and disk-save success is distinguished from alert-scheduling failures. Native receivers restore schedules after reboot/time changes/app updates and guard stale/duplicate alarm actions. Fourteen reminder regressions passed; the integrated Android compile, lint and APK build passed locally. Parent review corrected notification routing, cancellation/write ordering and foreground notification-state refresh.

Both sections now read the same local records. Discipline shows FuelNote targets, diary totals, logged-meal status and saved meal names; FuelNote shows current reminder completion and opens Discipline. Food-reminder links retain the existing FuelNote login/profile gate before opening the intended meal search. Completion never changes the diary. Internal activities/receivers remain unexported; the chooser is the only launcher. Read-only listeners refresh visible state without alarm-rescheduling loops.

Checkpoint 3: 210 Android unit tests passed with zero failures/errors/skips, including three shared-data regressions for real profile targets, exact diary/meal totals, independent saved-plan labels and incomplete-profile recovery. All 40 existing food/photo/catalogue checks passed. Android lint and the complete 0.9.0 APK build passed. The compiled manifest uses the chooser as the single launcher and keeps the reminder receivers internal. No existing planner source or food-catalogue assets changed.

Reminder records and drafts use private local preferences inside this installation. Existing cloud snapshot formats do not include reminders; the app's existing Android OS backup policy remains unchanged. No additional account is required. Physical-device appearance, TalkBack/keyboard behavior and background notification delivery remain unverified in this environment. Final branch CI must pass before merge and the stable-signed main release must be verified before delivery.
