# Bundled Abilities

> Applies to: Additional Abilities for DS `2.0.0`
> Requirements: Dragon Survival `≥ 2.0.68` · (optional)Wing Kirin `≥ 3.3.0`

> This mod also bundles an **optional datapack** that reworks some Wing Kirin's abilities using this mod's own custom components. It is **off by default**; see
> [07-Bundled-Datapacks.md](07-Bundled-Datapacks.md) for what it changes and how to enable it.

> There is also a set of `test_*` abilities (`test_charged` / `test_charged_hold` / `test_optional_charged` /
> `test_block_quake` / `test_glow` / `test_screen_vision` / `test_enchantment_bonus` / `test_durability` /
> `test_anti_dragon_breath` / `test_annulus` / `test_domain` /
> `test_on_block_placed` / `test_on_item_consumed` / `test_on_ability_cast`) for development and
> debugging only.
> They are **not attached to any species** and must be granted with a command.
> See the [README](../../README.md) quick-reference table for which custom type each one exercises.  
> ***Tips: Due to requirements of test, test abilities are not all consistent with description partically. Subject to actual.***

---

## Overview

| Ability id | Name | Species | Activation | Max level | Unlock / upgrade |
|---|---|---|---|---|---|
| `sea_dragon:extinguish_breath` | Extinguish Breath | Sea Dragon| `channeled` continuous breath | 4 | Experience levels `0 / 10 / 20 / 40` |
| `sea_dragon:lightning_domain` | Lightning Domain | Sea Dragon | `simple` cast | 5 | Experience levels `10 / 20 / 30 / 40 / 50` |
| `cave_dragon:smoke_breath` | Smoke Breath | Cave Dragon | `channeled` continuous breath | 3 | Experience levels `0 / 10 / 20` |
| `cave_dragon:piercing_eye` | Piercing Eye | Cave Dragon | `passive` | 1 (no upgrade) | Active as soon as the ability is present |
| `forest_dragon:natural_alies` | Natural Allies | Forest Dragon | `simple` cast | 4 | Growth `30 / 40 / 50 / 60` (upgrade type `dragon_growth`) |
| `forest_dragon:photorepair` | Photorepair | Forest Dragon | `passive` | 3 | Growth `30 / 40 / 50` (upgrade type `dragon_growth`) |
| `additional_abilities:explosion_arrow` | Explosion Arrow | Wing Kirin | `simple` cast | 1 | Complete the "Return to Sender" advancement |
| `additional_abilities:entity_marker` | Entity Marker | Wing Kirin | `passive` + key trigger (left mouse button) | 2 | Complete the "Glow and Behold!" advancement; upgraded with experience points |

Localisation keys for each ability's name and description:

```
dragon_ability.<namespace>.<ability id>          → name
dragon_ability.<namespace>.<ability id>.desc     → description
```

---

## Extinguish Breath — `extinguish_breath`

> "Breathe a deluge of water vapour that extinguishes fire (including burning mobs)."
> "Its range depends on age, and the duration of effect depends on the experience level."
> "Cannot be used under lava." 

| Item | Value |
|---|---|
| Activation | `dragonsurvival:channeled`, `cast_time` 30 ticks, cooldown 60 ticks |
| Continuous mana cost | `0.02` per tick |
| Usage restriction | **Blocked while the eyes are in lava** |

**Effect**: a cone-shaped breath made of four actions:

1. applies **Fire Resistance** to **every** entity in the cone, lasting 200 × tier ticks (10 / 20 / 30 / 40 seconds);
2. destroys `minecraft:fire` and `minecraft:soul_fire` in the cone;
3. converts **campfires / soul campfires** to their unlit state (50% chance each) and leaves behind a cloud
   of vapour (80 ticks, 50% chance inside the cloud to top up 600 ticks of Fire Resistance);
4. plays a breath particle effect (bubble particles) on the caster.

**Usage notes**: this is a support / firefighting ability that deals almost no damage. Use it to put out
allies (and yourself) and to clear a burning area.

---

## Lightning Domain — `lightning_domain`

