# Activation Types (`activation_type`)

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68`
> Location: `activation.activation_type`
> See also: [08-Trigger-Type.md](08-Trigger-Type.md) — passive abilities
> (`activation_type: dragonsurvival:passive`) carry a further `trigger.trigger_type` that decides
> **when** the passive runs

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
| `max_overcharged_duration` | level value | ❌ | unset (= hold indefinitely) | Length of the **overcharge window** (ticks): how long you may keep holding past `cast_time` before it fires automatically. **Must be positive**, and it may only be written **while `can_charge_exceed_cast_time` is on** — otherwise datapack loading fails |
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
>
> **Setting `max_overcharged_duration` truncates the picking window**: when the window runs out the native
> flow fires, using the **player's own highest tier** — so a lower picked tier (or even "picked `0` =
> cancel") no longer applies. To guarantee the player can always release at their own pick, leave the field
> unset (unset = hold indefinitely, release only by letting go).

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

That "keep holding" stretch (the **overcharge window**) can itself be bounded with
`max_overcharged_duration`:

```
release tick = cast_time + max_overcharged_duration     with the field set
             = unbounded                                without it (hold as long as you like)

inside the window: the tier no longer climbs (the cap is still `cast_time`) —
                   it is just the window where you park at max, may let go,
                   or may pick a tier with the wheel
