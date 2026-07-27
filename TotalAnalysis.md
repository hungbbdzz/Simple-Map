# Đánh giá tổng thể

Phân tích này dựa trên:

* Source **Simple Map v17.4 alpha** hiện tại.
* Source Xaero World Map và Xaero Minimap mà bạn cung cấp.
* Kiểm tra kiến trúc tĩnh, không chạy Minecraft, không profiler, không đo FPS thực tế.
* Source Xaero là bản decompile nên có thể mất tên biến, generic, comment và một số cấu trúc gốc.

## Kết luận quan trọng nhất

Bản v17.4 đã sửa được một số **invariant an toàn** rất quan trọng:

* Không còn xóa LOD update chính ở `SurfaceLodTree`.
* Có ancestor fallback.
* Có coverage fence trước eviction.
* Publication chuyển sang render frame.
* Scheduler đã tính cả queued và active cost.
* Cave đã có vertical run archive và region container tương đối tốt.

Nhưng kiến trúc tổng thể vẫn là:

> **Một pipeline page-centric cũ được bổ sung nhiều lớp bảo vệ**, chưa phải một hệ thống region-state-centric như Xaero.

Xaero không mạnh đơn giản vì có nhiều thread hay cache lớn hơn. Điểm cốt lõi là:

> Mỗi vùng bản đồ là một đối tượng trạng thái bền vững. Queue chỉ giúp xử lý nhanh hơn, không phải nơi duy nhất lưu “việc cần làm”.

Trong Simple Map hiện tại, rất nhiều trạng thái vẫn nằm rải rác trong:

* Queue.
* Future.
* Dirty set.
* Atlas slot.
* HashMap.
* Compatibility cache.
* Manager riêng.
* String key.
* Các giới hạn queue có thể từ chối công việc.

Đó là khoảng cách lớn nhất còn lại.

---

# 1. Mức độ hiện tại so với Xaero

Các tỷ lệ dưới đây chỉ là **ước lượng kiến trúc tĩnh**, không phải benchmark.

| Hạng mục                | Simple Map v17.4 | Khoảng cách chính                                            |
| ----------------------- | ---------------: | ------------------------------------------------------------ |
| Không mất vùng đã biết  |           78–86% | Vẫn còn queue/cap có thể bỏ mutation hoặc request            |
| Scheduler tổng thể      |           65–75% | Nhiều control plane, thiếu work graph và fairness thật       |
| Chia frame budget       |           65–72% | Chỉ drain một projection family mỗi frame                    |
| Surface cold loading    |           50–62% | Snapshot từng page trên client thread                        |
| Surface LOD             |           62–72% | Parent propagation còn chạy render thread                    |
| Cave source archive     |           68–78% | Có archive nhưng lưu màu kết quả, cấu trúc object-heavy      |
| Layered Cave            |           55–67% | Projection/page admission còn nhỏ lẻ                         |
| Full Cave               |           45–58% | Đòi hỏi dữ liệu toàn chiều cao, chưa coarse-first đúng nghĩa |
| GPU upload              |           55–65% | PBO không fence, nhiều uploader riêng, CPU repack            |
| Render throughput       |           55–67% | CPU batch, rebuild plan, giới hạn quad im lặng               |
| Persistence surface/LOD |           42–55% | Nhiều file GZIP nhỏ, write amplification                     |
| Persistence cave        |           70–80% | `.cvr` region container đã khá tốt                           |
| Quản lý RAM/VRAM        |           45–58% | Budget chưa bao trọn tổng footprint                          |
| Minimap isolation       |           45–58% | Vẫn phụ thuộc nhiều vào world-map pipeline                   |
| Lifecycle/recovery      |           45–58% | Generation rải rác, chưa có một session state machine        |
| Telemetry và kiểm thử   |           30–42% | Chưa có runtime benchmark, JFR, GPU timing thật              |

## Nhận định thực tế

Bản v17.4 hiện gần với một **bản chuyển tiếp an toàn** hơn là kiến trúc cuối cùng.

Tiếp tục sửa nhỏ trên các class hiện tại vẫn giúp được một thời gian, nhưng sẽ sớm gặp trần vì ba class quá lớn:

| Class                       | Số dòng xấp xỉ |
| --------------------------- | -------------: |
| `MapTextureManager`         |          2.598 |
| `MapRenderer`               |          1.986 |
| `UnifiedCaveTextureManager` |          1.929 |

Khi một class đồng thời quản lý:

* Demand.
* Snapshot.
* Worker.
* Future.
* CPU cache.
* Atlas.
* GPU publication.
* Eviction.
* Persistence.
* Compatibility.
* Telemetry.

thì việc tối ưu một phần thường tạo regression ở phần khác.

---

# 2. Khác biệt kiến trúc nền tảng

## Simple Map hiện tại

```text
Viewport
   ↓
Tạo hàng loạt page demand 64×64
   ↓
Dirty set / PriorityQueue / HashMap
   ↓
Snapshot dữ liệu theo từng page
   ↓
Worker build texture
   ↓
Future hoàn thành
   ↓
GPU atlas publication
   ↓
Gửi bản sao sang LOD tree
   ↓
Derive parent
   ↓
Disk cache riêng
```

Mỗi page trải qua nhiều giao dịch độc lập.

Một region surface 512×512 chứa 8×8 page, tức 64 page. Việc mở một region mới có thể tạo:

* 64 demand.
* 64 lần snapshot.
* 64 job.
* 64 future.
* 64 revision lookup.
* 64 lần atlas admission.
* 64 lần LOD leaf update.
* Nhiều parent update.
* Nhiều lần render-plan invalidation.

## Xaero theo source được cung cấp

Xaero tổ chức quanh:

```text
LeveledRegion
   ├── 8×8 RegionTexture
   ├── load/cache/upload states
   ├── leaf texture version sums
   ├── cached texture versions
   ├── dirty/update flags
   ├── child/parent hierarchy
   └── lifecycle processing
```

Một region biết rõ:

* Nó đã load chưa.
* Texture nào stale.
* Texture nào cần upload.
* Cache đã chuẩn bị chưa.
* Child revision nào đã thay đổi.
* Parent nào cần cập nhật.
* Khi nào được kết thúc processing.
* Khi nào được giải phóng.

Trong `MapProcessor.onRenderProcess()` của Xaero, code duyệt các `LeveledRegion`, xử lý texture trong deadline và chỉ kết thúc region khi các điều kiện như `allCleaned`, `allCached`, `allUploaded` đạt yêu cầu.

Đây là khác biệt căn bản:

```text
Simple Map:
Queue/Future thường đại diện cho việc còn phải làm.

Xaero:
Region state đại diện cho việc còn phải làm.
Queue chỉ lựa chọn region nào được tiến triển trước.
```

## Nguyên tắc phải áp dụng

> Mọi dirty state, source revision và publication state phải nằm trong một đối tượng vùng bền vững. Queue có thể bị xóa, tái tạo hoặc giới hạn mà không làm mất sự thật.

---

# 3. Simple Map vẫn còn các điểm có thể làm mất công việc

V17.4 đã sửa queue LOD chính, nhưng chưa loại bỏ toàn bộ semantic-loss path.

## 3.1 `MapProcessor` vẫn bỏ task

Trong `MapProcessor.java:20`:

```java
private static final int MAX_QUEUED_TASKS = 4096;
```

Khi đầy:

```java
dropLowestPriorityTask(task.priority);
```

Task thấp hơn có thể bị xóa.

Đối với viewport demand, task có thể được phát hiện lại sau. Nhưng kiến trúc này vẫn không bảo đảm rằng source region luôn giữ cờ:

```text
needsLoad = true
```

Nếu request không được tái phát hiện đúng thời điểm, vùng có thể trì hoãn vô hạn.

## 3.2 Mutation bus vẫn âm thầm bỏ update

Trong `MapMutationBus`:

```java
MAX_PENDING_CHUNKS = 4096;
MAX_PENDING_COLUMNS = 65536;
```

Khi đầy:

```java
if (chunks.size() >= MAX_PENDING_CHUNKS) return;
if (columns.size() >= MAX_PENDING_COLUMNS) return;
```

Đây nguy hiểm hơn request viewport.

Một block hoặc light mutation có thể là nguồn duy nhất báo rằng dữ liệu cache cũ đã stale. Nếu event bị bỏ:

* Surface có thể giữ block cũ.
* Cave có thể giữ run cũ.
* LOD có thể tiếp tục chứa màu cũ.
* Lỗi chỉ tự sửa khi có một rescan khác tình cờ đi qua.

### Hướng đúng

Không lưu từng mutation vô hạn. Khi queue áp lực, nâng cấp độ hạt:

```text
Column dirty
→ nếu quá nhiều column trong chunk:
Chunk dirty
→ nếu quá nhiều chunk trong region:
Region dirty
```

Ví dụ:

