# Changelog

All notable changes to **Simple Map** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.0.4] - 2026-11-08

### Fixed
- **[Forge & NeoForge]** Fixed a critical crash (`Registry Object not present`) occurring when crafting any item in certain multiplayer or connector environments ([#6](https://github.com/hungbbdzz/Simple-Map/issues/6)).
- **[Forge & NeoForge]** Added defensive registry checks across recipes (`CopyMapBookRecipe`, `MergeMapBooksRecipe`) and creative inventory tabs (`BuildCreativeModeTabContentsEvent`).
- **[Forge & NeoForge]** Added safe guard checks in `hasMapBookInInventory` to prevent registry lookups on unbound items.

### Optimized
- **[Fabric]** Optimized crafting event performance by skipping client-side processing early, preventing unnecessary NBT tag copying on the render thread.

---

## [2.0.3] - 2026-11-02

### Added
- 1:1 9-slice vanilla frame style for minimap and full map UI.
- Smart screen & F3 minimap auto-visibility rules.
- 8-neighbor MCA chunk scanning validation to scrub bald boundary chunks and cleanly drain pending saves.

### Changed
- Refactored settings GUI layout to be fully responsive across all GUI scale factors without overlapping bottom buttons.
- Optimized surface LOD hierarchy traversal to maintain consistent 120+ FPS.

---

## [2.0.2] - 2026-10-28

### Fixed
- Restored true 64-segment circular minimap mesh rendering across all 4 loaders.
- Fixed viewport freeze and minimap render desync when panning at far zoom levels.

---

## [2.0.1] - 2026-10-15

### Added
- Complete multi-loader release suite: **Fabric 1.20.1**, **Fabric 1.21.1**, **Forge 1.20.1**, and **NeoForge 1.21.1**.
- Automatic cave layer mapping and outdoor transition scanner.
- Map Book exploration sync pipeline and merging mechanics.
- In-game coordinate link sharing in chat with one-click waypoint creation.
