# Simple Map — Master Roadmap từ v17.4 Alpha đến hệ thống ổn định

## 1. Mục đích tài liệu

Tài liệu này hợp nhất:

- `roadmap.md` lịch sử từ V14.9 đến V17.2;
- `ARCHITECTURE_XAERO_REWORK.md` của V17.4;
- `STATIC_CHECK_REPORT.md` của V17.4;
- các phân tích kiến trúc Surface, Layered Cave, Full Cave, Minimap, LOD, GPU, cache và scheduler;
- các nguyên tắc quan sát được từ source Xaero World Map và Xaero Minimap được cung cấp.

Mục tiêu cuối cùng không phải sao chép Xaero. Mục tiêu là đạt cùng lớp chất lượng:

- map không mất coverage đã biết;
- tải vùng mới không tạo spike lớn trên client/render thread;
- coarse coverage xuất hiện trước, exact refinement xuất hiện sau;
- Minimap luôn ổn định dù Full Map đang tái dựng;
- Layered Cave và Full Cave dùng chung source archive, không quét lại vô ích;
- RAM, native memory và VRAM có hard envelope đo được;
- queue không bao giờ là nơi duy nhất giữ sự thật;
- toàn bộ pipeline chịu được pan/zoom, chuyển dimension, disconnect, resource reload và cache lỗi.

Đây là roadmap theo **quality gate**, không theo ngày. Không được chuyển sang mốc kế tiếp chỉ vì code đã được viết; phải đạt tiêu chí thoát của mốc hiện tại.

---

## 2. Baseline hiện tại: V17.4 Alpha

### 2.1 Những phần đã có

V17.4 đã triển khai tĩnh các nền tảng sau:

- Publication GPU được chuyển từ client tick sang render frame.
- Scheduler tính cả queued cost và active cost.
- Runtime EWMA theo loại công việc.
- Surface LOD và Cave LOD không còn xóa semantic update khi queue đầy.
- Exact page chỉ được eviction bình thường khi branch đã publish coverage tương ứng.
- Branch chỉ được eviction khi parent đã publish child revision tương ứng.
- Surface renderer có ancestor underlay để tránh lỗ đen.
- Atlas slot được cấp muộn khi payload đã GPU-ready.
- Completed payload có thể chờ GPU budget thay vì bị bỏ.
- Surface halo arrays 68×68 có buffer pool.
- Lookup resident exact subtree của surface chuyển sang chỉ mục O(1).
- Cave world-save reconstruction có adaptive concurrency 1–4 transaction.
- Decoded cave source có projection memo nhỏ.

### 2.2 Những phần chưa được xác nhận

V17.4 mới chỉ qua static check. Chưa có bằng chứng runtime cho:

- build NeoForge hoàn chỉnh;
- event hook render có đúng một lần mỗi frame hay không;
- OpenGL upload có stall hay không;
- cache migration và cache corruption;
- race khi chuyển dimension/disconnect;
- p99 frame time;
- allocation rate và GC pause;
- throughput Surface/Cave thực tế;
- driver NVIDIA/AMD/Intel;
- chơi lâu 30–60 phút.

### 2.3 Nợ kiến trúc còn tồn tại

- `MapTextureManager`, `MapRenderer`, `UnifiedCaveTextureManager` vẫn là god class.
- Surface vẫn page-centric 64×64 trong phần source capture/build.
- Nhiều control plane và queue riêng vẫn tồn tại.
- `MapMutationBus` và một số scheduler vẫn có đường từ chối/drop sự kiện.
- LOD parent propagation vẫn còn việc trên render thread.
- Renderer vẫn dùng CPU-built quad batches.
- Minimap chưa có pipeline độc lập hoàn chỉnh.
- Cave archive vẫn object-heavy và lưu màu cuối thay vì material identity.
- Surface/LOD persistence còn nhiều file GZIP nhỏ.
- GPU budget chưa bao trọn toàn bộ atlas, pending payload, CPU retention và native buffers.

---

## 3. Các invariant không được phép phá vỡ

Mọi milestone phải giữ các invariant sau:

1. Vùng đã từng có authoritative source không được trở thành đen chỉ vì GPU eviction.
2. Texture cũ chỉ retire sau khi texture thay thế đã upload và được renderer nhìn thấy.
3. Queue có thể bị xóa hoặc tái tạo mà không làm mất dirty state.
4. Kết quả mang session/style/projection revision cũ không được publish.
5. Minecraft `ClientLevel`, `LevelChunk`, `BlockState` không được đọc tùy ý ngoài client thread.
6. OpenGL chỉ chạy trên render thread.
7. Khi geometry budget đầy, renderer phải hạ LOD chứ không bỏ coverage.
8. Full Cave không xem dữ liệu truncated/partial là complete.
9. Minimap có execution và memory reserve riêng.
10. Hard memory budget phải bao gồm mọi category có thể tăng theo dữ liệu map.
11. Không tăng queue hoặc worker để che lỗi correctness.
12. Mỗi thay đổi hiệu năng phải có số liệu trước và sau.

---

# 4. Tổng quan các milestone

