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
