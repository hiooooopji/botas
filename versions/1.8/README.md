# Botas for Minecraft 1.8

The primary Botas build uses Legacy Fabric, with its own pathfinding and task backend.

- [Installation and required mods](../../docs/INSTALL.md)
- [All commands, with non-speedrun features labeled](../../docs/FEATURES.md)
- [Build instructions](../../docs/DEVELOPMENT.md)

## Build and run

```sh
bash gradlew -p versions/1.8 build
bash gradlew -p versions/1.8 runClient
```

Install `build/libs/botas-1.8-0.3.0.jar` in a Legacy Fabric **1.8** instance with Fabric Loader **0.16.2**. No Fabric API, Baritone or LoTAS JAR is required. Remove the old AltoClef JAR and older Botas copies first.

## Assisted TAS route

`@tasloot` loots original gold from a natural village chest, obtains one log, uses batched **drop duplication**, guides/trades with a cleric, crafts two golden pickaxes, two golden axes and one golden shovel, obtains a bed, travels to a stronghold and enters the End. The default finishes with a simulated credits warp; `@tasloot end` stops at first End entry. This is private-world assisted automation, not an unmodified speedrun or a real low-Xss exploit.

The original chest is only a loot source. Gold, wood, emeralds, redstone, Eyes of Ender and later duplicated resources use drops; the bot collects and verifies each batch before continuing. `@dupe chest ...` is accepted as an old spelling but now produces drops. Ordinary chest looting and inventory deposits still work.

## NON-SPEEDRUN — interface and practice

`@remove hud` hides the Botas timer/task overlay. `@hud on` restores it. Minecraft's hotbar, health, hunger, chest and trading screens remain visible. A space after `@` is accepted.

Parkour arenas/rehearsal, generated test courses, Creative stronghold searches, mold inspection, HUD/gamma controls, other development utilities are labeled NON-SPEEDRUN in the feature guide. Route/resource actions and assisted TAS operations have separate labels.