| Mốc | Version đề xuất | Mục tiêu chính | Phụ thuộc | Quy mô |
|---|---|---|---|---|
| M0 | V17.4.1 | Khôi phục build và baseline profiler | V17.4 | M |
| M1 | V17.5 | Correctness core, session và không semantic drop | M0 | L |
| M2 | V17.6 | RegionRecord và unified work graph | M1 | XL |
| M3 | V17.7 | Surface source database và supertile pipeline | M2 | XL |
| M4 | V17.8 | Region LOD, coarse-first và worker-only derivation | M3 | XL |
| M5 | V17.9 | GPU page table và renderer mới | M4 | XL |
| M6 | V18.0 | Minimap pipeline độc lập | M3, M5 một phần | L |
| M7 | V18.1 | Cave archive thế hệ 2 | M2, M3 source model | XL |
| M8 | V18.2 | Layered/Full Cave coarse-first projection | M7, M4 | XL |
| M9 | V18.3 | Shared GPU upload engine và residency hoàn chỉnh | M5, M8 | L/XL |
| M10 | V18.4 | Persistence v2, journal, migration và recovery | M3, M7, format ổn định | XL |
| M11 | V18.5 | Xóa legacy, tách god class, đóng nợ kiến trúc | M5–M10 | L |
| M12 | V19.0 Alpha/Beta Gate | Compatibility, soak, tuning đa phần cứng | M11 | XL |
| M13 | Stable Gate | Release candidate và tiêu chí ổn định cuối | M12 | L |

Không triển khai song song các mốc có dependency trực tiếp. Có thể làm telemetry, test harness và tài liệu xuyên suốt.

---

# 5. M0 — V17.4.1: Build Recovery và Evidence Baseline

## 5.1 Mục tiêu

Biến V17.4 từ source static-only thành project có thể build, chạy và đo được. Không tối ưu lớn tại mốc này.

## 5.2 Công việc

### Project/build

- Đưa source V17.4 vào project NeoForge 1.21.1 hoàn chỉnh.
- Khôi phục:
  - `settings.gradle`;
  - `build.gradle`;
  - `gradle.properties`;
  - Gradle wrapper;
  - `META-INF/neoforge.mods.toml`;
  - mappings/Parchment;
  - resources và mixin config nếu có.
- Chạy Java 21.
- Sửa toàn bộ compile error, event registration và access mismatch.

### Runtime instrumentation tối thiểu

Thêm metric không tạo allocation lớn:

- `mapMainThreadNanos` mỗi frame;
- `mapGpuSubmitNanos` mỗi frame;
- queued/active cost theo work type;
- completed payload bytes;
- exact page publish/s;
- branch publish/s;
- atlas resident/eviction;
- source cache hit/miss;
- stale completion count;
- dropped/rejected task count;
- render-plan rebuild/s;
- heap used, native staging bytes và atlas storage.

### Benchmark world

Tạo ba world cố định:

1. Vanilla Overworld có rừng, ocean, núi và cave.
2. World modded có nhiều block/biome tint.
3. World lớn đã khám phá để test cache-hot và zoom xa.

Tạo route cố định:

- đi bộ;
- bay Elytra;
- teleport 5.000 block;
- pan/zoom fullscreen;
- Layered Cave scrub Top-Y;
- Full Cave cold reconstruction.

## 5.3 Class tác động chính

- `SimpleMap`
- `MapPerformanceGovernor`
- `MapGpuBudgetController`
- `MapWorkScheduler`
- `MapPublicationCoordinator`
- `MapObservationTelemetry`
- class debug overlay mới: `MapPipelineDebugOverlay`

## 5.4 Đầu ra bắt buộc

- Project build hoàn chỉnh.
- `gradlew clean build` pass.
- `runClient` mở được world.
- Debug overlay và CSV/JSON metrics dump.
- Baseline report cho tối thiểu 6 scenario.

## 5.5 Exit gate

Chỉ qua M0 khi:

- không crash trong smoke test 20 phút;
- surface/minimap/layered/full cave đều mở được;
- render publication xác nhận đúng một ledger mỗi frame bằng `frameId` hoặc callback identity đáng tin cậy;
- có p50/p95/p99 frame-time riêng cho map;
- không còn compile/static-only uncertainty.

## 5.6 Không làm ở M0

- Không viết lại renderer.
- Không tăng worker count lớn.
- Không đổi cache format.
- Không xóa legacy.

---

# 6. M1 — V17.5: Correctness Core, Session và Durable Dirty State

## 6.1 Mục tiêu

Loại bỏ mọi đường có thể mất sự thật khi queue đầy, task stale hoặc lifecycle thay đổi.

## 6.2 Thành phần mới

### `MapSession`

```java
final class MapSession {
    long sessionId;
    SessionState state;
    WorldIdentity worldIdentity;
    int dimensionId;
    long sourceGeneration;
    long styleGeneration;
    long projectionGeneration;
    MapCancellationToken rootToken;
}
```

State:

```text
CREATED → OPENING → ACTIVE → FLUSHING → CLOSED
```

Mọi task/output/upload/cache write phải mang:

