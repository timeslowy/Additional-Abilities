# Activation Types (`activation_type`)

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68`
> Location: `activation.activation_type`

---

## `additional_abilities:charged` — Charged Tiers

**In one sentence**: hold the ability key to charge, then **release to fire at the tier you reached** —
the longer you hold, the stronger it gets.

Behaviourally it is the "hold to cast" of `dragonsurvival:simple`, except the casting process is treated as
**charging**: the tier climbs while you hold, and the moment you release the ability fires once at the tier
you had reached.

### Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `charged_duration_per_level` | level value | ✅ | — | Charge time required per tier (ticks). Tier L uses the value of this function at L |
| `cast_time` | level value | ✅ | — | Total charge cap (ticks). **Required and must be positive** — 0 or negative fails datapack loading outright |
| `can_charge_exceed_cast_time` | boolean | ❌ | `false` | Whether you may keep holding past the cap, see below |
| `cooldown` | level value | ❌ | `0` | Resolved **against the final tier** |
| `initial_mana_cost` | level value | ❌ | `0` | Resolved **against the final tier** |
| `notification` | object | ❌ | same as Dragon Survival | Messages for "not enough mana" / "usage blocked" |
| `can_move_while_casting` | boolean | ❌ | `true` | Whether you may move while charging |
| `sound` | object | ❌ | — | Same as `simple`, but **`looping` is not supported** (it errors out) |
| `animations` | object | ❌ | — | Same, **`looping` is not supported** (it errors out) |

> In `dragonsurvival:simple`, `cast_time` is optional. This type makes it **required and strictly positive**,
> because it *is* the total charge cap — a missing or zero value makes charging meaningless, so it fails at
> datapack load time rather than misbehaving at runtime.

### Example

```json
"activation": {
  "activation_type": "additional_abilities:charged",
  "can_move_while_casting": false,
  "cast_time": 50.0,
  "charged_duration_per_level": {
    "type": "minecraft:linear",
    "base": 10.0,
    "per_level_above_first": 10.0
  },
  "cooldown": 60.0,
  "initial_mana_cost": 1.0,
  "sound": {
    "charging": "block.conduit.activate",
    "end": "block.enchantment_table.use"
  }
}
```

What the config above means: tiers 1–5 need 10 / 20 / 30 / 40 / 50 ticks of charging, and the total cap is
50 ticks (49 ticks in practice under the default mode, see the conversion below), so all five tiers are
reachable.

---

## `additional_abilities:optional_charged` — Optional Charged Tiers

**In one line**: the `charged` variant that lets you **pick the tier you release** with the mouse wheel while
charging — including picking *cancel, release nothing*.

The fields are **identical** to `charged` (same table as above); only one default differs, plus a wheel
interaction on top.

### Differences from `charged`

| | `charged` | `optional_charged` |
|---|---|---|
| `can_charge_exceed_cast_time` default | `false` | **`true`** |
| Tier used on release | the tier you have reached | the tier you **picked** with the wheel |
| Can you cancel? | only by releasing before tier 1 | **at any time** (pick tier `0`) |

> **Why the default flips to `true`**: picking a tier needs a window where holding past the cap does not
> fire. With `false`, Dragon Survival releases automatically at the player's highest tier the moment
> `currentTick == cast_time`, leaving no time to pick. The field can still be written as `false`
> explicitly, but then picking only works within `[0, cast_time - 1]`.

### Wheel interaction

While the ability key is held:

| Action | Effect |
|---|---|
| Scroll up | release tier +1 |
| Scroll down | release tier −1 |
| Scroll down to `0` | picks **cancel**: releasing then casts nothing |

**The selectable range is always `[0, the tier you have reached]`** — you cannot pick a tier you have not
charged up to. Before tier 1 there is nothing to pick, so the wheel is not captured and keeps switching
hotbar items as usual.

**Auto-follow by default**: without scrolling, the release tier equals the reached tier, exactly like
`charged`. The first scroll switches to **manual**; scrolling back up to the reached tier returns to
auto-follow.

**Manual picking does not freeze the charge**: the reached tier keeps rising (reach 5, pick 3, keep holding
— the reached tier is still 5), so you can always scroll back up to 4, 5.

> **Note**: while charging, the wheel is claimed by the ability and **does not switch hotbar items**
> (intentional).

### What cancelling costs

Picking `0` and releasing **cancels the cast**: no actions run, no initial mana is spent, **no cooldown is
applied**, and the looping sound and ability animation are cleared immediately.

> The cancel path deliberately bypasses Dragon Survival's native stop: `DragonAbilityInstance#isApplyingEffects()`
> tests `currentTick >= cast_time`, and with `can_charge_exceed_cast_time` enabled the "holding past the cap"
> stretch makes it **return true early** — Dragon Survival would then treat the cast as "effects already
> applied" and hand out a cooldown plus ending sound / animation. Cancelling is therefore wrapped up by this
> mod explicitly as "no cooldown", with its own stop-sound and stop-animation broadcasts.

### Balance note

`cooldown` and `initial_mana_cost` are resolved against the **tier actually released**, so releasing a low
tier is naturally cheaper — that is the point. But if you configure those two to **decrease** with the tier,
spamming the lowest tier becomes the optimal play; keep them non-decreasing in the tier.

---

## Tier conversion

```
ticks required for tier L = min( charged_duration_per_level(L), charge cap )

charge cap = cast_time           when can_charge_exceed_cast_time = true
           = cast_time - 1       when can_charge_exceed_cast_time = false (the default)
```

