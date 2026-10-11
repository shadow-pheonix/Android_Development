# Personalized diet planner

Current UI: one saved standard plan with swipeable meal alternatives and guided preferences. The original daily/weekly implementation history below is retained. [Current implementation and verification](GUIDED_PLANNER.md).

Base snapshot: `b2e5cc18ed30e7d30ec01db943dce6cee78d62fe`, verified against GitHub main and every tracked blob on 11 October 2026.
Work branch: `feature/personalized-diet-planner`.

## Sequential implementation plan

1. Preference and restriction model: explicit Love / Okay / Avoid / Never choices, dietary pattern, ingredient exclusions, budget, cooking access and routine. Bounded meal-specific learning from distinct diary days; explicit choices outrank inferred habits. Test restriction precedence and learning before checkpointing.
2. Meal data and affordability: source-linked meal combinations, realistic portions, ingredient verification, editable prices, consumed-cost and shopping-cost distinction. Validate catalogue references, nutrition and pricing before checkpointing.
3. Full-day engine: bounded whole-day search, dietary exclusions before scoring, calorie/macro fit, budget, variety and practical limits; explain infeasible inputs and uncertain nutrition/prices. Verify against adversarial profiles before checkpointing.
4. Daily planner UI: preference editor, food selection, plan persistence, swaps, locks, cheaper/quicker options, rest-of-day replanning, explicit logging and reason-specific feedback. Verify navigation, persistence and ledger separation before checkpointing.
5. Extended planning: personal ingredient recipes, pantry quantities, seven-day menus, consolidated shopping lists, leftovers and understandable recommendation explanations. Verify stock accounting, weekly variety and cumulative Android build before merging.

## Design commitments

- Offline core; no external AI dependency or planner API transmitting preferences. Planner settings are private app preferences; the existing Android OS backup setting still applies.
- Explicit exclusions remain exclusions regardless of history, budget or nutrition scores. Avoid is omitted automatically; users can change it explicitly.
- Logging indicates familiarity and meal context, not consent to override a preference or evidence of medical suitability.
- Food IDs carry identity. Unknown manual entries do not acquire fabricated catalogue matches.
- Recipe nutrition uses source data and amounts; missing nutrients remain unknown. No generated medical diets, diagnosis or hidden target changes.
- Costs are editable estimates unless all required prices have been supplied. Consumed food cost differs from cash needed to buy packs. Pantry has explicit units and never automatically changes when a plan is merely viewed.
- Plans do not enter the diary until consumption is confirmed. Logged food remains the source of truth for actual consumption; the reusable template has separate totals.
- Worker computation is bounded, results are tied to the initiating request, and diagnostics contain counts/timing only.

## Progress and verification

Implementation checkpoints and their evidence are recorded here as each stage completes.

Checkpoint 1: preference/intent foundation implemented. Four Java regression tests passed locally with Java 17 and JUnit 4.13.2: duplicate-day bounds, explicit exclusion precedence, recency/meal learning, separate feedback reasons and input validation. No app UI or diary data changes in this checkpoint.

Checkpoint 2: 31 meal combinations reference 20 pinned IFCT/USDA ingredients, with explicit raw/cooked price states, oil/salt amounts and preparation steps. Eight cumulative Java tests and two catalogue tests passed; all 31 existing Python tests passed. The complete 28,102-food database was rebuilt locally. Reference prices are illustrative editable estimates, not live market data; allergy cross-contact and product labels remain the user’s responsibility.

Checkpoint 3: bounded whole-day beam search implemented (192 retained states, up to 400 candidates per meal). Fourteen cumulative Java tests passed, covering budget-feasible alternatives, explicit ingredient exclusions, meal locks, unknown micronutrients, conflicting targets, determinism and rest-of-day plans after over-target logging. Search is approximate; infeasibility is reported within the available catalogue/search limits, never asserted as a mathematical impossibility.

Checkpoint 4: daily Android screens, persistent explicit preferences/prices, user-selected remaining meals, diary-aware totals, saved plans, swaps/locks, cheaper/quicker options and reason-specific feedback are implemented. The complete 131-test app unit suite and Android lint passed locally, and the activity compiled against SDK 35/min SDK 29. Consumption uses a transaction-protected stable token; missing recipe micronutrients are not fabricated. Physical-device interaction testing remains outstanding.

Checkpoint 5: personal ingredient recipes and catalogue additions, explicit diary-food links and favorite import, seven-day menus, pantry/pack prices, prepared portions and shareable consolidated shopping are implemented. Planning does not consume inventory; today's confirmed consumption uses staged, idempotent stock deductions with recovery after an interrupted diary write. Replaced stock batches are protected from older deductions. Past-date diary corrections require manual inventory adjustments. The search retains at most 336 candidate states while selecting a 192-state beam, evaluates up to 600 options per meal (covering all 131 supported recipes), and cancels when its activity closes. Learning also covers weak meal-specific keep/swap signals and bounded portion balance from at least four broadly logged days; explicit largest-meal choices override routine learning.