```text
sessionId + sourceGeneration + styleGeneration + projectionGeneration
```

### Durable dirty hierarchy

`MapMutationBus` chuyển từ event list có cap sang escalation:

```text
column dirty
→ chunk dirty
→ region dirty
→ full region rescan
```

Queue chỉ chứa key để tiến triển; dirty mask nằm trong region/session state.

## 6.3 Công việc cụ thể

- Thay `MAX_PENDING_* → return` bằng dirty escalation.
- `MapProcessor` không được drop semantic task. Task bị từ chối phải để stage ở `DIRTY`.
- `CaveDisplayScheduler`, `CaveWorldSaveReader`, `CavePageBuildWorker` phải trả trạng thái admission rõ:
  - accepted;
  - coalesced;
  - deferred;
  - cancelled;
  - stale.
- Thay duplicate-frame heuristic bằng `frameId` thật.
- `MapRenderPlan.MAX_QUADS` overflow phải render ancestor thay thế.
- Pending viewport set có giới hạn phải có coarse fallback hoặc persistent viewport demand.
- Resource reload tạo style generation mới, không mutate cache được worker cũ đọc.
- Dimension switch/disconnect invalid toàn bộ root token cũ.

## 6.4 Class tác động

- `MapMutationBus`
- `MapProcessor`
- `MapWorkScheduler`
- `MapViewportCoordinator`
- `MapPublicationCoordinator`
- `MapRenderPlan`
- `MapRenderer`
- `CaveDisplayScheduler`
- `CaveWorldSaveReader`
- `CavePageBuildWorker`
- class mới trong `client/map/session/`

## 6.5 Static tests

- Tìm toàn source các pattern queue full rồi `return` hoặc `poll/remove`.
- Unit test dirty escalation.
- Unit test generation mismatch.
- Unit test quad overflow → ancestor fallback.
- Unit test session close khi task đang chạy.

## 6.6 Runtime tests

- Pan nhanh cho tới khi queue pressure cao.
- Teleport liên tục.
- Chuyển Overworld/Nether 20 lần.
- Disconnect/reconnect khi build đang chạy.
- Resource reload giữa lúc surface/cave build.
- Cố ép atlas eviction.

## 6.7 Exit gate

- `semanticDropCount = 0` trong mọi stress test.
- Không có black hole bên trong vùng đã biết.
- Không publish output của session/style cũ.
- Quad overflow không làm mất vùng.
- Cache write cũ không publish vào session mới.

---

# 7. M2 — V17.6: RegionRecord và Unified Work Graph

## 7.1 Mục tiêu

Chuyển authority từ queue/page manager sang region state bền vững. Hợp nhất admission, fairness, memory và lifecycle của CPU/IO work.

## 7.2 Kiến trúc mới

### `RegionRecord`

```java
final class RegionRecord {
    RegionKey key;
    long sourceRevision;
    long styleRevision;
    long projectionRevision;

    long dirtySourceMask;
    long dirtyExactMask;
    long dirtyLodMask;
    long cacheDirtyMask;
    long gpuResidentMask;

    RegionStageState sourceState;
    RegionStageState projectionState;
    RegionStageState lodState;
    RegionStageState gpuState;
    RegionStageState persistenceState;
}
```

### `MapWorkGraph`

Stages:

```text
SOURCE_CAPTURE
SOURCE_READ
SOURCE_DECODE
SOURCE_COMMIT
PROJECT
STYLE
LOD_DERIVE
GPU_PREPARE
GPU_UPLOAD
CACHE_COMMIT
```

Task key:

```java
record WorkKey(long sessionId, long regionKey, Stage stage, int projectionId) {}
```

Repeated requests chỉ cập nhật target revision/dirty mask, không tạo semantic task mới.

## 7.3 Scheduler policy

- Deficit round robin giữa:
  - minimap foreground;
  - visible fullscreen;
  - visible branch;
  - view margin;
  - persistence/maintenance.
- Tách CPU projection, IO decode, compression và GPU publication.
- Adaptive concurrency dựa trên:
  - frame pressure;
  - IO latency;
  - pending memory;
  - queue age;
  - active worker count.
- Memory lease cho task lớn:

```text
PENDING_SOURCE
PENDING_PROJECTION
PENDING_LOD
PENDING_UPLOAD
IO_BUFFER
```

Không có lease thì stage giữ `DIRTY`, không allocate.

## 7.4 Migration strategy

- Tạo adapters để manager cũ đọc/ghi `RegionRecord`.
- Chưa xóa manager cũ trong mốc này.
- `MapWorkScheduler` trở thành execution backend của `MapWorkGraph`.
- `MapProcessor` được thu hẹp và sau đó deprecate.

## 7.5 Class tác động

- `MapWorkScheduler`
- `MapProcessor`
- `MapPerformanceGovernor`
- `MapGpuBudgetController`
- `MapResidencyManager`
- `MapTextureManager`
- `UnifiedCaveTextureManager`
- package mới `client/map/region/` và `client/map/pipeline/`

## 7.6 Exit gate

Có thể truy vấn một region và biết chính xác:

