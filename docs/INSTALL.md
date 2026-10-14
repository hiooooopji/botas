# Installing Botas 1.8

## Required

1. A **Minecraft Java 1.8** instance using **Legacy Fabric**. This is exact 1.8, not 1.8.9, Forge, or modern Minecraft.
2. **Fabric Loader 0.16.2**, the loader version pinned by this build. Install it through a Legacy Fabric-compatible launcher/profile.
3. `botas-1.8-0.3.0.jar` in that instance's `mods` folder.

**No other mod JAR is required.** The build has no Fabric API or Baritone dependency. Mixin/MixinExtras and legacy game libraries shown in the development startup log are supplied by the loader/development environment, not extra Botas mods to install manually. You do not need LoTAS, Carpet, OptiFine, a separate tick controller, or the old AltoClef JAR.

Remove the old `altoclef-1.8-legacy-*.jar` and any older Botas JAR from this instance. The new metadata rejects the old `altoclef_legacy` mod to prevent two bots intercepting the same commands. Do not put the 1.7.10 build in a 1.8 instance.

Optional mods are not required or verified by this release. Start with just Botas when diagnosing a problem.

The mod targets Java 8 bytecode. Development runs use Java 21 and Legacy Fabric's game libraries; building with Gradle 8.8 needs Java 17 or newer. Use the Java runtime supported by your installed Legacy Fabric launcher profile.

## First use

Join a world and type `@help`. Commands are intercepted locally.

```text
@get stone_pickaxe
@overworld
@tasloot end
@tasloot
@remove hud
@hud on
@stop
```

`@overworld` prepares supplies; `@overworld nether` also builds and enters a Nether portal. `@tasloot` is a separate assisted route. It does not reset seeds. Original loot and an accessible cleric must be found in the world.

TAS, player rollback, controlled drops, mob assistance and seed inspection require a **private integrated-server world**. They are not multiplayer-server exploits. Publishing a world to LAN disables the private-server facilities. The automatic TAS route enables its own resource duplication; ordinary acquisition only duplicates after `@autodupe on`.

## HUD

`@remove hud` and `@hud off` hide only the Botas task/timer overlay. `@hud on` restores it. The vanilla hotbar, health, hunger, held item, chest and trading interfaces remain visible. This setting lasts for the current client session. Both `@remove hud` and `@ remove hud` work.

## Distribution verification

The release JAR must contain `fabric.mod.json` with id `botas`, display name `Botas 1.8`, Minecraft dependency `1.8`, and version `0.3.0`. Development-client testing does not by itself prove compatibility with every external launcher or optional mod.
