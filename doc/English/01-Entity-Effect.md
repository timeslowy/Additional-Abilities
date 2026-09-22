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

The three `effect_type` values below are added by this mod.

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

**In one sentence**: overlay a camera shake or a screen blur on another player's view.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `type` | enum | ✅ | — | `shake` camera shake / `blur` screen blur |
| `duration` | level value | ✅ | — | Duration in **ticks** (1 second = 20). Floored after evaluation; ≤ 0 does nothing |
| `amplifier` | level value | ❌ | `1.0` | Strength multiplier, see the table below |
| `probability` | level value | ❌ | `1.0` | Chance to take effect, **rolled independently on every trigger** (same convention as Dragon Survival's built-in potion effects) |

### Strength reference

| `type` | What `amplifier` means | Suggested range |
|---|---|---|
| `shake` | `1.0` ≈ a maximum camera roll offset of **1.5°** | 0.5 – 5 |
| `blur` | `1.0` = a blur radius of **1 pixel**, capped at **20** (anything higher counts as 20) | 1 – 20 |

### Example (both visions from a single cast)

```json
"entity_effect": [
  {
    "effect_type": "additional_abilities:simple_screen_vision",
    "type": "blur",
    "duration": { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 20.0 },
    "amplifier": 5,
    "probability": 1.0
  },
  {
    "effect_type": "additional_abilities:simple_screen_vision",
    "type": "shake",
    "duration": { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 20.0 },
    "amplifier": 3.0,
    "probability": 1.0
  }
]
```

### Things to know

- **Players only.** Non-player targets (even living ones) are skipped.
- **Zero side effects**: no damage, no movement, no attributes — purely a visual on the receiving end.
- `blur` **blurs the world only, never the GUI** — health bar, hotbar and chat stay perfectly sharp.
- **The two visions coexist.** Sending both in one cast does not let either swallow the other.
- **Repeated sends are safe** and will neither extend nor intensify the effect indefinitely:
  for the same type, duration takes the larger value and strength takes the larger value.
  Passive abilities refreshing at high frequency are therefore fine.
- There is **server-side throttling**: for the same player and the same vision type, nothing is sent
  within 5 ticks unless the strength increased. A strength increase always goes through.
- Presentation details: 5-tick fade-in, 10-tick fade-out, so nothing snaps on or off; the shake phase is
  driven by the local tick count, so it is **frame-rate independent**.
- **To clear it immediately** (debugging): `/additional-abilities simple-screen-vision clear <targets>`, see
  [06-Debug-and-Query-Commands.md](06-Debug-and-Query-Commands.md). Note that a passive ability will simply
  re-send on the next tick, so clearing only affects the current moment.