- source nào đã có;
- stage nào dirty/running/prepared;
- revision nào CPU-ready;
- revision nào GPU-visible;
- revision nào đã cache commit.

Ngoài ra:

- Không có hai scheduler cùng authority cho một stage.
- Pending memory không vượt hard lease budget.
- Full Cave không thể flood CPU khi GPU/pending memory đang nghẽn.
- Minimap không bị starve khi Full Map hoạt động.

---

# 8. M3 — V17.7: Surface Source Database và Supertile Pipeline

## 8.1 Mục tiêu

Loại bottleneck `captureSurfacePageBuildInputs()` theo từng page trên client thread. Snapshot Minecraft data một lần rồi project off-thread theo region.

## 8.2 Source model

### `ChunkSnapshot`

Chụp trên client thread:

- block/material palette;
- biome palette;
- surface heights;
- light arrays;
- fluid/transparency flags;
- chunk source revision.

Không lưu `BlockState` object dài hạn.

### `SurfaceRegionSource`

- Region 512×512 block.
- 32×32 Minecraft chunk.
- 8×8 leaf page 64×64.
- Immutable snapshot/versioned segments.
- Dirty chunk mask và dirty leaf mask.

## 8.3 Supertile transaction

Cold load/build dùng:

- 4×4 page = 256×256 cho visible foreground;
- 8×8 page = 512×512 cho region reconstruction/cache-hot.

Một transaction:

1. Acquire immutable source views.
2. Resolve material/style palette một lần.
3. Build nhiều exact leaf.
4. Có thể build target LOD trực tiếp.
5. Trả `PreparedSurfaceRegionBatch`.

Page 64×64 vẫn là đơn vị render và update nhỏ, nhưng không còn là đơn vị source transaction duy nhất.

## 8.4 Immutable style snapshot

```java
final class MapStyleSnapshot {
    long revision;
    MaterialStyle[] materials;
    BiomeStyle[] biomes;
    TintPolicy[] tintPolicies;
    int shadingProfile;
}
```

Worker chỉ đọc snapshot immutable. Không đọc shared mutable `HashMap` giữa hai style generation.

## 8.5 Thay đổi class

- Tách source capture khỏi `MapTextureManager`.
- Thu nhỏ `MapTextureBuildWorker` thành projector thuần.
- Thay `SurfacePageBufferPool` bằng buffer leases theo batch/size class.
- `RegionDataStore` tạm đọc/ghi source model mới qua adapter.

## 8.6 Metrics mục tiêu

- Main-thread surface capture p99 khi vào vùng mới ≤ 0,75 ms/frame.
- Allocation/page giảm ít nhất 60% so baseline M0.
- Region lock/snapshot transaction giảm mạnh so với 64 page riêng lẻ.
- Cold surface exact throughput tăng ít nhất 2× so baseline hoặc đạt giới hạn GPU budget mà không tạo main-thread spike.

## 8.7 Exit gate

- Không còn đường foreground gọi full page palette remap từ Minecraft state trên client thread.
- 64 leaf cùng region có thể được build từ một region transaction.
- Resource reload chỉ đổi style snapshot, không bắt buộc đọc lại Minecraft world source.
- Surface map giữ coverage liên tục khi Elytra/teleport.

---

# 9. M4 — V17.8: Region LOD, Coarse-First và Worker-Only Derivation

## 9.1 Mục tiêu

Thay LOD factor-2/page event-centric bằng hierarchy region-state-centric. Coverage thô phải xuất hiện trước exact.

## 9.2 Region hierarchy

Đề xuất:

```text
Leaf: 64×64 block
Region level 0: 8×8 leaf = 512×512 block
Level 1: 8×8 region level 0
Level 2: 8×8 level 1
Level 3: 8×8 level 2
```

Không bắt buộc đúng ba level nếu world bounds yêu cầu khác, nhưng hierarchy phải dùng region/version sums, không tạo bảy tầng object nặng chỉ vì factor-2.

## 9.3 Version propagation

Mỗi region giữ:

- `leafVersions[64]`;
- `leafVersionSum`;
- child version sums;
- CPU prepared revision;
- GPU published revision;
- coverage mask.

Dirty state tồn tại trong region, queue chỉ chọn region nào xử lý trước.

## 9.4 Worker-only LOD derivation

Render thread không làm:

- downsample;
- recursive parent propagation;
- coverage merge;
- cache payload compression.

Worker trả immutable `PreparedBranch` có:

- pixels hoặc compressed block;
- known/complete mask;
- child version sums;
- dirty rect;
- source/style/projection revision.

## 9.5 Coarse-first direct projection

Surface projector phải có thể dựng thẳng target LOD từ `SurfaceRegionSource`:

```text
Missing ancestor coverage → ưu tiên 1
Target screen-space LOD → ưu tiên 2
Exact visible → ưu tiên 3
View margin → ưu tiên 4
```

Không bắt buộc exact → L1 → L2 tuần tự mới có thể nhìn thấy far zoom.

## 9.6 Replace old trees

- `SurfaceLodTree` và `CaveLodTree` được adapter sang `RegionLodGraph`.
- Giữ old path tạm để A/B test.
- Không xóa old path cho tới khi parity/correctness pass.

