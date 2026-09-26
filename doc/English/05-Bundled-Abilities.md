# Bundled Abilities

> Applies to: Additional Abilities for DS `2.0.0`
> Requirements: Dragon Survival `≥ 2.0.68` · (optional)Wing Kirin `≥ 3.3.0`

> This mod also bundles an **optional datapack** that reworks some Wing Kirin's abilities using this mod's own custom components. It is **off by default**; see
> [07-Bundled-Datapacks.md](07-Bundled-Datapacks.md) for what it changes and how to enable it.

> There is also a set of `test_*` abilities (`test_charged` / `test_charged_hold` / `test_optional_charged` /
> `test_block_quake` / `test_screen_vision` / `test_anti_dragon_breath` / `test_annulus` /
> `test_domain` / `test_on_block_placed` / `test_on_item_consumed`) for development and
> debugging only.
> They are **not attached to any species** and must be granted with a command.
> See the [README](../../README.md) quick-reference table for which custom type each one exercises.  
> ***Tips: Due to requirements of test, test abilities are not all consistent with description partically. Subject to actual.***

---

## Overview

| Ability id | Name | Species | Activation | Max level | Unlock / upgrade |
|---|---|---|---|---|---|
| `sea_dragon:extinguish_breath` | Extinguish Breath | Sea Dragon| `channeled` continuous breath | 4 | Experience levels `0 / 10 / 20 / 40` |
| `cave_dragon:smoke_breath` | Smoke Breath | Cave Dragon, `channeled` continuous breath | 3 | Experience levels `0 / 10 / 20` |
| `cave_dragon:piercing_eye` | Piercing Eye | Cave Dragon | `passive` | 1 (no upgrade) | Active as soon as the ability is present |
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
> "Cannot be used under lava." "(Cannot extinguish candles)"

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
Unlocking requires completing "Return to Sender" in the Overworld (deflecting a ghast fireball).

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