> "Cast the spell and scatter the 'Thunderling Force' within you into a domain, held for a time."
> "Any outsider who dares trespass is pulled down by the electricity coursing everywhere and feels far
> heavier, and is more easily hurt by lightning; while the caster is buffed, coming and going freely."
> "Best of all, their attacks more easily pierce armour."
> "The domain's radius grows with the skill level (experience levels)." "Cannot be cast inside lava."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:simple`, `cast_time` 80 ticks, cooldown 2000 ticks (100 seconds), initial mana cost 8 |
| Can move while casting | No (neck and tail are locked during the cast; animations `cast_magic_alt` → `magic_alt`) |
| Usage restriction | **Blocked while the eyes are in lava** |
| Level | max level 5, upgraded by `dragonsurvival:experience_levels`: `10 / 20 / 30 / 40 / 50` |
| Shape | annulus radius `4 + 4 × (level - 1)`; uses both `additional_abilities:annulus` and `additional_abilities:domain` |

**Effect**: four actions.

1. **Charging telegraph** (`trigger_point` = `charging`, `trigger_rate` 10): while the cast bar runs,
   **every 10 ticks** the **non-air blocks** within the annulus (`additional_abilities:annulus`) around your
   feet — inner radius `4 + 4 × (level - 1)`, width 2, height 4 (extending 4 blocks downwards) — put out
   10 `minecraft:end_rod` particles. That is the visual telegraph for the "electricity coursing everywhere".
2. **Hostile domain** (`additional_abilities:domain`, same radius, cylinder, duration 600 ticks = 30 seconds):
   **non-allied creatures** inside it (`targeting_mode` = `non_allies`, `is_harmful` = `true`) keep gaining:
   - **Gravity +0.6** (`generic.gravity`, `add_multiplied_total`) — the "feels far heavier" part;
   - **×2 damage taken from electric sources** (`#dragonsurvival:is_electric`, e.g. lightning, storm breath);
   - a **30% chance** of **Broken Wings** (`dragonsurvival:broken_wings`) — no flying.
   All three are **hidden** effects (`is_hidden: true`) and their duration is refreshed every tick by the domain.
3. **Ally domain** (`additional_abilities:domain`, same parameters): **allies and yourself**
   (`targeting_mode` = `allies_and_self`) inside it keep gaining:
   - **Armour Ignore Chance +0.4** (`dragonsurvival:armor_ignore_chance`) — the "attacks pierce armour" part;
   - **Strength II + Speed II + Haste II** (`amplifier` 1) — the "buffed, coming and going freely" part.
4. **Charged cloud on blocks** (`dragonsurvival:disc`, same radius): **non-air blocks** inside the domain have
   a **30% chance** to be left with a `dragonsurvival:area_cloud` (duration 600 ticks) — creatures inside gain
   **Charged** (`dragonsurvival:charged`) and **Glowing** (`minecraft:glowing`), with
   `dragonsurvival:large_lightning` particles.

**Usage notes**: a **two-sided area domain** that stakes out your surrounding ground as your own turf. The
damage does not come from the ability itself but from **amplifying the environment**: enemies inside are
**crushed by double gravity, slowed, take double lightning damage and have a 30% chance to be grounded**,
while allies get **Strength, Speed and Haste plus armour-piercing attacks**. **The domain does not move** —
it is fixed where it was cast and fades after 30 seconds. Pair it with lightning, storm breath or a
teammate's electric attacks to get the most out of it. The **100-second cooldown** and **8 initial mana cost**
make it an opening move for a real fight rather than a throwaway aura.

---

## Smoke Breath — `smoke_breath`

> "Breathe a deluge of fire and smoke, blinding the enemy."
> "Its range depends on age, and the duration of effect depends on the experience level."
> "Cannot be used under water, and during rain."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:channeled`, `cast_time` 5 ticks, cooldown 20 ticks |
| Continuous mana cost | `0.02` per tick |
| Usage restriction | **Blocked with eyes in water**; **blocked while raining / snowing** |

**Effect**: a cone-shaped breath made of three actions:

1. deals fire damage to enemies in the cone (`0.5 × tier`) and applies **Blindness for 200 ticks (10 seconds)**;
2. plays a smoke breath particle effect on the caster;
3. leaves behind a smoke cloud (40 ticks, 10% chance inside the cloud to apply 600 ticks of Blindness).

**Usage notes**: an extremely fast start-up (`cast_time` 5 ticks), making it a short-cooldown option for
point-blank interrupts and blinding. Backing off after the enemy is blinded is where the value is.

---

## Piercing Eye — `piercing_eye`

> "the life in lava that make you can have innate immunity the blindness."
> "Of course, this ability cannot immune the darkness, because cave dragon has never had any relationship
> with Warden."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:passive` |
| Level | no `upgrade` block → fixed at level 1, cannot be upgraded |
| Effect | multiplies the duration of `minecraft:blindness` by **0**, i.e. full immunity to Blindness |