## 9.7 Exit gate

- Mở map 0.25x–0.5x: coarse coverage của viewport xuất hiện liền mạch trước exact.
- Không có random exact islands phía trước frontier nếu mode chọn ordered refinement.
- Render thread LOD derive p99 gần 0; chỉ còn validation/publication.
- Không có node dirty bị mất khi queue bị reset.
- Exact eviction luôn có published replacement.

---

# 10. M5 — V17.9: GPU Page Table và Renderer mới

## 10.1 Mục tiêu

Tách geometry khỏi atlas slot, giảm render-plan rebuild và Java allocation, bảo đảm publication atomic.

## 10.2 Page table

Logical key:

```text
TileKey → atlas/layer slot + generation + LOD + flags
```

Double buffer:

```text
pageTableFront: renderer đọc
pageTableBack: upload engine cập nhật
frame boundary: atomic swap
```

Publication order:

1. Upload texture mới.
2. Update back page table.
3. Swap page table.
4. Sau swap mới retire slot cũ.

## 10.3 Renderer

- Static unit quad VBO.
- Instance buffer cho tile.
- Không tạo `Quad` object hàng loạt.
- Không sort bằng `texture.toString()`.
- Geometry budget overflow → chọn ancestor node.
- LOD hysteresis giữ ổn định khi zoom quanh threshold.
- Plan chỉ thay khi viewport/LOD selection thay, không khi tile đổi slot.

## 10.4 Transition

- `MapAtlasBatchRenderer` hỗ trợ cả legacy batch và instance path.
- `MapRenderPlan` cũ chỉ giữ adapter/debug comparison.
- Có debug mode render hai path vào offscreen buffer để compare coverage.

## 10.5 Metrics mục tiêu

- Plan rebuild/s giảm ít nhất 80% khi cold streaming.
- Java allocation trong render path gần 0 sau warm-up.
- Pan/zoom p99 map main-thread ≤ 1,0 ms trên benchmark target.
- Slot relocation không rebuild geometry.

## 10.6 Exit gate

- Không black flash khi slot swap.
- Page table generation mismatch không vẽ dữ liệu sai.
- Geometry cap không mất coverage.
- Old renderer và new renderer cho cùng coverage/coordinates trong test suite.

---

# 11. M6 — V18.0: Dedicated Minimap Pipeline

## 11.1 Mục tiêu

Minimap không phụ thuộc vào cold Full Map reconstruction, LOD backlog hoặc atlas churn.

## 11.2 Kiến trúc

```java
MinimapService {
    FixedTileRing ring;
    MinimapScanCursor cursor;
    MinimapTexture target;
    ColumnSignatureStore signatures;
    MinimapBudget budget;
}
```

### Fixed ring

- 9×9 hoặc 13×13 chunk quanh camera/player.
- Footprint cố định.
- Double-buffer/staging texture.
- Last-good texture luôn giữ khi update chưa hoàn tất.

### Signature reuse

Mỗi pixel/column có signature gồm:

- material ID;
- biome/tint ID;
- top Y;
- light;
- slope;
- transparency/fluid;
- style revision.

Nếu signature không đổi, giữ màu cũ.

## 11.3 Chia sẻ với World Map

Được chia sẻ:

- `ChunkSnapshotService`;
- material/style snapshot;
- optional region source.

Không chia sẻ authority:

- fullscreen demand queue;
- LOD publication;
- exact atlas eviction;
- fullscreen render plan;
- cold reconstruction.

## 11.4 Exit gate

- Full Map/Full Cave reconstruction không làm minimap giật đáng kể.
- Minimap main-thread p99 ≤ 0,25–0,35 ms trên benchmark target.
- Minimap không đen khi source mới chưa xong; giữ last-good.
- Di chuyển tốc độ cao không tạo backlog tăng vô hạn.

---

# 12. M7 — V18.1: Cave Archive thế hệ 2

## 12.1 Mục tiêu

Tạo source archive chung, compact và style-independent cho Minimap Cave, Layered Cave và Full Cave.

## 12.2 Format mới

Thay object-per-column và final color bằng Structure of Arrays:

```java
CompactCaveTile {
    int[] columnOffsets;      // 257
    short[] runTopY;
    short[] runFloorY;
    int[] materialIds;
    short[] biomeIds;
    byte[] blockLight;
    byte[] skyLight;
    byte[] fluidDepth;
    byte[] flags;
}
```

Column status rõ:

```text
UNKNOWN
PARTIAL
COMPLETE
COMPLETE_TRUNCATED
CORRUPT
```

## 12.3 Capture/scan pipeline

Client thread:

- snapshot compact chunk sections;
- classify all-air/all-solid/mixed/fluid;
- không chạy full vertical cave scan.

Worker:

- tìm cavity run;
- floor/roof;
- fluid/open-sky/light flags;
- commit compact archive.

World-save decoder:

- decode `.mca` vào cùng archive format;
- không tạo representation khác với live source.

## 12.4 Style independence

Archive lưu material/biome/light identity. Màu chỉ được tạo ở projection stage.

Resource pack/config reload:

