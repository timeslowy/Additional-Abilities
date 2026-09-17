# Bundled Datapacks

> Applies to: Additional Abilities for DS `2.0.0` · Dragon Survival `≥ 2.0.68` · Wing Kirin `≥ 3.3.0`

This mod ships **optional** datapacks — off by default, the player has to enable them manually.
Their purpose is to rework abilities that already exist in another mod (currently Wing Kirin) using
this mod's own custom ability components.

---

## 1. What is bundled

| Pack | Display name | Type | Purpose |
|---|---|---|---|
| `innovative_wingkirin_abilities` | 翼麒麟革新版技能 (Innovative WingKirin's Abilities) | overriding datapack | Reworks Wing Kirin's "Spell Binder" and "Thunderous Shout" with this mod's custom components |

All bundled packs live under:

```
src/main/resources/data/additional_abilities/datapacks/
```

---

## 2. How to enable it

### Option A: the Data Packs screen (recommended)

1. When creating a world, or via "Edit → Data Packs" for an existing world;
2. Find **翼麒麟革新版技能** in the "Available" list on the left (it carries a grey source label);
3. Move it to the "Selected" list on the right with the arrow, then confirm.

### Option B: commands

```
/datapack list                                     # find its full id
/datapack enable "<id>"                            # enable
/datapack disable "<id>"                           # disable
```

The pack id is built from the mod id plus the pack's resource path, and looks like:

```
mod/additional_abilities:data/additional_abilities/datapacks/innovative_wingkirin_abilities
```

It contains a `:`, so it **must be quoted**. Always trust the actual output of `/datapack list`.

### Two traps you must know about

**1. Never use `first`**

```
/datapack enable "<id>" first     ← silently disables the entire pack, with no error at all
```

For enabled datapacks, **the later a pack sits in the list, the higher its priority**. `first` inserts the
pack at the very **front** of the list, which is the *lowest* priority — so its ability definitions get
overwritten by Wing Kirin's originals. The UI says "enabled", the in-game numbers **never change**,
and there is **no log line or error whatsoever**.

Just use the bare form (equivalent to `last`, appended to the end = highest priority).
If you really need to control the position, `/datapack enable "<id>" after <some pack>` works too.

**2. You must leave and re-enter the world after enabling or disabling it**

Dragon Survival's ability definitions live in a **datapack registry**, which is built **once when the world
loads**. `/reload` does **not** rebuild them. So if you only run `/reload`, you will see the illusion of
"the pack is enabled but nothing changed". Go back to the title screen and re-enter the world instead.

---

## 3. What each pack changes

### `innovative_wingkirin_abilities`

Overrides two ability definitions from Wing Kirin (under `data/wing_kirin/dragonsurvival/dragon_ability/`).

#### "Spell Binder" — `wing_kirin:spell_binder`

| Item | Wing Kirin original | This pack |
|---|---|---|
| Activation type | `dragonsurvival:simple` | **`additional_abilities:charged`** (charge tiers) |
| `cast_time` | fixed `20` ticks | `10 × level` ticks |
| `charged_duration_per_level` | — | `10 × level` ticks |
| `can_charge_exceed_cast_time` | — | `false` (fires automatically at full charge; you cannot hold past the cap) |
| "Ignite yourself and allies" action during casting | present (`charging` trigger point, `ignite` for 10 ticks) | **removed** |

> `cast_time` and `charged_duration_per_level` use the same values; because the default mode moves the
> charge cap back by one tick, tier L's actual threshold is `10 × level - 1` ticks.
> See [04-Activation-Type.md](04-Activation-Type.md) for the conversion rules.

**How the gameplay changes**: the original is a fixed 20-tick cast; this becomes **charge tiers** —
how long you charge decides the level actually used, **it fires on release**, and releasing before tier 1
cancels the cast. Both the duration and the strength of the buff are resolved against the tier.
Two general limits still apply: the tier never exceeds the player's own upgrade level, and the up-front
check still spends mana based on the real level.

#### "Thunderous Shout" — `wing_kirin:thunderous_shout`

| Item | Wing Kirin original | This pack |
|---|---|---|
| Knockback `push_force` | `0.1 + 0.1 × (level - 1)` | **doubled** to `0.2 + 0.2 × (level - 1)` |
| Camera shake | none | adds `additional_abilities:simple_screen_vision`: `shake`, duration `40 + 20 × (level - 1)` ticks, strength `1 + 1 × (level - 1)`, always applies |

Everything else (damage, weakness, hunger, the exhaustion debuff on `channel_completion`, animations,
sounds, upgrade rules) is identical to the original.

---

## 4. For add-on authors: adding another bundled pack

1. Put the pack directory under `src/main/resources/`, e.g.
   `src/main/resources/data/<modid>/datapacks/<pack name>/`;
2. The directory root **must** contain `pack.mcmeta` (with `pack.pack_format`) and `pack.png`;
3. Add one call in `addPackFinders` inside `registry/PackFinders.java` — the path argument **must** match
   the real directory exactly;
4. Add the display-name language keys to `lang/en_us.json` and `lang/zh_cn.json`
   (`datapack.<modid>.<pack name>`, plus a `.desc` key for the description);
5. Compile, launch the game, and confirm it shows up in `/datapack list`.

Which registration method to use:

| Pack type | Does it override a same-named file that already exists in another mod? | Method |
|---|---|---|
| Server datapack (`data/…`) | **Yes** | `addOverridingDataPack` |
| Server datapack (`data/…`) | No — it only adds new ids / new files | `addSimplePack` |
| Client resource pack (`assets/…`) | Not applicable (client-side only) | `addSimplePack` |

**Overriding packs must use `addOverridingDataPack`**: it deliberately **does not declare a KnownPack**.
Without that, you get a silent mismatch where "the override works on the server, but the client resolves to
the mod's original locally" — the symptom is again "the pack is enabled but the UI text never changed",
while everything the server computes (levels, effect durations, damage, tags) behaves perfectly fine,
and neither side logs anything. The reasoning and trade-offs are documented in `PackFinders`' class javadoc.
Purely additive packs do not have this problem, and the convenience API keeps the transfer optimisation.

> Two parameter details: keep `alwaysActive` at `false` for an "optional" pack, and keep the position at
> `Pack.Position.TOP` so it lands at the end of the enabled list and gains priority over `mod_data`.
