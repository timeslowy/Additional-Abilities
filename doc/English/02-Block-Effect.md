# Block Effects (`block_effect`)

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68`
> Location: `actions[].target_selection.applied_effects.block_effect[]`

A block effect lives inside the `applied_effects.block_effect[]` array. Each element selects its type with
`effect_type`, and that type's own fields sit **next to** `effect_type`:

```jsonc
"applied_effects": {
  "block_effect": [
    { "effect_type": "additional_abilities:block_quake", /* ← fields go here */ }
  ]
}
```

---

## `additional_abilities:block_quake` — Block Quake

**In one sentence**: makes the selected blocks hop into the air like a ground slam, then settle back down.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `amplifier` | level value | ❌ | `0.5` | Height multiplier, see the conversion below |
| `probability` | level value | ❌ | `1.0` | Chance to take effect, **rolled independently for every block** |
| `valid_blocks` | block predicate | ❌ | match everything | Same format as Dragon Survival's other block effects |
| `sound` | sound id | ❌ | silent | Played when the blocks hop. Write the registry id string directly |

### Height conversion

```
hop height (blocks) = min(3.0, 0.5 × amplifier)
```

| `amplifier` | 0.5 | 1.0 | 2.0 | 6.0 and above |
|---|---|---|---|---|
| Height | 0.25 blocks | 0.5 blocks | 1.0 blocks | 3.0 blocks (capped) |

The hop duration grows with the square root of the height (higher hops take longer) and is automatically
clamped to **6 – 24 ticks**, so there is nothing to tune by hand.

### Example

```json
"block_effect": [
  {
    "effect_type": "additional_abilities:block_quake",
    "amplifier": { "type": "minecraft:linear", "base": 0.5, "per_level_above_first": 0.25 },
    "probability": 0.35,
    "valid_blocks": { "type": "minecraft:matching_block_tag", "tag": "minecraft:dirt" },
    "sound": "minecraft:item.mace.smash_ground"
  }
]
```

### Things to know

- **Purely visual, it does not modify the world.** The hopping blocks are client-side dummies; they
  **do not collide, are not saved, and do not drop**, and there are no neighbour updates or lighting
  changes. Safe to use over large areas.
- **There is a filter chain**, and anything filtered out simply will not move (the order is also the cost order):
  1. the probability roll;
  2. the `valid_blocks` predicate;
  3. air is excluded;
  4. **only full-collision blocks are kept** — slabs, stairs, torches, grass and fluids look wrong when
     lifted a full block, so they are filtered out too;
  5. **surface check** — anything with an occluding or fluid block above it is skipped.
- Points 4 and 5 mean **blocks under water or buried underground never hop**. That is deliberate
  visual polish, not a bug.
- `sound` uses the same format as `activation.sound` in Dragon Survival ability JSON: just write the sound
  registry id. Custom sounds work too, as long as they are registered under `minecraft:sound_event`.
  Playback details:
  - **It fires once per ability action**, with the sound source placed at the **geometric centre** of the
    batch — a large quake lifts dozens of blocks at once, and playing per block would stack dozens of
    sources into an audible blast rather than a single "slam";
  - Volume and pitch are the Vanilla defaults of `1.0` (audible radius roughly 16 blocks) and
    **do not scale with `amplifier`**;
  - It is broadcast server-side, so **the caster hears it too**.
- **Throttling is built in**, so a high `trigger_rate` will not turn the terrain into a seizure:
  - **Position cooldown**: the same coordinate only quakes once every 10 ticks;
  - Triggers within the same tick are merged into as few network packets as possible and only sent to
    nearby players.
- This effect only moves the **shape** of the blocks and **deals no damage**. Add a `dragonsurvival:damage`
  style entity effect for that, and remember to use a **separate action** — entity effects and block
  effects cannot live in the same `applied_effects`.