```

- When the window runs out the native "casting complete" flow fires it, at **the player's own highest tier**:
  actions run once, the initial mana is spent, the cooldown applies — identical to firing at the cap, with no
  extra handling in this mod.
- The field **only bounds how long you may hold**; it does not raise the **charge cap** (the tier threshold
  ceiling is still `cast_time`).
- Two **load-time checks**: it **must be positive** (`0` means there is no window at all, and it would also park
  the top tier on the release frame, so its number and cue would never appear); and it **requires
  `can_charge_exceed_cast_time` to be on**. Both are self-contradictory configs and fail datapack loading, for
  the same reason `cast_time` must be positive — catch it at load time instead of silently misbehaving later.

---

## Behaviour at a glance

| Situation | Result |
|---|---|
| Released before tier 1's required charge time | **Cast cancelled**: no cooldown, no effect, no mana spent (matches Dragon Survival's native "released early" path) |
| Released at or beyond tier 1 | Fires once at the current tier: spend initial mana → run the actions → resolve cooldown / ending sound / ending animation against that tier |
| Charged all the way to `cast_time` | Dragon Survival's native "casting complete" releases it automatically, at the player's own upgrade level (max tier) |
| `can_charge_exceed_cast_time` enabled | You may hold past the cap indefinitely; the tier parks at your own maximum and **only releasing fires it — nothing is automatic** |
| ... enabled **and** `max_overcharged_duration` set | You may hold past the cap for at most that long, then it **fires automatically at the player's highest tier** (identical to "hold until it fires") |
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

A **tier number** is drawn to the right of the cast bar (**its position is configurable** — see below):

| Colour | Meaning |
|---|---|
| Grey | Tier 1 not reached yet (releasing now cancels the cast) |
| White | Charging |
| Gold | The player's own maximum level reached |

A cue plays each time a tier is crossed, rising in pitch with the tier (**both the sound and the pitch curve are configurable — see "Tier sounds" below**).
**The number shown is exactly the tier that will be used on release.**

Under `optional_charged` the readout changes shape with the selection (the progress bar and its percentage
below keep their meaning):

| State | Shown | Colour |
|---|---|---|
| Auto-follow (not scrolled) | a single number = the reached tier | white / gold at max, same as `charged` |
| Manual pick | `picked/reached`, e.g. `3/5` | picked value in **cyan**, `/reached` in grey |
| Manual pick set to cancel | `0/reached`, e.g. `0/5` | **red** |

Each wheel step that actually changes the tier plays a click (pitch rises with the tier; picking `0` uses a
lower pitch so it stands out). Both cues share one toggle and one volume — see "Tier sounds" below.

#### Remaining overcharge window

When an ability sets `max_overcharged_duration`, the readout gains an **overcharge row** below the usual two:
a `remaining/total window` string (e.g. `18/20`) with a thin bar underneath that **drains** as the window runs out.

| Colour | Meaning |
|---|---|
| White | Inside the overcharge window, plenty of time left |
| Red | Remaining **fraction** ≤ `overcharge_warning_ratio` (default `0.3`, i.e. 30 percentage ) — the automatic release is close |

| Option | Default | Notes |
|---|---|---|
| `charged_indicator.overcharge_warning_ratio` | `0.3` | The overcharge row turns red once the remaining fraction of the window is at or below this value (`0.0` - `1.0`; `0` disables it). A fraction rather than a tick count, so it adapts to each ability's window length |

- **Before the window starts this block is blank**: the readout's height is decided by "does this ability have an
  overcharge window", so it stays constant for the whole cast instead of jumping — the price is the reserved
  blank space before the window begins.
- Content is drawn **only once inside the window** (charge has reached `cast_time`); before that the "remaining"
  value would exceed the window's total length (e.g. `30/20`), which would be meaningless.
- **Abilities without `max_overcharged_duration` (including every default-mode ability) keep the two-row readout**,
  exactly as before.
- This option **only changes colour — it plays no sound**: the window ends in an automatic release and the visual
  countdown already covers it; adding audio would fight Dragon Survival's own charging sound and this mod's tier cue.

### Indicator position (client config)

By default the readout sits on the **right side** of the cast bar, vertically centred on it. Its position can be
changed in `config/additional_abilities-client.toml`, or straight from the in-game
**Mods → Additional Abilities for DS → Config** screen — either way it **applies immediately**, with no restart
and no need to re-enter the world.

| Option | Default | Notes |
|---|---|---|
| `charged_indicator.anchor` | `CAST_BAR_RIGHT` | Which side of the cast bar the readout sits on (see below) |
| `charged_indicator.offset_x` | `0` | Pixels to shift horizontally on top of the chosen placement (positive = right) |
| `charged_indicator.offset_y` | `0` | Pixels to shift vertically on top of the chosen placement (positive = down) |

The four placements (all of them follow the cast bar — move the cast bar in DS and the readout moves with it):

| `anchor` value | Placement |
|---|---|
| `CAST_BAR_RIGHT` | **Right** of the cast bar, vertically centred on it (default = the old behaviour) |
| `CAST_BAR_LEFT` | **Left** of the cast bar, vertically centred on it |
| `CAST_BAR_ABOVE` | **Above** the cast bar, horizontally aligned with it |
| `CAST_BAR_BELOW` | **Below** the cast bar, horizontally aligned with it |

Every placement leaves a 6-pixel gap between the readout and the cast bar.

- Offering only these four sides is deliberate: while casting your eyes are already on the cast bar, so an
  indicator hugging it costs you no glance away. "Screen corner / screen centre" presets look flexible but
  nobody actually uses them in combat. Need a little more clearance? Use the offsets (up to ±4000 pixels).
- Coordinates are in **GUI-scaled pixels**, the same units as the game UI — changing the GUI scale moves the
  readout's absolute position, but a placement's meaning does not change.
- Left / above / below all subtract the readout's own width and height first, so it is **the whole readout**
  that sits flush — never half of it hanging off-screen.
- This config is **client-only**: it takes no part in any server-side decision and is not synced to other players.
- The in-game config screen renders the placement as a dropdown, so you never have to type these values; if you
  do edit the file by hand, use the uppercase enum names, e.g. `CAST_BAR_LEFT`.

### Tier sounds (client config)

Both cues play locally only (they are "your own" tier feedback and are never sent to other players), and they
share a single toggle and a single volume multiplier:

| Option | Default | Notes |
|---|---|---|
| `charged_indicator.play_sound` | `true` | Whether to play the cues: the **tier sound** (one per tier gained) and the **wheel click** (one per actual tier change) are switched together |
| `charged_indicator.sound_volume` | `1.0` | Master multiplier (`0.0` - `1.0`) applied on top of each sound's own **base volume** (`0.7` for the tier sound, `0.6` for the wheel click) |
| `charged_indicator.level_up_sound` | `NOTE_PLING` | Which sound the tier cue uses. **Eight fixed presets** (dropdown); the default is the old note-block "pling" |
| `charged_indicator.level_up_sound_pitch` | `0.9` | Pitch at tier 1 (`0.5` - `2.0`; `1.0` is the sound's original pitch) |
| `charged_indicator.level_up_sound_pitch_per_level` | `0.12` | Pitch added per tier gained (`0.0` - `1.0`; `0` keeps a single pitch for every tier) |
| `charged_indicator.level_up_sound_max_pitch` | `2.0` | Pitch ceiling (`0.5` - `2.0`) |

- The two base volumes differ **on purpose** (the wheel click is shorter and lighter) and the multiplier does not
  flatten them; the default `1.0` matches the pre-update loudness exactly.
- Turning the toggle off only affects these two cues: **Dragon Survival's own** charging sound (`sound.charging`)
  and its start / end sounds still play.
- The volume option cannot be greyed out in step with the toggle (NeoForge 21.1's config screen has no such feature
  yet), so it stays editable while the toggle is off — it simply has no effect.
- **Eight presets is a hard limit of the config system**: `ModConfigSpec` supports only primitives and enums —
  there is no registry-object type — and vanilla alone ships **1486** sound events, far too many for a dropdown.
  Offering any registered sound would require a custom search screen (none exists yet).
- The **pitch curve** is `min(base pitch + per-tier step x tier, ceiling)` and applies to the tier cue **only**
  (the wheel click keeps its own fixed curve). The sound engine clamps pitch to `[0.5, 2.0]`, so the ceiling cannot
  go higher. Short percussive sounds take pitch-shifting well; long or ambient ones distort audibly — retune the
  curve after switching sounds.

- If a sound id maps to **several audio variants** (vanilla `sounds.json` lets one id point at multiple files and
  the game picks one at random per play), the tier sound here is **locked to a single fixed variant**, so a given
  tier always sounds exactly the same. The wheel click is not locked.

> **Behaviour change**: hiding the HUD with `F1` **no longer** mutes the tier sounds.
> Previously the "did a tier get crossed?" check lived inside the HUD layer's render callback, so hiding the HUD
> skipped it and silently killed the sound along with the picture. The cues are now driven by the **client tick**
> (once per tick, independent of frame rate) and are fully decoupled from HUD visibility.

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

## Ability info panel (activation type)

The **first line** of the **Info** side panel (expanded by holding `Shift`) shows the ability's activation type:

| Activation type | Shown in the panel |
|---|---|
| `dragonsurvival:passive` | Passive |
| `dragonsurvival:simple` | Active |
| `dragonsurvival:channeled` | Channeled |
| `additional_abilities:charged` | Charged |
| `additional_abilities:optional_charged` | Optional Charged |

- The line exists to **tell `charged` and `optional_charged` apart**: Dragon Survival sees both as
  `dragonsurvival:simple` (the panel header always reads "Active Ability") even though they behave
  completely differently — and DS itself **never** shows the activation type, it only shows a "Trigger"
  line for passive abilities.
- The name is looked up from the activation type's **own registry id** (translation key
  `activation_type.<namespace>.<path>`), so **activation types registered by other mods show up
  automatically** — this mod needs no code change when upstream adds or removes one.
- When no translation exists the **raw id** is shown instead (e.g. `some_mod:my_type`), so a machine key
  name is never drawn into the UI.
- The line is **client-side only**: it takes no part in any server-side decision and is not part of the
  ability description text.

---

## Test abilities

| Ability id | Config | Purpose |
|---|---|---|
| `additional_abilities:test_charged` | `cast_time: 50`, `can_charge_exceed_cast_time` off | Default mode: fires automatically at the cap, all 5 tiers reachable |
| `additional_abilities:test_charged_hold` | `cast_time: 60`, `can_charge_exceed_cast_time: true`, `max_overcharged_duration: 20` | **Overcharge window**: hold at most 20 ticks past the cap, then it fires automatically at the player's highest tier |
| `additional_abilities:test_optional_charged` | `cast_time: 60`, type `optional_charged` | Wheel picking: scroll to pick a tier, pick `0` to cancel |

None is attached to any species; grant them with `/dragon-ability add <target> <ability>`.
