# Debug and Query Commands

> Applies to: Additional Abilities for DS `2.0.0`

This mod adds one standalone command tree, **`/additional-abilities`** (with 3 debug sub-commands), and
**appends** 2 query sub-commands to Dragon Survival's command tree. All of them require
**permission level 2** (the same as `/effect clear`; the owner of a single-player world has it by default);
for the standalone tree the check is applied once, on the **root** node.

```
/additional-abilities
 ├─ simple-screen-vision clear <targets>      Clear simple screen vision from the targeted players
 ├─ domain clear <targets>                    Clear the domains left behind by the targeted players
 └─ block-glow clear <targets>                Clear the block glow caused by the targeted players
```

---

## 1. `/additional-abilities simple-screen-vision clear <targets>`

Clears **all** simple screen vision effects (`shake`, `blur` and `edge_light` from `simple_screen_vision`) from the
targeted players.

```
/additional-abilities simple-screen-vision clear Steve
/additional-abilities simple-screen-vision clear @a
/additional-abilities simple-screen-vision clear @a[distance=..10]
```

| Argument | Notes |
|---|---|
| `<targets>` | Uses Vanilla's player-selector argument, so **player name / target selector / UUID** all work |

On success it reports: `Cleared simple screen vision from %s player(s)`.

> **Path change**: earlier versions registered this as a standalone root command, `/simple-screen-vision`.
> It has now been **moved verbatim** under `/additional-abilities` — the sub-command name, arguments,
> behaviour, message and return value are all unchanged, the path simply gained one level.
> The old `/simple-screen-vision` is **no longer registered**.

### Things to know

- **Players only.** Non-player entities have no "screen" to speak of.
- **Clearing only affects the current moment.** Screen vision is entirely client-side render state; the
  server keeps no copy at all and can only tell the target player to clear it.
  **A passive ability will simply re-send on the next tick** — to make the effect disappear for good, disable
  or remove that ability.
- Non-dragon forms and spectator mode never accumulate visuals, so there is nothing to clean up there.

---

## 2. `/additional-abilities domain clear <targets>`

Clears **every domain that the targeted players created as the caster** and that is still alive
(the areas left behind by the `additional_abilities:domain` target type — see
[03-Target-Type.md](03-Target-Type.md)).

```
/additional-abilities domain clear Steve
/additional-abilities domain clear @a
/additional-abilities domain clear @p[distance=..32]
```

| Argument | Notes |
|---|---|
| `<targets>` | Same as the previous section: **player name / target selector / UUID** all work |

On success it reports: `Cleared %2$s domain(s) created by %1$s player(s)`.
The **command's return value is the number of domains actually removed**, so it can be consumed by
`/execute store result`; if the players own no living domain it returns `0`.

### What exactly it clears

- It goes through **exactly the same removal path as natural expiry**, so a domain configured with
  `remove_effects_on_end: true` also runs its closing `remove` on the entities inside
  (immediately reverting potions / attribute modifiers and the like).
- It does **not** retroactively undo timed effects already sitting on targets: what a domain did to them is
  decided entirely by whatever Dragon Survival effect types were written into `applied_effects`, and there is
  no generic ledger of "which domain applied what to whom" to reverse. The reliable meaning of
  "clear the domain" is therefore **make the area stop existing** — it no longer settles and no longer
  refreshes anything, while effects already applied expire on their own `duration`.
- **It scans every dimension**: domains live on per-dimension data, and when the caster switches dimension a
  domain in the old dimension does **not** disappear on its own (it is merely skipped because the caster is
  unavailable, while its timer keeps running). So this command walks all levels instead of only the caster's
  current one.
- Counting is **by domain**: for a given ability "one cast = one domain" (multiple actions merge into the
  same one), so the number tracks casts, not the number of actions.

### When to use it

- Domains are **not persisted** — they are pure in-memory objects that vanish on server restart — but a
  domain with a long `duration` does slow down debugging; use this to finish it off instead of waiting.
- Verify that `remove_effects_on_end` behaves as expected.
- Confirm that "casting again evicts the old domain": `clear` first, cast, then compare the return values.

---

## 3. `/additional-abilities block-glow clear <targets>`

Clears the block glow left behind by the `additional_abilities:glow` block effect, for the players selected by
`<targets>` **as the casters** (see [02-Block-Effect.md](02-Block-Effect.md)).

```text
/additional-abilities block-glow clear Steve
/additional-abilities block-glow clear @a
/additional-abilities block-glow clear @p[distance=..32]
```

### What exactly does it clear

`<targets>` selects the **causers**, not "the players whose screen is affected" — the same convention as
`domain clear` above.

- Block glow is **shared** on the server by "position + colour + display type" (which is exactly what lets
  different casters stack), so the server has no notion of "this glow belongs to one player's screen only".
  The only reliable meaning is therefore to **revoke these casters' contributions**;
- a spot that other players are still contributing to is **left untouched**, so nobody else is affected;
- once a spot has no contributor left the entry is dropped, and nearby players are told **immediately** to stop
  drawing it (otherwise they would keep it until the next 20-tick refresh).

### Return value

