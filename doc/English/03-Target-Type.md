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

## `additional_abilities:anti_dragon_breath` — Reverse Dragon Breath Cone

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
