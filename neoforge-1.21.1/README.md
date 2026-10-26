# Simple Map

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)]()
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/badge/Release-v2.0.0-blue.svg)]()

**Simple Map** is a high-performance, exploration-driven world map and minimap mod for Minecraft. It combines accurate GPU-sampled block colors, persistent layered cave mapping, waypoints, and an optional server-side Map Book progression system—all optimized for a silky-smooth **120+ FPS** experience with zero frame tearing.

---

## ✨ Highlights

- **Silky Smooth 120+ FPS Panning:** Retained GPU framebuffer compositor and spatial $O(1)$ page tables eliminate stutters and screen tearing.
- **Universal Mod Compatibility:** Automatically extracts true in-game colors and biome tints for *any* modded block directly from compiled GPU models (`BakedModel`).
- **Deep Cave Exploration:** Full subterranean network mapping, automatic roof detection, manual Top-Y layers, and bedrock depth (`Y <= 29`) scanning in the Nether and Overworld.
- **Instant Lighting (0 ms):** Cycle between Day, Night, and Cave lighting instantly via shaders without reloading regions or LODs.
- **Anvil (`.mca`) World Recovery:** Automatically discovers and backfills explored regions on pre-existing worlds across all dimensions.
- **Customizable Minimap:** Round or Square shapes, rotating map or player pointer, North-lock, waypoint icons, and drag-and-drop HUD placement.
- **Waypoints & Navigation Pins:** Item-icon browser, live distance/direction radar, death markers, and right-click context menu editing.
- **Map Book System:** Optional vanilla-friendly progression: save, copy, merge, and learn maps across multiplayer servers.

---

## 🗺️ World Map

Press `M` to open the full-screen world map.

- **Navigation:** Click and drag to pan; scroll to zoom smoothly toward the mouse cursor.
- **Quick Re-Center:** Shift + Left-click the Refresh button to instantly center on your player and reset zoom to `1.0x`.
- **Right-Click Context Menu:**
  - Place or clear temporary navigation pins.
  - Create, edit, follow, share, or delete waypoints.
  - Teleport to coordinates (when permissions allow).
  - Set custom block color overrides.
- **Hover Inspector:** Live cursor coordinates (X, Y, Z), block identity, and biome name.
- **Chat Coordinate Sharing:** Click the bottom-right coordinate badge to paste your current location into chat. Clicking shared coordinates in chat opens the map directly at that spot (`/simplemap_view`).
- **Dimension Switcher:** Seamlessly view any visited dimension with proportional coordinate scaling (1:8 between Nether and Overworld).

---

## 🧭 Minimap

A lightweight HUD minimap rendered from the same explored data as the world map.

- **Shapes & Modes:** Toggle between **Square** and **Circular** shapes. Choose between a rotating map or a fixed-North rotating pointer (`L` key).
- **Free Placement:** Drag-and-drop the minimap anywhere on screen with automatic resolution and GUI-scale anchoring.
- **Visual Customization:** Configurable size (16–150 px), independent zoom, opacity, border ring colors, compass letters (`N`, `E`, `S`, `W`), and player arrow/skin styles.
- **Screen Overlay:** Optionally keep the minimap visible while viewing inventories, containers, chat, or pause screens.
- **Radar & Clamping:** Navigation pins and waypoints clamp smoothly to the minimap edge with live distance indicators.

---

## 🏔️ Cave Mapping

Underground mapping features dedicated roof detection and vertical volume rendering:

| Server Mode | Behavior |
|:---|:---|
| `OFF` | Surface mapping only. |
| `AUTO` | Automatically engages layered cave mapping when a solid ceiling/roof is detected above the player (including Nether & End ceilings). |
| `ON` | Full player control with `OFF`, `LAYERED`, and `FULL` view options plus manual Top-Y slider selection. |

- **Layered Mode:** Renders a bounded vertical slice below the selected Top-Y level. Auto Top-Y smoothly tracks player elevation in stable bands.
- **Full Cave View:** Projects deep cave systems, mining shafts, and Ancient Debris tunnels down to bedrock (`Y <= 29`).
- **Seamless Transitions:** Previous cave layers remain visible during layer switching until new data is ready—eliminating black screen flashes.
- **0 ms Cave Lighting:** Toggle between `OFF` (clear visibility), `AUTO` (environmental shading), and `ON` (atmospheric darkening) instantly on the GPU.

---

## 🎨 Universal Color & Terrain Engine

Simple Map features an advanced rendering engine that supports vanilla and all modded blocks out of the box:

