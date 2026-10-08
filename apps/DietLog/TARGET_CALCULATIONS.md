# Recommended daily targets (0.7.34)

Targets are starting estimates, not laboratory measurements or long-term weight forecasts.
Required profile details and setup-completion rules are unchanged. Optional details appear
in a collapsible section in setup and Edit profile, with guidance about reliable measurements.
Leaving every optional field blank remains supported.

## Energy estimate

Adult resting calories use this priority:

1. A recent laboratory-measured resting metabolic rate (RMR), if supplied.
2. Cunningham: `500 + 22 × fat-free mass (kg)` when a measured body-fat percentage is supplied.
   Fat-free mass is `weight × (1 − body-fat % / 100)`.
3. Mifflin–St Jeor: `10 × kg + 6.25 × cm − 5 × age + sex constant`
   (male +5, female −161), retaining the existing baseline.

Maintenance is resting calories times the selected activity factor. Activity describes the
whole usual week: work, walking and exercise. Training type personalizes protein and does
not add another calorie multiplier. The existing factors 1.20/1.375/1.55/1.725/1.90 remain
estimates rather than precise measurements of exercise expenditure.

A user-supplied observed maintenance intake replaces that multiplication entirely. It is
the average logged food intake during at least 2–4 weeks of stable weight at the current
activity level, not a desired calorie target, watch calorie estimate, or intake during an
ongoing loss/gain phase. It already includes activity. Measurements should be updated when
body composition, weight or routine changes substantially. A guessed body-fat number or an
inaccurate food log can reduce accuracy; filling a field does not guarantee improvement.

Automatic adult goal adjustments retain the existing goal-specific energy caps and 1,200
kcal floor; targets round to 10 kcal. Explicit manual overrides (800–8,000 kcal) still win.
The displayed initial energy-equivalent pace uses the actual bounded target, including the
floor, rounding and manual override, rather than the requested pace before those limits.
The existing 7,700 kcal/kg conversion is a rough initial conversion: adaptation and changes
in body composition mean it cannot predict sustained weekly weight change. Recomposition
and maintenance retain zero planned change despite negligible rounding differences.

For supported ages 13–17, the FAO/WHO/UNU Schofield 10–18-year equations replace the adult
model: boys `17.686 × kg + 658.2`, girls `13.384 × kg + 692.6`. Maintenance includes the
report's 1% growth allowance. Adult measured calibration inputs and automatic loss/bulk
adjustments are not used for this age group. The preview explains this; custom manual
targets are still user-controlled.

## Macro and other targets

Existing goal starting points remain: cut 2.2, recomp/loss 2.0, lean bulk/gain 1.8 and
maintenance/dirty bulk 1.6 g protein/kg (custom 1.8). Optional training type refines
maintenance protein: no structured training starts at 0.83 g/kg, or 1.2 g/kg at age 65+;
endurance starts at 1.4 g/kg. Muscle-building goals keep their higher protein starting point.
For a cut with resistance/mixed training and measured body fat, protein starts at 2.6 g/kg
fat-free mass, within the ISSN 2.3–3.1 range for resistance-trained energy restriction.
Teens start at 1.0 g/kg before energy-budget constraints.

Generated adult macro energy shares fit protein 10–35%, fat 20–35% and carbs 45–65%.
For teens, protein is 10–30% and fat 25–35%. Fat starts at the higher of 0.8 g/kg or the
age-specific minimum share, then adjusts to keep carbohydrate within its range. Carbs
use the remaining energy. Whole-gram rounding can produce at most a 2 kcal total difference
and small share differences. These budget constraints can supersede weight-based protein
when body weight and a custom calorie target are far apart.

Fibre starts at the higher of 25 g or 14 g/1,000 kcal; sodium limit remains 2,000 mg.
WHO's free-sugar guidance (<10% energy) is kept separate from catalogue **total sugars**,
which include intrinsic fruit/milk sugars. Generating targets leaves the optional total-sugar
limit unset rather than applying a free-sugar ceiling to incompatible total-sugar data.
Existing/custom total-sugar limits remain intact. Vitamin/mineral reference tables are
unchanged; energy-dependent references automatically use the refined maintenance estimate.

First-run recommendations and explicitly generated macros follow subsequent profile,
weight and calorie changes. Editing a nutrition target or choosing a manual macro mode
switches to custom targets. Legacy profiles default to custom, preserving existing values.
The policy and optional measurements are saved in `dietlog_profile`; the existing generic
preference snapshot/merge includes them without a backup schema change. Optional fields
never affect whether returning users must repeat setup. Drafts survive Activity recreation
and use scoped bindings so the setup/editor instances do not share input state.

## Reference sources

- [Mifflin et al., 1990](https://pubmed.ncbi.nlm.nih.gov/2305711/)
- [Cunningham, 1980](https://pubmed.ncbi.nlm.nih.gov/7435418/)
- [FAO/WHO/UNU Human energy requirements: adult energy/PAL and equations](https://www.fao.org/4/y5686e/y5686e07.htm)
- [FAO/WHO/UNU: growth allowance](https://www.fao.org/4/y5686e/y5686e06.htm)
- [ISSN position stand: protein and exercise, 2017](https://pubmed.ncbi.nlm.nih.gov/28642676/)
- [WHO/FAO/UNU protein requirements, TRS 935](https://iris.who.int/bitstream/handle/10665/43411/WHO_TRS_935_eng.pdf)
- [ESPEN healthy older-adult protein recommendations](https://pubmed.ncbi.nlm.nih.gov/24814383/)
- [National Academies macronutrient energy ranges](https://www.ncbi.nlm.nih.gov/books/NBK208874/)
- [Dietary reference intakes: fibre](https://www.ncbi.nlm.nih.gov/books/NBK56068/)
- [WHO healthy diet: fibre, free sugars and sodium](https://www.who.int/news-room/fact-sheets/detail/healthy-diet)
- [NIDDK: limits of static weight-change rules](https://www.niddk.nih.gov/research-funding/at-niddk/labs-branches/laboratory-biological-modeling/integrative-physiology-section/research/body-weight-planner)

Validation includes independent equation examples, input precedence, growth/adult boundaries,
goal caps/floors, manual override behavior, macro budget/ranges across supported weights and
calories, custom-target preservation, optional preference restoration and returning-user
completion. Android lint, all unit tests and APK assembly run in DietLog's CI workflow.