The number of entries **removed outright** (usable with `/execute store result`); `0` when the targeted players
have no live contribution.

### How it differs from waiting for natural expiry

| | Takes effect | Scope |
|---|---|---|
| Waiting out `duration` | up to 60 seconds later | that one entry |
| This command | **immediately** | revokes only the `<targets>` contributions; other players' glow stays |

### Things to know

- **It is a debug "undo", not an off switch**: while the ability keeps triggering, the glow is registered again
  on the very next tick. To make it disappear for good, disable the ability first.
- The clear only applies to the present moment: the client is told "this entry is gone", and the server will not
  refresh it again.

---

## 4. `/dragon-ability query <target> <ability> current_charged_level`

One of the two **sub-commands appended** to Dragon Survival's own ability query, covering the charged-tier
family (`additional_abilities:charged` and `additional_abilities:optional_charged`):

```
/dragon-ability query @s additional_abilities:test_charged current_charged_level
```

| Situation | Value returned |
|---|---|
| Currently charging | The **tier** corresponding to the current charge |
| Not charging | The tier used by the **most recent actual release** (cancels are not recorded) |
| The ability's activation type is not a charged type | `0` |

The output reuses Dragon Survival's own query result message, so it is **formatted exactly like** the existing
`level` / `cast_time` entries.

### How it differs from `level`

| Query | Meaning |
|---|---|
| `level` | The player's **upgrade** level for the ability (its growth progress) |
| `current_charged_level` | The **tier actually used by this cast** (the charging outcome), which may be lower than `level` |

**A charged ability's damage, cooldown, mana cost and durations are all resolved against
`current_charged_level`**, so watch this entry when debugging numbers — not `level`.

---

## 5. `/dragon-ability query <target> <ability> current_selected_level`

Works with `additional_abilities:optional_charged` (the optional charged-tier type) and its wheel picking:

```
/dragon-ability query @s additional_abilities:test_optional_charged current_selected_level
```

| Situation | Value returned |
|---|---|
| The player has picked a tier with the wheel | The **picked release tier** (`0` means it has been picked as "cancel, release nothing") |
| Auto-follow (never scrolled, or scrolled back up to the reached tier) | The **tier reached so far** (exactly what a release would use) |
| Not charging, or charging has not reached tier 1 yet | `-1` (unspecified) |
| The ability's activation type is not a charged type | `-1` |

**The picked tier takes no part in any numeric resolution**: the tier that actually fires is reported by the
release packet and re-validated / clamped on the server. This entry merely exposes the client's picking state
so you can confirm "how many tiers will actually come out on release". The server clears the record at the end
of every cast (release or cancel), so it never leaks into the next one.

> **Why auto-follow reports a tier number instead of `-1`**: `-1` only ever means "no tier to refer to".
> When the player scrolls back to the top (or never scrolled at all) the client returns to auto-follow, and
> reporting "unspecified" at a moment when a concrete tier is perfectly computable only misleads debugging —
> the entry answers "how many tiers will fire", not "has the player turned the wheel".

### How it differs from `current_charged_level`

| Query | Meaning |
|---|---|
| `current_charged_level` | The tier you have **reached** (the charge-progress conversion) |
| `current_selected_level` | The tier **about to be released** (the player's wheel pick), never above the reached tier |

---

## 6. For reference: Dragon Survival's own query entries

Under `/dragon-ability query <target> <ability>` these sub-entries already exist and can be mixed with the ones
above:

| Sub-entry | Meaning |
|---|---|
| `level` | Current ability level |
| `max_level` | Maximum level |
| `current_cooldown` | Remaining cooldown |
| `cooldown` | Total cooldown for that level |
| `current_tick` | Ticks elapsed in the current cast (the charge ticks while charging) |
| `cast_time` | Total cast / charge length for that level |
| `is_applying_effects` | Whether effects are being applied (`1` / `0`) |

---

## 7. Granting abilities by hand

![test_glow](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/block_glow.png)![test_annulus](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/annulus.png)![test_optional_charged](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/optional_charged.png)![test_block_quake](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/block_quake.png)![test_domain](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/domain.png)![test_screen_vision](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/simple-screen-vision.png)![test_enchantment-bonus](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/enchantment_bonus.png)![test_durability](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/durability.png)![test_anti_dragon_breath](../../src/main/resources/assets/additional_abilities/textures/gui/sprites/abilities/test/anti_dragon_breath.png) ...

This mod's `test_*` abilities are not attached to any species, so grant them manually while debugging:

```
/dragon-ability add @s additional_abilities:test_charged
/dragon-ability add @s additional_abilities:test_anti_dragon_breath
/dragon-ability remove @s additional_abilities:test_charged
/dragon-ability refresh @s
```

- `add` grants the ability to the target player (starting at the lowest level, then upgraded through its own
  conditions);
- `remove` takes it away;
- `refresh` reloads ability data from the datapack — use it after editing JSON.

> When debugging `additional_abilities:domain`, keep in mind that **a level-0 active ability cannot be cast**
> (Dragon Survival's pre-cast validation blocks it). So after granting `test_domain` you still have to raise
> its level first, or temporarily switch it to `passive`.
