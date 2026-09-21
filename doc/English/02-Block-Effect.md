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

---

## `additional_abilities:extinguish` — Extinguish

**In one sentence**: snuffs out whatever is **burning** on the selected block — the mirror of Dragon Survival's
built-in `dragonsurvival:fire`.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `extinguish_probability` | level value | ❌ | `1.0` | **Applies to fire blocks only** (`fire` / `soul_fire`); campfires and candles are always put out |

### What it puts out

The order matches vanilla's splash water bottle (`ThrownPotion#dowseFire`) exactly, and the three cases are
mutually exclusive (the first hit returns):

| # | Target | Behaviour |
|---|---|---|
| 1 | Fire: `minecraft:fire` / `minecraft:soul_fire` (the `#minecraft:fire` tag) | Removed on the `extinguish_probability` roll (**no drops**) |
| 2 | Lit candle / candle cake | `lit=false`, smoke particles and the `CANDLE_EXTINGUISH` sound |
| 3 | Lit campfire / soul campfire | `lit=false`, cooking stopped (`CampfireBlockEntity#dowse`) and the `FIRE_EXTINGUISH` sound |

### How it mirrors `dragonsurvival:fire`

| `dragonsurvival:fire` branch | `extinguish` counterpart |
|---|---|
| TNT → ignite | **none** — an ignited TNT cannot be called off |
| Unlit campfire → light it | Lit campfire → put it out |
| `snowy` block → clear the snow | **none** — "adding snow" has nothing to do with extinguishing |
| Air + probability → place fire | Fire block + probability → remove it |
| (none) | Candle / candle cake → extinguish **(added by this effect)** |

The structure mirrors as well: **only the "place fire / put out fire" branch is gated by probability**, the
special branches are not.

### Example

```json
"block_effect": [
  {
    "effect_type": "additional_abilities:extinguish",
    "extinguish_probability": 1.0
  }
]
```

### Things to know

- **Vanilla has no "extinguish" API.** MC 1.21.1 offers no method that puts out a fire block
  (`FireBlock` / `BaseFireBlock` have no `extinguish`), so this effect copies vanilla's splash water bottle
  handling — sound, particles and the `BLOCK_CHANGE` game event included.
- Fire is removed with `destroyBlock(pos, false, dragon)` and **drops nothing** (fire has no loot table).
- **`direction` takes no part in any check**, so it is immune to the "`direction` is usually null" caveat.
- The sidebar only appends "with a x% chance" when the probability is **below 100%**.
- Division of labour with the built-ins: `dragonsurvival:block_break` can only break fire and is silent;
  `dragonsurvival:conversion` can only map one block state to another (campfire → unlit campfire). To
  "put out every fire in an area", use this effect — it covers fire, campfires and candles at once.
