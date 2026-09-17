# Debug and Query Commands

> Applies to: Additional Abilities for DS `2.0.0`

This mod adds 1 standalone command and **appends** 1 query sub-command to Dragon Survival's command tree.
Both require **permission level 2** (the same as `/effect clear`; the owner of a single-player world has it
by default).

---

## 1. `/simple-screen-vision clear <targets>`

Clears **all** simple screen vision effects (both `shake` and `blur` from `simple_screen_vision`) from the
targeted players.

```
/simple-screen-vision clear Steve
/simple-screen-vision clear @a
/simple-screen-vision clear @a[distance=..10]
```

| Argument | Notes |
|---|---|
| `<targets>` | Uses Vanilla's player-selector argument, so **player name / target selector / UUID** all work |

On success it reports: `Cleared simple screen vision from %s player(s)`.

### Things to know

- **Players only.** Non-player entities have no "screen" to speak of.
- **Clearing only affects the current moment.** Screen vision is entirely client-side render state; the
  server keeps no copy at all and can only tell the target player to clear it.
  **A passive ability will simply re-send on the next tick** — to make the effect disappear for good, disable
  or remove that ability.
- Non-dragon forms and spectator mode never accumulate visuals, so there is nothing to clean up there.

---

## 2. `/dragon-ability query <target> <ability> current_charged_level`

A **sub-command appended** to Dragon Survival's own ability query, dedicated to `additional_abilities:charged`:

```
/dragon-ability query @s additional_abilities:test_charged current_charged_level
```

| Situation | Value returned |
|---|---|
| Currently charging | The **tier** corresponding to the current charge |
| Not charging | The tier used by the **most recent actual release** |
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

### For reference: Dragon Survival's own query entries

Under `/dragon-ability query <target> <ability>` these sub-entries already exist and can be mixed with the one
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

## 3. Granting abilities by hand (Dragon Survival's own, not added by this mod)

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