- **GPU BakedModel Inspection:** Samples exact textures and colors from compiled block models (`Direction.UP` prioritized).
- **NeoForge BlockColors Probing:** Automatically detects and applies modded color providers (`BIOME_FOLIAGE`, `BIOME_GRASS`, and custom dynamic tint resolvers).
- **Achromatic Foliage Protection:** Identifies grayscale template textures (such as *Socotra Leaves* in Regions Unexplored) and applies correct biome coloration while preserving pre-colored vibrant foliage (Cherry, Autumn leaves).
- **Color Modes:**
  - `Accurate`: High-detail texture-sampled terrain with biome blending and translucent water depth.
  - `Vanilla`: Traditional built-in Minecraft map colors.
- **Terrain Relief:** Optional `OFF`, `2D`, or `3D` hillshading slopes for realistic landscape depth.
- **Visual Profiles:** Switch between `Balanced`, `Vibrant`, `Natural`, and `Contrast` color palettes.

---

## 📍 Waypoints & Navigation Pins

- **Quick Waypoints:** Press `U` to open the Waypoint Manager, or right-click anywhere on the map to create or edit a waypoint.
- **Categorized Icon Browser:** Select icons from vanilla creative categories or search alphabetically (A–Z).
- **In-World Beacons:** Waypoints render customizable beacon rays, distance labels, and minimap icons.
- **Death Markers:** Automatically marks your death position with configurable history limits.
- **Temporary Pins:** Left-click on the map to drop a temporary navigation beacon. Pins automatically clear upon arrival (within 5 blocks).

---

## 📖 Map Book System

An optional, vanilla-friendly progression mechanic designed for survival and multiplayer servers:

| Item | Crafting / Purpose |
|:---|:---|
| **Empty Map Book** | Crafted with **Book + Compass + Empty Map** (shapeless). Right-click to record all your explored regions into a new Map Book. |
| **Map Book** | Stores explored map data. Shift + Right-click to update newly explored terrain into the book. Non-owners can right-click to **Learn** and absorb the map data into their client. |

- **Copying:** Combine a written Map Book with an Empty Map Book to create duplicate copies.
- **Merging:** Craft two written Map Books together to combine exploration data from both players.
- **Server Enforcement:** When `requireMapBook` is enabled on a server, players must carry a valid Map Book in their inventory to access the map or minimap.

---

## ⚙️ Configuration

### Client Settings (In-Game GUI)
Access via the map toolbar gear icon or mod menu. Divided into 4 intuitive tabs:
- **Map:** Minimap toggle, shape, size, zoom, rotation, pointer style, screen overlay, and waypoint radar.
- **Coords:** Coordinate HUD toggle, scale, positioning, cursor biome, and cursor block display.
- **Colors:** UI color picker, preset palettes, custom hex values, map profile, and per-block overrides.
- **System:** Block color pipeline (`Accurate`/`Vanilla`), 2D/3D slopes, flower rendering, scan budgets, and death waypoints.

### Server Configuration (`config/simplemap-server.json`)
```json
{
  "requireMapBook": false,
  "caveMapMode": 0
}
```
- `requireMapBook`: `true` forces players to own and carry a Map Book to use map features.
- `caveMapMode`: `0 = OFF` (Surface only), `1 = AUTO` (Automatic roof detection), `2 = ON` (Full player control).

---

## ⌨️ Controls & Keybinds

| Action | Default Key | Description |
|:---|:---:|:---|
| **Open World Map** | `M` | Opens the full-screen world map |
| **Toggle Minimap** | `N` | Toggles minimap HUD visibility |
| **Toggle North Lock** | `L` | Locks minimap orientation to North |
| **Waypoint Manager** | `U` | Opens the waypoints list and editor |
| **Zoom In / Out** | `= / -` | Adjusts zoom level (or use mouse scroll) |
| **Pan Map** | Left-Click Drag | Drags and pans across explored terrain |
| **Place / Clear Pin** | Left-Click | Drops or removes temporary navigation pin |
| **Context Menu** | Right-Click | Opens context menu on map or waypoint |
| **Re-Center on Player** | Shift + Refresh | Centers map on player and resets zoom to 1.0x |
| **Cycle Brightness** | Toolbar Sun/Moon | Instantly toggles Day / Auto / Night lighting |

*Additional functions (Cave mode cycle, shape toggle, quick add waypoint) can be bound in Minecraft's **Controls > Key Binds** menu.*

---

## 📦 Installation & Build

### Requirements
- **Minecraft:** `1.21.1`
- **Mod Loader:** `NeoForge 21.1.65+`
- **Java:** `21`

### Build from Source
```bash
# Linux / macOS
./gradlew build

# Windows
.\gradlew.bat build
```
Built `.jar` artifacts are located in `../mods/` or `build/libs/`.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).  
Copyright © 2026 **Velorise**.
