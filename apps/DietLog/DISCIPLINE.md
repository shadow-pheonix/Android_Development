# FuelNote and Discipline

## Follow-ups, Supplements and daily activity log

Implementation base: `e20a71c7806e4b46918c6141b810e861741cf8c6`, verified byte-for-byte against GitHub main. Branch: `feature/discipline-followups`. APK packaging and APK-producing CI wait for the owner’s build-timing choice.

Each unfinished routine receives its scheduled alert and a follow-up one elapsed hour after the original scheduled time. Completing it from the dashboard or notification saves one daily activity entry and cancels its remaining alert. Weekday recurrence uses the original reminder day. A late follow-up may arrive after midnight and still belongs to that original day, as requested by the owner. The dashboard provides a separate yesterday completion action for these overnight routines. Android permission, exact-alarm access and device restrictions still affect delivery. A first alert delayed past the follow-up time is consolidated into one alert instead of two back-to-back alerts. Explicit snoozes remain user-controlled additional alerts.

The category list includes Food, Skin, Hair, Supplements and Other. Supplements are user-defined routine reminders; the app does not select products or doses.

**Daily activity log** displays completed routines by reminder date, with title, category and actual completion time. Completion from either the app or a notification uses the same atomic saved record. Repeated Done actions do not duplicate entries; Undo removes the completion. Renaming or deleting a reminder preserves existing activity snapshots. History is bounded to the latest 365 days and 2,000 entries. Existing completion dates are migrated with an unavailable time label rather than an invented timestamp. Per-reminder recurrence flags and delivery evidence retain their existing 90-date bound. Completion records stay in Discipline; they do not add nutrition entries to FuelNote. Existing local/cloud backup behavior remains unchanged.

Planned checkpoints: (1) persistent daily completion history, Supplements and staged alarm scheduling with focused recurrence/rollback regressions; (2) daily-log browsing, overnight actions, category controls and cumulative non-APK Android validation.

Validation: all 247 Android unit tests passed with zero failures/errors/skips, including 40 reminder scheduling/storage tests. Android lint passed with no errors. The local checks compiled the updated app and ran tests/lint without assembling an APK. Physical-device notification timing and the new screens still need device verification. No APK-producing CI or merge is started until the owner chooses build timing.

## Original connected-app implementation record

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

## Flexible routines implementation

Base: `a8ecb6c216fae18c04d81f6a83b992dad94bd147`; branch: `feature/discipline-flexible-routines`.
The owner explicitly authorized a cloned chat-local checkout, overriding the ZIP handoff rule for this chat.
Planned checkpoints, completed sequentially: skip/dated pause and outcomes; flexible schedules and occurrence identity; routine checklists; consistency calendar; backup/merge preview; approved timing suggestions and cumulative validation. APK packaging is deferred pending the required build choice.

Skip today records Skipped without completion or deletion, cancels the occurrence's alerts, and can be undone. Dated pauses exclude dates from activity, preserve the reminder, and schedule automatic resumption on the selected date (including Pause until Monday). Resume now ends the pause. Done, Skipped and Missed use distinct history labels; missed history begins at reminder creation/upgrade and completed days keep their original snapshots. Editing/deleting reminders preserves past missed snapshots too. Pause intervals are retained for the activity window. Notification actions reject skipped and paused occurrences.

Flexible schedules support weekdays, every 1–365 days anchored to a start date, or up to 366 selected dates; optional inclusive start/end dates constrain recurrence. Up to eight distinct wall-clock times have independent durable Done/Skipped records, notification stages and snoozes. Existing single-time records retain their IDs and completion history; adding a time starts tracking it on the addition day. Follow-ups default to 60 elapsed minutes and can be adjusted to 5–1440 minutes per reminder. Date and time pickers supplement editable date/time fields; invalid ranges, duplicate times and empty selected-date schedules are rejected before saving. FuelNote's summary counts actual scheduled occurrences and excludes pauses/skips.

Routines group existing or new reminders by a normalized optional routine name. Create routine accepts 1–12 unique checklist steps and a shared initial schedule; each saved step can then be edited, completed, skipped, paused or removed independently. Creation is one durable transaction and rejects duplicate routine names. Home shows one compact card with progress and a short step preview; Open checklist exposes individual controls. Routine names and scheduled-time snapshots stay in history after edits/deletion. Creation and step-edit drafts survive leaving the app and rotation through the existing editor draft checkpoint.

Consistency shows a Monday-aligned calendar for the last 7 or 30 days with accessible date cells and per-date routine totals. A routine is complete only when all its scheduled occurrences are Done. Skips, misses, still-open occurrences and excluded pauses remain separate. Overnight follow-ups stay open through the scheduler's six-hour delivery window before becoming missed. Existing activity snapshots remain visible after deletion and take precedence over current schedule settings. Tapping any date opens the daily activity log. Calendar rollups read one local snapshot per refresh rather than issuing a storage read per cell.
