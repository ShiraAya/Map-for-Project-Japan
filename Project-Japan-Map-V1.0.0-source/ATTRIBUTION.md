# Project Japan Map V1.0.0 — Attribution

This file preserves the source and reference attribution carried by the pre-release PJM source tree.

## Bundled geography data

### Cities

Normalized nationwide city / special-ward centroids were prepared from:

- `yutanakamurajp/JapanCityLocationData`
- source file: `市区町村緯度経度.csv`

Rows belonging to a designated city's wards were grouped to the parent city. Tokyo special wards remain independent city-level labels in the bundled source data; Project Japan Map may present them at a broader Tokyo label level in the UI.

### Marine regions, mountains and islands

Normalized subsets were prepared from the machine-readable edited `Gazetteer of Japan` published by **sorami**. The underlying Gazetteer was originally compiled by the **Geospatial Information Authority of Japan (GSI)**.

Project Japan Map includes only the fields it needs, such as real names, latitude and longitude, and further filters / normalizes the source material for the mod.

Famous non-four-main-island reference points are normalized from the same Gazetteer dataset. Their in-game footprint is derived from current Project Japan land / ocean data rather than copied as static geometry.

## Waypoint interaction references

The waypoint rework uses established public user-facing interaction patterns as references, primarily:

- Xaero's Minimap / World Map
- Xaero Minimap Translations
- XaeroPlus

Reference pages used by the pre-release implementation notes:

- https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap
- https://github.com/thexaero/xaero-minimap-translations
- https://github.com/rfresh2/XaeroPlus

**No Xaero implementation code was copied.** Project Japan Map implements its waypoint renderer, storage and UI against Forge 1.20.1 using its own code. Xaero / XaeroPlus are referenced only for established user-facing interaction patterns described above.

## Project Japan

Project Japan Map can synchronize map terrain and hydrology with the currently loaded Project Japan runtime through its exposed sampling APIs. Project Japan data is not bundled as a copied river / lake coordinate catalogue in this repository.
