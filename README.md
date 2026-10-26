# Velorise Simple Map

<p align="center">
  <a href="https://modrinth.com/mod/velorise-simple-map"><img src="https://img.shields.io/badge/Modrinth-Available_Now-00af5c?style=for-the-badge&logo=modrinth&logoColor=white" alt="Modrinth" /></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/velorise-simple-map"><img src="https://img.shields.io/badge/CurseForge-Available_Now-f16436?style=for-the-badge&logo=curseforge&logoColor=white" alt="CurseForge" /></a>
  <a href="https://github.com/hungbbdzz/Simple-Map"><img src="https://img.shields.io/badge/GitHub-Repository-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub" /></a>
  <a href="https://github.com/sponsors/hungbbdzz"><img src="https://img.shields.io/badge/Sponsor-GitHub_Sponsors-ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="GitHub Sponsors" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Release-v2.0.1-brightgreen.svg" alt="Release" />
  <img src="https://img.shields.io/badge/Minecraft-1.21.1%20%7C%201.20.1-blue.svg" alt="Minecraft Versions" />
  <img src="https://img.shields.io/badge/Loaders-NeoForge%20%7C%20Fabric%20%7C%20Forge-orange.svg" alt="Mod Loaders" />
</p>

A modern, lightweight and vanilla-friendly Minecraft map mod featuring an all-in-one Minimap and World Map, 3D Cave mapping, accurate block and biome tinting, optimized GPU framebuffer rendering, and physical Map Book sharing.

---

## 📦 Multi-Loader Project Architecture

This repository contains all official editions of Velorise Simple Map:

| Subfolder | Minecraft Version | Mod Loader | Java | Status | Release Artifact |
| :--- | :---: | :---: | :---: | :---: | :--- |
| [`neoforge-1.21.1/`](neoforge-1.21.1/) | **1.21.1** | NeoForge 21.1.65+ | Java 21 | Stable Release | `simplemap-1.21.1-neoforge-2.0.1.jar` |
| [`fabric-1.21.1/`](fabric-1.21.1/) | **1.21.1** | Fabric Loader 0.16.9+ | Java 21 | Stable Release | `simplemap-1.21.1-fabric-2.0.1.jar` |
| [`forge-1.20.1/`](forge-1.20.1/) | **1.20.1** | Forge 47.3.0+ | Java 17 | Stable Release | `simplemap-1.20.1-forge-2.0.1.jar` |
| [`fabric-1.20.1/`](fabric-1.20.1/) | **1.20.1** | Fabric Loader 0.16.9+ | Java 17 | Stable Release | `simplemap-1.20.1-fabric-2.0.1.jar` |

---

## ⚡ Key Architectural Features (v2.0.1)

1. **Retained Framebuffer Geometry Pipeline**:
   - Zero-allocation NDC circular winding geometry eliminates standard stencil flushes and buffer recreation spikes.
   - Paced partial-tick rotation with sub-pixel interpolation.

2. **Universal Block Color & Foliage Engine**:
   - Dynamic `BakedModel` quad inspection and `BlockColors` provider probing.
   - 100% color accuracy for all vanilla and modded blocks, grass, leaves, and custom biome foliage across all loaders.

3. **Multi-Band Cave Engine**:
   - Layer-sliced cave visualization with instant (0 ms) brightness cycling and zero CPU alloc per step.
   - Seamless Anvil `.mca` chunk reconstruction for offline and remote world exploration.

4. **Map Book Sharing System**:
   - Craftable physical Map Books (`book_map`) allowing players to record and trade explored territories.
   - Fully compatible with vanilla servers and modded servers.

---

## 🛠️ Build & Dev Instructions

### 1. Build All 4 Loaders in One Step
Run the master PowerShell automation script from the root folder:
```cmd
build-all.bat
```
* Built `.jar` files will automatically be assembled and copied into `mods/`.
* Requires JDK 21 (`JAVA21_HOME`) and JDK 17 (`JAVA17_HOME`).

### 2. Build or Run a Specific Loader
Navigate into any subproject folder and run Gradle:

```cmd
# NeoForge 1.21.1 (Java 21)
cd neoforge-1.21.1
gradlew build
# or run client: ..\scripts\run-neoforge.bat
# or run debug client: ..\scripts\run-neoforge-debug.bat

# Fabric 1.21.1 (Java 21)
cd fabric-1.21.1
gradlew build
# or run client: ..\scripts\run-fabric.bat 1
# or run debug client: ..\scripts\run-fabric-debug.bat

# Forge 1.20.1 (Java 17)
cd forge-1.20.1
gradlew build
# or run client: ..\scripts\run-forge-1.20.1.bat
# or run debug client: ..\scripts\run-forge-1.20.1-debug.bat

# Fabric 1.20.1 (Java 17)
cd fabric-1.20.1
gradlew build
# or run client: ..\scripts\run-fabric.bat 2
# or run debug client: ..\scripts\run-fabric-1.20.1-debug.bat

# Unified Master Client Launcher (Root):
run.bat
```

### 3. Debug & Telemetry Controls
When launched via any of the debug scripts:
* **`F8`**: Toggle in-game telemetry HUD overlay (FPS, Frame ms, Heap/GC, exact pipeline queues, FBO status).
* **`F9`**: Toggle 1 Hz metrics recording to CSV.
* **`F10`**: **Mark Debug Issue**: Triggers 20-second burst capture (250 ms rate) and records an explicit `USER_MARKER` event into `events.jsonl` with an immediate file flush.
* Telemetry files are written into `<loader>/run/simplemap-debug/<session>/` (`metrics.csv` & `events.jsonl`).

### 4. Known Issues & Diagnostic Notes
See [`BUG_NOTES.md`](BUG_NOTES.md) for full technical diagnosis and reproduction notes regarding multi-loader port status.

---

## 📄 License
Licensed under the [MIT License](LICENSE).