```java
RegionDirtyState {
    long dirtyChunkMaskLow;
    long dirtyChunkMaskHigh;
    boolean fullRegionRescan;
}
```

Khi 256 column của một chunk cùng dirty, không cần giữ 256 event. Chỉ cần:

```text
chunkNeedsRebuild = true
```

Không mất semantic state và còn giảm RAM.

## 3.3 Live cave scheduler vẫn giới hạn bằng cách từ chối

`CaveDisplayScheduler`:

```java
MAX_TASKS = 512;
if (queued.size() >= MAX_TASKS) return;
```

`CaveWorldSaveReader`:

```java
MAX_QUEUED_PAGES = 1024;
if (queued.size() >= MAX_QUEUED_PAGES) return false;
```

`CavePageBuildWorker`:

```java
MAX_QUEUED = 96;
return null;
```

Những giới hạn này không nhất thiết tạo lỗi ngay vì nhiều caller sẽ retry. Nhưng retry hiện là hành vi phân tán, không phải invariant tập trung.

### Hướng đúng

Mỗi page hoặc region có trạng thái:

```java
enum StageState {
    CLEAN,
    DIRTY,
    QUEUED,
    RUNNING,
    PREPARED,
    GPU_READY,
    FAILED_RETRYABLE
}
```

Nếu scheduler không nhận task:

```text
Stage vẫn DIRTY.
```

Task có thể biến mất, nhưng dirty state không được biến mất.

---

# 4. Control plane vẫn bị phân mảnh

## Hiện tại

Simple Map có:

* `MapProcessor`.
* `MapWorkScheduler`.
* `MapPublicationCoordinator`.
* `CaveDisplayScheduler`.
* `CaveWorldSaveReader`.
* `CaveTileScheduler`.
* Dirty queues trong `MapTextureManager`.
* Dirty queues trong `SurfaceLodTree`.
* Dirty queues trong cave texture manager.
* Save queues riêng.
* Cache maintenance riêng.

Dù nhiều worker cuối cùng đã dùng chung `MapWorkScheduler`, **quyết định admission** vẫn nằm ở nhiều subsystem.

## Vấn đề

Mỗi subsystem tự quyết định:

* Queue đầy hay chưa.
* Bao nhiêu task được chạy.
* Bao nhiêu page được publish.
* Khi nào retry.
* Khi nào drop viewport cũ.
* Khi nào ưu tiên minimap.
* Khi nào giữ completed payload.

Do đó có thể xảy ra:

```text
Surface nhận nhiều CPU token
Cave source đã decode nhưng không có projection token
LOD đang backlog
GPU lại dành phần lớn frame cho exact surface
Disk save đồng thời bắt đầu
```

Từng hệ thống nhìn riêng có vẻ hợp lệ, nhưng tổng pipeline không cân bằng.

## Xaero hơn ở điểm nào

Xaero tập trung tiến trình của region trong một lifecycle:

```text
load
→ prepare
→ upload
→ clean/cache
→ complete
```

Một region không tiếp tục sinh thêm vô hạn output nếu stage sau đang nghẽn.

## Hướng kiến trúc cuối

Chỉ giữ **một global work graph**:

```text
SOURCE_READ
    ↓
SOURCE_DECODE
    ↓
SOURCE_COMMIT
    ↓
PROJECT
    ↓
STYLE
    ↓
LOD_DERIVE
    ↓
GPU_PREPARE
    ↓
GPU_UPLOAD
    ↓
CACHE_COMMIT
```

Task key:

```java
record WorkKey(
    long sessionId,
    long regionKey,
    Stage stage,
    int projectionId
) {}
```

Scheduler không nhận 20 task trùng nhau. Nó chỉ cập nhật:

```text
WorkNode.dirtyMask |= newDirtyMask
WorkNode.targetRevision = latestRevision
```

---

# 5. Scheduler hiện vẫn chưa đạt chất lượng Xaero

## 5.1 CPU thread cap quá cứng

`MapWorkScheduler` giới hạn khoảng:

```text
min(4, availableProcessors / 3)
```

Điều này an toàn cho gameplay, nhưng khi fullscreen đứng yên trên CPU 12–20 thread, reconstruction có thể để nhiều core rảnh.

Không nên đơn giản tăng thành 8. Cần tách:

| Loại việc          | Concurrency             |
| ------------------ | ----------------------- |
| Minecraft snapshot | Client thread, cực thấp |
| IO read            | 1–4 tùy ổ               |
| NBT decode         | 1–4                     |
| Surface projection | 2–N                     |
| Cave projection    | 2–N                     |
| LOD derive         | 1–N                     |
| Compression/write  | 1–2                     |
| GPU publication    | Render thread           |

## 5.2 Cost vẫn là số thủ công

Task có các cost như 8, 12, 24, nhưng hai task cùng loại có thể chênh lệch lớn:

* Region file cache-hot và cache-cold.
* Vanilla palette và modded palette.
* Page trống và rừng phức tạp.
* Cave có hai run và cave có hàng chục run.
* Full projection và Layered projection.

EWMA runtime hiện giúp một phần, nhưng chưa tính:

* Input bytes.
* Output bytes.
* Số chunk.
* Số column.
* Số run.
* Allocation.
* Cache hit/miss.
* Compression ratio.

### Hướng đúng

Cost prediction:

```text
predictedCost =
    baseCost
    + inputBytes × byteFactor
    + columnCount × columnFactor
    + runCount × runFactor
    + expectedOutputBytes × outputFactor
```

Sau mỗi task:

```text
actual runtime
actual bytes
actual allocation
actual cache hit
```

được đưa vào EWMA theo loại hardware/session.

## 5.3 Thiếu memory admission

Scheduler hiện có thể nhận CPU task vì CPU queue còn chỗ, nhưng task đó có thể cần:

* 3 mảng 68×68.
* 2 output 64×64.
* Region snapshots.
* Palette.
* Completed payload giữ chờ GPU.

Khi GPU nghẽn, CPU vẫn có thể sản xuất output nhanh hơn GPU tiêu thụ.

### Hướng đúng

Mọi job lớn phải xin `MemoryLease`:

```java
MemoryLease lease = memoryBudget.tryAcquire(
    MemoryCategory.PENDING_SURFACE_BUILD,
    predictedBytes
);
```

Không có lease thì:

* Giữ dirty state.
* Không capture.
* Không allocate.
* Retry sau.

---

# 6. Publication theo render frame đã tốt hơn, nhưng vẫn chưa đúng hoàn toàn

## Hiện tại

`MapPublicationCoordinator.drainFrame()` chỉ xử lý **một projection family**:

```java
if (fullCaveRequested) {
    full cave
} else if (layeredCaveRequested) {
    layered cave
} else if (surfaceRequested) {
    surface
}
```

## Hệ quả

Nếu Full Cave đang được yêu cầu:

* Surface publication không tiến triển.
* Layered Cave không tiến triển.
* Minimap hoặc compatibility work có thể phải chờ tùy đường gọi.
* LOD family khác có thể bị starve.

Đây chưa phải shared frame ledger thật sự. Nó là:

```text
Một frame → chọn một subsystem.
```

Xaero duyệt nhiều level và nhiều region trong cùng một render process, dừng khi hết thời gian/GPU budget.

## Duplicate-frame guard chưa đáng tin cậy

Hiện dùng:

```java
DUPLICATE_FRAME_GUARD_NANOS = 750_000L;
```

Nếu hai render callback trong cùng frame cách nhau hơn 0,75 ms, budget có thể bị mở lại. Nếu hai frame cực nhanh cách nhau ít hơn ngưỡng, frame mới có thể bị bỏ.

### Hướng đúng

Truyền `frameId` thực từ render event:

```java
void beginFrame(long frameId, FrameTiming timing)
```

Mỗi frame chỉ được mở ledger một lần dựa trên ID, không dựa vào khoảng thời gian heuristic.

## Cần chia budget bằng fairness

Ví dụ một frame có 1,5 ms map budget:

```text
Minimap reserve: 0,25 ms
Visible exact: tối đa 0,45 ms
Visible branch: tối đa 0,35 ms
Cave/full cave: tối đa 0,35 ms
Maintenance: tối đa 0,10 ms
```

Phần không dùng được cho lane khác mượn sau.

Không hardcode tỷ lệ vĩnh viễn; dùng deficit round robin:

```java
lane.deficit += lane.weight;
while (lane.deficit >= predictedCost) {
    runOne();
    lane.deficit -= predictedCost;
}
```

Nhờ vậy:

* Full Cave không khóa Surface.
* Surface exact không khóa branch.
* Background không ăn reserve minimap.
* Một lane chậm vẫn không bị bỏ đói.

---

# 7. Frame budget vẫn chỉ là ước lượng CPU, chưa đo GPU thật

`MapGpuBudgetController` hiện:

* Dự đoán nanos theo `UploadKind`.
* Dự đoán bytes.
* Đo thời gian CPU quanh lệnh upload.
* Cập nhật EWMA.

Nhưng thời gian `glTexSubImage2D()` trả về không nhất thiết là thời gian GPU đã hoàn tất. Driver có thể:

* Queue lệnh nhanh rồi stall ở frame sau.
* Stall khi PBO bị tái sử dụng.
* Chờ VRAM migration.
* Deferred copy.

## Xaero có gì đáng học

Source Xaero có:

* `TextureUploadBenchmark`.
* Direct buffers.
* PBO upload/download state.
* Texture-level `canUpload`, `shouldUpload`, `preUpload`, `postUpload`.
* Time budget dựa trên khoảng trống frame.
* Upload state gắn với texture/region.

Không nhất thiết Xaero đo GPU hoàn hảo, nhưng lifecycle upload rõ ràng hơn.

## Hướng đúng

`UploadEngine` toàn cục:

```java
UploadCommand {
    TextureHandle target;
    Rect rect;
    BufferLease payload;
    int byteCount;
    MapRequestLane lane;
    long sourceRevision;
    long generation;
}
```

PBO slot:

```java
PboSlot {
    int id;
    long fence;
    int capacity;
    ByteBuffer mapped;
    SlotState state;
}
```

Trước khi tái sử dụng:

```text
Fence signaled
hoặc dùng slot khác
hoặc trì hoãn upload
```

Không dùng vòng ba PBO chỉ dựa vào hy vọng rằng driver đã dùng xong.

---

# 8. Surface snapshot vẫn là bottleneck chính trên client thread

Đây là khoảng cách hiệu năng lớn nhất còn lại.

## Hiện tại

`captureSurfacePageBuildInputs()` vẫn chạy trước khi gửi worker.

Mỗi page 64×64 có halo 68×68 và chuẩn bị:

* `long[68×68]`.
* `int[68×68]`.
* `byte[68×68]`.
* Biome palette.
* Block palette.
* HashMap index.
* Region windows.
* Light snapshot.
* Registry lookup.
* Tint/color resolution metadata.
* Remap theo từng pixel.

Buffer pool mới chỉ giảm ba mảng chính. Nó không loại bỏ:

* Lock region.
* Snapshot region.
* Palette remap.
* Các collection.
* Registry access.
* Object allocation.
* 64 giao dịch cho một region.

## Tại sao Xaero ít bị kiểu spike này hơn

Xaero giữ dữ liệu tile/region lâu dài và dựng `RegionTexture` từ cấu trúc region đã tồn tại. Nó không cần tái-snapshot toàn bộ nguồn Minecraft cho từng texture publication.

## Kiến trúc đúng

### Bước 1: Chụp chunk một lần

Khi chunk load hoặc thay đổi:

```java
ChunkSnapshot {
    long chunkKey;
    long sourceRevision;
    SectionSnapshot[] sections;
    short[] surfaceHeight;
    short[] motionBlockingHeight;
    Palette blockPalette;
    Palette biomePalette;
    byte[] blockLight;
    byte[] skyLight;
}
```

Client thread chỉ truy cập Minecraft object để tạo snapshot compact.

### Bước 2: Commit vào source database do mod sở hữu

```text
ClientLevel
   ↓
ChunkSnapshot
   ↓
RegionSourceRecord
```

Sau commit, tất cả worker chỉ đọc dữ liệu bất biến do Simple Map sở hữu.

### Bước 3: Build theo region/supertile

Một job 512×512:

1. Acquire source leases cho 32×32 chunk.
2. Resolve region palette một lần.
3. Dựng 64 leaf page.
4. Dựng branch cần thiết.
5. Trả một batch output.

Không cần 64 lần snapshot và 64 lần tạo palette.

---

# 9. Surface vẫn cần chuyển từ page-centric sang region-centric

## Đơn vị lưu trữ phù hợp

Giữ:

```text
Base map region = 512×512 block
Base leaf = 64×64 block
Một region = 8×8 leaf
```

Đây phù hợp với persistence hiện tại và gần cách Xaero tổ chức 8×8 texture trong một `LeveledRegion`.

## `SurfaceRegionRecord` đề xuất

```java
final class SurfaceRegionRecord {
    RegionKey key;

    long sourceRevision;
    long styleRevision;

    long dirtySourceTiles;      // 64 bit
    long dirtyExactTiles;       // 64 bit
    long dirtyBranchTiles;      // 64 bit
    long gpuResidentTiles;      // 64 bit
    long cacheDirtyTiles;       // 64 bit

    SurfaceTileSource[] sourceTiles;   // 64
    ExactTileOutput[] exactTiles;      // optional/hot only
    RegionStageState state;

    long[] leafVersions;        // 64
    long leafVersionSum;
}
```

Queue chỉ chứa:

```java
RegionWorkKey(regionKey, STAGE_SURFACE_PROJECT)
```

Nếu thêm 20 dirty page trong cùng region:

```text
dirtyExactTiles |= mask
```

Không tạo 20 semantic task độc lập.

---

# 10. Surface LOD đã đúng hơn nhưng còn nhiều công việc render-thread

## Điểm đã sửa tốt

`SurfaceLodTree` hiện có:

* Version-backed leaf state.
* Queue key coalescing.
* Worker cho leaf 64→32.
* CPU mask và published mask riêng.
* Branch-to-parent coverage fence.
* Ancestor fallback.

Đây là nền tảng tốt.

## Điểm chưa đạt

### 10.1 Chỉ leaf reduction chạy worker

Sau khi leaf hoàn thành, `applyPreparedLeaf()` gọi `propagate(node)` và xây các parent level cao hơn.

Phần level 1→7 vẫn có thể chạy trong publication/render thread.

Một leaf update có thể chạm:

```text
level 1
level 2
level 3
...
level 7
```

Kèm theo:

* Merge children.
* Update masks.
* Revision.
* Dirty queues.
* Save request.

### 10.2 Mỗi node quá nặng

Một node chứa gần:

```text
int[4096]
long[64] knownRows
long[64] completeRows
metadata
```

Khoảng hơn 17 KiB trước object overhead.

Node sparse vẫn trả giá gần như node đầy.

### 10.3 Bảy level tạo quá nhiều atlas/cache object

Simple Map có 7 branch level cho surface và cave.

Xaero source dùng `MAX_LEVEL=3`, nhưng mỗi level là một hệ 8×8 region nên mỗi bước bao phủ không gian lớn hơn nhiều.

Không nên sao chép con số 3 một cách máy móc. Nhưng 7 level factor-2 hiện gây:

* Nhiều atlas.
* Nhiều node.
* Nhiều file cache.
* Nhiều propagation step.
* Nhiều residency metadata.

### 10.4 Branch upload luôn gần full 66×66

Dù chỉ một quadrant thay đổi, gutter khiến pipeline thường xây và upload toàn branch.

### Hướng đúng

## Region hierarchy factor lớn hơn

Ví dụ:

```text
Leaf tile: 64×64 block

Level 0 region:
8×8 leaf = 512×512 block

Level 1 branch region:
8×8 level-0 region = 4096×4096 block

Level 2:
32768×32768 block

Level 3:
262144×262144 block
```

Mỗi level vẫn có các texture con để giữ độ chi tiết, nhưng hierarchy quản lý theo region chứ không tạo một node factor-2 cho mọi bước.

## Derivation hoàn toàn worker-owned

Worker nhận immutable child snapshot:

```java
BranchBuildInput {
    long parentKey;
    long[] childRevisions;
    ChildTextureView[] children;
    long dirtyChildMask;
}
```

Worker trả:

```java
PreparedBranch {
    pixels;
    knownMask;
    sourceVersionSum;
    dirtyRect;
}
```

Render thread chỉ:

* Validate generation/version.
* Upload.
* Atomically publish handle.

Không recursive downsample trên render thread.

---

# 11. Coarse-first vẫn chưa triệt để

Ở zoom 0.29x, người chơi không cần exact 64×64 trước.

Pipeline lý tưởng:

```text
Region source có sẵn
    ↓
Dựng trực tiếp target branch
    ↓
Hiện coverage toàn viewport
    ↓
Exact dần dần thay thế khi zoom gần
```

Pipeline hiện vẫn phần lớn:

```text
Exact page
    ↓
Level-1 branch
    ↓
Parent branch
```

Nếu cần một branch xa nhưng exact chưa dựng, branch có thể phải chờ chuỗi trung gian.

## Hướng đúng

Mỗi projector phải hỗ trợ output trực tiếp theo LOD:

```java
projectSurface(regionSource, requestedLod)
projectCave(caveArchive, projection, requestedLod)
```

Ví dụ với LOD xa:

* Mỗi output pixel lấy đại diện từ vùng 4×4 hoặc 8×8 block.
* Không dựng exact array.
* Không tạo 64 exact future.
* Không chiếm exact atlas slot.