- không decode lại NBT;
- không rescan world;
- chỉ reproject/restyle visible/needed regions.

## 12.5 Migration

- Reader cho `CaveColumnData`/old `.cvr`.
- Background conversion theo region.
- Không delete old cache cho tới khi new record CRC pass.

## 12.6 Exit gate

- Layered và Full Cave dùng đúng cùng source archive.
- Top-Y change không đọc lại `.mca` hoặc `ClientLevel`.
- Heap/object count cave giảm rõ rệt so baseline.
- Truncated column không được đánh dấu complete.
- Live source và world-save source merge theo revision/ownership rõ ràng.

---

# 13. M8 — V18.2: Layered/Full Cave Coarse-First Projection

## 13.1 Mục tiêu

Biến Cave thành projection rẻ từ archive thay vì pipeline exact-page reconstruction bắt buộc.

## 13.2 Layered Cave

- Band cache mỗi 8 hoặc 16 Y-level.
- Khi đang scrub Top-Y:
  - dùng nearest band;
  - không build exact cho mọi thay đổi chuột.
- Sau debounce 100–200 ms:
  - exact refinement visible area.
- Projection delta chỉ xử lý run cắt qua Top-Y mới.

## 13.3 Full Cave

Tạo summary per chunk/tile:

- cave coverage ratio;
- dominant floor/material;
- water/emissive ratio;
- completeness;
- depth range.

Far zoom:

```text
archive summary → coarse branch → viewport coverage
```

Near zoom:

```text
exact projection → exact tile refinement
```

Không chờ toàn bộ exact page mới có thể vẽ Full Cave xa.

## 13.4 Admission

Tách concurrency:

- IO region decode;
- archive build;
- projection;
- LOD derive;
- GPU publication.

Adaptive range gợi ý:

- gameplay: 1–2 source transaction;
- fullscreen ổn định: 2–4;
- source cache-hot projection: 4–N theo CPU/memory pressure;
- minimap reserve: ít nhất một lane/transaction.

## 13.5 Exit gate

- Full Cave 0.29x cho coarse coverage liền mạch trước exact.
- Layered Cave scrub không tạo queue stale lớn.
- Projection cache hit cao khi đổi Top-Y trong cùng band.
- Full Cave cold source không làm gameplay p99 vượt budget kéo dài.
- Không có random 64×64 islands làm representation chính ở far zoom.

---

# 14. M9 — V18.3: Shared GPU Upload Engine và Residency hoàn chỉnh

## 14.1 Mục tiêu

Một transfer engine duy nhất cho Surface, Cave, Full Cave và Minimap; đo và kiểm soát native/GPU memory thực.

## 14.2 `MapUploadEngine`

```java
UploadCommand {
    TextureHandle target;
    Rect rect;
    BufferLease payload;
    int byteCount;
    MapRequestLane lane;
    long sessionId;
    long sourceRevision;
    long styleRevision;
}
```

PBO pool:

- 4–8 slot dùng chung;
- explicit `glFenceSync`;
- không reuse slot trước fence;
- size classes;
- fallback nếu extension/driver không hỗ trợ tốt.

Worker tạo payload đúng GPU byte order để render thread không loop repack từng pixel.

## 14.3 Texture storage

Đánh giá runtime hai phương án:

1. Texture arrays theo size bucket.
2. Dynamic atlas pools có thể mở rộng/thu hẹp.

Không preallocate mọi atlas level theo hardcoded profile nếu chưa dùng.

## 14.4 Residency policy

Segment:

- protected visible;
- minimap protected;
- probation recent;
- warm source-backed;
- cold disk-backed.

Victim score tính:

- age;
- viewport distance;
- replacement coverage;
- regeneration cost;
- source/cache availability;
- bytes;
- lane.

## 14.5 Memory accounting

Tách hard/soft budget:

- GPU storage;
- GPU logical residency;
- CPU source;
- CPU derived;
- pending builds;
- native upload buffers;
- IO buffers;
- metadata.

## 14.6 Exit gate

- Không PBO reuse trước fence.
- Native staging không tăng theo số atlas.
- Không stall GPU kéo dài do upload burst.
- Tổng memory category khớp tương đối với profiler/driver report.
- Under pressure, quality giảm bằng LOD/eviction an toàn, không bằng black hole.

---

# 15. M10 — V18.4: Persistence v2, Journal, Migration và Recovery

## 15.1 Mục tiêu

Hợp nhất source/derived cache theo region container, giảm file nhỏ và hỗ trợ corruption recovery.

## 15.2 Phân lớp cache

### Source cache

- Surface region source.
- Cave compact archive.

### Derived cache

- Exact tile output tùy chọn.
- Surface LOD.
- Cave LOD/projection bands.

Derived cache có thể xóa/rebuild. Source cache có authority cao hơn.

## 15.3 Region container

Format có:

- magic/version;
- world identity;
- data version;
- source/style generation;
- record directory;
- offsets/lengths;
- CRC/checksum;
- append journal;
- checkpoint;
- compaction.

Dùng ý tưởng tốt từ `CaveRegionStore`, mở rộng cho Surface và LOD.

