# Botas

Minecraft automation for **1.8** and **1.7.10**. These are the only retained version targets.

The main 1.8 build supports resource gathering, crafting, smelting, navigation, villager trading, portal preparation and an assisted TAS route. Botas uses its own legacy navigation backend; Baritone is not required.

- [Install the 1.8 JAR and required mods](docs/INSTALL.md)
- [Commands and feature labels](docs/FEATURES.md)
- [Build and project layout](docs/DEVELOPMENT.md)
- [1.8 guide](versions/1.8/README.md)
- [1.7.10 guide and limitations](versions/1.7.10/README.md)

In a private Minecraft 1.8 world, `@tasloot` starts the assisted route. `@tasloot end` stops at first End entry. `@remove hud` hides the Botas overlay; `@hud on` restores it without hiding Minecraft's hotbar or inventory screens. Spaces after `@` are accepted.

TAS duplication uses real dropped items and player-state rollback. The route loots original items from a natural chest, but never returns to that chest to duplicate them. Multiple item types share one drop checkpoint and are collected before proceeding. Every item must first exist in the player's inventory.

The default TAS ending is a **simulated credits warp**, not a dragon kill or the original low-Xss exploit. Its setup, death, bed respawn and second End entry happen in-game, followed by the mod applying the credits result. This is assisted automation, not a legitimate unmodified speedrun.

```sh
bash gradlew -p versions/1.8 clean build
bash gradlew -p versions/1.8 runClient
```

The distribution file is `versions/1.8/build/libs/botas-1.8-0.3.0.jar`. Use the normal remapped JAR, not the sources or development JAR.

Botas derives from AltoClef. Original copyright and MIT license notices remain in [LICENSE](LICENSE); internal Java package names retain their original names for compatibility.
