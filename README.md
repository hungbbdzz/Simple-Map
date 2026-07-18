# Simple Map

A lightweight, vanilla-style world map and minimap mod for Minecraft 1.21.1, built on NeoForge.

Simple Map is designed to fit seamlessly into the base game without bloated UIs or complicated configuration files.

---

## Features

### World Map
- Full-screen world map opened from inventory (requires a Map Book item)
- Smooth panning (click and drag) and zooming (scroll wheel, zoom-to-cursor)
- North-up orientation by default
- Compact cursor coordinates with optional biome; over water, Y shows bottom and surface level
- Live player and cursor coordinates
- Reset button to quickly re-center on the player's position and reset zoom to 1.0x
- Night mode is controlled directly from the world-map toolbar
- When the server sets Cave Map to ON, the world map exposes an Auto/Manual control and a full-height Y slider
- Extended zoom range (0.0125x to 12.0x) to view your entire explored world smoothly

### Minimap HUD
- Always-on minimap overlay during gameplay (top-right by default, draggable)
- **Two shapes (toggle in settings):**
  - **Circular Shape (Default):** Premium circular layout with sleek border ring and rotating compass directions (N, E, S, W) for intuitive navigation. North is highlighted in red.
  - **Square Shape:** Sleek rectangular layout with scaling borders.
- Two rotation modes (toggle in settings):
  - Rotate map: minimap spins, player icon always faces up
  - Rotate icon: map stays North-up, player icon rotates
- Coordinate display below the minimap (scale and position are draggable)
- Scaling borders matching the minimap HUD size
- Anchor-based placement that stays stable when the GUI scale or window size changes
- Optional minimap display over inventory, pause, chat, and container screens
- A reusable saved-color palette for arrow, ring, compass, and coordinate colors
- Surface block-light glow for lava, lamps, torches, and compatible modded blocks
- Switchable Balanced, Vibrant, Natural, and Contrast map color profiles
- Per-block color overrides from the world-map context menu
- Server-controlled Cave Map modes: OFF (surface only, with a restricted downward projection in ceiling dimensions), AUTO (stable Full Cave map underground), and ON (AUTO plus manual Top-Y projection)

### Pin Navigation
- Left-click anywhere on the world map to drop a navigation pin (red X marker)
- Dashed white line drawn from your position to the pin on both the map and minimap
- Pin persists when you close the map so the minimap continues guiding you
- Live distance labels (e.g., 234m or 1.2k blocks for far targets)
- Pin clamps to the minimap edge when the target is off-screen (compass-arrow style red dot centered on the border line)
- Left-click the active pin again to remove it

### Waypoints and Follow Mode
- Right-click on the map to add waypoints from a context menu
- Choose custom icons from categorized item groups (Mining, Combat, Travel, Food, Search A-Z browser)
- Right-click an existing waypoint to follow it, which copies its coordinates to your active pin navigation
- Right-click an existing waypoint to delete it

### Minimap Settings (In-Game)
Accessible via the "Settings" button on the world map:
- Drag the minimap or coordinate display to any screen position
- Toggle coordinates on/off and adjust scale (default 0.64x)
- Player marker style (arrow or dot) and scale (default 0.6x)
- One shared `#RRGGBB` / `#AARRGGBB` editor with reusable saved colors
- Right-click a loaded map location and choose `Customize Color` to override that surface block
- Existing distant map regions learn their night-light mask as you revisit or refresh them; the
  local light mask is then cached for later sessions and is not exported with a Map Book
- Rotation mode toggle
- Pin scale slider (0.1x to 1.0x, default 0.5x)
- Scan Points/Tick slider to control scanning speed (defaults to a value dynamically calculated based on your system RAM)
- Reset to Default button

---

## Getting Started

### Map Book Mechanics

| Item | Recipe |
|---|---|
| Empty Map Book | Book + Compass + Empty Map (shapeless) |
| Map Book | Created when you save your map data to an Empty Map Book |

