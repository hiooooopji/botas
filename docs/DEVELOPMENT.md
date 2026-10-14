# Building Botas

Use Java 17 or newer for Gradle 8.8. Both mod builds target Java 8.

```sh
# Build both versions
bash gradlew build

# Build or run Minecraft 1.8
bash gradlew -p versions/1.8 clean build
bash gradlew -p versions/1.8 runClient

# Build the separate 1.7.10 port
bash gradlew -p versions/1.7.10 build
```

The first build downloads Gradle, Minecraft, mappings and Legacy Fabric dependencies. Later builds can use `--offline` when these dependencies are cached. Distribution JARs are in each version's `build/libs` directory; install the normal JAR, not the sources JAR.

## Source layout

- `versions/1.8/src/main`: primary bot, assisted TAS, navigation and user commands.
- `versions/1.7.10/src/main`: separate legacy port.
- `src/main/java/adris/altoclef/eventbus`: shared event classes copied by each build.
- `docs`: installation, commands and build instructions.
- `gradle/wrapper`: reproducible Gradle launcher.

Developer tests, seed-search programs, thumbnails, local worlds, recordings, logs and build caches are excluded from the published source. Normal gameplay commands, including parkour practice and seed-based navigation, remain available. The build compiles and packages the clients; no automated gameplay tests are included.

Internal Java packages and mixin names retain their original names for compatibility. Original MIT attribution is preserved.
