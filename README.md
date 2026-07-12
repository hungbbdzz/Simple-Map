# Simple Map

A lightweight, vanilla-style world map and minimap mod for Minecraft 1.21.1, built on NeoForge.

Simple Map is designed to fit seamlessly into the base game without bloated UIs or complicated configuration files.

---

## Features

### World Map
- Full-screen world map opened from inventory (requires a Map Book item)
- Smooth panning (click and drag) and zooming (scroll wheel, zoom-to-cursor)
- North-up orientation by default
- Block Y-level tooltip: hover anywhere to see the surface block coordinates and name (shows "???" for unloaded chunks)
- Live player and cursor coordinates
- Reset View button to quickly re-center on the player's position and reset zoom to 1.0x
- Extended zoom range (0.0125x to 12.0x) to view your entire explored world smoothly

### Minimap HUD
- Always-on minimap overlay during gameplay (top-right by default, draggable)
- Two rotation modes (toggle in settings):
  - Rotate map: minimap spins, player icon always faces up
  - Rotate icon: map stays North-up, player icon rotates
- Coordinate display below the minimap (scale and position are draggable)
- Scaling borders matching the minimap HUD size

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
Accessible via the "Minimap Config" button on the world map:
- Drag the minimap or coordinate display to any screen position
- Toggle coordinates on/off and adjust scale (default 0.64x)
- Player marker style (arrow or dot) and scale (default 0.6x)
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

> **Server option:** `requireMapBook` in `simplemap/config.json` controls whether players must hold the Map Book in their inventory to view the map, or if it is always accessible after crafting once.

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
| Toggle waypoints | "Waypoints: SHOW/HIDE" button |
| Open minimap settings | "Minimap Config" button |
| Reset center and scale | "Reset View" button |

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