Sau đó exact refinement chạy riêng.

## Quy tắc ưu tiên

```text
1. Ancestor coverage còn thiếu.
2. Target LOD đang nhìn thấy.
3. Exact visible.
4. Vùng ngay ngoài viewport.
5. Cache maintenance.
```

Không ưu tiên exact gần tâm nếu toàn bộ nửa màn hình còn đen.

---

# 12. Render plan vẫn còn giới hạn correctness

## 12.1 `MAX_QUADS = 8192`

Trong `MapRenderPlan.Builder.add()`:

```java
if (quads.size() >= MAX_QUADS) return false;
```

Nếu viewport cần nhiều hơn, quad bị từ chối.

Caller có thể ghi nhận việc add thất bại, nhưng không có invariant mạnh rằng khu vực đó sẽ tự chuyển sang LOD thô hơn.

### Hướng đúng

Khi vượt geometry budget:

```text
Không bỏ quad.
Giảm LOD cho subtree đó.
```

Ví dụ:

```java
if (!builder.canFit(childCount)) {
    renderAncestor(parent);
}
```

Correctness phải được giữ bằng coarser coverage.

## 12.2 Pending region chỉ giữ 256

```java
if (pending.size() >= 256) return;
```

Ở zoom xa, phần ngoài 256 region có thể không được đưa lại vào request path của plan hiện tại.

Viewport coordinator có thể vẫn yêu cầu bằng cách khác, nhưng đây tiếp tục là trạng thái phân tán.

## 12.3 Build plan còn allocation lớn

Mỗi lần build:

* Tạo `Quad` object.
* Sort theo phase và `texture.toString()`.
* Tạo `float[]` cho từng batch.
* Tạo pending set.
* Chuyển sang arrays.

Trong thời gian cold streaming, topology/content thay đổi thường xuyên nên plan có thể bị dựng lại nhiều lần.

## 12.4 Replay vẫn dùng BufferBuilder

Dù đã batch, mỗi draw vẫn đi qua CPU vertex submission thay vì một static unit quad và instance list.

### Hướng cuối

Dùng:

```text
Static unit quad VBO
+ instance buffer
+ texture/page table
```

Mỗi instance:

```java
struct MapTileInstance {
    float screenX;
    float screenY;
    float screenW;
    float screenH;
    int atlasSlot;
    int lod;
    int flags;
}
```

Render:

```text
Bind atlas/texture array
Upload instance range
Draw instanced
```

Không tạo 16 float cho mỗi quad trong Java cho mỗi plan rebuild.

---

# 13. Page table tốt hơn phụ thuộc trực tiếp vào atlas slot

Hiện render plan giữ UV/texture atlas cụ thể. Nếu slot thay đổi:

* Topology revision đổi.
* Plan có thể phải rebuild.

## Kiến trúc tốt hơn

Renderer dùng logical tile ID:

```text
TileKey → PageTableEntry
```

Page table:

```java
PageTableEntry {
    short atlasId;
    short layerOrSlot;
    int generation;
    byte lod;
    byte flags;
}
```

Khi một tile chuyển slot:

* Cập nhật page table.
* Geometry không đổi.
* Plan không cần rebuild.

Dùng double buffer:

```text
pageTableFront: renderer đọc
pageTableBack: upload engine cập nhật
frame boundary: swap
```

Điều này cũng giúp publish-before-retire trở nên rõ ràng:

1. Upload texture mới.
2. Update back page table.
3. Swap page table.
4. Sau swap mới giải phóng slot cũ.

---

# 14. GPU atlas hiện chiếm fixed VRAM khá lớn

Với profile mặc định 96 MiB, `plannedAtlasBytes()` xấp xỉ:

| Thành phần                    |    Dung lượng |
| ----------------------------- | ------------: |
| Surface exact color + glow    |     18,00 MiB |
| Cave exact 64/32/16/8         |     21,25 MiB |
| Surface + cave branch atlases |     40,94 MiB |
| **Tổng fixed atlas**          | **80,19 MiB** |

Ngoài ra còn:

* Resident content budget riêng.
* Pending upload budget 20 MiB.
* CPU exact arrays.
* Branch arrays.
* PBO staging.
* Direct buffers.
* Driver overhead.
* Legacy textures.
* Java object overhead.

Do đó `gpuBudgetMiB=96` không có nghĩa tổng map footprint là 96 MiB.

## Vấn đề khác

* VRAM probe chỉ hỗ trợ `GL_NVX_gpu_memory_info`.
* AMD và Intel dùng fallback.
* Mỗi LOD level có atlas riêng.
* Atlas được cấp toàn bộ storage dù ít dùng.
* Mỗi atlas có thể có uploader/PBO riêng.
* Glow dùng atlas riêng.

## Hướng đúng

Budget phải chia rõ:

```java
MemoryBudget {
    long gpuAtlasStorageHard;
    long gpuResidentSoft;
    long cpuSourceHard;
    long cpuDerivedSoft;
    long pendingBuildHard;
    long nativeUploadHard;
    long ioBufferHard;
}
```

Tổng hard budget phải là tổng thật, không chỉ một phần.

## Atlas tương lai

Hai lựa chọn:

### Texture array

* Mỗi tile là một layer.
* UV cố định.
* Không fragmentation 2D.
* Slot = layer.
* Dễ page table.
* Có giới hạn layer count tùy GPU.

### Resizable atlas pools

* Một vài atlas theo bucket kích thước.
* Tạo thêm atlas khi cần.
* Xóa atlas trống.
* Không preallocate tất cả level ngay từ đầu.

Texture array thường phù hợp hơn với tile có kích thước cố định 64×64 hoặc 66×66.

---

# 15. PBO hiện chưa thật sự tránh stall

`CavePboUploader` dùng ring ba PBO:

```text
glBufferData
glBufferSubData
glTexSubImage2D
```

Mỗi upload:

1. Java loop chuyển `int` ABGR thành bốn byte RGBA.
2. Orphan PBO.
3. Copy staging → PBO.
4. Submit texture upload.
5. Chuyển sang PBO tiếp theo.

## Điểm yếu

* Không có `glFenceSync`.
* Không biết PBO cũ đã hết được GPU sử dụng chưa.
* Orphaning giảm stall nhưng không bảo đảm.
* CPU chuyển từng pixel thành byte.
* Mỗi atlas có uploader/staging riêng.
* Nhiều direct buffer tồn tại song song.
* Không có một transfer queue toàn cục.

## Hướng đúng

Một `MapUploadEngine` dùng chung:

```text
4–8 PBO slot toàn hệ thống
Shared direct buffer pool
Explicit fence
Size-class buffers
Driver fallback
```

Nếu hỗ trợ:

```text
Persistent mapped PBO
```

Nếu không:

```text
Map/unmap hoặc orphaning có fence
```

Ngoài ra nên chuẩn hóa pixel format từ worker để không phải repack trên render thread:

```text
Worker output đúng byte order GPU cần.
```

---

# 16. Residency policy còn quá đơn giản

`MapResidencyManager` hiện dựa nhiều vào:

* Lane.
* Kind.
* Thời gian truy cập.
* Estimated bytes.
* Pin TTL.

## Chưa tính đủ

* Khoảng cách tới viewport.
* Tile có ancestor thay thế hay chưa.
* Chi phí reload từ disk.
* Chi phí regenerate từ source.
* Source hiện còn trong RAM hay không.
* Tile thuộc minimap ring hay chỉ lịch sử.
* Tile đang được dùng làm underlay.
* Tile có nhiều child phụ thuộc hay không.
* Atlas fragmentation.
* Dự đoán sẽ dùng lại theo hướng di chuyển.

## `enforceBudget()` có thể quét nhiều lần

Nếu mỗi vòng:

1. Tính lại tổng.
2. Duyệt entries.
3. Chọn victim.
4. Evict.
5. Lặp lại.

thì dưới pressure có thể trở thành chi phí đáng kể.

## Hướng đúng

Dùng segmented policy:

```text
Protected visible
Probation recently used
Warm source-backed
Cold disk-backed
```

Victim score:

```text
score =
    ageWeight
    + distanceWeight
    + reloadCostWeight
    + replacementCoverageWeight
    + laneWeight
    + sizeWeight
```

Không cần mô phỏng hoàn hảo. Quan trọng là:

```text
Known visible coverage không bao giờ là victim nếu không có replacement.
```

---

# 17. CPU exact output đang được giữ quá lâu

Sau GPU eviction, `PageTextureInfo` có thể vẫn giữ:

* `colorPixels`.
* `glowPixels`.

Điều này giúp restore nhanh nhưng làm RAM thực tế vượt xa resident GPU accounting.

Ví dụ một exact surface page:

```text
color 64×64×4 = 16 KiB
glow  64×64×4 = 16 KiB
Tổng             32 KiB
```

