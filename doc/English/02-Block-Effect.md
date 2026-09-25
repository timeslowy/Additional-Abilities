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

TODO: inject picture here.

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

---

## `additional_abilities:glow` — Block Glow

**In one sentence**: makes the selected blocks **glow** — every player within view distance sees the same spot,
in the same colour, for the same duration.

TODO: inject picture here.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `color` | colour | ✅ | — | Vanilla `TextColor`: one of 16 colour names, or `#RRGGBB` — see below |
| `alpha` | `0.0` – `1.0` | ❌ | `1.0` | Opacity. `0` means invisible and is skipped outright |
| `display_type` | enum | ❌ | `outline` | `outline` (wireframe) / `simple_shader` (the whole block tinted) |
| `duration` | level value | ❌ | `60` (3s) | Glow duration in ticks, clamped to **1 – 1200** |
| `probability` | level value | ❌ | `1.0` | Chance to take effect, **rolled independently for every block** |
| `valid_blocks` | block predicate | ❌ | match everything | Same format as Dragon Survival's other block effects |
| `hide_occluded` | boolean | ❌ | `true` | Whether to drop fully occluded blocks. **`simple_shader` only** |

### How to write the colour

| Form | Examples |
|---|---|
| Colour name (16 of them, all lowercase) | `"aqua"` / `"dark_purple"` / `"light_purple"` / `"gold"` … |
| Hex | `"#3FD9FF"` |

The 16 names are `black` `dark_blue` `dark_green` `dark_aqua` `dark_red` `dark_purple` `gold` `gray`
`dark_gray` `blue` `green` `aqua` `red` `light_purple` `yellow` `white`.

> ⚠️ **Two traps**:
> 1. The `#` branch parses the text as a **hex integer**, **not** as CSS shorthand — `"#FFF"` is `0x000FFF`
>    (a near-black blue), not white. **Always write all six digits.**
> 2. **`color` itself carries no alpha** (an eight-digit `#AARRGGBB` is out of range and errors out).
>    For transparency use the separate `alpha` field.

### The two display types

| `display_type` | Look | Cost per block | Filtering applied |
|---|---|---|---|
| `outline` | A wireframe along the block's edges — as if the block itself were alight | **A fixed 12 edges**, independent of block shape | Distance (64 blocks) + frustum |
| `simple_shader` | The whole block model tinted with the colour | Re-bakes the block model every frame — noticeably more expensive | Distance (32 blocks) + frustum + **occluded / non-full-shaped blocks never even leave the server** |

`outline` **uses the depth test**: whatever is hidden behind another block stays hidden — it reads as "the block
is glowing", **not** as X-ray.

### Example

```json
"block_effect": [
  {
    "effect_type": "additional_abilities:glow",
    "color": "#3FD9FF",
    "alpha": 0.85,
    "display_type": "outline",
    "duration": { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 20.0 },
    "valid_blocks": { "type": "minecraft:matching_block_tag", "tag": "minecraft:ores" }
  }
]
```

### Things to know

- **This is not `dragonsurvival:glow`** (and that is the point of the effect):
  - Dragon Survival's `glow` is an **entity effect** — the state lives on the **player**, and the client only
    outlines **that player's own dragon model**, so the glow follows the player and only they see it;
  - this effect is a **block effect** — the target is a block coordinate, synced from the server to nearby
    players, so the glow is pinned to the world and anyone passing by sees it.
- **How it relates to `dragonsurvival:block_vision`** (the ore vision ability): that system already implements
  `outline` / `simple_shader`, but it is a **client-side local scan** attached to a player and never sent over
  the network. This effect reuses its **rendering** and replaces its **sync and lifetime** semantics.
- **Purely visual, it does not modify the world**: no `setBlock`, no lighting writes, no neighbour updates —
  it **does not affect the actual block light level**.
- **Overlapping works**: different colours / display types are separate entries on the server and are drawn
  separately on the client, so different casts and different players stack naturally instead of overriding each
  other. (Same colour and same type merge into one entry — visually indistinguishable anyway.)
- **The lifetime is self-managed**: the block effect interface has no `remove` (block effects are instantaneous),
  so this effect keeps its own clock — the server re-sends every live entry once every 20 ticks to "keep it
  alive", and the client counts down locally from the remaining duration carried in the packet. A repeatedly
  triggered ability stays lit; once it stops, the glow expires on its own. The trade-off: **if the ability is
  interrupted early, the glow lingers until `duration` runs out.**
- **The occlusion filter serves `simple_shader` only**: it asks whether **all six** neighbouring blocks are
  solid-rendered — a strict definition of "completely occluded", so visible blocks are never dropped by mistake.
  `outline` skips this filter: its cost is independent of visibility, and with the depth test on the GPU hides
  the covered edges for free. Set `hide_occluded` to `false` for an "X-ray ore finding" style effect.
- **Sidebar**: extra notes are appended only when the probability is below 100%, `alpha` is below 1.0, or
  `valid_blocks` is not "match everything".
### Client config (visibility distance)

How far the glow is **drawn** is a client-side setting. It lives in the `[block_glow]` section of
`config/additional_abilities-client.toml`, or you can edit it in-game under "Mods -> Additional Abilities for DS
-> Config". **It takes effect immediately, no restart needed.**

| Option | Default | Notes |
|---|---|---|
| `block_glow.outline_distance` | `64.0` | Visibility distance of the wireframe channel (blocks), range `8 - 64` |
| `block_glow.shader_distance` | `32.0` | Visibility distance of the tint channel (blocks), range `8 - 64`. **Lower this one first if the frame rate suffers** |

Two things to keep in mind:

- **The 64 ceiling is not arbitrary**: the server decides "who receives the glow packets" using a fixed margin,
  and it **cannot read** this client-side config (client configs are not loaded on a dedicated server at all).
  The config ceiling is therefore pinned to the server's broadcast margin (`BlockGlows.VIEW_MARGIN`) —
  **you can only lower it, never raise it**, which structurally rules out the silent truncation where
  "the client wants to draw but the server never sent the packet".
- Lowering it affects **only what you see** — not other players, not the glow's duration, and not any
  server-side behaviour.
