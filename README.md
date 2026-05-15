# FriendLink

FriendLink is an experimental Fabric client mod that backports Minecraft's official Friends / P2P multiplayer flow to Minecraft 1.21.x.

It uses Mojang/Microsoft services for friends presence, official signaling, TURN auth, and WebRTC transport.

FriendLink is an unofficial Minecraft mod. It is not approved by, endorsed by, or associated with Mojang Studios or Microsoft.

## Status

Experimental. The current build is being migrated from the official newer multiplayer flow down to Minecraft 1.21.x.

## Requirements

- Minecraft 1.21.x
- Fabric Loader
- Fabric API
- Microsoft accounts with Minecraft friends / online presence enabled

## Notes

- FriendLink does not include, distribute, or replace Minecraft itself.
- This is not a custom server relay.
- The official flow depends on Microsoft services and may be unstable on restricted or high-latency networks.
- Both clients should use the same mod build.

## Build

```bat
gradlew.bat :versions:Fabric-1.21.1:build
gradlew.bat :versions:Fabric-1.21.2:build
gradlew.bat :versions:Fabric-1.21.11:build
```

Version jars are produced under each version module, for example `versions/Fabric-1.21.1/build/libs/`.

## Project layout

- `shared/src/main/java` contains version-neutral code shared by all supported Minecraft versions.
- `versions/Fabric-1.21.1` contains Fabric resources, dependency versions, and Minecraft/Fabric API adapters for Minecraft 1.21.1.
- `versions/Fabric-1.21.2` contains Fabric resources, dependency versions, and Minecraft/Fabric API adapters for Minecraft 1.21.2.
- `versions/Fabric-1.21.11` contains Fabric resources, dependency versions, and Minecraft/Fabric API adapters for Minecraft 1.21.11.
- To add another Fabric version, copy one `versions/Fabric-*` folder, update its `gradle.properties`, then add it to `settings.gradle`.

## License

FriendLink is licensed under GPL-3.0-only. See `LICENSE`.