3.072 page:

```text
khoảng 96 MiB
```

chưa tính object, keys, source, LOD.

## Hướng đúng

Tách hot/warm/cold:

| Trạng thái         | Giữ gì                         |
| ------------------ | ------------------------------ |
| Hot visible        | GPU + CPU output tùy cần       |
| Warm nearby        | GPU hoặc compressed CPU output |
| Cold source-backed | Chỉ source archive             |
| Disk-backed        | Chỉ metadata/index             |

Nếu exact output có thể regenerate nhanh từ `RegionSourceRecord`, không cần giữ hai `int[]` cho hàng nghìn page.

---

# 18. String key và collection object gây overhead

Surface manager dùng nhiều:

```text
Map<String, ...>
Set<String>
String concatenation
substring
Integer.parseInt
```

Trong hot path có thể tạo:

```text
"surface:" + generation + ":" + x + "," + z
```

## Hệ quả

* Allocation.
* Hashing chuỗi.
* Parse ngược.
* Bộ nhớ lớn.
* Khó locality.
* Khó dùng bitmask region.

## Hướng đúng

Packed primitive key:

```java
long pageKey = ((long) pageX << 32) ^ (pageZ & 0xffffffffL);
```

Session/dimension/projection để ở owner hoặc một ID riêng:

```java
record SpatialKey(int dimensionId, int projectionId, long packedXZ) {}
```

Dùng primitive map nếu dependency cho phép, hoặc custom open-addressing map nhỏ.

---

# 19. Persistence surface chưa đạt mức region database

## Surface source hiện tại

`RegionDataStore` đã có:

* Region 512×512.
* Palette.
* GZIP.
* Atomic replacement.
* Pending save coalescing.

Đây không tệ.

Nhưng mỗi save vẫn có xu hướng:

* Snapshot toàn region.
* Tạo raw payload lớn.
* GZIP toàn bộ.
* Thay cả file dù chỉ một số column đổi.

## Surface LOD disk cache yếu hơn

`LodBranchDiskCache` lưu:

```text
Một node → một .lod.gz
```

Mỗi save:

* Deep-copy full 64×64.
* GZIP.
* File temp.
* Rename.
* Metadata update.
* Sau 64 writes có thể scan cây file và sort theo `lastModified`.

Ở map lớn sẽ sinh:

* Nhiều file nhỏ.
* Metadata filesystem overhead.
* Directory traversal.
* Write amplification.
* Antivirus/indexer interaction trên Windows.
* Cache trim chậm.

## Cave persistence hiện tốt hơn Surface LOD

`CaveRegionStore` đã có:

* Packed `.cvr` region.
* Append records.
* CRC32.
* Record pointer index.
* Truncated-tail recovery.
* Compaction.
* Atomic replace khi compact.

Đây là kiến trúc nên dùng làm nền cho surface và LOD, thay vì tiếp tục một file GZIP mỗi node.

## Hướng persistence cuối

Tách hai loại dữ liệu:

### Source cache

```text
surface-source/
cave-source/
```

Chứa dữ liệu đủ để project lại sau resource/style change.

### Derived cache

```text
surface-derived/
cave-derived/
lod-derived/
```

Chứa pixel/branch có thể xóa và dựng lại.

## Region container đề xuất

```text
Header
  magic
  format version
  world/session identity
  data version
  source generation
  style generation

Directory
  record key
  offset
  compressed length
  uncompressed length
  revision
  checksum

Records
  source tile
  exact tile
  branch tile
  metadata

Footer/checkpoint
```

Append journal và compaction tương tự cave store hiện có.

---

# 20. Cave hiện đã có vertical archive, nhưng vẫn chưa phải dạng cuối

Đây là phần cần đánh giá công bằng: Simple Map cave hiện **không còn hoàn toàn quét lại từ đầu cho mọi projection**.

## Những gì đã tốt

`CaveColumnData` lưu các cavity run:

```text
topY
bottomY
color
flags
```

`CaveChunkTile` lưu 256 column với:

* Scanned mask.
* Full-height mask.
* Pending mask.
* Recheck mask.
* Live-owned mask.
* Revision.
* Cursor.
* Non-destructive revalidation.

`CaveRegionStore` có packed region persistence khá tốt.

Layered và Full Cave có thể dùng chung archive.

## Nhưng archive vẫn chưa tối ưu hoàn toàn

### 20.1 Archive lưu màu cuối, không lưu đầy đủ source identity

`CaveColumnData` lưu:

```java
int[] colors;
```

Nó không giữ đầy đủ:

* Block palette ID.
* Biome ID.
* Tint source.
* Fluid identity chi tiết.
* Lighting source.
* Style-generation-independent material identity.

Khi:

* Resource pack đổi.
* Block color config đổi.
* Modded block support đổi.
* Shading algorithm đổi.
* Night/light style đổi.

archive có thể phải bị invalidate hoặc cho kết quả màu cũ.

### Hướng đúng

Archive nên lưu material record:

```java
CaveRun {
    short topY;
    short floorY;
    int materialId;
    short biomeId;
    byte blockLight;
    byte skyLight;
    byte fluidDepth;
    byte flags;
}
```

Màu được tính ở projection/style stage.

### 20.2 Object-per-column và array-per-column

Mỗi `CaveColumnData` có nhiều array riêng. Một chunk có 256 column object.

Với hàng nghìn chunk:

* Nhiều object.
* Nhiều array header.
* Fragmentation heap.
* GC scanning.
* Poor cache locality.

### Hướng đúng: Structure of Arrays

```java
CompactCaveTile {
    int[] columnOffsets;     // 257
    short[] runTopY;
    short[] runFloorY;
    int[] materialIds;
    short[] biomeIds;
    byte[] lights;
    byte[] flags;
}
```

Tất cả run của 256 column nằm trong các mảng liên tục.

### 20.3 `MAX_RUNS = 255`

Giới hạn này có thể hợp lý, nhưng cần trạng thái rõ:

```text
complete
truncated
corrupt
unknown
```

Full Cave không được xem truncated column là authoritative.

Hiện có `COMPLETE_TRUNCATED`, đây là hướng đúng, nhưng projection và UI phải luôn phân biệt rõ.

---

# 21. Live cave scan vẫn nằm trên client thread

`CaveTileScanner` là client-thread-only và đi dọc Y:

* `level.getHeight`.
* `level.getBlockState`.
* Collision checks.
* Fluid checks.
* Color resolution.
* Run creation.

Nó đã tối ưu bằng:

* Bỏ qua all-air section.
* Bỏ qua all-solid section.
* Reuse `CaveTileScanContext`.
* Persistent cursor ở scheduler.

Nhưng khi đi vào terrain mới, rất nhiều column vẫn phải đọc Minecraft world state trên client thread.

## Hướng đúng

### Client thread chỉ capture section snapshot

```java
CaveSectionSnapshot {
    Palette palette;
    long[] packedBlockIndices;
    NibbleArray blockLight;
    NibbleArray skyLight;
    SectionClassification classification;
}
```

Classification nhanh:

```text
ALL_AIR
ALL_SOLID
MIXED
FLUID
DYNAMIC
```

### Worker dựng run archive

Worker:

* Tìm open intervals.
* Tìm floor.
* Tính flags.
* Tạo compact run arrays.
* Project Layered/Full.
* Dựng LOD.

Không đọc `ClientLevel` off-thread.

Điểm quan trọng:

> Không chuyển trực tiếp `level.getBlockState()` sang worker. Phải snapshot dữ liệu mod-owned trước.

---

# 22. Cave world-save reconstruction vẫn quá page-oriented

`CaveWorldSaveReader` dùng page 64×64, tức 4×4 chunk.

Mỗi page có thể decode 16 chunk. Concurrency đã tăng thích nghi lên 1–4 page, nhưng vẫn tồn tại:

* Page queue.
* Page in-flight map.
* Projection Top-Y theo page.
* Retry theo page.
* Exact page coherence.
* Branch derive sau exact.

## Hướng đúng

Decode theo Minecraft region hoặc batch lớn:

```text
Anvil region 32×32 chunk
→ decode chunk source cache
→ commit compact cave archive
→ build target branch trực tiếp
→ exact page refinement
```

Không nhất thiết decode toàn bộ 32×32 chunk cùng lúc. Có thể dùng chunk masks:

```java
RegionDecodeState {
    BitSet neededChunks;
    BitSet decodedChunks;
    BitSet failedChunks;
}
```

Một region file chỉ mở/đọc header/index một lần cho batch.

---

# 23. Full Cave phải dùng coarse-first riêng

Full Cave là pipeline nặng nhất vì cần biết run trên toàn chiều cao.

Ở zoom xa, không nên yêu cầu mỗi exact page có đủ 4×4 chunk và 4096 column trước khi hiện coverage.

## Coarse Full Cave representation

Trong source archive có thể lưu summary theo chunk/tile:

```java
CaveSummary {
    int caveCoverage;
    short dominantFloorY;
    int dominantMaterial;
    byte waterRatio;
    byte emissiveRatio;
    boolean fullHeightComplete;
}
```

Branch xa được dựng từ summary trước.

Sau đó exact full cave mới được dựng khi:

* Zoom gần.
* Người dùng dừng pan.
* Region có đủ archive.

## Kết quả mong muốn

```text
Mở Full Cave ở 0.29x:
1. Coverage thô xuất hiện nhanh thành vùng liền mạch.
2. Màu và đường nét tăng dần.
3. Exact chỉ xuất hiện nơi screen-space cần.
```

Không phải:

```text
Nhiều rectangle 64×64 xuất hiện ngẫu nhiên.
```

---

# 24. Layered Cave Top-Y cần projection cache theo band và delta

Hiện có normalization theo band và một projection memo nhỏ. Đây là nền tốt.

Nhưng khi kéo Top-Y liên tục:

* Nhiều request stale.
* Page retarget.
* Projection cache churn.
* Exact output có thể bị dựng lại nhiều lần.

## Hướng đúng

### Cache hai cấp

```text
Band cache:
Mỗi 16 block hoặc 8 block.

Exact Top-Y overlay:
Chỉ chứa khác biệt trong band.
```

Ví dụ:

```text
Band Y=64 đại diện 64–79
Exact Top-Y=71 chỉ điều chỉnh các run cắt qua 71
```

### UI scrubbing

Khi người chơi đang kéo:

```text
Dùng nearest cached band.
Không dựng exact mỗi pixel chuyển động.
```

Sau khi dừng 100–200 ms:

```text
Dựng exact Top-Y visible area.
```

Minimap AUTO có thể cập nhật nhanh hơn nhưng chỉ trong ring nhỏ.

---

# 25. Minimap vẫn chưa độc lập như Xaero Minimap

Xaero Minimap có `MinimapWriter` riêng với:

* Persistent tile/chunk cursor.
* Time limit.
* Fixed nearby working set.
* Old tile comparison.
* `pixelChanged()` signature.
* Reuse màu nếu block/light/slope/settings không đổi.
* Pipeline nhỏ hơn World Map.

Trong Simple Map:

* Minimap có lane và reserve.
* Scanner có một số cursor.
* Renderer có minimap plan riêng.
* Nhưng minimap vẫn dùng nhiều thành phần chung với full map:

  * Exact atlas.
  * Surface page manager.
  * Residency.
  * Publication coordinator.
  * World-map render hierarchy.

## Hệ quả

Một fullscreen reconstruction hoặc cave backlog vẫn có thể tác động đến:

* Queue pressure.
* Memory pressure.
* Atlas churn.
* Render-plan state.
* Publication fairness.

## Hướng đúng

`MinimapService` độc lập:

```java
MinimapService {
    FixedTileRing ring;
    MinimapScanCursor cursor;
    MinimapTexture texture;
    ColumnSignatureStore signatures;
    MinimapBudget budget;
}
```

### Ring nhỏ cố định

Ví dụ:

```text
9×9 hoặc 13×13 chunk quanh người chơi
```

Không cần LOD tree lớn.

### Reuse signature như Xaero

Mỗi pixel lưu compact signature:

```java
long signature =
    materialId
    + biomeId
    + topY
    + light
    + slope
    + transparency
    + styleRevision;
```

Nếu signature không đổi:

```text
Giữ màu cũ.
Không resolve texture/block color lại.
```

### Chia sẻ gì với World Map?

Chỉ chia sẻ:

* Chunk snapshot source.
* Style snapshot.
* Material registry.
* Optional exact output nếu đã sẵn.

Không chia sẻ:

* Fullscreen demand queue.
* LOD publication.
* Atlas eviction.
* Render plan.
* Cold reconstruction.

---

# 26. Style state chưa hoàn toàn bất biến

Một số worker surface nhận shared concurrent caches.

Điều này giảm copy nhưng có vấn đề khác:

```text
Worker bắt đầu ở style revision N
Resource/config thay đổi
Shared cache thay đổi thành revision N+1
Worker có thể đọc hỗn hợp N và N+1
```

Source revision không mô tả đầy đủ output lúc đó.

## Hướng đúng

Mỗi resource reload hoặc color config change tạo:

```java
final class MapStyleSnapshot {
    long revision;
    MaterialStyle[] materials;
    BiomeStyle[] biomes;
    TintPolicy[] tintPolicies;
    int shadingProfile;
    int terrainSlopeMode;
}
```

Worker giữ reference immutable:

```java
MapStyleSnapshot style = styleManager.current();
```

Output tag:

```text
sourceRevision
styleRevision
projectionRevision
```

Publication chỉ chấp nhận nếu cả ba còn hợp lệ.

---

# 27. Session và lifecycle vẫn phân tán

Hiện có generation ở nhiều manager:

* Map generation.
* Cave layer generation.
* Full cave generation.
* Repository generation.
* LOD generation.
* Style generation.
* Viewport epoch.

Điều này giúp chống stale result, nhưng khó chứng minh toàn bộ pipeline không trộn dữ liệu.

## Tình huống nguy hiểm

* Chuyển dimension khi IO cũ còn chạy.
* Disconnect rồi kết nối server khác cùng dimension ID.
* Resource reload khi build đang chạy.
* Xóa/chuyển cache directory khi save pending.
* Death/rejoin.
* Singleplayer → multiplayer.
* Server address giống nhưng world khác.
* Integrated server save path thay đổi.

## Hướng đúng

Một `MapSession` duy nhất:

```java
final class MapSession {
    long sessionId;
    SessionState state;

    WorldIdentity world;
    DimensionIdentity dimension;

    long sourceGeneration;
    long styleGeneration;
    long projectionGeneration;

    CancellationToken rootToken;
}
```

State machine:

```text
CREATED
→ OPENING
→ ACTIVE
→ PAUSING
→ FLUSHING
→ CLOSED
```

Mọi task, output, upload command và cache write mang:

```text
sessionId
generation
```

Khi session đổi:

* Không cần tìm từng manager để clear.
* Root token invalid toàn bộ work.
* Old upload commands bị từ chối.
* Old write có thể hoàn thành vào đúng old directory nhưng không publish.

---

# 28. Locking và ownership chưa rõ ràng

Hiện có nhiều:

* `synchronized (pageCache)`.
* `synchronized (pages)`.
* Synchronized method.
* Concurrent maps.
* Nested manager calls.
* Render thread gọi manager có lock.
* Worker completion gọi callback có lock.

## Rủi ro

* Lock contention.
* Long critical section.
* Deadlock khó tái hiện.
* Render thread chờ worker metadata.
* Priority inversion.
* Snapshot giữ region lock quá lâu.

## Hướng đúng

Mỗi region có một owner model:

### Phương án A: region actor

Mọi mutation state của region được commit tuần tự qua scheduler. Worker chỉ tạo immutable result.

### Phương án B: striped locks

```text
256 lock stripe
regionKey → stripe
```

Critical section chỉ:

* So sánh revision.
* Swap immutable reference.
* Update masks.

Không thực hiện:

* Compression.
* Downsample.
* Registry lookup.
* GPU upload.
* Full-array copy.

bên trong lock.

---

# 29. Legacy pipeline đang làm tăng độ phức tạp

`MapTextureManager` vẫn giữ:

* Exact page atlas.
* Legacy 512×512 textures.
* Compatibility leaf publication.
* Pending compatibility queue.
* Overview texture paths.

`MAX_PENDING_LEAF_REGIONS = 8` còn có đường:

```java
pendingLeafPublications.pollFirst();
```

Đây có thể không làm mất source vì legacy region còn đó, nhưng nó cho thấy hệ thống vẫn duy trì hai authority representation.

## Hướng đúng khi đang alpha

Không nên giữ compatibility nội bộ quá lâu.

Lộ trình:

1. Viết migration reader cho cache cũ.
2. Convert cache cũ sang RegionRecord mới.
3. Chỉ renderer mới được sử dụng.
4. Xóa legacy texture path.
5. Xóa compatibility leaf queue.
6. Xóa các revision/key/cache chỉ phục vụ legacy.

Alpha là thời điểm tốt nhất để làm việc này.

---

# 30. Những phần không nên sao chép nguyên trạng từ Xaero

Mục tiêu là đạt độ ổn định tương đương, không phải chép code hoặc mọi quyết định lịch sử.

Không nên:

* Sao chép source decompile.
* Sao chép tên class/logic chi tiết.
* Giả định mọi constant của Xaero là tối ưu cho Simple Map.
* Giữ cấu trúc cũ chỉ vì Xaero cũng có class lớn.
* Dùng cùng format cache nếu không có quyền và tài liệu.
* Bắt chước hành vi không cần thiết cho scope Simple Map.

