# Feature and command guide

Labels distinguish **ROUTE / RESOURCES**, **ASSISTED TAS**, and **NON-SPEEDRUN** utilities and experiments. A route label means the feature contributes to automated progression, not that its run is accepted by speedrun leaderboards. Help messages carry these labels too.

## Assisted TAS — 1.8

| Commands / feature | Behavior |
| --- | --- |
| `@tasloot`, `@tasloot credits` | Loot natural gold, obtain one log, duplicate carried supplies as drops, trade with a cleric, craft tools, obtain a bed, travel to the stronghold, enter the End and perform the simulated credits sequence. |
| `@tasloot end` | Same preparation and travel, stopping after the first End entry. |
| `@creditswarp` | Simulated ending from the End platform, requiring two redstone, a wooden pressure plate and a valid bed spawn beside an active Overworld End portal. |
| `@dupe item [count]` | Save player state, drop the requested carried originals and restore player state. Extra items remain as real ground drops. |
| `@dupe gold_ingot 8 log 4 emerald 1` | Multiple item types in one checkpoint. Every requested item is validated before mutation. |
| `@dupe chest ...` | Compatibility spelling only: now performs **drop** duplication, with a notice. No chest is selected or used. |
| `@dupe save`, `@savestate` | Save the player's state in memory for this session. |
| `@dupe load`, `@loadstate` | Restore the manual checkpoint, leaving world changes and drops intact. A manually changed chest also remains changed; this does not make automatic duplication use chests. |
| `@autodupe on/off` | Allow ordinary acquisition to duplicate an item already obtained. Default off outside the TAS route. |
| `@tick pause/resume/normal`, `@tick rate 5`, `@tick step [count]` | Private-world synchronized simulation control. Controlled rate is 0.25–20 ticks/sec. F6 pauses/resumes; F7 steps. `normal` stops an active planner and returns to the vanilla clock. |
| `@tasenderman` | Move an existing loaded Enderman near the player, fight using normal melee, control its pearl-drop RNG and collect the pearl. |
| Assisted travel | Sprint jumps, planned pearl trajectories, suppressed damage for bot pearl throws, seed-based stronghold location, a bed spawn and portal activation. |
| Assisted trading | Temporarily guide a cleric toward the player. Trade gold for an emerald, buy redstone and Eyes from the same merchant, and use drop batches to replenish supplies. |

Automatic batches double only to their target counts. They pick up copies and verify the resulting inventory on the server before the next batch. Different item targets can be processed together. The TAS cycle targets ten simulation ticks including pickup, rather than hiding collection time from its timer. Failed pickup stops the task; it does not invent replacement items.

The credits sequence is explicitly simulated. The game performs the platform setup, void death, bed respawn and second portal crossing; the mod then invokes the credits result. It does not defeat the living dragon or reproduce the low-Xss exploit.

## Route / resources

| Commands | Behavior |
| --- | --- |
| `@overworld [prepare|nether]`, `@overwold` | Prepare stone tools, food and an iron kit; optional portal construction/Nether entry. |
| `@structurekit [nether]` | 1.8 surface-structure iron route with seed retries. Explicitly separate from `@tasloot`. |
| `@templevillage`, `@templeloot`, `@templevillage temple`, `@templevillage cane` | 1.8 temple loot/TNT and sugar-cane/village phases; cane target is 30. |
| `@fortress` | Nether search, one-block-wide bridging with a block budget, blaze combat/rods and return route. |
| `@tower [pearls]`, `@swordtower`, `@tower base`, `@towerbase` | Pearl-farm tower or the captured first build section. These can also be invoked as isolated build tests. |
| `@dragon` | Experimental End combat automation. Separate from simulated credits; no guarantee of success on arbitrary terrain/equipment. |
| `@get item [count]`, `@craft item [count]`, `@smelt item [count]` | Resource acquisition, crafting and furnace use. Recipes and gathering coverage are finite. |
| `@food [count]`, `@meat [count]` | Obtain edible supplies, including supported animal/crop foods. |
| `@trade item [count]` | Search usable villager offers. Ordinary emerald acquisition can use paper/books. |
| Built-in movement and work | Tool selection, dropped-item pickup, water escape, obstacle clearing, pillar exits, furnace overlap and multiple furnaces. These are task components, not separate mods. |

## NON-SPEEDRUN — navigation, inventory and interface utilities

| Commands | Behavior |
| --- | --- |
| `@goto x z`, `@goto x y z` | Standalone travel; relative `~` coordinates are supported. |
| `@follow player` | Follow another player. |
| `@equip item` | Equip a carried item. |
| `@deposit item [count]`, `@stash`, `@give player item [count]` | Ordinary inventory/chest transfers. Removing chest **duplication** does not remove legitimate chest looting/depositing. |
| `@inventory`, `@inv`, `@coords`, `@list [filter]` | Inspect inventory, location or supported item names. |
| `@help`, `@status`, `@pause`, `@unpause`, `@resume`, `@stop`, `@idle` | Inspect and control tasks. |
| `@remove hud`, `@hud on/off` | Hide/restore the Botas overlay; retain vanilla HUD and menus. |
| `@gamma value`, `@setgamma value` | Adjust brightness. |
| `@dumpmold` | Log nearby portal mold blocks for inspection. |
| `@stronghold`, `@stronghold scan` | Creative seed/render-distance search experiments in 1.8; not a Survival speedrun route. |

## NON-SPEEDRUN — parkour practice and development tools (1.8)

| Commands / tool | Behavior |
| --- | --- |
| `@parkour x y z` | Rehearse a loaded course and play the chosen movement inputs toward the target. Arbitrary courses are not guaranteed solvable. |
| `@parkour-smooth x y z` | Live terrain/velocity navigation without a complete course rehearsal. |
| `@parkour practice`, `@parkour triple`, `@parkour challenge` | Generated gap/neo/extension, triple-neo and composite practice fixtures. |
| `@parkour cw2cw` / `winged`, `@parkour h2h` / `head-to-head` | Specific cobblewall and head-to-head neo fixtures. |
| `@parkour skills [list|map|name|run name]` | Sixteen technique lanes and their practice arena. |
| `@parkour map`, `@parkour runmap`, `@parkour marathon` | Create/rehearse/play the marathon practice map. |

## 1.7.10 boundaries

The retained 1.7.10 port has its own command implementation. It does not inherit the 1.8 TAS route or player-state drop batches. Its `@glitchless` and temple routes are partial progression experiments; automatic full stronghold/End progression is not established there. The TNT `@dupe` and pickup/save-reload `@dupe_save` commands are **NON-SPEEDRUN: EXPERIMENTAL** and are not confirmed successful duplication methods. See its version guide for the exact available commands.