* **First-time Save:** Right-click an **Empty Map Book** to upload a snapshot of your client-side explored regions to the server. The book becomes a written **Map Book** with a unique ID and marks you as the owner.
* **Update Map:** If you are the owner, hold the written **Map Book** and **Shift + Right-click** (sneak) to save your newly explored regions into the same book. This updates the book on the server so you don't have to keep crafting new ones. Non-owners cannot update books they do not own.
* **Learn Map (One-Time Use):** If you receive a map book from someone else (or a duplicated copy), **Right-click** it normally to learn/read the map data. The regions they explored will merge with your own local map, and **the book will crumble to dust once learned**.
* **Open Map Screen:** If you are the owner of the book, simply **Right-click** it normally to open the fullscreen world map instantly (you can also press the `M` key).
* **Duplication:** Place a written **Map Book** and an **Empty Map Book** in a crafting table to duplicate the map book so you can share it with friends. The copied book keeps the original owner.

> **Server options:** `requireMapBook` and `caveMapMode` in `config/simplemap-server.json` are authoritative and synced to players. Cave modes are `0 = OFF` (surface only, plus a restricted automatic downward projection in ceiling dimensions), `1 = AUTO` (Full Cave cache while underground), and `2 = ON` (AUTO plus manual Top-Y selection on the world map).

In ceiling-only dimensions such as the Nether there is no trustworthy overworld-style
surface. If the server sets Cave Map to `OFF`, Simple Map therefore projects each permitted
scan column from the player's automatic Top Y down to the dimension floor and merges it
into the same persistent Full Cave cache. It does not expose manual Y selection, but moving
between height bands no longer swaps or resets the displayed Nether map.

---

## Controls

| Action | Input |
|---|---|
| Open world map / Learn map | Right-click Map Book |
| Update map book | Shift + Right-click Map Book |
| Pan map | Click and drag |
| Zoom in / out | Scroll wheel |
| Place / remove pin | Left-click on map |
| Open waypoint menu | Right-click on map |
| Toggle waypoints | "WP: ON/OFF" button |
| Toggle global night rendering | "Night: OFF/AUTO/ON" button |
| Open minimap settings | "Settings" button |
| Reset center and scale | "Reset" button |

Every dimension has a separate Full Cave cache. Underground scans project loaded columns
from Top Y down to the dimension floor, selecting the first exposed floor, fluid, or
visible emitter. Work is scaled by projection depth and observations merge into a stable
composite, so ordinary vertical movement does not replace the whole displayed map.

The System tab keeps `Render: DOTS/NORMAL` beside `Scan Points/Tick`. DOTS preserves
Simple Map's random speckled signature inside an adaptive circular radius. NORMAL scans
the nearest loaded square chunks without circular clipping, then moves outward. Moving
quickly contracts the active range; stopping lets it expand again. Both modes share the
same cache and work budget.

When the server sets Cave Map to `ON`, the world-map control cycles `OFF -> LAYER ->
FULL`. OFF keeps the surface map. LAYER treats Top Y as a ceiling and projects deeply
downward; moving the slider only commits a new layer after release, and the lowest slider
position restores `Top Y: AUTO`. A selected layer is drawn over the accumulated Full Cave
cache, so changing Y never blanks the known cave map while the new projection fills in.
FULL shows the surface cache outdoors and automatically switches to that accumulated cave
cache underground. The three most recently viewed layers stay in memory, while every
layer remains persisted on disk.

Map settings retain the original colour-style profiles and relief shading, with optional
flowers and an optional cursor biome line. Over water, cursor Y reports the actual bottom
and shows the water surface level in parentheses.

---

## Performance

Simple Map is designed to be lightweight by default:

- Scans client-side chunk data only, requiring zero server-side processing.
- Scanning runs in small configurable bursts per tick to avoid stutters.
- Waypoint icons reuse vanilla item textures already loaded in memory to save assets.
- The pin marker uses the vanilla map icons sprite sheet.
- File existence checks are offloaded to background threads to guarantee lag-free zooming.
- Clean LRU (Least Recently Used) caching for both region files and OpenGL textures, capping memory usage at under 128 MB.
- Dynamic scan rate settings that adjust based on detected system RAM.
