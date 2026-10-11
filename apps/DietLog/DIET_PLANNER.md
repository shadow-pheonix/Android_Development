# Personalized diet planner

Base snapshot: `b2e5cc18ed30e7d30ec01db943dce6cee78d62fe`, verified against GitHub main and every tracked blob on 11 October 2026.
Work branch: `feature/personalized-diet-planner`.

## Sequential implementation plan

1. Preference and restriction model: explicit Love / Okay / Avoid / Never choices, dietary pattern, ingredient exclusions, budget, cooking access and routine. Bounded meal-specific learning from distinct diary days; explicit choices outrank inferred habits. Test restriction precedence and learning before checkpointing.
2. Meal data and affordability: source-linked meal combinations, realistic portions, ingredient verification, editable prices, consumed-cost and shopping-cost distinction. Validate catalogue references, nutrition and pricing before checkpointing.
3. Full-day engine: bounded whole-day search, dietary exclusions before scoring, calorie/macro fit, budget, variety and practical limits; explain infeasible inputs and uncertain nutrition/prices. Verify against adversarial profiles before checkpointing.
4. Daily planner UI: preference editor, food selection, plan persistence, swaps, locks, cheaper/quicker options, rest-of-day replanning, explicit logging and reason-specific feedback. Verify navigation, persistence and ledger separation before checkpointing.
5. Extended planning: personal ingredient recipes, pantry quantities, seven-day menus, consolidated shopping lists, leftovers and understandable recommendation explanations. Verify stock accounting, weekly variety and cumulative Android build before merging.

## Design commitments

- Offline core; no external AI dependency or transmission of eating preferences.
- Explicit exclusions remain exclusions regardless of history, budget or nutrition scores. Avoid is omitted automatically; users can change it explicitly.
- Logging indicates familiarity and meal context, not consent to override a preference or evidence of medical suitability.
- Food IDs carry identity. Unknown manual entries do not acquire fabricated catalogue matches.
- Recipe nutrition uses source data and amounts; missing nutrients remain unknown. No generated medical diets, diagnosis or hidden target changes.
- Costs are editable estimates unless all required prices have been supplied. Consumed food cost differs from cash needed to buy packs. Pantry has explicit units and never automatically changes when a plan is merely viewed.
- Plans do not enter the diary until consumption is confirmed. Logged food remains the source of truth during replanning.
- Worker computation is bounded, results are tied to the initiating request, and diagnostics contain counts/timing only.

## Progress and verification

Implementation checkpoints and their evidence are recorded here as each stage completes.

Checkpoint 1: preference/intent foundation implemented. Four Java regression tests passed locally with Java 17 and JUnit 4.13.2: duplicate-day bounds, explicit exclusion precedence, recency/meal learning, separate feedback reasons and input validation. No app UI or diary data changes in this checkpoint.
