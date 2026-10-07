# Entity Effects (`entity_effect`)

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68`
> Location: `actions[].target_selection.applied_effects.entity_effect[]`

An entity effect lives inside the `applied_effects.entity_effect[]` array. Each element selects its type
with `effect_type`, and that type's own fields sit **next to** `effect_type`:

```jsonc
"applied_effects": {
  "entity_effect": [
    { "effect_type": "additional_abilities:xxx", /* ← this type's own fields go here */ }
  ],
  "targeting_mode": "non_allies"
}
```

The five `effect_type` values below are added by this mod.

---

## 1. `additional_abilities:damage_reflection` — Damage Reflection

**In one sentence**: when the owner takes damage, a share of it is blasted back at every enemy around them.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `reflection_percentage` | level value | ✅ | — | Fraction reflected, a **decimal between 0 and 1**. Negative values are treated as 0; values above 1 amplify damage, use with care |
| `reflection_range` | level value | ✅ | — | Radius in blocks, floored after evaluation. The effect does nothing when ≤ 0 |
| `use_same_damage_type` | boolean | ❌ | `false` | `false` → use `additional_abilities:counter_shock`; `true` → reuse whatever damage type hit you |

### Example

```json
{
  "actions": [
    {
      "target_selection": {
        "applied_effects": {
          "entity_effect": [
            {
              "effect_type": "additional_abilities:damage_reflection",
              "reflection_percentage": { "type": "minecraft:linear", "base": 0.25, "per_level_above_first": 0.05 },
              "reflection_range": { "type": "minecraft:linear", "base": 8.0, "per_level_above_first": 1.0 },
              "use_same_damage_type": false
            }
          ],
          "targeting_mode": "all"
        },
        "target_type": "dragonsurvival:self"
      }
    }
  ],
  "activation": { "activation_type": "dragonsurvival:passive" }
}
```

### Things to know

- **Players only.** If the ability target is not a player, the whole effect is skipped, so `target_type`
  practically has to be `dragonsurvival:self`.
- **It reflects at "everyone nearby", not at "the one who hit you".** Every hostile mob inside a cube of
  side `reflection_range` centred on the damaged player takes a **full** reflected hit.
  Hostility uses Dragon Survival's `enemies` logic (respecting the PvP setting and team rules).
  If you wanted Vanilla-Thorns behaviour — reflect only onto the attacker — this effect cannot do it,
  so keep the radius modest to avoid collateral damage.
- **The base is the pre-mitigation original damage.** Even if armour or Resistance V reduces the actual
  health loss to 0, the full original damage is still reflected.
- **Reflected damage is never reflected again.** Three guards: the damage source is yourself /
  the damage type is counter shock / a re-entrancy flag while the reflection is being resolved.
  Two players both running this ability will not ping-pong forever.
- **It is not a permanent buff — it has to be refreshed.** Each stored entry only lives for
  **100 ticks (5 seconds)**:
  - **Passive abilities**: the `trigger_rate` interval must be clearly shorter than 100 ticks,
    otherwise the reflection will visibly flicker;
  - **Active abilities**: it is refreshed periodically while casting, and lingers at most 5 seconds after
    you stop.
- **Multiple abilities resolve independently.** Two abilities on the same player each reflect once at their
  own percentage, and the damage stacks.

---

## 2. `additional_abilities:percentaged_damage` — Percentage Damage

**In one sentence**: deal damage as a percentage of the target's health — the "by ratio" variant of Dragon
Survival's built-in `dragonsurvival:damage`.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `damage_type` | damage type | ✅ | — | Same field as `dragonsurvival:damage`. Accepts a registry id string, e.g. `"minecraft:magic"`, `"additional_abilities:counter_shock"` |
| `percentage` | level value | ✅ | — | Health percentage, a **decimal between 0 and 1**; negative values are treated as 0 |
| `calculate_type` | enum | ✅ | — | `max_health` → based on **maximum** health; `current_health` → based on **current** health |
| `scale` | attribute | ❌ | `dragonsurvival:dragon_ability_damage` | Attribute used for the multiplication (defaults to "dragon ability damage") |
| `expression` | string | ❌ | `"amount * scale"` | Expression for the final damage; variables listed below |
| `use_claw` | boolean | ❌ | `false` | Whether to temporarily swap in the dragon claw sword attack (same as `damage`) |

The two variables available inside the expression:

| Variable | Meaning |
|---|---|
| `amount` | Base damage = `percentage` × the target's (max / current) health |
| `scale` | Current value of the attribute named in `scale`, taken from the caster |

### Example

```json
{
  "effect_type": "additional_abilities:percentaged_damage",
  "damage_type": "minecraft:magic",
  "percentage": { "type": "minecraft:linear", "base": 0.05, "per_level_above_first": 0.02 },
  "calculate_type": "max_health",
  "expression": "amount"
}
```

### Things to know

- **Living entities only.** Non-living targets (minecarts, dropped items, armour stands) are skipped.
- Final damage defaults to `percentage` × target health × the caster's `dragon_ability_damage` attribute.
  **If you want pure percentage damage with no attribute scaling, set `expression` to `"amount"`.**
- The percentage is **re-evaluated against the target's health every time it resolves**:
  - `current_health` deals very little to an already wounded target — good for an "execute" flavour;
  - `max_health` is brutal against high-health targets (bosses, iron golems), so mind the balance.
- Target health comes from `getMaxHealth()`, which **includes attribute modifiers** — giving an enemy a
  health buff will also raise the reflected amount.

---

## 3. `additional_abilities:simple_screen_vision` — Simple Screen Vision

**In one sentence**: overlay a camera shake, a screen blur or an edge mask on another player's view.

![简单屏幕视觉screen-vision](../图片Pictures/屏幕效果screen-vision.png)

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `base` | object | ✅ | — | Identity + duration; **mirrors Dragon Survival's "duration instance" effects** (`modifier` / `glow` / `block_vision` …) |
| `base.id` | resource location | ✅ | — | Identifier of this effect, e.g. `additional_abilities:screen_vision_shake` |
| `base.duration` | level value | ❌ | 60 ticks | Duration in **ticks** (1 second = 20). Floored after evaluation; **omitted or negative** falls back to 60 ticks, an explicit `0` does nothing |
| `type` | enum | ✅ | — | `shake` camera shake / `blur` screen blur / `edge_light` edge mask |
| `amplifier` | level value | ❌ | `1.0` | Strength multiplier; for `edge_light` it reads as the **mask opacity** (anything above 1 counts as 1). See the table below |
| `size` | level value | ❌ | `0.15` | **`edge_light` only**: mask edge thickness as a fraction of the screen's **shorter side**, clamped to 0–0.5 |
| `color` | colour | ❌ | `white` | **`edge_light` only**: vanilla colour name or `#RRGGBB` (**no alpha** — use `amplifier` for transparency) |
| `probability` | level value | ❌ | `1.0` | Chance to take effect, **rolled independently on every trigger** (same convention as Dragon Survival's built-in potion effects) |

`base` is the very same `DurationInstanceBase` that Dragon Survival's `modifier` / `damage_modification` effects use;
its full field set is `id` (required), `duration`, `should_remove_automatically`, `early_removal_condition`,
`custom_icon` and `is_hidden`. ⚠️ **This effect only reads `id` and `duration`** — the other four are switches for
Dragon Survival's "store the effect instance in an entity attachment and tick it every tick" machinery, while this
effect's visuals are a one-shot client state that counts down locally and has no instance to tick. Those four fields
are therefore **accepted but carry no runtime meaning**. They are taken as-is to keep the JSON shape identical to
Dragon Survival's and to leave room for a future upgrade.

### Strength reference

| `type` | What `amplifier` means | Suggested range |
|---|---|---|
| `shake` | `1.0` ≈ a maximum camera roll offset of **1.5°** | 0.5 – 5 |
| `blur` | `1.0` = a blur radius of **1 pixel**, capped at **20** (anything higher counts as 20) | 1 – 20 |
| `edge_light` | `1.0` = a fully opaque mask (**`amplifier` reads as the opacity here**) | 0.2 – 1 |

### Example (three visions from a single cast)

```json
"entity_effect": [
  {
    "effect_type": "additional_abilities:simple_screen_vision",
    "base": {
      "id": "additional_abilities:screen_vision_blur",
      "duration": { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 20.0 }
    },
    "type": "blur",
    "amplifier": 5,
    "probability": 1.0
  },
  {
    "effect_type": "additional_abilities:simple_screen_vision",
    "base": {
      "id": "additional_abilities:screen_vision_shake",
      "duration": { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 20.0 }
    },
    "type": "shake",
    "amplifier": 3.0,
    "probability": 1.0
  },
  {
    "effect_type": "additional_abilities:simple_screen_vision",
    "base": {
      "id": "additional_abilities:screen_vision_edge_light",
      "duration": { "type": "minecraft:linear", "base": 60.0, "per_level_above_first": 20.0 }
    },
    "type": "edge_light",
    "amplifier": 0.6,
    "size": 0.25,
    "color": "gold",
    "probability": 1.0
  }
]
```

### Things to know

- **Players only.** Non-player targets (even living ones) are skipped.
- **Zero side effects**: no damage, no movement, no attributes — purely a visual on the receiving end.
- `blur` **blurs the world only, never the GUI** — health bar, hotbar and chat stay perfectly sharp.
- **The visions coexist.** Sending several in one cast does not let one swallow another.
- The `edge_light` mask sits **below the HUD** (health bar, hotbar and chat stay above it) and is drawn after the
  `blur` post-processing chain, so the **mask itself is never blurred** — both can be active at the same time.
- `edge_light`'s `size` is relative to the screen's **shorter side**, so all four edges have the same thickness;
  writing `color: black` turns the "light edge" into a darkening vignette (same renderer, different colour).
- ⚠️ `size` and `color` are `edge_light`-only fields: writing them on a `shake` / `blur` entry
  **neither errors nor does anything** (deliberately no hard validation — a codec exception would take the
  whole data pack down).
- ⚠️ Likewise, `should_remove_automatically` / `early_removal_condition` / `custom_icon` / `is_hidden` inside `base`
  **do nothing here** either (see the field notes above). `custom_icon` and `is_hidden` govern which icon the ability
  info sidebar shows and whether the entry is hidden there — this effect has no sidebar entry, so changing them has no
  visible effect at all.
- ⚠️ Keep `base.id` unique and **never copy an existing `dragonsurvival:` id**: when Dragon Survival performs its
  "caster out of range → remove early" check it compares the ids of every effect in the ability, so a clash could
  wrongly cull a same-named Dragon Survival effect instance. The namespaces are already isolated, so normal
  purpose-based naming (`additional_abilities:screen_vision_shake`) never runs into this; **reusing one id across
  several abilities is fine** (Dragon Survival itself does that, e.g. `good_mana_condition`).
- **Repeated sends are safe** and will neither extend nor intensify the effect indefinitely:
  for the same type, duration takes the larger value and strength takes the larger value.
  Passive abilities refreshing at high frequency are therefore fine.
- There is **server-side throttling**: for the same player and the same vision type, nothing is sent
  within 5 ticks unless **nothing about the parameters changed** (strength, size and colour all identical).
  Any parameter change goes through.
- Presentation details: 5-tick fade-in, 10-tick fade-out, so nothing snaps on or off; the shake phase is
  driven by the local tick count, so it is **frame-rate independent**.
- **To clear it immediately** (debugging): `/additional-abilities simple-screen-vision clear <targets>`, see
  [06-Debug-and-Query-Commands.md](06-Debug-and-Query-Commands.md). Note that a passive ability will simply
  re-send on the next tick, so clearing only affects the current moment.

---

## 4. `additional_abilities:enchantment_bonus` — Enchantment Bonus

**In one sentence**: grants the chosen enchantments to the items the target **holds or wears**,
for as long as the effect lasts (and only while the item stays in hand).

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `enchantment_bonuses` | list | ✅ | — | At least one entry; each carries its own `base`, **shaped exactly like Dragon Survival's `dragonsurvival:harvest_bonus`** |
| `enchantment_bonuses[].base` | object | ✅ | — | Identity + duration, the very same "duration instance" `DurationInstanceBase` that `simple_screen_vision` above uses: `id` (required), `duration`, `should_remove_automatically`, `early_removal_condition`, `custom_icon`, `is_hidden` |
| `enchantment_bonuses[].enchantments` | list | ✅ | — | At least one "enchantment + level" pair |
| `…[].enchantment` | enchantment | ✅ | — | Enchantment id, e.g. `"minecraft:efficiency"`. **Treasure enchantments are allowed too** |
| `…[].level` | level value | ❌ | `1` | Applied level, evaluated against the ability level; clamped at runtime to `[1, the enchantment's own maximum]` |

⚠️ Unlike `simple_screen_vision`, **all six `base` fields carry real meaning here**: this is an actual duration
instance (it counts down, and it is collected when the caster moves out of range or the ability stops),
and `is_hidden` / `custom_icon` genuinely drive the UI — see "Things to know" below.

### Example

One cast granting four enchantments to nearby allies:

```json
{
  "actions": [
    {
      "target_selection": {
        "target_type": "dragonsurvival:area",
        "radius": 6.0,
        "applied_effects": {
          "entity_effect": [
            {
              "effect_type": "additional_abilities:enchantment_bonus",
              "enchantment_bonuses": [
                {
                  "base": {
                    "id": "additional_abilities:ench_bonus_demo",
                    "duration": { "type": "minecraft:linear", "base": 400.0, "per_level_above_first": 200.0 }
                  },
                  "enchantments": [
                    { "enchantment": "minecraft:sharpness", "level": 3 },
                    {
                      "enchantment": "minecraft:efficiency",
                      "level": { "type": "minecraft:linear", "base": 1.0, "per_level_above_first": 1.0 }
                    },
                    { "enchantment": "minecraft:unbreaking", "level": 3 },
                    { "enchantment": "minecraft:protection", "level": 2 }
                  ]
                }
              ]
            }
          ],
          "targeting_mode": "allies_and_self"
        }
      }
    }
  ],
  "activation": {
    "activation_type": "dragonsurvival:simple",
    "cast_time": 20.0,
    "cooldown": 100.0,
    "initial_mana_cost": 3.0
  }
}
```

### Things to know

- **Living entities only.** Minecarts, dropped items and armour stands are skipped.
- **What counts as "held or worn"**: main hand / off hand / helmet / chestplate / leggings / boots, plus
  **Dragon Survival's four claw slots** (sword / pickaxe / axe / shovel) and the main-hand item parked away
  during a tool swap. **An identical item sitting in your backpack does not count** — move the item back into
  your inventory and the enchantment is gone immediately.
- **Why the claw slots work**: Dragon Survival does not teach the enchantment layer about claw slots, it
  **physically swaps** the claw tool into the main hand at the moment of mining or attacking, so
  `efficiency` / `sharpness` / `mending` all fire at their normal moment. Where no swap happens
  (say the mending repair check), this effect scans the claw slots itself and works just the same.
- **It works exactly like a real enchantment** for damage bonuses, protection, mining speed, durability and
  mending, crossbow / trident / fishing, and the enchantment's own **attribute modifiers**
  (`efficiency` is an attribute in 1.21).
- ⚠️ **What you will not get**: the enchantment **glint** (the item does not glow), the vanilla tooltip lines,
  and recognition by the anvil / grindstone / repair recipe / `/enchant`. The first two are trade-offs (this mod
  draws its own "temporary enchantment" tooltip line); the other four are actually a *feature* — those read the
  item's NBT, cannot see a temporary enchantment, and therefore cannot be abused for free grindstone XP.
- **It never touches the item's data**: the effect does **not** write the `enchantments` component, and it does
  **not** leave any "effect truth" on the item either. Resolution happens at the very moment an enchantment is
  queried — the mod looks up *who is holding this item right now* and reads that holder's duration instances.
  All that remains on the item is a numeric pulse carrying no readable information, used solely to make vanilla
  re-collect attribute modifiers; it is cleared when the effect ends.
- **Only the current holder counts**: an item **stops benefiting the instant it leaves a held slot** — hand it to
  a teammate, drop it on the ground, or stash it in a chest and the bonus is gone. Even if that numeric pulse is
  left behind (say the item was dropped into a chest right as the effect ended), it takes part in neither
  resolution nor the tooltip — it **cannot mislead, and can never become a permanently active fake enchantment**.
- **Item eligibility** uses the same test as the anvil (`ItemStack#supportsEnchantment`): an item that does not
  support the enchantment simply does nothing while held; enchanted books are excluded.
- **Levels**: a **fixed** level above the enchantment's maximum **fails at data pack load**; a level that scales
  with the ability level is clamped to the maximum at runtime.
- **UI / commands**: the effect shows up in the **ability effect list and HUD** ,
  `/dragon-modifiers clear <targets>` removes it, along with the pulse marks on the items.
- **Multiple sources stack**: when several abilities or several casters hit the same item, each is tracked
  separately and the **higher level wins** — a lower one never overwrites it.
- **Infinite duration**: omitting `duration` means "until removed", so collection then relies entirely on
  `should_remove_automatically` (caster out of range / ability disabled). A passive ability with
  `dragonsurvival:self` is the recommended pairing.

---

## 5. `additional_abilities:durability` — Durability

**In one sentence**: changes the durability of the damageable items sitting in the target's chosen slots —
wear them down, repair them, or set them to a specific value.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `durability_changes` | list | ✅ | — | At least one entry; each entry is an independent rule that scans slots and settles on its own |
| `durability_changes[].slots` | enum list | ✅ | — | At least one entry, see the slot table below |
| `durability_changes[].item` | item predicate | ❌ | **every** damageable item in those slots | Same fields as the vanilla `ItemPredicate` (`items` / `count` / `components` / `predicates`) |
| `durability_changes[].operation` | enum | ✅ | — | `add` = change the current durability (**positive `amount` repairs, negative wears**); `set` = set it to the value `amount` resolves to |
| `durability_changes[].amount` | level value | ✅ | — | The delta (`add`) or the target value (`set`), evaluated against the ability level |
| `durability_changes[].unit` | enum | ✅ | — | `durability` = durability points; `percent` = a percentage |
| `durability_changes[].percent_base` | enum | ⚠️ | — | `max_durability` / `current_durability`. **Required when `unit` is `percent`; must be omitted when `unit` is `durability`** — either mistake fails at data pack load |
| `durability_changes[].break_item` | boolean | ❌ | `false` | **Only** decides whether the item is destroyed once the result is `≤ 0`; it does not affect anything else |

#### Slot values (`slots`)

| Value | Covers |
|---|---|
| `mainhand` / `offhand` | Main hand / off hand |
| `head` / `chest` / `legs` / `feet` / `body` | Helmet / chestplate / leggings / boots / animal armour slot (horse and wolf armour) |
| `equipment` | All of the equipment slots above |
| `hotbar` / `inventory` | Hotbar (9 slots) / inventory (36 slots, hotbar included) |
| `claw_sword` / `claw_pickaxe` / `claw_axe` / `claw_shovel` | A single Dragon Survival claw slot |
| `claws` | All four claw slots |
| `all` | Equipment slots + 36 inventory slots + 4 claw slots |

### Example

One cast settling three rules: repair the main hand, wear the armour, and put the off hand at
"half of its current durability", destroying it once it runs out:

```json
{
  "effect_type": "additional_abilities:durability",
  "durability_changes": [
    {
      "slots": ["mainhand", "claw_sword", "claw_pickaxe"],
      "operation": "add",
      "amount": { "type": "minecraft:linear", "base": 0.25, "per_level_above_first": 0.05 },
      "unit": "percent",
      "percent_base": "max_durability"
    },
    {
      "slots": ["head", "chest", "legs", "feet"],
      "operation": "add",
      "amount": -4.0,
      "unit": "durability"
    },
    {
      "slots": ["offhand"],
      "item": { "items": "#minecraft:swords" },
      "operation": "set",
      "amount": 0.5,
      "unit": "percent",
      "percent_base": "current_durability",
      "break_item": true
    }
  ]
}
```

### Things to know

- **Living entities only.** Minecarts, dropped items and armour stands are skipped, and non-player mobs only
  have equipment slots — no inventory, no claw slots.
- **"Damageable" is the vanilla definition**: the test is `ItemStack#isDamageableItem()` — it has `max_damage`,
  it does **not** carry an `unbreakable` component, and it has `damage`. "Unbreakable" items are therefore
  excluded for free, and a brand-new tool already qualifies (vanilla's `Item.Properties#durability` writes
  `damage = 0` as well).
- **The Unbreaking enchantment only softens wear**: when `operation` is `add` and the resolved change is a
  **loss**, that loss first goes through Unbreaking (including the "fully negated" chance — a high enough level
  can mean no durability is lost at all). **Repairs and `set` never go through it**, so "how much you repair /
  set" is exactly what you wrote, never eaten by a random roll.
- **Where the durability ending at 0 goes** (the full meaning of `break_item`):
  - `false` (default) → the durability is clamped to 0 and the **item is kept**. ⚠️ **1.21.1 has no "broken"
    state** — the item **still works normally** and is only destroyed by the next ordinary wear, exactly as
    vanilla would. Treat this as "one free life", not as turning a tool into scrap.
  - `true` → when this change would take the durability to 0, the whole step is handed to vanilla `hurtAndBreak`
    (Unbreaking applies as usual, `shrink(1)` destroys the item, `onEquippedItemBroken` fires). The cost is that
    **vanilla skips the whole thing in creative mode**; wear that does not reach 0 never takes this path, so it
    is unaffected by that side effect.
- **The percentage base is a required choice**: `max_durability` and `current_durability` have no default.
  `max_durability` is a fixed yardstick, the same idea as vanilla `set_damage` (⚠️ vanilla works on the **damage**
  axis, the opposite direction); `current_durability` moves with the item's state — the blunter the blade, the
  less it recovers, which suits "proportional patching".
- ⚠️ **A purely instant effect, so the frequency is entirely up to the ability.** `trigger_rate` inside
  `actions[]` **defaults to 1 (every tick)**: on a **passive** ability without an explicit `trigger_rate`,
  repairs fill up instantly and wear empties instantly. An **active** ability (one settlement per cast) is the
  safest use; if you really want a passive aura, always set `trigger_rate` (e.g. 20 = once per second).
- **The same item is only changed once per settlement**: matching several `slots` at once (say the selected
  hotbar item, which is also the main-hand item) does not stack. Different `durability_changes` entries do settle
  independently, though — two rules pointing at the same item each apply once, by design.
- **Omitting `item` means "every damageable item in those slots"**, junk in the backpack included. To target only
  tools, narrow it down with something like `"item": { "items": "#minecraft:swords" }`.
- **The UI updates immediately**: equipment slots and the inventory are synced by vanilla itself, while the
  **claw slots are not part of any container menu** and get a sync packet from this effect, so durability changes
  in the claw slots show up in Dragon Survival's UI right away.
- **During a tool swap the slot is taken literally**: Dragon Survival physically moves the claw tool into the main
  hand while mining or attacking, so at that instant it counts as `mainhand`, not as a claw slot. An instant effect
  normally settles the moment the ability fires, so hitting that single tick is very unlikely.
- **This effect is not part of the "duration instance" family**: it has no state to keep across ticks, so it
  creates no attachment, takes no slot in the ability effect HUD and never shows up in `/dragon-modifiers` —
  every trigger is a standalone settlement.
