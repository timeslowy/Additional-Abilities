# Trigger Types (`trigger_type`)

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68`
> Location: `activation.trigger.trigger_type` — **only `activation_type: dragonsurvival:passive` has a `trigger`**

A passive ability uses `trigger` to decide **when** it runs. `trigger_type` sits at the **same level** as
the trigger's own fields (such as `condition`):

```jsonc
"activation": {
  "activation_type": "dragonsurvival:passive",
  "trigger": {
    "trigger_type": "additional_abilities:xxx",
    /* ← this trigger type's own fields go here too */
  }
}
```

> - The whole `trigger` object may be omitted — it then defaults to Dragon Survival's built-in
>   `dragonsurvival:constant` (always on, runs every tick).
> - **Event-driven passives do not take part in the per-tick update**; they run only on the frame the
>   trigger condition holds.
> - This is a **two-level dispatch**: `activation_type` selects `passive`, whose codec then dispatches on
>   `trigger_type`.

---

## Trigger types added by this mod

| `trigger_type` | In one sentence | Built-in counterpart |
|---|---|---|
| `additional_abilities:on_block_placed` | Fires when a block is **placed** | `dragonsurvival:on_block_break` (fires when a block is **broken**) |
| `additional_abilities:on_item_consumed` | Fires when an item is **consumed** | Vanilla advancement criterion `minecraft:consume_item` (identical field structure) |
| `additional_abilities:on_ability_cast` | Fires **after an active ability's cast settles** (scope it with `abilities`) | No built-in counterpart (DS has no cast event; this mod wires it through a Mixin) |

---

## 1. `additional_abilities:on_block_placed` — On Block Placed

**In one sentence**: the **mirrored counterpart** of Dragon Survival's built-in
`dragonsurvival:on_block_break` — the structure and fields are identical, only the event changes from
"block broken" to "block placed", so it uses the **same block loot context**.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `condition` | loot condition | ❌ | none (always true) | Evaluated in the **block loot context**, see below |

The fields are identical, word for word, to `dragonsurvival:on_block_break`. **Swap the `trigger_type`
and any "when a block is broken" passive becomes "when a block is placed".**

### Example

```json
{
  "activation": {
    "activation_type": "dragonsurvival:passive",
    "trigger": {
      "trigger_type": "additional_abilities:on_block_placed",
      "condition": {
        "condition": "minecraft:location_check",
        "predicate": {
          "block": {
            "blocks": "#minecraft:dirt"
          }
        }
      }
    }
  }
}
```

### When it fires

- Once, when a player **right-clicks with an item in hand to place a block**.
- One interaction that places **several** blocks (bed / door / tall plants) still counts as **one** —
  the position and block state are taken from the **first** block (`snapshot[0]`).
- Only **players in dragon form** trigger it (same as `on_block_break`), and it triggers the
  **placing player's own** abilities, not everyone's nearby.
- Each firing calls `ability.tick(player)` once: the actions resolve for **a single frame**. For effects
  that must persist (attribute modifiers, status effects) use a `constant` passive instead.

### Coverage (important)

| How the block got placed | Fires? |
|---|---|
| Player right-clicks to place a block / item | ✅ |
| Buckets and bottles emptying fluid | ❌ |
| Dispensers and shulker box dispensers | ❌ |
| Falling sand / gravel landing | ❌ |
| Endermen placing blocks | ❌ |
| `/setblock`, `/fill` | ❌ |
| Structure blocks, structure and world generation | ❌ |
| Saplings growing into trees, plant growth | ❌ |
| Fluid spreading (water creating new blocks, etc.) | ❌ |

The reason: this trigger listens to NeoForge's `BlockEvent.EntityPlaceEvent`. That event is only posted
when a **player's `ItemStack#useOn` completes the placement flow**; none of the other sources above go
through that path (bucket items do not even capture a BlockSnapshot, so no event is produced at all).

> So its scope is "**a player placed a block**", not "a block appeared somewhere in the world".
> Covering the latter would require separate events such as `FluidPlaceBlockEvent`; this mod does not
> cover those.

### Context and predicates

`condition` is evaluated in the **block loot context**, with these parameters available:

| Parameter | Meaning |
|---|---|
| `this_entity` | **The dragon player who placed the block** (use `"entity": "this"` to test the player) |
| `origin` | Centre of the block |
| `block_state` | **The block that was placed** (its state after placement, including facing / waterlogged) |
| `block_entity` | The block entity at that position (optional, absent if there is none) |

> ⚠️ **`condition` holds a vanilla *loot condition* (the `minecraft:loot_condition_type` registry), not a
> block predicate** — this is the easiest thing to get wrong here:
>
> - ✅ Test by **tag**: `minecraft:location_check` → `predicate.block.blocks: "#minecraft:dirt"`
>   (`blocks` is a `HolderSet`, accepting `"#namespace:tag"` or a list of block ids; note that it reads
>   the **live block** at `origin` rather than the context's `block_state`)
> - ✅ Test a **single block**: `minecraft:block_state_property` → `block: "minecraft:dirt"`
>   (reads the context's `block_state`; **one block id only, no tags**, plus optional `properties`)
> - ❌ `{"condition": "minecraft:matching_block_tag", "tag": "..."}` — `matching_block_tag` belongs to the
>   **`block_predicate_type`** registry (it can only be nested inside a `BlockPredicate`). Writing it as a
>   `condition` reports `Unknown registry key in ResourceKey[…minecraft:loot_condition_type]` and
>   **prevents the world from loading at all**.
> - Other usable vanilla conditions: `minecraft:all_of` / `any_of` / `inverted` / `random_chance` /
>   `weather_check` / `time_check` / `value_check` / `reference` and friends.

### Usage notes

- **It cancels nothing.** The built-in `on_block_break` cancels the break event when the ability changed
  the block; the placed version **deliberately does not** replicate that — the place event is posted
  *after* the block is already in the world, so cancelling would mean "roll the placement back and refund
  the item", which is a completely different meaning. Treat it as a pure **observation** trigger.
- **No per-tick involvement**: like every event-driven passive, it runs only on the placing frame.
- **Use `cooldown` to throttle.** Building places blocks rapidly, and each placement walks that player's
  entire ability list doing an `instanceof` plus predicate test (Dragon Survival has no cache yet), so
  `cooldown` is the most direct throttle.
- **`trigger_point` must stay `default`** — a hard requirement for passive abilities (including
  event-driven ones) in Dragon Survival; any other value throws `IllegalStateException` during data load.
- **An extra line appears in the sidebar**: `■ Trigger: On Block Place`
  (translation key `trigger_type.additional_abilities.on_block_placed`).

### Test ability

| Ability id | Configuration | Purpose |
|---|---|---|
| `additional_abilities:test_on_block_placed` | This trigger + `condition = #minecraft:dirt` | Placing a dirt-family block (dirt / grass block / coarse dirt / mycelium, …) makes you glow with end-rod particles; placing stone etc. does not fire |

It is not attached to any species; grant it with `/dragon-ability add <target> <ability>`.

> That test ability doubles as a copy-paste template for tag predicates: delete the `condition` and it
> fires on placing anything.

---

## 2. `additional_abilities:on_item_consumed` — On Item Consumed

**In one sentence**: fires once when the player **eats or drinks a matching item**. The field structure
**mirrors the vanilla advancement criterion `minecraft:consume_item`** (an item predicate,
`ItemPredicate`), but the driver is the passive ability system, so it can carry any `actions`.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `item` | `ItemPredicate` | ❌ | none (always true) | The **item predicate** — identical syntax to vanilla, see below |

Sub-fields of `ItemPredicate`:

| Sub-field | Type | Notes |
|---|---|---|
| `items` | `HolderSet<Item>` | A list of item ids, or `"#namespace:tag"` (e.g. `"#minecraft:meat"`) |
| `count` | integer bounds | A bare number `5` means **exactly** 5; `{"min": 1, "max": 3}` gives a range |
| `components` | data component predicate | Matches components exactly, e.g. `{"minecraft:damage": 0}` |
| `predicates` | `ItemSubPredicate` | Matches sub-predicates (`minecraft:damage` / `minecraft:enchantments` / `minecraft:potion_contents`, …) |

### Example

```json
{
  "activation": {
    "activation_type": "dragonsurvival:passive",
    "cooldown": 100.0,
    "trigger": {
      "trigger_type": "additional_abilities:on_item_consumed",
      "item": { "items": "#minecraft:meat" }
    }
  }
}
```

### When it fires

- Once, when a player **uses an item all the way to the end of its use duration** — and only
  **players in dragon form** trigger it.
- It triggers the **consumer's own** abilities, not those of players nearby.
- Each firing calls `ability.tick(player)` once: the actions resolve for **a single frame**. For effects
  that must persist (attribute modifiers, status effects) use a `constant` passive instead.

### Coverage (important: how it differs from vanilla `consume_item`)

The vanilla `minecraft:consume_item` criterion **has no single hook** — in 1.21.1 it is driven by **five
hardcoded call sites**: `Player#eat` (every edible item), `PotionItem`, `HoneyBottleItem`,
`MilkBucketItem` and `OminousBottleItem`. This trigger listens to NeoForge's
`LivingEntityUseItemEvent.Finish` instead — all five of those sites live **inside
`LivingEntity#completeUsingItem`**, so one event covers them all, with **no Mixin required**.

| Scenario | Vanilla `consume_item` | This trigger |
|---|---|---|
| Food (bread / golden apple / cooked meat / suspicious stew / chorus fruit, …) | ✅ | ✅ |
| Potions | ✅ | ✅ |
| Honey bottle | ✅ | ✅ |
| Milk bucket | ✅ | ✅ |
| Ominous bottle | ✅ | ✅ |
| Goat horn (140-tick use duration elapses) | ❌ | ⚠️ fires |
| Spyglass (1200-tick use duration elapses) | ❌ | ⚠️ fires |
| Brush (200-tick use duration) | ❌ | ⚠️ may fire |
| Cake / candle cake (block interaction, goes through `FoodData#eat`) | ❌ | ❌ |
| Another mod calling `Player#eat` directly | ✅ | ❌ |

Everything extra is a **non-consumable**, so a normal `item` predicate filters it out for free (you are
not going to write a consumption ability for a goat horn); the misses only affect third-party mods that
bypass `completeUsingItem`. Matching vanilla exactly would require Mixin-ing all five sites, which this
mod deliberately does not do.

### Two gotchas

1. **`count` is evaluated on the pre-consumption count.** The vanilla trigger point runs before the
   decrement, and this trigger receives the "stack before use" copy as well, so `"count": 1` does **not**
   match a stack of 64 bread (identical to vanilla behaviour).
2. **The item matched is the one before consumption.** Post-consumption swaps such as honey bottle →
   glass bottle or milk bucket → empty bucket do not affect the match.

### Usage notes

- **Use `cooldown` to throttle.** Like every event-driven passive, it only runs on the triggering frame.
- **`trigger_point` must stay `default`** — a hard requirement for passive abilities (including
  event-driven ones) in Dragon Survival.
- **No same-tick deduplication and no loop guard** (same as `on_block_break` and `on_block_placed`);
  if an ability's effect can cause another consumption, add a `cooldown` yourself.
- **An extra line appears in the sidebar**: `■ Trigger: On Item Consumed`
  (translation key `trigger_type.additional_abilities.on_item_consumed`).

### Test ability

| Ability id | Configuration | Purpose |
|---|---|---|
| `additional_abilities:test_on_item_consumed` | This trigger + `item.items = "#minecraft:meat"` | Eating raw or cooked beef, chicken, mutton, porkchop or rabbit — or rotten flesh — makes you glow with end-rod particles; eating bread and other non-meat food does not fire |

It is not attached to any species; grant it with `/dragon-ability add <target> <ability>`.

> That test ability doubles as a copy-paste template for item predicates: delete the whole `item` object
> and it fires on consuming anything.

---

## 3. `additional_abilities:on_ability_cast` — On Ability Cast

**In one sentence**: fires once **after an active ability's cast settles** — `abilities` decides which
abilities count, and omitting it means **any active ability**.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `abilities` | `HolderSet<DragonAbility>` | ❌ | none (**any active ability**) | A **single ability id**, a **list of ids**, or `"#namespace:tag"`; all three are equivalent |

Ways to write it (all equivalent):

```jsonc
"abilities": "additional_abilities:explosion_arrow"          // a single ability
"abilities": ["additional_abilities:explosion_arrow"]        // one-element list (same thing)
"abilities": ["ns:a", "ns:b"]                                // several
"abilities": "#dragonsurvival:wing_kirin"                    // a tag (see below)
```

> A single element and a one-element list are equivalent because the codec is built with
> `disallowInline = false`, i.e. `Codec.either(element list, single element)` (1.21.1
> `HolderSetCodec#homogenousList`).

> Ability tags live in `data/<namespace>/tags/dragonsurvival/dragon_ability/<name>.json` and are
> referenced **without** the `dragon_ability/` prefix — this mod's existing `#dragonsurvival:wing_kirin`
> and `#dragonsurvival:cave_dragon` follow exactly that layout.

### Example

```json
{
  "activation": {
    "activation_type": "dragonsurvival:passive",
    "cooldown": 20.0,
    "trigger": {
      "trigger_type": "additional_abilities:on_ability_cast",
      "abilities": ["additional_abilities:explosion_arrow"]
    }
  }
}
```

### When it fires

"Settles" means the moment **Dragon Survival judged that the cast's effects have actually been
applied** (`isApplyingEffects()`) — not the moment the key was pressed.

| Outcome | Fires |
|---|---|
| `simple` finished casting, actions executed | ✅ |
| `channeled` released mid-channel, or wrapped up at `max_duration` | ✅ (once per cast) |
| This mod's charged family (`charged` / `optional_charged`) released at a tier | ✅ |
| This mod's charged family released as a **cancel** (scrolled to tier 0) | ❌ a cancel is not a use |
| Released before `cast_time` (the use bar never finished) | ❌ effects never applied |
| Interrupted by casting another ability | ❌ goes through an internal `release`, not a settle |
| **A passive being triggered** (constant / event-driven / cooldown-based) | ❌ passives are never "used", see below |

### How it is wired (unlike the other triggers)

Dragon Survival has **no "ability cast" event** — both the start and the end of a cast live inside
`MagicData` methods: not on the event bus, and not a payload this mod could register a handler for.
This is therefore the **only trigger in this mod that is not wired to the event bus**: `mixins/MagicDataMixin`
injects into the `withCooldown == true` branch of `MagicData#stopCasting(Player, DragonAbilityInstance,
boolean)` — that very parameter is DS's own verdict on "did this cast actually go off", and **a cancel
explicitly passes `false`**.

### Loop safety: listing the ability itself still cannot loop

- **A passive ability can never be "used"**: DS only puts **non-passive** abilities into the ability
  hotbar, and casting can only pull an instance from that hotbar; on top of that this trigger skips
  passives outright. So even if you put the ability's **own id** into `abilities`, it can never become
  "the used ability" — which means **no `exclude_this`-style field is needed**.
- Consistent with `on_block_break` / `on_block_placed` / `on_item_consumed`, there is **no same-tick
  deduplication**: the `triggered` flag is reset every tick, and in the "one cast on two consecutive
  ticks" ordering it would actually *drop* a trigger. Use `cooldown` to throttle instead.

### Usage notes

- **Use `cooldown` to throttle.** Like every event-driven passive, it only runs on the triggering frame.
- **`trigger_point` must stay `default`** — a hard requirement for passive abilities (including
  event-driven ones) in Dragon Survival.
- **An extra line appears in the sidebar**: `■ Trigger: On Ability Cast: <abilities>` (a tag name is
  shown for tags; omitted shows `Any Ability`). Translation keys
  `trigger_type.additional_abilities.on_ability_cast` and `…on_ability_cast.any`.

### Test ability

| Ability id | Configuration | Purpose |
|---|---|---|
| `additional_abilities:test_on_ability_cast` | This trigger + `abilities = [test_screen_vision, test_charged]` + `cooldown = 20` | Casting `test_screen_vision` (`simple`, settles after a 30-tick cast) or `test_charged` (charged family: auto-released when fully charged, or released early at the reached tier) makes you glow with end-rod particles |

It is not attached to any species; grant it with `/dragon-ability add <target> <ability>` — note that
both `test_on_ability_cast` and the two abilities listed under `abilities` must be granted.

> That test ability doubles as a copy-paste template for ability sets: delete the whole `abilities`
> object and it fires on any active ability; swap in a tag to verify the tag syntax.