Nên học các nguyên tắc:

1. Region là authority.
2. Version propagation bền vững.
3. Processing lifecycle rõ.
4. Frame-slack publication.
5. Minimap pipeline riêng.
6. Reuse pixel khi source không đổi.
7. Buffer pool.
8. Cache theo region.
9. Queue không phải source of truth.
10. Coarse coverage trước exact.

---

# 31. Kiến trúc cuối cùng đề xuất

```text
Minecraft packets / chunk load / world-save reader
                        │
                        ▼
                ChunkSnapshotService
                        │
                        ▼
                 MapSourceDatabase
            ┌───────────┴───────────┐
            ▼                       ▼
  SurfaceRegionSource       CaveRegionArchive
            │                       │
            ├──────────────┬────────┤
            ▼              ▼        ▼
 SurfaceProjector   LayeredProjector FullProjector
            │              │        │
            └──────────────┴────────┘
                        │
                        ▼
                 RegionOutputState
              exact masks / revisions
                        │
                        ▼
                  RegionLodGraph
                        │
                        ▼
                 GPU Upload Engine
                        │
                        ▼
                   GPU Page Table
                        │
                        ▼
                    Renderer
```

Song song:

```text
MapSourceDatabase
        │
        ▼
  MinimapService
fixed ring + signatures + dedicated texture
```

Persistence:

```text
MapSourceDatabase
   ├── Surface source containers
   ├── Cave archive containers
   └── Metadata/index

RegionOutputState
   ├── Derived exact cache
   └── Derived LOD cache
```

---

# 32. Cấu trúc package đề xuất

```text
client/map/
├── session/
│   ├── MapSession.java
│   ├── MapSessionManager.java
│   ├── WorldIdentity.java
│   └── SessionGeneration.java
│
├── source/
│   ├── ChunkSnapshotService.java
│   ├── ChunkSnapshot.java
│   ├── RegionSourceDatabase.java
│   ├── SurfaceRegionSource.java
│   ├── CaveRegionArchive.java
│   └── MaterialRegistry.java
│
├── region/
│   ├── RegionKey.java
│   ├── RegionRecord.java
│   ├── RegionStage.java
│   ├── RegionDirtyMasks.java
│   └── RegionHierarchy.java
│
├── pipeline/
│   ├── MapWorkGraph.java
│   ├── MapScheduler.java
│   ├── WorkNode.java
│   ├── WorkAdmission.java
│   ├── MemoryLease.java
│   └── LaneFairness.java
│
├── projection/
│   ├── SurfaceProjector.java
│   ├── LayeredCaveProjector.java
│   ├── FullCaveProjector.java
│   └── ProjectionKey.java
│
├── lod/
│   ├── RegionLodTree.java
│   ├── BranchRecord.java
│   ├── BranchBuilder.java
│   └── CoverageFence.java
│
├── gpu/
│   ├── MapUploadEngine.java
│   ├── PboRing.java
│   ├── TexturePool.java
│   ├── PageTable.java
│   └── GpuResidencyPolicy.java
│
├── render/
│   ├── MapRenderGraph.java
│   ├── MapInstanceBuffer.java
│   ├── LodSelector.java
│   └── ViewportState.java
│
├── minimap/
│   ├── MinimapService.java
│   ├── MinimapRing.java
│   ├── MinimapScanCursor.java
│   └── ColumnSignatureStore.java
│
├── storage/
│   ├── RegionContainerStore.java
│   ├── RegionJournal.java
│   ├── CacheManifest.java
│   └── CacheMigration.java
│
└── telemetry/
    ├── MapMetrics.java
    ├── PipelineTrace.java
    └── DebugOverlay.java
```

---

# 33. Lộ trình thay đổi chuẩn

## Giai đoạn 0 — Khôi phục build đầy đủ

Trước khi thay kiến trúc tiếp:

* Đưa source vào project Gradle hoàn chỉnh.
* `gradlew clean build`.
* Chạy NeoForge client.
* Bật assertion/debug metrics.
* Tạo một world benchmark cố định.

Không nên tiếp tục nhiều thay đổi static-only liên tiếp mà không compile/runtime. Static check không phát hiện được:

* Sai event hook.
* OpenGL context.
* Mixin mismatch.
* Race.
* Stale publication.
* Driver stall.
* Actual allocation.

---

## Giai đoạn 1 — Loại bỏ mọi semantic drop

Sửa trước khi tối ưu throughput:

* `MapProcessor` không drop authority.
* `MapMutationBus` nâng column → chunk → region dirty.
* Cave scheduler từ chối task nhưng giữ durable dirty state.
* Render quad overflow chuyển sang ancestor.
* Pending region overflow được biểu diễn bằng viewport demand, không set giới hạn.
* Compatibility leaf queue được xóa hoặc không còn là authority.

### Điều kiện hoàn thành

```text
Không có đoạn code:
queue full → return → mất dirty fact.
```

---

## Giai đoạn 2 — Tạo `MapSession` và `RegionRecord`

Chưa cần đổi renderer ngay.

* Tạo session ID.
* Packed region key.
* Region state.
* Dirty masks.
* Source/output/style revision.
* Task generation.
* Root cancellation token.

Các manager cũ chuyển dần sang đọc/ghi RegionRecord.

### Điều kiện hoàn thành

Có thể hỏi một region duy nhất:

```text
Source đã có gì?
Stage nào dirty?
Stage nào đang chạy?
Output revision nào GPU đang hiển thị?
Cache revision nào đã commit?
```

mà không cần dò sáu manager.

---

## Giai đoạn 3 — Một work graph duy nhất

* Loại `MapProcessor` riêng.
* Các queue subsystem chỉ giữ scheduling hints.
* Tất cả work đi qua `MapWorkGraph`.
* Deficit round robin giữa lane.
* Memory lease.
* Adaptive CPU/IO concurrency.
* Task coalescing theo region/stage.

### Điều kiện hoàn thành

Không thể có:

```text
Surface CPU chạy đầy
trong khi LOD/GPU backlog đã vượt hard pending budget.
```

---

## Giai đoạn 4 — Surface source database và supertile build

* Chunk snapshot.
* Region source record.
* Immutable style snapshot.
* Worker project 8×8 leaf batch.
* Dirty tile mask.
* Coarse-first direct output.
* Xóa per-page region remap trên client thread.

### Điều kiện hoàn thành

Khi 64 page cùng region cần rebuild:

```text
Một region transaction
thay vì 64 capture transaction.
```

---

## Giai đoạn 5 — Thay LOD tree và renderer

* Region hierarchy.
* Worker-only branch derive.
* Version sums.
* Published page table.
* Ancestor underlay.
* LOD hysteresis.
* Quad overflow → coarser node.
* Instanced draw.
* Xóa render-plan rebuild cho slot change.

### Điều kiện hoàn thành

Pan/zoom chỉ thay:

* Viewport constants.
* Instance selection.
* Page-table lookup.

Không tạo hàng nghìn object Java.

---

## Giai đoạn 6 — Cave archive thế hệ mới

* Compact SoA run archive.
* Material ID thay final color.
* Section snapshot worker scan.
* World-save decode vào cùng source format.
* Layered và Full dùng chung archive.
* Direct branch projection.
* Band cache và Top-Y debounce.
* Exact chỉ dựng khi cần.

### Điều kiện hoàn thành

Thay Top-Y không:

* Đọc lại `.mca`.
* Parse lại NBT.
* Đọc lại Minecraft block states.
* Scan lại toàn chiều cao.

---

## Giai đoạn 7 — GPU transfer engine

* Một PBO pool toàn hệ thống.
* Fence.
* Buffer leases.
* Pixel format chuẩn từ worker.
* Texture arrays hoặc atlas pool động.
* Page table double-buffer.
* Driver calibration.
* Delayed timing metrics.

### Điều kiện hoàn thành

Không có atlas nào tự tạo riêng:

```text
staging ByteBuffer + 3 PBO
```

nếu tất cả có thể dùng transfer engine chung.

---

## Giai đoạn 8 — Persistence hợp nhất

* Áp dụng region append container như cave `.cvr` cho:

  * Surface source.
  * Surface derived.
  * Surface/cave LOD.
* CRC.
* Manifest.
* Journal.
* Compaction.
* Style/source generation riêng.
* Migration từ cache v17.

### Điều kiện hoàn thành

Cache map lớn không tạo hàng chục nghìn `.lod.gz` nhỏ.

---

## Giai đoạn 9 — Xóa legacy

Chỉ làm sau khi pipeline mới đạt parity:

* Xóa legacy `DynamicTexture` region path.
* Xóa compatibility publication.
* Xóa parse String key.
* Xóa old LOD cache path.
* Xóa các revision map trùng lặp.
* Tách các god class.

Đây là bước bắt buộc. Nếu giữ cả hai pipeline mãi, độ ổn định sẽ không đạt Xaero.