**Usage notes**: **it only blocks Blindness, not Darkness.** One of the Cave Dragon's permanent passives.

---

## Natural Allies — `natural_alies`

> "You live in symbiosis with nature, attuned to its every breath — and you have gained the power to
> make every blade of grass and every tree carry your strike."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:simple`, `cast_time` 40 ticks, initial mana cost 6 |
| Cooldown | `600 / 800 / 1000 / 1200` ticks per level (30 / 40 / 50 / 60 seconds; linear `base` 600, +200 per level) |
| Can move while casting | No (neck and tail are locked during the cast; animations `cast_mass_buff` → `mass_buff`) |
| Usage restriction | **You must be standing on a grass-and-wood block yourself** (`#dragonsurvival:speeds_up_forest_dragon`) |
| Level | max level 4, upgraded by `dragonsurvival:dragon_growth`: growth `30 / 40 / 50 / 60` → level 1 / 2 / 3 / 4 (fallback `10` beyond) |
| Shape | radius 8, `dragonsurvival:area` target type |

**Effect**: three actions.

1. **Charging telegraph** (`trigger_point` = `charging`, `trigger_rate` 10): while the cast bar runs,
   **every 10 ticks** all **non-air blocks** within a radius of 8 that belong to
   `#dragonsurvival:speeds_up_forest_dragon` are tinted by this mod's `additional_abilities:glow` —
   green `#0cb97f`, `alpha` 0.5, `display_type` = `simple_shader`, 40 ticks per application.
   The greenery lights up first, which doubles as a countdown for whoever is standing in it.
2. **Settlement** (once, when the cast finishes — the default `trigger_point`): every
   **non-allied creature** within a radius of 8 (`targeting_mode` = `non_allies`) that is
   **standing on a grass-and-wood block** (`target_conditions` → `entity_properties.predicate.stepping_on`)
   - takes `forest_dragon:natural_force` (Natural Force) damage equal to
     **the target's max health × `0.05 × ability level`** (5 / 10 / 15 / 20%), then multiplied by the
     caster's *Dragon Ability Damage* attribute through the default expression;
   - gets **Slowness II + Weakness II** (`amplifier` 1) for `100 / 160 / 220 / 280` ticks (5 / 8 / 11 / 14 seconds);
   - gains a **hidden** damage modification `forest_dragon:natural_damage_increase`: **×2 damage taken**
     from `#forest_dragon:is_forest_dragon` (forest dragon magic) for the same duration — the
     "far more vulnerable to a forest dragon's magic" part of the description.
3. **Presentation**: 5 `minecraft:egg_crack` particles burst from the non-air blocks within radius 8.

**Usage notes**: this is a **terrain-bound area debuff**. The predicate is attached to the block under the
**target's** feet; the block under **your own** feet only decides whether you may cast it at all
(`usage_blocked`). So the correct play is to drag the enemy onto a grass-and-wood block
(grass, dirt, logs or planks — the tag expands to `#minecraft:dirt` + `#minecraft:logs` + `#minecraft:planks` +
`#minecraft:wooden_slabs` + `#dragonsurvival:is_grassy` + grass blocks) and only then cast — on stone, sand,
water or mid-air it deals **no damage at all**. Applying the ×2 vulnerability and then following up with a
breath attack is where the payoff is.

> Shipped alongside the ability: the damage type `forest_dragon:natural_force`
> (`data/forest_dragon/damage_type/natural_force.json`), the dragon-magic damage tag
> `#forest_dragon:is_forest_dragon` (`data/forest_dragon/tags/damage_type/is_forest_dragon.json`, holding
> `dragonsurvival:forest_breath` / `dragonsurvival:spike` / this damage type), and the death message key
> `death.attack.forest_dragon.natural_force`.

---

## Photorepair — `photorepair`

