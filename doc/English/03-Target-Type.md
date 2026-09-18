# Target Types (`target_type`)

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68`
> Location: `actions[].target_selection.target_type`

A target type decides **who the ability selects**. It is written inside `actions[].target_selection`, with
`target_type` sitting at the **same level** as `applied_effects`:

```jsonc
"target_selection": {
  "target_type": "additional_abilities:xxx",
  "applied_effects": { /* what to do with the selection */ },
  /* ← this target type's own fields go here too */
}
```

---

## Target types added by this mod

| `target_type` | In one sentence | Built-in counterpart |
|---|---|---|
| `additional_abilities:anti_dragon_breath` | A dragon breath cone, but extending **behind** the caster | `dragonsurvival:dragon_breath` |
| `additional_abilities:annulus` | A ring — a disc with the inner circle hollowed out | `dragonsurvival:disc` |

---

## 1. `additional_abilities:anti_dragon_breath` — Reverse Dragon Breath Cone

**In one sentence**: exactly the same as Dragon Survival's built-in `dragonsurvival:dragon_breath`, except
the cone extends **behind** the caster.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `applied_effects` | object | ✅ | — | Common to every Dragon Survival target type: write `entity_effect[]` or `block_effect[]`, plus `target_conditions` / `targeting_mode` / `is_harmful` |
| `range_multiplier` | level value | ✅ | — | **Multiplied** with the caster's `dragonsurvival:dragon_breath_range` attribute to give the actual range |

The fields are identical, word for word, to `dragonsurvival:dragon_breath`. **Swap the `target_type` and any
breath ability becomes a "spray backwards" ability.**

### Example

```json
{
  "target_selection": {
    "target_type": "additional_abilities:anti_dragon_breath",
    "range_multiplier": 1.0,
    "applied_effects": {
      "entity_effect": [
        {
          "effect_type": "dragonsurvival:potion",
          "potion": {
            "amplifier": 0.0,
            "duration": 60.0,
            "effects": "minecraft:glowing",
            "probability": 1.0
          }
        }
      ],
      "targeting_mode": "non_allies"
    }
  },
  "trigger_rate": 10.0
}
```

### What the selection box looks like

Taking "eye height 1.62, scale 1, range 4, facing +X horizontally" as an example:

| Target type | X range | Y range | Z range |
|---|---|---|---|
| `dragonsurvival:dragon_breath` | -1 ~ 4 | 0.81 ~ 2.43 | -1 ~ 1 |
| `additional_abilities:anti_dragon_breath` | **-4 ~ 1** | 0.81 ~ 2.43 | -1 ~ 1 |

Backwards and sideways are reversed, and **the box does not shift downwards** — the body-thickness portion
stays on the body's side.

### Things to know

- **`target_type` is the only switch.** The rest of the ability (`sound`, `animations`, `trigger_rate`,
  `applied_effects`) needs no changes at all to become "reversed".
- **The block branch's facing argument is unchanged from Dragon Survival's breath**: the `direction` handed
  to block effects is "the nearest axis from the eye's world position" and has nothing to do with where you
  are looking. Keep that in mind if your block effect depends on it.
- **Who can be hit is decided by `applied_effects.targeting_mode`**, not by the target type itself.
  Use `non_allies` or `enemies` to hit only hostiles, or `allies_and_self` to buff your own side.
- The sidebar description is already localised in this mod:
  - block branch → `Targets a %s block cone behind you`
  - entity branch → `Targets %s in a %s block cone behind you`

> A runnable reference lives in
> `data/additional_abilities/dragonsurvival/dragon_ability/test_anti_dragon_breath.json`
> (affected entities glow and white particles fill the box, so the reversed volume is visible to the eye).
> That test ability is not attached to any species — grant it with `/dragon-ability add`.

---

## 2. `additional_abilities:annulus` — Ring

**In one sentence**: the same idea as Dragon Survival's built-in `dragonsurvival:disc`, but with the
**inner circle hollowed out** — only the band between the inner and outer radius is selected.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `applied_effects` | object | ✅ | — | Common to every Dragon Survival target type: write `entity_effect[]` or `block_effect[]`, plus `target_conditions` / `targeting_mode` / `is_harmful` |
| `inner_radius` | level value | ✅ | — | Inner radius in blocks. **Nothing inside this radius is selected** |
| `width` | level value | ✅ | — | Ring width in blocks, **added radially on top of the inner radius** |
| `height` | level value | ❌ | `1` | Thickness in blocks |
| `height_starts_below` | boolean | ❌ | `false` | `false` → Y range `[y, y + height]`; `true` → `[y - 1, y + height - 1]` |

`inner_radius`, `width` and `height` all accept every `LevelBasedValue` form
(`linear` / `lookup` / constant …), for example
`"width": { "type": "minecraft:linear", "base": 2.0, "per_level_above_first": 0.5 }`.

### Area of effect

```
origin = the caster's foot position (same as disc)
r_in   = inner_radius
r_out  = inner_radius + width          ← width is added radially, it is not the outer radius
Bounding box (XZ) = origin ± r_out
Y range           = height_starts_below ? [y - 1, y + height - 1] : [y, y + height]
Hit condition: horizontal distance d satisfies  r_in ≤ d ≤ r_out  (inclusive)
```

### Example

```json
{
  "target_selection": {
    "target_type": "additional_abilities:annulus",
    "inner_radius": 5,
    "width": 1.0,
    "height": 3.0,
    "height_starts_below": true,
    "applied_effects": {
      "entity_effect": [
        {
          "effect_type": "dragonsurvival:potion",
          "potion": { "amplifier": 0.0, "duration": 60.0, "effects": "minecraft:glowing", "probability": 1.0 }
        }
      ],
      "targeting_mode": "all"
    }
  },
  "trigger_rate": 10.0
}
```

The config above selects a band from radius 5 to 6, three blocks thick starting one block below the feet.

### Things to know

- **It selects a ring, not a disc.** Everything inside `inner_radius` is untouched — that is precisely the
  value it adds over `disc` (for example a "halo" that only hits the surroundings while leaving a safe spot
  under your own feet).
- **Only the horizontal distance is compared**; the vertical extent is entirely governed by `height` and
  `height_starts_below`. It is therefore a **cylindrical shell** — a ring times a thickness — not a
  three-dimensional spherical shell. That is also why `height` can exist as a separate parameter.
- **The iteration count does not shrink just because the inner circle is empty.** After the bounding-box
  pre-filter, `(2 × r_out + 1)² × height` block positions are still visited. When `inner_radius` is large,
  raise `trigger_rate` accordingly (5 or higher is advisable) or the per-tick cost will be noticeable.
- **Boundary testing uses the centre-point method**: blocks use their cell centre
  `(x + 0.5, z + 0.5)`, entities use `position()` (foot centre). The result is predictable and reproducible,
  and no block ever looks like it was "eaten" at the edge.
- **The sidebar description keeps two decimals** (Dragon Survival's `disc` truncates with `(int)`), so a
  fractional radius is not displayed as a whole number. The range shown is the **outer radius**
  (the far edge of the band).
- **The block branch passes `direction` as `null`** — consistent with Dragon Survival's `area` / `disc`
  (only `looking_at` passes a real hit-face orientation). Block effects that depend on it need their own
  fallback.
- **F3+B draws two boxes**: cyan for the outer bound, dark cyan for the inner (hollowed) bound.
  This mod renders that pair itself — Dragon Survival's `ClientDragonRenderer#renderAbilityHitbox` is a
  hard-coded branch chain with no extension point.
- Who can be hit is again decided by `applied_effects.targeting_mode`, not by the target type itself.

> A runnable reference lives in
> `data/additional_abilities/dragonsurvival/dragon_ability/test_annulus.json`
> (inner radius 5 / width 1 / thickness 3 starting one block below the feet; affected entities glow and are
> wrapped in dragon breath particles, while the block side spawns end-rod particles and sets fire).
> That test ability is not attached to any species — grant it with `/dragon-ability add`.