## 15.4 Migration

- Read old `RegionDataStore`.
- Read old `.lod.gz`.
- Read old cave `.cvr`.
- Convert lazy/background.
- Atomic replace.
- Có marker migration complete.
- Có khả năng rollback/ignore file mới nếu checksum lỗi.

## 15.5 Recovery tests

- truncate tail;
- corrupt record CRC;
- missing directory entry;
- crash giữa append;
- crash giữa compaction;
- disk full/read-only;
- cache version mismatch.

## 15.6 Exit gate

- Map lớn không tạo hàng chục nghìn file LOD nhỏ.
- Cache corrupt không crash client và không block viewport mãi.
- Old cache migration không mất dữ liệu authoritative.
- Save/write amplification giảm đáng kể.
- Shutdown không phải chờ vô hạn.

---

# 16. M11 — V18.5: Xóa Legacy và Tách God Class

## 16.1 Mục tiêu

Sau khi pipeline mới đạt parity, xóa hai authority representation và giảm độ phức tạp để ổn định lâu dài.

## 16.2 Xóa hoặc deprecate

- Legacy region `DynamicTexture` path.
- Compatibility leaf publication queue.
- Old `SurfaceLodTree`/`CaveLodTree` nếu RegionLodGraph đã thay thế.
- Old render-plan/batch path sau A/B parity.
- String spatial keys trong hot path.
- Old per-node `.lod.gz` writer.
- Duplicate scheduler/control plane.
- Dead revision maps và range-load state.

## 16.3 Tách class

### `MapTextureManager`

Tách thành:

- `SurfaceDemandController`;
- `SurfaceSourceService`;
- `SurfaceProjectionService`;
- `SurfaceResidencyService`;
- `SurfacePublicationService`.

### `MapRenderer`

Tách thành:

- `LodSelector`;
- `MapInstancePlanner`;
- `MapGpuRenderer`;
- `OverlayRenderer`.

### `UnifiedCaveTextureManager`

Tách thành:

- `CaveProjectionController`;
- `CaveResidencyService`;
- `CavePublicationService`;
- `CaveCacheService`.

## 16.4 Exit gate

- Một source of truth cho mỗi stage.
- Không manager nào vừa đọc Minecraft world, build CPU, upload GPU và ghi disk.
- Hot path không parse/construct String key.
- Code coverage/unit tests cho state transition chính.
- Không còn adapter legacy trong production path mặc định.

---

# 17. M12 — V19.0: Compatibility, Soak và Hardware Tuning

## 17.1 Mục tiêu

Chuyển từ kiến trúc hoàn chỉnh sang sản phẩm ổn định trên nhiều world, modpack và phần cứng.

## 17.2 Test matrix

### Gameplay

- Đi bộ 60 phút vào terrain mới.
- Elytra 30 phút.
- Teleport nhiều vùng.
- Pan/zoom liên tục 15 phút.
- Layered Cave Top-Y scrub.
- Full Cave cold/hot.

### Lifecycle

- Overworld/Nether/End lặp lại.
- Disconnect/reconnect.
- Hai server cùng dimension ID.
- Resource pack reload.
- GUI scale/FPS cap/VSync thay đổi.
- Death/rejoin.
- Shutdown khi cache đang ghi.

### Hardware

- NVIDIA discrete.
- AMD discrete.
- Intel/AMD integrated.
- 60 Hz và 144 Hz.
- HDD, SATA SSD, NVMe.
- Heap 2 GiB, 4 GiB, 8 GiB.

### Mod compatibility

- Modded biomes.
- Modded blocks có tint.
- Custom world height.
- Nether ceiling.
- Large structures.
- Shader/resource pack phổ biến.

## 17.3 Tuning

- Auto profile CPU workers.
- IO concurrency theo latency.
- GPU upload calibration theo driver.
- Atlas/page pool size.
- LOD threshold và hysteresis.
- Minimap ring size.
- Cache compression/compaction cadence.

Không hardcode theo máy phát triển duy nhất.

## 17.4 Exit gate

- Không memory growth tuyến tính trong soak test.
- Không black coverage regression.
- Không deadlock/race quan sát được.
- p99 frame-time đạt target trên máy tham chiếu và graceful degradation trên máy yếu.
- Cache recovery pass.
- Crash report không chứa stale OpenGL/session publication path.

---

# 18. M13 — Stable Release Gate

## 18.1 Correctness Definition of Done

Phải đạt tuyệt đối:

- Known source không biến mất vì eviction.
- Queue overflow không làm mất dirty fact.
- Old session/style output không publish.
- Full Cave partial data không giả complete.
- Quad/instance budget đầy tự hạ LOD.
- Minimap giữ last-good trong mọi reconstruction.
- Cache corrupt được isolate/rebuild.

## 18.2 Performance target ban đầu

Các số này phải được điều chỉnh theo máy, nhưng dùng làm gate trên máy tham chiếu:

