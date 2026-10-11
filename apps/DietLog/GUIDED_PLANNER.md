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

Checkpoint 4: horizontal alternatives, explicit selection, independently edited meal portions and new-identity recipe copies, simplified planner navigation, and template totals separated from diary consumption. Recipe/plan saves are atomic; failed disk commits restore the previous visible checkpoint. Subagent review corrected swipe paging and setup draft/rotation recovery. Full local verification passed: 171 app unit tests, 33 Python catalogue/photo tests, Android lint and the 0.8.1 APK build. Temporary-storage quota was resolved by moving the SDK to ignored development tooling storage. Physical-device gestures, keyboard, TalkBack and appearance remain unverified. Final feature CI must pass before merge.

## Recommendation repair, 0.8.2

Verified repair base: `bb1ec98e268d4e3820002ad9e344ee0da08802cd`; all 648 tracked files in both existing chat-local worktrees matched the GitHub main tree byte-for-byte. Branch: `feature/dietlog-complete-andhra-plans`.

The reported screen had no lunch/dinner recommendation. Investigation found that the 31-recipe starter catalogue offered only two lunch/dinner options without cooking or with a ten-minute limit; excluding these could block both slots. The engine discarded every meal when one slot had no candidates, and restoration removed incompatible selections without replacing them. Multi-meal rating recipes were assigned to their first eligible meal, leaving dinner poorly represented.

Repair checkpoints:

1. Expand source-linked Andhra recipes, distinct dish families and appropriate meal categories; preserve existing recipe IDs. Validate exact pinned nutrition, honest preparation states and raw/cooked preference links.
2. Preserve eligible meals when another slot is blocked; repair missing/invalid saved slots without changing valid selections; diversify alternatives and prefer the selected cuisine while respecting explicit tastes, food restrictions and affordability. Verify actual production catalogue profiles.
3. Balance and diversify guided rating lists, refresh old discovery queues without removing saved ratings, show missing-meal explanations, and size swipe pages to the screen. Run cumulative Android tests, lint and APK build, then verify branch CI and the signed main APK.

Checkpoint 1: 86 recipes, 57 Andhra options and 55 dish families; all 31 existing recipe IDs retained. Nine catalogue contracts and the existing food/photo checks passed against the full database. The JVM nutrition fixture is checked against every pinned database value in CI. Purchased cooked dishes with unknown complete composition remain excluded for dietary/allergy/ingredient restrictions; ready meals clearly require already-cooked/purchased ingredients. Recipe instructions distinguish active preparation time from soaking/fermentation. Exact plain rice and whole-green-gram raw/cooked forms share taste exclusions while retaining separate nutrition identities.

Checkpoint 2: whole-day generation now retains safe available meals if another category is blocked and marks the plan incomplete. Saved templates fill only missing/incompatible slots and retain valid selections and exact portions. Alternatives rotate distinct dish families; selected paired meals differ when eligible choices permit it. Andhra-first planning retains explicit recipe favourites and falls back to broader cuisines or repeated families when seeking a lower-cost combination. The final focused JVM run passed 69 cases, including the actual shipped catalogue across all dietary patterns, no-cook profiles, saved partial templates, explicit exclusions, regional prompts and affordability/restoration edge cases. Search coverage now includes all four portion candidates for the maximum supported recipe count.