**Why the default cap is one tick short**: Dragon Survival completes the cast and stops the moment
`currentTick == cast_time`, and the player never sees that frame. If the cap sat exactly on `cast_time`,
the highest tier would land on that invisible frame — the max-tier number and its sound cue would never show
up, and "release at the last moment" would be one tier *lower* than "hold until it fires automatically",
which is self-contradictory. Moving the cap back by one tick removes the mismatch.

**With `can_charge_exceed_cast_time` enabled**: holding past the cap no longer releases; `currentTick` keeps
growing past `cast_time`, the cap becomes `cast_time` itself, and the top tier stays on the HUD until you
release.

---

## Behaviour at a glance

| Situation | Result |
|---|---|
| Released before tier 1's required charge time | **Cast cancelled**: no cooldown, no effect, no mana spent (matches Dragon Survival's native "released early" path) |
| Released at or beyond tier 1 | Fires once at the current tier: spend initial mana → run the actions → resolve cooldown / ending sound / ending animation against that tier |
| Charged all the way to `cast_time` | Dragon Survival's native "casting complete" releases it automatically, at the player's own upgrade level (max tier) |
| `can_charge_exceed_cast_time` enabled | You may hold past the cap indefinitely; the tier parks at your own maximum and **only releasing fires it — nothing is automatic** |
| `optional_charged`: released with a picked tier | Fires at the **picked tier** (this cell is always "the reached tier" for `charged`) |
| `optional_charged`: released with tier `0` picked | **Cast cancelled**: no actions run, no initial mana spent, **no cooldown**, looping sound and animation cleared |

---

## What the tier actually affects (the important part)

**Every field evaluated by level switches from the player's upgrade level to the charge tier.** Specifically:

- **every action parameter inside the ability** — damage, projectile count / speed / spread, block effect
  parameters, probability rolls, durations, and so on;
- **`cooldown`** — the wrap-up goes through Dragon Survival's native `release`, which reads
  `getCooldown(level)`, so the cooldown length follows the tier;
- **`initial_mana_cost`** — the initial mana cost is resolved against the tier;
- **`cast_time`** — also passed along as the modulus basis for the actions' `trigger_rate`.

The **only thing not affected by the tier** is the tier conversion itself (`charged_duration_per_level` is
evaluated per level to produce the thresholds).

### Three edges that are easy to trip over

1. **The tier never exceeds the player's upgrade level.** A player who has only upgraded to level 3 will
   fire at level 3 even at a full charge.
2. **The up-front check uses the real level.** The moment you press the ability key, Dragon Survival
   validates mana against the `initial_mana_cost` of your **real** level. So "charge a low tier to save
   mana" does not work — you have to afford the full-tier up-front check regardless.
3. **Deferred effects cannot see the tier.** Effects that spawn an entity and only read the ability level
   on a later tick (some projectile-derived logic, for example) read the player's real level.
   Effects resolved **immediately within the same tick** (damage, projectile spawning, block effects,
   probability rolls) are unaffected.

---

## HUD and queries

### On-screen indicator

A **tier number** is drawn to the right of the cast bar:

| Colour | Meaning |
|---|---|
| Grey | Tier 1 not reached yet (releasing now cancels the cast) |
| White | Charging |
| Gold | The player's own maximum level reached |

A note-block cue plays each time a tier is crossed, rising in pitch with the tier.
**The number shown is exactly the tier that will be used on release.**

Under `optional_charged` the readout changes shape with the selection (the progress bar and its percentage
below keep their meaning):

| State | Shown | Colour |
|---|---|---|
| Auto-follow (not scrolled) | a single number = the reached tier | white / gold at max, same as `charged` |
| Manual pick | `picked/reached`, e.g. `3/5` | picked value in **cyan**, `/reached` in grey |
| Manual pick set to cancel | `0/reached`, e.g. `0/5` | **red** |

Each wheel step that actually changes the tier plays a click (pitch rises with the tier; picking `0` uses a
lower pitch so it stands out).

### Query command

```
/dragon-ability query <target> <ability> current_charged_level
/dragon-ability query <target> <ability> current_selected_level
```

`current_charged_level`:

- currently charging → the **tier corresponding to the current charge**;
- not charging → the tier used by the **most recent actual release** (cancels are not recorded);
- the ability is not a charged type → `0`.

`current_selected_level` (for `optional_charged`) — always answers "**how many tiers would come out if I released right now**":

- manually picked → the **picked release tier**; `0` means it has been picked as **cancel**;
- **auto-follow** (never scrolled, or scrolled back up to the reached tier) → the **tier reached so far**
  (which is exactly what a release would use);
- not charging, or charging has not reached tier 1 yet → `-1` (unspecified);
- the ability is not a charged type → `-1`.

> The internal "auto / manual" state is deliberately **not** exposed: as soon as the player scrolls back to the
> top the client returns to auto-follow, and reporting `-1` ("unspecified") at a moment when a concrete tier
> is perfectly computable only misleads debugging.
> There is also no state of "manually picked exactly the reached tier" that would need to be told apart from
> auto-follow — the two are identical in effect.

---

## Test abilities

| Ability id | Config | Purpose |
|---|---|---|
| `additional_abilities:test_charged` | `cast_time: 50`, `can_charge_exceed_cast_time` off | Default mode: fires automatically at the cap, all 5 tiers reachable |
| `additional_abilities:test_charged_hold` | `cast_time: 60`, `can_charge_exceed_cast_time: true` | Past-the-cap charging: hold at max tier as long as you like |
| `additional_abilities:test_optional_charged` | `cast_time: 60`, type `optional_charged` | Wheel picking: scroll to pick a tier, pick `0` to cancel |

None is attached to any species; grant them with `/dragon-ability add <target> <ability>`.
