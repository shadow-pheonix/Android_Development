# Guided preferences and standard meal plan

Verified base: `7fcc73af80097b7c87cdb70b9eb21239ebbbdf0d` (all 641 tracked files matched Git blobs).
Branch: `feature/diet-planner-guided-preferences`.

Sequential checkpoints:

1. Persist resumable setup pages and stable recipe-first batches atomically with ratings. Keep diary evidence continuously refreshed and weaker than an explicit Okay.
2. Introduce full-page Back/Next setup: eating pattern, suitability, routine, budget/cooking, meal-specific recipe batches, secondary ingredient batches, and review. Preserve drafts and allow unrated cards without inventing preferences.
3. Implement a saved standard plan with independently selectable alternatives and checks for combinations. A single meal can be replaced without changing the other meals.
4. Integrate horizontal meal alternatives, single-meal editing and simplified planner navigation. Validate cumulative unit tests, lint and APK; verify branch CI before merge and signed main build before delivery.

Progress: checkpoint 1 passed all 39 planner regression cases, including atomic-save failure recovery, stable recipe-first queues and continuously refreshed secondary diary signals.