| Scenario | Main-thread map p99 |
|---|---:|
| Gameplay bình thường | ≤ 0,50 ms/frame |
| Minimap | ≤ 0,25–0,35 ms/frame |
| Đi vào terrain mới | ≤ 1,00 ms/frame |
| Fullscreen pan/zoom | ≤ 1,00 ms/frame |
| Fullscreen đứng yên reconstruct | ≤ 1,50 ms/frame |

GPU submit p99:

| Scenario | Target |
|---|---:|
| Gameplay | ≤ 0,75 ms/frame |
| Fullscreen | ≤ 2,00 ms/frame |

## 18.3 Coverage target

- Cache-hot fullscreen: coarse viewport coverage trong khoảng gần tức thời/không tạo lỗ kéo dài.
- Cache-cold: coarse coverage phải tiến triển thành vùng liền mạch, không random islands.
- Exact refinement không được chặn interaction.
- Minimap center latency ưu tiên hơn background World Map.

## 18.4 Memory target

- Tổng memory đo được theo category.
- Pending payload bounded.
- Heap trở về plateau sau pan dài.
- Native/PBO không tăng vô hạn.
- GPU storage không vượt hard envelope kéo dài.
- Cache metadata không tăng theo số pixel đã khám phá.

---

# 19. Telemetry bắt buộc xuyên suốt

## 19.1 Per-stage metrics

- queue latency p50/p95/p99;
- execution p50/p95/p99;
- active/queued count và predicted cost;
- actual bytes/allocation;
- admission deferred/rejected/coalesced;
- stale completions;
- source/projection/cache hit;
- exact pages/s;
- coarse coverage blocks/s;
- GPU bytes/s;
- fence wait time;
- atlas/page-table evictions;
- render-plan/instance-plan rebuild/s;
- cache write amplification;
- main-thread map nanos;
- memory per category.

## 19.2 Regression gate

Mỗi PR/milestone phải kèm:

- scenario;
- baseline commit;
- new commit;
- p50/p95/p99;
- allocation rate;
- coverage time;
- memory plateau;
- screenshot/video nếu liên quan correctness.

Không chấp nhận claim “mượt hơn” nếu không có metric.

---

# 20. Quy tắc triển khai

1. Mỗi milestone có feature flag để rollback trong giai đoạn chuyển tiếp.
2. Không giữ hai authority path lâu hơn hai milestone.
3. Không đổi source format và renderer lớn trong cùng một PR.
4. Mỗi state transition mới phải có unit test.
5. Mọi worker result phải validate session/source/style/projection revision trước commit.
6. Mọi buffer lease phải có ownership và terminal release rõ.
7. Không giữ lock khi compression, downsample, registry lookup hoặc OpenGL.
8. Không gọi Minecraft world API từ worker.
9. Không tăng budget trước khi đo bottleneck thật.
10. Khi throughput và frame pacing xung đột, ưu tiên frame pacing và coarse coverage.

---

# 21. Thứ tự thực hiện ngay từ hiện tại

## Sprint/PR đầu tiên — V17.4.1 Build Recovery

1. Khôi phục Gradle project.
2. Build toàn bộ.
3. Fix event/render hook.
4. Thêm frame ID và metric ledger.
5. Tạo benchmark world/route.
6. Ghi baseline report.

## PR kế tiếp — V17.5 Correctness audit

1. Search toàn source các queue caps/drop paths.
2. Implement `MapSession`.
3. Implement dirty escalation trong `MapMutationBus`.
4. Fix quad overflow fallback.
5. Add stale-generation tests.
6. Stress dimension/disconnect/resource reload.

## Sau khi V17.5 pass

Bắt đầu `RegionRecord` và `MapWorkGraph`. Không nhảy thẳng sang supertile, PBO hoặc tăng concurrency trước khi authority và lifecycle đã ổn định.

---

# 22. Dấu hiệu phải dừng và quay lại milestone trước

Dừng phát triển tính năng mới nếu xuất hiện một trong các dấu hiệu:

- lỗ đen trở lại trong vùng known;
- pending bytes tăng liên tục;
- task stale publish được;
- minimap chậm theo Full Map;
- cache corrupt khóa viewport;
- main-thread p99 tăng sau tối ưu throughput;
- atlas eviction/s tăng mạnh nhưng coverage không tăng;
- queue latency tăng vô hạn;
- resource reload trộn màu cũ/mới;
- dimension switch hiện texture dimension trước.

---

# 23. Kết luận

Roadmap này không yêu cầu viết lại tất cả trong một lần. Trình tự bắt buộc là:

```text
Build và đo được
→ correctness/session
→ region authority/work graph
→ surface source batch
→ LOD/coarse-first
→ page table/renderer
→ minimap riêng
→ cave archive/projection
→ GPU transfer
→ persistence
→ xóa legacy
→ soak/tuning
```

Mốc quan trọng nhất không phải renderer hay PBO. Mốc quyết định toàn bộ dự án là V17.6:

> Region phải sở hữu dirty state, revision và lifecycle; scheduler chỉ tiến triển trạng thái đó.

Khi V17.6 và V17.7 được hoàn thành đúng, các bước LOD, Cave, GPU và persistence phía sau mới có nền tảng đủ sạch để đạt độ ổn định lâu dài tương đương lớp chất lượng của Xaero.
