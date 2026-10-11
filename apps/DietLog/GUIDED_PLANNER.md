# Guided preferences and standard meal plan

Verified base: `7fcc73af80097b7c87cdb70b9eb21239ebbbdf0d` (all 641 tracked files matched Git blobs).
Branch: `feature/diet-planner-guided-preferences`.

Sequential checkpoints:

1. Persist resumable setup pages and stable recipe-first batches atomically with ratings. Keep diary evidence continuously refreshed and weaker than an explicit Okay.
2. Introduce full-page Back/Next setup: eating pattern, suitability, routine, budget/cooking, meal-specific recipe batches, secondary ingredient batches, and review. Preserve drafts and allow unrated cards without inventing preferences.
3. Implement a saved standard plan with independently selectable alternatives and checks for combinations. A single meal can be replaced without changing the other meals.
4. Integrate horizontal meal alternatives, single-meal editing and simplified planner navigation. Validate cumulative unit tests, lint and APK; verify branch CI before merge and signed main build before delivery.

Progress: checkpoint 1 passed all 39 planner regression cases, including atomic-save failure recovery, stable recipe-first queues and continuously refreshed secondary diary signals.

Checkpoint 2: guided preference screens compile against SDK 35/min 29. All 39 planner tests and Android lint passed. Back/Next and persistent drafts replace the preference menus, with immediate explicit recipe/ingredient ratings, frozen batches and a final review. Physical-device navigation/appearance is not yet verified.

Checkpoint 3: standard-plan engine implemented. Ten focused Java/JUnit cases passed, including independently selectable alternatives, unchanged other meals during swaps/refresh, exact nutrition sums, diary/inventory independence, JSON restoration, changed restrictions, budget warnings and rollback when evaluation fails. Existing carousel choices retain their order.
