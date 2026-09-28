# Briefest Boxer

Client-side aiming guide for Minecraft Java Edition. The Fabric renderers for 1.16.5–1.21.1 and Forge renderers for 1.20.1–1.21.1, plus both 26.2 loader builds, select the nearest visible entity surface from the camera and draw a smooth, reach-clipped colored cover on its interpolated hittable bounds. The 26.2 renderer fills the camera-facing reachable surface immediately with the configured color. On version 26.2, the mod also previews a Sulfur Cube's predicted motion using the cube's body-item archetype and the player's attack attributes and aim angle.

## Release targets

Each row is a separate loader build. Release artifacts are not cross-version compatible.

| Minecraft | Loader target | Entity guide | Sulfur Cube path | Build status |
| --- | --- | --- | --- | --- |
| 1.6.4 | Legacy Fabric | Yes | — | Client starts and Fabric lists the mod; legacy logging does not confirm initializer or mixin status |
| 1.7.10 | Forge and Legacy Fabric | Yes | — | Forge and Fabric builds succeed; Forge build uses Java 8-compatible bytecode |
| 1.8.9 | Forge and Legacy Fabric | Yes | — | Forge build succeeds; Fabric client launched, host lock prevents visual inspection |
| 1.9.4 | Legacy Fabric | Yes | — | Clean build succeeded |
| 1.10.2 | Legacy Fabric | Yes | — | Client log confirms mod initialization, tick mixin application, and resource loading |
| 1.11.2 | Legacy Fabric | Yes | — | Clean build succeeded |
| 1.12.2 | Forge and Legacy Fabric | Yes | — | Forge and Fabric builds succeed; ARM narrator library prevents Fabric client verification on this host |
| 1.14.4 | Fabric | Yes | — | Fabric build succeeded; client registered mod and reached resource loading |
| 1.15.2 | Fabric | Yes | — | Fabric build succeeded; client registered mod and reached resource loading |
| 1.16.5 | Forge and Fabric | Yes | — | Forge and Fabric builds succeed; Fabric client-launched |
| 1.17.1 | Fabric | Yes | — | Fabric build succeeded; client registered mod and reached resource loading |
| 1.18.2 | Fabric | Yes | — | Fabric built and client-launched |
| 1.19.2 | Fabric | Yes | — | Fabric built and client-launched |
| 1.20.1 | Forge and Fabric | Yes | — | Both loader builds succeed; Fabric client-launched |
| 1.21.1 | Forge and Fabric | Yes | — | Forge and Fabric builds succeed; both dev clients launch and load the mod |
| 26.2 | Forge and Fabric | Yes | Yes | Forge and Fabric builds succeed; both dev clients initialize; Fabric also loads Sodium and Mod Menu; visual inspection awaits an unlocked host |

The target list covers established modded release hubs rather than every patch release. Legacy Fabric supplies older targets. Forge builds are included where requested; NeoForge projects are excluded from the release script and bundle.

Version 0.1.0 distributable jars are collected in [`releases/0.1.0`](releases/0.1.0), with SHA-256 hashes in `SHA256SUMS`. Install only the jar matching both your Minecraft version and loader. These are locally built artifacts; they have not been published to a mod hosting site. Run [`scripts/build-releases.sh`](scripts/build-releases.sh) to clean-build the loader/version matrix and refresh the bundle. The build matrix uses Java 8 for 1.8.9, Java 17/21 for later targets, and Java 25 for 26.2; set `BRIEFEST_BOXER_JAVA8_HOME`, `BRIEFEST_BOXER_JAVA17_HOME`, `BRIEFEST_BOXER_JAVA21_HOME`, and `BRIEFEST_BOXER_JAVA25_HOME` if those JDKs are not discoverable.

## Install and use

These are client-side mods. Put the matching jar in the Minecraft instance's `mods` folder and launch that same Minecraft version with the listed loader. Fabric versions that declare Fabric API as a dependency also need the matching Fabric API release. Forge jars require Forge for their exact Minecraft version.

In a multiplayer world, the Fabric builds from 1.16.5 through 1.21.1, Forge builds 1.20.1 and 1.21.1, and both 26.2 builds select the closest visible entity from the camera position. They fill only the reachable portions of the entity hitbox with a smoothly clipped colored cover, so the highlight grows as more of the hitbox enters interaction reach and stays within its bounds. Target selection has a small distance hysteresis to prevent rapid switching between nearly equidistant entities. The 26.2 cover overlays the entity model and checks each patch against block collision shapes, allowing visibility through non-colliding foliage; the older renderers use world depth testing. Player and other-entity toggles, scan range, and colors are configured in `config/briefest_boxer.properties`; on 26.2 Fabric, the same settings are available from the Mods screen through Mod Menu 20.0.3 or newer. Other legacy builds, including Forge 1.16.5, still use their earlier aim-marker renderers. On 26.2, aiming at any Sulfur Cube shows a layered, thicker predicted path and enlarged endpoint marker. The default preview horizon is 128 ticks (6.4 seconds), with longer and shorter choices in the config. The flight prediction clips the cube's swept hitbox against the world's block collision shapes and models ground friction and bounce. Cubes without a body item use vanilla knockback; equipped cubes use their matching archetype knockback, with held-weapon damage, knockback effects, attack-direction angle rotation, and aim-height transfer. A short target grace period prevents one-frame aim-result changes from flickering the path. The estimate also uses the cube's current motion and the player's attack cooldown and aim angle. It is client-side and does not require other players to install the mod.

## Architecture

The project is split into version-specific loader adapters and shared, loader-independent aiming/prediction logic. Adapters own entity/model queries, rendering hooks, attack-attribute lookup, and version-specific Sulfur Cube physics access. Older builds must not ship cube-specific code or references.

## Current status

Fabric adapters for 1.14.4 through 26.2 and Forge adapters for 1.7.10 through 26.2 build and package. A clean run of the complete Fabric/Forge release matrix succeeded. Fabric 1.16.5–1.21.1 and Forge 1.20.1/1.21.1 use camera-based reachable hitbox surface shading; the 26.2 Fabric and Forge adapters use the game's gizmo renderer, overlay the model, and check each patch against block visibility. The 26.2 surface mesh batches its geometry to reduce per-frame overhead. The 26.2 Fabric dev client entered the saved singleplayer world with Briefest Boxer, Sodium 0.9.2-alpha.4, and Mod Menu 20.0.3 loaded; the Forge dev client also initializes the mod. Shared-core tests cover coverage near the reach boundary, hitbox and reach bounds, wall-collision clipping, vanilla knockback for empty cubes, and ground friction. Rendered in-world visuals still need inspection because the Minecraft window was not exposed to the available desktop automation. Forge 26.2 development uses ForgeGradle's merged source-set setting and a macOS first-thread JVM flag; its launcher was run with Java 25 for the game. The 1.21.1 Forge development run now merges resources with compiled classes, and its runtime log confirms the mod class is loaded. OptiFine compatibility remains unverified. The 26.2 trajectory estimate models the cube's separate default-damage and player-knockback impulses, cube knockback scaling, post-attack horizontal damping, attack cooldown, held-item damage, critical hits, sprint knockback, cube archetypes, existing cube velocity, block friction, collisions, and bounce. Effects that cannot be resolved from client-side state remain estimates.