> "As the dragon grows in age, the plants it lives in symbiosis with become ever more familiar, and it
> develops the power to make them settle on its armour and claws — using 'photosynthesis' to repair tools."
> "The trigger cooldown drops as the skill level (growth stage) rises."
> "As the name suggests, it only works by day, outdoors, under clear skies."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:passive` |
| Continuous mana cost | `1` reserved (`continuous_mana_cost`, `type` = `reserved`) |
| Cooldown | `800 / 600 / 400` ticks per level (40 / 30 / 20 seconds; linear `base` 800, −200 per level) |
| Usage restriction | **Blocked when the sky is not visible**; **blocked while raining / snowing**; **blocked at night (ticks 12000~23999)** |
| Level | max level 3, upgraded by `dragonsurvival:dragon_growth`: growth `30 / 40 / 50` → level 1 / 2 / 3 (fallback `10` beyond) |

**Effect**: a single action — settles one `additional_abilities:durability` (durability setting) on
**yourself** (`target_type` = `dragonsurvival:self`, `targeting_mode` = `allies_and_self`):

- damageable items in the **equipment slots and claw slots** (`slots` = `["equipment", "claws"]`) gain
  **+1% of max durability** (`operation` = `add`, `unit` = `percent`, `percent_base` = `max_durability`).

**Usage notes**: an **offline auto-repair** passive — a purely flavour-driven "photosynthesis" way to keep
your gear in shape. Its **three conditions must all hold**: you must be **exposed to the sky**, it must
**not be raining or snowing**, and it must be **daytime** (in-game ticks 0~11999, i.e. sunrise to sunset) —
which means it fails **underground, underwater, deep in a forest, at night and in the rain**. A `reserved`
continuous cost means it **permanently holds 1 point of your mana cap**; it will not drain you, but your
total mana is always 1 lower. Since `percent_base` is **max durability**, the beefier the item (diamond
pickaxe, netherite armour) the more it repairs; wear faster than 1.25% per second (the 400-tick cooldown at
level 3) outpaces it, but normal use is comfortably covered. **Note**: the durability effect resolves
**instantly**, so the amount repaired is a single fixed value — there is no continuous "repairing" process.

---

## Explosion Arrow — `explosion_arrow`

> "Shoot three branches of Explosion Arrow."
> "Unlock it with the advancement: Return to Sender."
> "The countdown of explosion will start instantly when shoot completion."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:simple`, `cast_time` 20 ticks, cooldown 300 ticks, initial mana cost 5 |
| Can move while casting | Yes |
| Unlock | upgrade type `condition_based`, condition = owning the `nether/return_to_sender` advancement ("Return to Sender") |

**Effect**: fires **3 Explosion Arrows** at once along your line of sight (speed 2, spread 4°).
On hit the arrow detonates; the damage / radius / sound of the explosion are driven by Wing Kirin's data
(`wing_kirin:dragonsurvival/projectile_data/explosion_arrow*` and the matching functions).

**Usage notes**: a long cooldown (15 seconds) and a high mana cost, so treat it as one burst.
Unlocking requires completing "Return to Sender" advancement.

---

## Entity Marker — `entity_marker`

> "Simple ability: marker any entity with your mouse right click just."
> "Unlock it with the advancement: Glow and Behold!."

| Item | Value |
|---|---|
| Activation | `dragonsurvival:passive` + trigger `dragonsurvival:on_key_pressed`, bound to the **left mouse button** |
| Level | max level 2, upgraded with `experience_points` (`50 / 100 / 200`, fallback `100`) |
| Unlock | must **already be a Wing Kirin**, and must have earned the `husbandry/make_a_sign_glow` advancement ("Glow and Behold!") |
| Targeting | range 200, `looking_at` target, `all_except_self` |

**Effect**: pressing the button marks **the entity you are looking directly at**, making it glow
(glow id `wing_kirin:looking_at_marker`, colour `#d80835` red) for 400 × tier ticks (20 / 40 seconds).

**Usage notes**: this is a *marker* — it reveals an enemy hiding in grass, darkness or behind geometry
(combined with x-ray style effects). **It marks a single target** (whatever your crosshair hits), not an area
scan. It can be disabled manually from the ability screen.

> Note: the shipped `en_us` description says "right click", but the ability JSON binds the effect to
> `key.mouse.left`. The JSON is authoritative — it is the **left** mouse button.

---

## Granting abilities to players

Abilities are handed out through the species tags in the table above. For testing or manual grants, use
Dragon Survival's own commands:

```
/dragon-ability add <target> <ability id>        # grant
/dragon-ability remove <target> <ability id>     # remove
/dragon-ability refresh [<target>]               # refresh ability data
```

Species tag file location:

```
data/dragonsurvival/tags/dragonsurvival/dragon_ability/<species>.json
```

This mod **appends** to those tags (`"replace": false`), so it never overwrites the Vanilla species' ability
lists.