Final local verification: 150 app unit tests passed, including preference JSON round trips, pending-consumption recovery, replaced-stock protection, pack consolidation, finite prepared stock across seven days, infeasible budgets, maximum recipe capacity, deterministic evidence and cancellation. All 33 Python food/photo/planner tests passed against the rebuilt full catalogue. Android lint passed and the complete 0.8.0 debug APK assembled locally. CI must validate the final feature head before merge and the signed main APK before handoff.

Final review correction: final candidate scoring now includes the selected/learned meal portion balance, and partially eaten meals subtract their logged energy from their share. All day totals come from one diary-entry query. An explicit choice to plan no further meals shows diary totals without adding recommendations or consumption. Three regression cases were added; the complete 150-test unit suite, lint and APK build passed again.

## How to use it

Open **My standard diet plan** from Home or **My diet plan and eating preferences** from Profile. Complete the existing personal profile first; planning currently supports adults.

1. Follow Back/Next through eating pattern, allergies, unsuitable ingredients, meal routine, budget and cooking access. Recipes appear first in meal-specific lists of up to four, followed by ingredients. Lists rotate dish families and cover dinner separately; with undecided cuisine they mix Andhra and other Indian options. Saved ratings survive catalogue upgrades, and new discovery sessions lead with unrated dishes. Every card has Love, Okay, Avoid and Never recommend underneath. Ratings save immediately; unfinished fields and progress can be resumed. Leaving a card unrated does not assign Okay.
2. Save the review to open your plan. Explicit choices are primary. Recent diary evidence refreshes whenever the standard plan is generated, loaded or changed; it remains secondary and never writes taste ratings. New logs do not silently replace selected meals. Diary learning can be disabled in the cooking step.
3. Generate one standard plan. Each enabled meal category offers up to four suitable alternatives from distinct dish families. Breakfast and snacks use appropriate separate recipe categories. Andhra leads when selected and sufficient suitable choices exist; explicit loved recipes remain eligible. Broader cuisine or repeated-family choices may be used to seek an affordable plan, with an explanation. Swipe horizontally or use Previous/Next; **Choose this** saves the selection. Browsing does not select or log food. The summary describes the selected combination; swaps can move it outside saved targets/budget. Read the displayed fit warnings.
4. **Edit Breakfast/Lunch/Dinner/Snacks only** allows another recipe, an adjusted portion, refreshed alternatives or a custom recipe. A customized copy has a new identity, so ingredients do not rewrite other meals. Recipe and selected meal save together. Restrictions still apply; other valid selections stay unchanged.
5. **Plan tools & local prices** contains recipe/ingredient management, prices, pantry/pack prices, prepared-food records and shopping. Shopping represents one full set of selected meals; pantry is deducted once. Reference costs are illustrative unless all prices are entered. The reusable plan does not assume today's finite pantry/prepared stock stays available on future days.
6. Confirm only food actually eaten: choose its diary date, review actual servings and any prepared portion, then log. Optional inventory updates apply only to today's confirmed consumption and retain interrupted-write recovery. Plan edits do not change existing diary entries.

Existing preferences and personal recipes are preserved on upgrade. Preference changes revalidate saved selections and replace only missing/incompatible meals when suitable candidates exist. Other valid selections and portions stay unchanged. If exclusions leave a meal without a candidate, its section explains the limitation and offers preference review or single-meal editing; other available meals remain visible. Whole-plan regeneration asks before replacing all selections.

## Practical limits

- A bounded, approximate search over 86 initial recipes (57 Andhra options, grouped into 55 dish families) plus up to 100 personal recipes; up to 200 added catalogue ingredients and 200 explicit identity links. It reports the closest available fit and search/candidate limits rather than silently changing targets or exclusions.
- Continuous secondary familiarity uses at most 2,000 recent diary records, distinct food/meal days within 42 days and a 14-day recency half-life. It does not infer medical suitability or equate arbitrary dish names. The inferred taste score is capped below an explicit Okay. Routine learning requires multiple broadly logged days and makes only modest adjustments. History/selection learning can be disabled; feedback can be reset independently of explicit preferences.
- Calories/protein/carbs/fat for the selected standard combination are evaluated separately from diary consumption. Fibre/sugar/sodium are used only where known. Logged-food micronutrient coverage and some cost data may be unknown; those limits/spending cannot be guaranteed. Meal micronutrients are logged only when every ingredient supplies that nutrient. There is no additional saturated-fat target invented outside the user's saved profile.
- Planner settings/recipes/plans are not part of the existing DietLog Drive/Supabase snapshot formats. They persist in app-private preferences and follow the app's existing Android OS backup policy. No planner network request or external AI service is required.
- General adult meal suggestions, not clinical diets. Cross-contact, actual food labels, preparation losses/yields and prepared-food storage suitability require user verification.
- Physical-device interaction, accessibility and appearance checks remain unverified in this environment. Automated compilation, tests and lint do not replace those checks.