---

# 34. Bộ telemetry bắt buộc

## Theo stage

| Metric                    | Ý nghĩa                         |
| ------------------------- | ------------------------------- |
| Queue latency p50/p95/p99 | Task chờ bao lâu                |
| Execution p50/p95/p99     | Stage thực thi bao lâu          |
| Admission rejected        | Budget từ chối bao nhiêu        |
| Coalesced requests        | Tránh được bao nhiêu task trùng |
| Stale completions         | Kết quả hoàn thành nhưng bị bỏ  |
| Retry count               | Stage không ổn định             |
| Allocated bytes           | GC pressure                     |
| Source cache hit          | Có phải decode lại không        |
| Projection cache hit      | Top-Y/full reuse                |
| Branch coverage/s         | Tốc độ phủ thô                  |
| Exact pages/s             | Tốc độ refinement               |
| GPU bytes/s               | Upload throughput               |
| GPU wait/fence time       | Driver stall                    |
| Plan rebuild/s            | Renderer churn                  |
| Atlas evictions/s         | Residency churn                 |
| Cache writes/s            | Write amplification             |
| Main-thread map nanos     | Tác động gameplay               |

## Debug overlay đề xuất

```text
Simple Map Pipeline
Frame: 7.1 ms | Map main: 0.34 ms | GPU submit: 0.41 ms

CPU:
Source 2/12
Project 4/18
LOD 1/6

Coverage:
Visible known 99.8%
Target LOD 94.1%
Exact 37.6%

GPU:
Atlas 78 MiB
Resident 53 MiB
Pending 4 MiB
Uploads 1.2 MiB/s

Cache:
Surface hit 91%
Cave source hit 84%
Projection hit 72%
```

Không tối ưu bằng cảm giác. Mọi thay đổi phải có metric trước và sau.

---

# 35. Ma trận benchmark bắt buộc

## Surface

1. Đi bộ vào chunk mới.
2. Elytra tốc độ cao.
3. Teleport 5.000 block.
4. Mở map 0.29x với cache nóng.
5. Mở map 0.29x với cache lạnh.
6. Pan liên tục.
7. Zoom 2x ↔ 0.25x liên tục.
8. Atlas pressure.
9. Resource pack reload.
10. Modded biome/block lớn.

## Cave

1. Cave AUTO quanh người chơi.
2. Layered Cave đứng yên.
3. Scrub Top-Y nhanh.
4. Full Cave cache nóng.
5. Full Cave world-save decode lạnh.
6. Nether có ceiling.
7. Deep Dark và Ancient City.
8. Ocean/water cave.
9. Modded world height.
10. Column có rất nhiều cavity run.

## Lifecycle

1. Overworld → Nether → Overworld.
2. Disconnect/reconnect.
3. Hai server cùng dimension name.
4. Xóa một phần cache.
5. Cắt đuôi region file.
6. Cache checksum sai.
7. Resource reload giữa lúc build.
8. Đóng game khi save đang pending.
9. Death/rejoin.
10. Thay world mà không restart client.

## Hardware

* 60 Hz.
* 144 Hz.
* Integrated Intel/AMD.
* NVIDIA discrete.
* AMD discrete.
* HDD.
* SATA SSD.
* NVMe.
* Heap 2 GiB.
* Heap 4–8 GiB.

---

# 36. Definition of Done

## Correctness invariant

Phải đạt tuyệt đối:

1. Vùng source đã biết không trở thành đen do eviction.
2. Texture cũ chỉ retire sau khi replacement đã publish.
3. Queue không phải nơi duy nhất giữ dirty state.
4. Stale generation không được publish.
5. Minecraft world object không bị đọc tùy ý ngoài client thread.
6. OpenGL chỉ chạy render thread.
7. Cache corrupt không crash hoặc chặn load.
8. Full Cave không xem truncated archive là complete.
9. Quad budget đầy phải giảm LOD, không bỏ coverage.
10. Resource reload không trộn hai style generation.

## Mục tiêu frame pacing ban đầu

Đây là target kỹ thuật để profiler xác nhận, không phải kết quả đã đạt:

| Trạng thái           | Main-thread map p99 |
| -------------------- | ------------------: |
| Gameplay bình thường |     ≤ 0,50 ms/frame |
| Minimap riêng        |     ≤ 0,25 ms/frame |
| Đi vào terrain mới   |     ≤ 1,00 ms/frame |
| Fullscreen đứng yên  |     ≤ 1,50 ms/frame |
| Fullscreen pan/zoom  |     ≤ 1,00 ms/frame |

GPU submission target:

| Trạng thái |             p99 |
| ---------- | --------------: |
| Gameplay   | ≤ 0,75 ms/frame |
| Fullscreen | ≤ 2,00 ms/frame |

Các target phải điều chỉnh theo phần cứng yếu, nhưng governor phải giữ frame pacing thay vì cố đạt throughput bằng mọi giá.

## Mục tiêu memory

* Tổng map memory phải đo được theo category.
* Không vượt hard budget lâu dài.
* Pending payload không tăng vô hạn.
* Cache lớn không làm Java heap tăng theo toàn bộ kích thước bản đồ.
* GPU atlas storage phải được tính vào budget thật.
* Minimap luôn có reserve nhỏ và cố định.

---

# 37. Những việc không nên làm tiếp

Không nên:

1. Chỉ tăng `MAX_QUEUED_*`.
2. Chỉ tăng số worker.
3. Tăng GPU budget để che eviction bug.
4. Giữ exact page lâu hơn để che LOD chậm.
5. Thêm một cache mới cạnh các cache hiện tại.
6. Thêm một scheduler mới cho subsystem mới.
7. Giữ legacy và new path cùng tồn tại vô hạn.
8. Dựng exact trước rồi mới nghĩ tới far LOD.
9. Đo FPS trung bình mà bỏ qua p99 frame time.
10. Khẳng định đạt Xaero chỉ dựa trên static check.

Tăng queue hoặc worker trên kiến trúc hiện tại có thể làm:

```text
CPU build nhanh hơn
→ completed payload nhiều hơn
→ GPU backlog tăng
→ RAM tăng
→ atlas churn tăng
→ render plan rebuild nhiều hơn
→ FPS còn tệ hơn
```

---

# 38. Thứ tự ưu tiên cuối cùng

## P0 — Bắt buộc trước mọi tối ưu khác

1. Loại mọi semantic drop còn lại.
2. Tạo `MapSession`.
3. Tạo durable `RegionRecord`.
4. Quad overflow phải fallback LOD.
5. Một frame ledger thật bằng frame ID.
6. Publication fairness giữa surface/cave/minimap.

## P1 — Tăng hiệu năng surface mạnh nhất

1. Chunk snapshot source.
2. Region/supertile transaction.
3. Immutable style snapshot.
4. Coarse-first direct projection.
5. Worker-only LOD derive.
6. Packed keys và primitive masks.
7. Xóa legacy texture pipeline.

## P2 — Cave ngang tầm

1. Compact material-based vertical archive.
2. Worker section scan.
3. World-save decode vào cùng archive.
4. Direct coarse branch.
5. Top-Y band/delta cache.
6. Full Cave summary-first.

## P3 — GPU/render

1. Shared upload engine.
2. Explicit fences.
3. Texture array/page table.
4. Instanced rendering.
5. Incremental page-table publication.
6. Driver calibration.

## P4 — Persistence và hardening

1. Region containers cho surface/LOD.
2. Journal/CRC/compaction.
3. Migration.
4. Corruption tests.
5. Lifecycle stress.
6. Benchmark và tuning đa phần cứng.

---

# Kết luận cuối

Simple Map v17.4 đã tiến một bước đáng kể: nó bắt đầu bảo vệ đúng **coverage correctness** và giảm một số đường mất LOD. Nhưng để thực sự đạt mức ổn định như Xaero, không nên tiếp tục coi mỗi page là một giao dịch độc lập rồi thêm nhiều cache, queue và fence xung quanh nó.

Kiến trúc cuối phải chuyển sang:

```text
Authoritative Region State
+ Durable Dirty Masks
+ Unified Work Graph
+ Compact Source Database
+ Coarse-First Projection
+ Frame-Slack Publication
+ Page-Table GPU Residency
+ Dedicated Minimap Pipeline
+ Region Container Persistence
```

Điểm chuyển đổi quan trọng nhất là:

> **Region phải sở hữu trạng thái và revision. Scheduler chỉ tiến triển trạng thái đó.**

Khi nguyên tắc này được áp dụng xuyên suốt surface, cave, full cave, LOD, GPU và disk cache, các vấn đề hiện tại như lỗ đen, CPU spike, atlas churn, queue overflow, Top-Y rebuild và FPS drop sẽ không còn được xử lý bằng từng bản vá riêng lẻ mà bị loại bỏ ngay từ cấu trúc hệ thống.
