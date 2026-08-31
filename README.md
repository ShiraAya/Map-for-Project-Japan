# Project Japan Map

**Project Japan Map V1.0.0** is a Minecraft Forge 1.20.1 map, minimap, geography HUD and waypoint companion designed for **Project Japan**.

> [!IMPORTANT]
> **This mod is AI-generated and AI-assisted.**  
> Source code, implementation, debugging, refactoring, data-processing logic and documentation have been produced or modified with generative AI under the direction and review of the project author.

## Project Information

- **Release:** Project Japan Map V1.0.0
- **Minecraft:** 1.20.1
- **Forge:** 47.4.20
- **Java:** 17
- **Mod ID:** `pjm`
- **Status:** Active

## Companion Project: Project Japan

Project Japan Map is designed as the companion map and geography interface for **[Project Japan](https://github.com/ShiraAya/Project-Japan)**.

Project Japan provides the terrain, geography and hydrology that Project Japan Map visualizes and samples at runtime.

- **Project Japan** provides the terrain, geography and hydrology.
- **Project Japan Map** provides the full map, minimap, geography HUD and waypoint interface.

For the intended experience, use Project Japan Map together with Project Japan.

## Features

### Full map and minimap

- `M` — open the full map
- `N` — toggle the minimap
- `Z` — change minimap zoom
- runtime synchronization with the currently loaded Project Japan terrain
- runtime river and lake synchronization through Project Japan sampling APIs
- centred minimap coordinates and geography information

### Offline geography HUD

Static geography required by the HUD is bundled in the mod JAR, so normal city / marine / mountain / island labels do not depend on network downloads while Minecraft is running.

Bundled normalized resources include:

- Japanese city / special-ward reference data
- named marine regions
- named mountains
- famous island reference points

River and lake geometry is **not** bundled as a duplicate Project Japan catalogue. Nearby hydrology is sampled dynamically from the loaded Project Japan runtime.

### Geography display behaviour

- nearest named river within 30 blocks
- nearest named lake within 30 blocks
- river and lake labels can be displayed together
- mountain labels use terrain-derived mountain footprints with a limited outside-foot tolerance
- marine labels use type-sensitive ranges so narrow straits and channels remain more local than bays and broad seas
- Tokyo special-ward locations are presented as `东京都` instead of individual ward names

### Waypoints

Project Japan Map includes its own waypoint implementation with a familiar map-mod workflow:

- `B` — create a waypoint
- `U` — open the waypoint manager
- add / edit / delete waypoints
- editable colour and initials / symbol
- hide / show individual waypoints
- distance / name / initials sorting
- safe single-player teleport
- full-map and minimap waypoint icons
- in-world waypoint icons with low-clutter distance display
- waypoint data separated by world / server identity

The interaction model takes inspiration from established Xaero-style waypoint UX, but **no Xaero implementation code is copied**. See `ATTRIBUTION.md` for reference details.

## Building

Requirements:

- Java 17
- Minecraft 1.20.1
- Forge 47.4.20

Windows:

```powershell
gradlew.bat build
```

Linux / macOS:

```bash
./gradlew build
```

The resulting JAR is normally written to:

```text
build/libs/
```

## AI Development Disclosure

Project Japan Map is an **AI-generated / AI-assisted software project**.

Generative AI has been used extensively for tasks including:

- Java source-code generation and modification
- refactoring
- debugging and build-error analysis
- map / HUD / waypoint implementation
- Project Japan runtime integration
- data-processing logic
- code review
- documentation

AI-generated code is not assumed to be correct automatically. Development direction, acceptance decisions and practical testing are controlled by the project author.

## Attribution

Bundled geography data and public interaction references are documented in:

```text
ATTRIBUTION.md
```

Project Japan Map does not claim ownership of third-party geographic source material.

## Disclaimer

Project Japan Map is an independent Minecraft modification project and is not affiliated with or endorsed by Mojang Studios, Microsoft, Xaero, XaeroPlus, the Japanese government, or the organizations providing geographic source data.

## Current Release

**Project Japan Map V1.0.0**
