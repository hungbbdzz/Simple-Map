# Simple Map Cave — Phân tích sâu PASS130 so với Xaero

**Phạm vi:** chỉ phân tích, không sửa code.  
**Nguồn đối chiếu:** log `2026-08-09_08-34-36...`, source Simple Map PASS130, source decompile `Xaero Minimap` + `Xaero World Map` đã cung cấp.  
**Mục tiêu:** trả lời ba câu hỏi chính:

1. Vì sao Simple Map vừa load Cave chậm hơn Xaero, vừa làm frame pacing tệ hơn?
2. Vì sao khi di chuyển nhanh vẫn có những vùng Cave không hiện, dù chunk đã nằm trong `.mca`?
3. Kiến trúc nào nên đi tiếp để tiến gần hành vi của Xaero mà không chỉ tăng thread/budget?

---

## 1. Kết luận ngắn nhất

Vấn đề hiện tại **không còn chủ yếu là “đọc `.mca` chậm”**.

PASS130 đang có ba bottleneck khác nhau chồng lên nhau:

1. **Raw world data, Cave archive và dữ liệu có thể render là ba trạng thái khác nhau.** Có chunk trong `.mca` chỉ có nghĩa là raw NBT tồn tại. Để vẽ Cave, Simple Map vẫn phải đọc/decompress/DataFix/decode section palette, đưa vào vertical archive, project theo FULL/LAYERED + Top-Y, style/assemble page, đưa exact/LOD lên GPU rồi mới render được.

2. **Simple Map hiện vẫn demand-load source theo viewport.** `CaveNativeRegionImportService` cố ý **không đọc toàn bộ 32×32 chunk của `.mca` region**. Nó chỉ đọc union của các cửa sổ source 6×6 chunk mà các page đang nhìn thấy cần. Khi người chơi chạy nhanh, viewport đổi thì source lease cũ có thể bị đóng/cancel. Vì vậy raw chunk có trong `.mca` nhưng chưa chắc đã kịp đi vào Cave archive trước khi demand biến mất.

3. **PASS130 có regression ownership rất lớn:** log mới ghi **21.878 `CAVE_REGION_FOREGROUND_HANDOFF_REJECTED`**. Toàn bộ là `LAYERED + MINIMAP`, và toàn bộ có `request_owned=false`, `planner_owned=false`, `projection_owned=false`. Nghĩa là một lượng rất lớn Cave page **đã được source/projection pipeline làm xong**, nhưng tới presentation stage thì bị kết luận “viewport này không còn sở hữu page nữa” và bị từ chối. Đây là waste trực tiếp và là ứng viên mạnh nhất giải thích nghịch lý “load không nhanh nhưng vẫn lag”.

Xaero tránh tình huống này không phải vì nó đọc `.mca` thần tốc hơn. Xaero tổ chức map theo kiểu:

```text
world save (.mca)
        ↓ ingestion
persistent map data / MapRegion / MapTileChunk
        ↓ retained textures
current layer ─────── previous layer fallback
        ↓
minimap chỉ draw dữ liệu đã viết
```

Trong khi Simple Map hiện vẫn gần hơn với:

```text
.mca / live chunk
      ↓
demand source window
      ↓
vertical archive
      ↓
projection
      ↓
64×64 presentation page
      ↓
exact + branch LOD
      ↓
GPU/FBO
      ↓
viewport đổi → một phần work vừa xong bị reject/cancel/rebase
```

**Điểm cần sửa kiến trúc trước tiên không phải tăng tốc `.mca`, mà là bảo đảm một Cave page đã được làm cho “loading/writer generation hiện tại” sẽ không bị presentation của “displayed generation cũ” từ chối.** Sau đó mới tách source ingestion khỏi viewport và xử lý branch queue.

---

# 2. Bằng chứng từ log PASS130

Log dài khoảng **814,5 giây**, toàn bộ capture này ở `minecraft:overworld`.

## 2.1 Frame pacing: bottleneck nằm ở Cave trong gameplay/minimap

| Context | Samples | Avg frame | P95 | Max | Allocation trung bình | Governor pressure |
|---|---:|---:|---:|---:|---:|---:|
| GAME + Surface minimap | 301 | **8,47 ms** | 10,75 ms | 53,12 ms* | 807 MiB/s | 8,3% |
| GAME + Layered Cave minimap | 284 | **9,68 ms** | **12,13 ms** | 29,99 ms | 762 MiB/s | **32,7%** |
| GAME + Full Cave minimap | 23 | **12,77 ms** | **18,26 ms** | 19,08 ms | **2034 MiB/s** | **87,0%** |
| MapScreen + Layered Cave | 61 | **8,04 ms** | 8,74 ms | 9,43 ms | 285 MiB/s | 1,6% |
| MapScreen + Full Cave | 48 | **8,82 ms** | 11,02 ms | 13,15 ms | 408 MiB/s | 10,4% |

\* Surface có một spike chung 53 ms; không nên dùng riêng max này để kết luận Surface tệ hơn Cave.

Điểm quan trọng:

- **Fullscreen Cave không phải bottleneck chính.** MapScreen Layered ~8,0 ms và Full ~8,8 ms khá ổn.
- **Gameplay + minimap Cave mới là nơi pressure tăng.** Full Cave minimap trung bình ~12,77 ms và allocation hơn **2 GiB/s**.
- Đây là bằng chứng mạnh rằng vấn đề không chỉ nằm ở phép tính projection. Nó nằm ở **pipeline Cave chạy đồng thời với game/render/minimap lifecycle**.

### So với log PASS129 trước đó

So sánh này không phải benchmark tuyệt đối vì route/gameplay không hoàn toàn giống nhau, nhưng vẫn chỉ ra hướng regression:

| Mode | PASS129 avg | PASS130 avg | Thay đổi |
|---|---:|---:|---:|
| Layered Cave minimap | 9,77 ms | 9,68 ms | ~-0,9% |
| Layered p95 | 13,24 ms | 12,13 ms | ~-8,4% |
| Layered allocation | 1316 MiB/s | 762 MiB/s | ~-42% |
| Full Cave minimap | 9,09 ms | **12,77 ms** | **+40,5%** |
| Full p95 | 10,92 ms | **18,26 ms** | **+67%** |
| Full allocation | 410 MiB/s | **2034 MiB/s** | **~5×** |

PASS130 có cải thiện một phần Layered churn, nhưng **Full Cave minimap bị regression rõ rệt**.

---

## 2.2 PASS130 đã giảm branch admission rate, nhưng queue lại già đi cực kỳ bất thường

PASS129 cuối log:

```text
branch_updates_queued = 15,326 / ~360 s
≈ 42.5 branch update / s
branch_queue_max ≈ 10.0 s
```

PASS130:

```text
branch_updates_queued = 16,333 / ~814.5 s
≈ 20.1 branch update / s
```

Như vậy coalescing đã **giảm hơn một nửa rate admission** vào branch pipeline. Đây là tiến bộ thật.

Nhưng:

```text
latency_branch_queue_avg_ms = 292.7 ms
latency_branch_queue_max_ms = 87,417 ms
```

Tức có branch item tồn tại trong queue **87,4 giây**.

Timeline max queue age tăng:

```text
199 s  → 4.5 s
230 s  → 8.1 s
260 s  → 16.1 s
701 s  → 25.2 s
706 s  → 32.8 s
719 s  → 42.6 s
724 s  → 73.5 s
728 s  → 79.1 s
737 s  → 87.4 s
```

Trong khi cost thật của branch rất nhỏ:

```text
branch derive avg ≈ 0.030 ms
branch upload avg ≈ 0.038 ms
```

Do đó **branch queue 87 giây không phải vì downsample/derive quá nặng**. Nó là lifecycle/scheduling/admission starvation:

- work cũ vẫn sống quá lâu;
- GPU branch reservation bị deny liên tục;
- queue item retained/offscreen/inactive vẫn mang “tuổi” cũ;
- branch bị xếp cùng hệ pressure dù exact/current presentation quan trọng hơn nhiều.

Final counters:

```text
gpu_branch_reservation_denied = 10,142
gpu_cave_reservation_denied   =    317
branch_updates_dropped        =    760
```

**Branch đang tạo pressure nhiều hơn giá trị mà nó mang lại cho minimap gần.**

---

## 2.3 Regression lớn nhất: 21.878 projected Cave pages bị foreground reject

Log PASS130:

```text
CAVE_REGION_FOREGROUND_HANDOFF_REJECTED = 21,878
```

Phân loại 100% event:

```text
view               = LAYERED
lane               = MINIMAP
request_owned      = false
planner_owned      = false
projection_owned   = false
action             = revoke_stale_lease
```

Top-Y bị reject nhiều nhất:

```text
26   → 4,547
75   → 4,546
58   → 2,748
-43  → 2,545
-22  → 2,047
-14  → 1,899
24   → 1,632
43   → 1,163
7    →   700
```

Các burst rất lớn:

```text
190–200 s → 1,163
200–210 s → 2,332
210–220 s → 3,738
220–230 s → 2,753

640–650 s → 1,173
650–660 s → 2,809

770–780 s → 2,432
780–790 s → 4,722
```

PASS129 trước đó có **0** rejection kiểu này trong run đã phân tích.

### Vì sao đây là bằng chứng rất quan trọng?

`CaveRegionProjectionService.ProjectedPage` không phải raw `.mca`. Trước khi page tới đây, đã có công việc source/archive/projection thực sự xảy ra. Sau đó `UnifiedCaveTextureManager.drainRegionProjectedPages()` kiểm tra:

```text
request còn sở hữu page?
OR
VisiblePlanner còn sở hữu đúng page + đúng projectionTopY?
```

Nếu không, nó gọi `rejectForeground()`.

Nói cách khác:

> **Simple Map đang bỏ đi một lượng rất lớn kết quả Cave đã hoàn thành, không phải vì không có `.mca`, mà vì producer và presentation không còn đồng ý về generation/Top-Y/viewport ownership.**

Đây chính là loại waste có thể khiến cả hai triệu chứng cùng xuất hiện:

- CPU/GC/frame pacing tệ vì đã làm work;
- map vẫn có hole vì work đó không được phép trở thành current presentation.

### Nguyên nhân kiến trúc có độ tin cậy cao

PASS130 đã thêm `writer layer` ổn định và `displayed layer/plan` retained, nhưng hai bên chưa được gắn bằng một **presentation generation/ticket bất biến**.

Source hiện tại cho thấy:

- importer được yêu cầu với một `requestedTopY` cụ thể;
- projected page giữ `projectionTopY`;
- consumer chỉ accept nếu live `PageRequest` hoặc live `VisiblePlanner` đang sở hữu **chính xác Top-Y đó**.

Trong transition, hoàn toàn có thể xảy ra:

```text
DISPLAYED = layer cũ
WRITER    = layer mới

writer làm page mới xong
        ↓
consumer nhìn planner của displayed/current viewport
        ↓
planner không sở hữu exact writer Top-Y
        ↓
reject
```

Xaero giải quyết bài toán này bằng cách bản thân data model đã tách `loading` và `loaded`, thay vì bắt loading result phải giả vờ là displayed result mới được sống.

---

# 3. “Chunk đã nằm trong `.mca`, tại sao Cave không hiện ngay?”

## 3.1 `.mca` chỉ là raw world database, không phải map texture

Một chunk có record trong `.mca` nghĩa là raw compressed NBT đã được save. Nó **không** có nghĩa là đã có dữ liệu Cave sẵn để GPU draw.

Simple Map hiện cần qua chuỗi:

```text
Anvil .mca location table
        ↓
ChunkStorage.read
        ↓
decompression / NBT
        ↓
DataFixer nếu cần
        ↓
decode section palette / blocks / lights / biomes
        ↓
CaveChunkTile vertical archive
        ↓
FULL hoặc LAYERED projection theo Top-Y
        ↓
style / assemble 16×16 children thành presentation page
        ↓
exact atlas
        ↓
LOD branch nếu cần
        ↓
GPU residency
        ↓
minimap FBO / render
```

Vì vậy câu trả lời chính xác là:

> **Đúng, raw information có thể nằm trong `.mca`; nhưng Cave map không được lưu dưới dạng “ảnh layer đã render sẵn” trong `.mca`.**

---

## 3.2 `AnvilPagePresenceIndex` chỉ biết chunk record tồn tại

`AnvilPagePresenceIndex` đọc location table 4 KiB của region file để biết chunk nào có record.

Nó trả lời:

```text
“chunk này có trong world save hay không?”
```

Nó không trả lời:

```text
“Cave layer Y=-14 của chunk này đã decode/project/style/GPU-ready chưa?”
```

Đây là hai khái niệm khác nhau.

---

## 3.3 Simple Map hiện cố ý KHÔNG decode toàn bộ `.mca` region

Đây là điểm quyết định cho câu hỏi của bạn.

`CaveNativeRegionImportService` định nghĩa native region:

```text
32×32 chunk body
+ 1 chunk halo mỗi phía
= 34×34 source cells
= 1,156 source cells
```

Nhưng `buildSourceOrder()` chỉ xây source order từ **các page đang nằm trong visible mask**. Mỗi page 64×64 block có body 4×4 chunks và cần halo, nên source window là:

```text
6×6 chunks = 36 source chunks/page
```

Source còn có comment rõ:

> không append ~1.100 source cells còn lại của native region; chỉ decode union của visible page halos.

Nói cách khác, `.mca` có thể chứa 1024 generated chunks, nhưng Simple Map **không ingest tất cả chỉ vì region đã được nhìn thấy**.

Đây là lựa chọn hợp lý để tránh flood CPU/I/O, nhưng nó có hệ quả:

```text
player chạy nhanh
→ viewport A cần source A
→ source A mới chạy một phần
→ viewport chuyển sang B
→ demand A mất
→ source lease A có thể bị close/cancel
→ B bắt đầu admission
```

Nếu sau này nhìn lại A, raw bytes vẫn nằm trong `.mca`, nhưng nếu A chưa kịp commit vertical archive thì Simple Map phải ingest lại phần còn thiếu.

---

## 3.4 Code hiện tại còn chủ động cancel source lease không còn thuộc viewport

`cancelUnneededSourcesLocked()` đóng `SourceLease` nếu source cell không còn cần cho Surface/Cave demand hiện tại.

Mục đích ban đầu là đúng: tránh source queue kéo dài nhiều giây cho những nơi người chơi đã bỏ xa.

Nhưng với fast traversal, nó tạo một vấn đề khác:

```text
viewport lifetime
<
.mca read + decode + archive lifetime
```

thì người chơi có thể **outrun ingestion**.

Đây là khác biệt quan trọng với mô hình map-cache của Xaero: source ingestion/written map data của Xaero có lifetime dài hơn một viewport frame.

---

## 3.5 Source pipeline có tail latency thật, nhưng trung bình không chậm

PASS130 final telemetry:

```text
source queue avg     0.34 ms
source queue max    40.30 ms

Anvil read avg       1.87 ms
Anvil read max     306.97 ms
count              16,788

chunk decode avg     0.48 ms
chunk decode max   107.21 ms
count              16,787

world-source fanout avg  0.86 ms
fanout max             61.70 ms
```

Kết luận:

- bình thường `.mca` read/decode đủ nhanh;
- nhưng có tail 100–300 ms;
- một cold minimap page có thể cần nhiều source chunks;
- source concurrency bị giới hạn có chủ đích;
- nếu viewport chạy qua nhanh, 100–300 ms tail + generation ownership đủ để tạo khoảng trống tạm thời.

Tuy nhiên **tail I/O không giải thích được 21.878 completed page bị reject**. Do đó nó là yếu tố phụ, không phải root cause chính trong log này.

---

## 3.6 Decoded source cache còn rất nhiều headroom

Cuối run:

```text
decoded_bytes        ≈ 60.3 MB
decoded_target_bytes ≈ 210.9 MB
```

Tức mới dùng khoảng **28,6% target decoded-cache budget**.

Do đó không có bằng chứng rằng các hole chính là vì source cache “hết RAM rồi đẩy dữ liệu ra quá nhanh”.

Không nên giải quyết bằng cách đơn giản tăng cache RAM thêm nữa.

---

# 4. Marker trong log chứng minh “raw/archive data có rồi nhưng presentation vẫn có thể chậm”

Marker khoảng **624 s** rất hữu ích.

Có 20 page fullscreen Layered báo:

```text
indexed_mask  = 0xffff
resident_mask = 0x0
```

Tức persistent archive index biết **đủ 16 central child chunks**, nhưng tile không resident RAM.

Sau `CAVE_ARCHIVE_PAGE_REHYDRATE_REQUEST`, nhiều page được đưa về:

```text
resident_mask = 0xffff
```

chỉ trong khoảng vài chục ms/page; cả wave khoảng hơn 1 giây.

Ví dụ:

```text
605.985 request page 3,-6
606.016 ready   page 3,-6
≈31 ms

605.985 request page 4,-6
606.016 ready   page 4,-6
≈31 ms
```

Điều này cho thấy persistent archive đã có giá trị thực và có thể phục hồi nhanh.

Nếu vẫn thấy hole sau khi archive có coverage đầy đủ, phải phân biệt:

```text
NO SOURCE
```

với:

```text
source có
archive có
projection đang loading
hoặc
projection completed nhưng presentation ownership từ chối
hoặc
exact GPU chưa resident
```

Hiện telemetry chưa có một event duy nhất phân loại “hole reason”, nên người dùng nhìn thấy cùng một vùng đen nhưng pipeline có thể đang ở các trạng thái hoàn toàn khác nhau.

---

# 5. Vì sao Xaero nhìn như “không bao giờ bị” dù nó cũng phải đọc world data?

Xaero thắng ở **lifetime + fallback + presentation model**, không chỉ tốc độ I/O.

## 5.1 `.mca` là ingestion source; Xaero có map database/cache riêng

Trong Xaero World Map, `WorldDataReader` có thể đọc world save:

```text
MapRegion
  ↓
MapTileChunk
  ↓
4×4 chunks / tile chunk
```

Khi load region từ local world save, nó đọc NBT chunk, DataFix, build `MapTile`, rồi kết quả được đưa vào hệ `MapRegion/MapTileChunk` và có cache riêng.

`MapSaveLoad` còn persist region/cache (`.xwmc` và texture/cache data liên quan).

Do đó khi map đã từng được viết:

```text
Xaero minimap view
≠
“đọc lại `.mca` rồi dựng layer từ đầu”
```

Nó thường là:

```text
load/reuse map cache
→ draw existing MapTileChunk texture
```

Đây là khác biệt nền tảng.

---

## 5.2 Xaero Minimap có thể render trực tiếp World Map GPU texture

`SupportXaeroWorldmap.renderChunks()` lấy `MapRegion` → `MapTileChunk` → `LeafTexture` đã có GL color texture.

Tức nếu World Map đã có tile đó:

```text
Minimap
→ không cần tự scan `.mca`
→ không cần projection riêng
→ không cần style riêng
→ không cần upload texture mới
→ chỉ draw texture shared
```

Simple Map đã share nhiều source/cache hơn trước, nhưng vẫn còn quá nhiều stage presentation riêng của Cave minimap.

---

## 5.3 Xaero có previous-layer fallback ở đúng tầng texture

Đây là lý do rất lớn khiến người dùng không nhìn thấy hole.

Xaero giữ:

```text
lastRenderedCaveLayer
previousRenderedCaveLayer
```

Trong `SupportXaeroWorldmap.renderChunks()`:

- nếu chunk của current cave layer có GL texture → vẽ current;
- nếu current chưa ready và previous layer có texture → **vẽ previous layer chunk**.

Nghĩa là source mới có thể chậm 100–300 ms, nhưng người chơi vẫn thấy một map đầy đủ.

Xaero không cần thắng cuộc đua I/O để thắng về UX.

### Đây là điểm Simple Map phải coi là invariant

```text
current tile chưa ready
+
previous/last-good tile tồn tại
=
KHÔNG BAO GIỜ render hole
```

Fallback không được chỉ là “optimization” hay một nhánh có thể bị bỏ qua bởi planner mismatch. Nó phải là contract của renderer.

---

## 5.4 Xaero không refresh exact Cave layer khi người chơi đang di chuyển liên tục

`MinimapRenderListener` chỉ coi `region.caveStartOutdated(...)` là lý do reload khi:

```text
!playerMoving
```

Tức trong lúc chạy/leo/rơi:

```text
Y thay đổi liên tục
→ target có thể đổi
→ nhưng Xaero không ép outdated cave region refresh liên tục
→ giữ last-good
→ khi movement ổn định mới refresh
```

Đây là trade-off rất đúng cho minimap:

- freshness chậm hơn một chút;
- frame pacing tốt hơn nhiều;
- không tạo transaction mới cho mỗi thay đổi Y.

---

## 5.5 Xaero giới hạn layer switch bằng 300 ms

`MapWriter`:

```text
writingLayer chỉ đổi nếu
now - lastLayerSwitch > 300 ms
```

Tức nó coi vertical jitter/di chuyển ngắn là noise, không phải một lý do để rebuild toàn bộ view ngay.

PASS130 đã bắt đầu đi theo hướng stable writer, nhưng mới làm một nửa: writer ổn hơn, còn presentation ownership chưa trở thành một transaction thống nhất. Chính đó có thể là nguồn 21.878 handoff reject.

---

## 5.6 Khi Cave layer đổi, Xaero THU NHỎ working set thay vì enumerate full viewport

Đây là khác biệt cực kỳ rõ với Simple Map hiện tại.

Xaero World Map `MapWriter`:

```text
Cave active ngoài GuiMap:
loadDistance <= 16 chunks

caveStart vừa đổi:
loadDistance <= 4 chunks
```

Xaero Minimap writer cũng thu `loadingStart/End` về vùng rất nhỏ:

```text
Cave transition:
maxDistance = 2 map chunks

Surface → Cave cold transition:
maxDistance = 1
```

Trong khi `CaveModeTransitionPolicy` của PASS130 có comment rõ rằng implementation hiện tại **không còn shrink visible radius/admission**, mà “enumerates full visible working set immediately” rồi dựa vào CPU/GPU deadline để giới hạn work mỗi frame.

Đây là một khác biệt kiến trúc cần đảo lại.

### Hai chiến lược

Simple Map hiện tại:

```text
transition
→ enumerate full viewport
→ queue rất nhiều obligation
→ deadline chỉ làm chúng hoàn thành chậm dần
```

Xaero:

```text
transition
→ obligation set bản thân đã nhỏ
→ center/current neighborhood xong trước
→ chỉ sau đó mở rộng
```

Deadline không thay thế được một working set nhỏ. Nếu queue đã chứa quá nhiều obligation, ta vẫn phải quản lý/cancel/rebase chúng và vẫn gây allocation/churn.

---

## 5.7 Xaero có cả item budget và hard time budget

`MinimapWriter` tính:

```text
tilesToUpdate <= 100
```

và đồng thời có `timeLimit` trong writer loop.

Loop dừng khi:

```text
đủ tile
OR
hết time slice
```

Nó không cố clear backlog trong một frame.

Điểm quan trọng hơn: backlog của Xaero là **retained map tile work**, không phải hàng nghìn ephemeral viewport obligations có thể trở thành stale sau vài frame.

---

## 5.8 Xaero chỉ request một region mới mỗi finalize pass

`MinimapRenderListener.finalize()`:

```text
toRequest = 1
```

Nó có thể chọn trong các candidate gần nhất, nhưng chỉ bắt đầu một region load mới tại một pass.

Điều này nghe có vẻ “chậm”, nhưng thực tế giúp:

- I/O không burst;
- DataFix/decode không burst;
- GC không burst;
- GPU upload không burst;
- frame pacing đều.

**Xaero tối ưu consistency trước throughput peak.**

---

# 6. Vì sao Simple Map hiện có thể vừa chậm hơn vừa lag hơn?

Đây không hề mâu thuẫn.

## 6.1 “Deferred work” không đồng nghĩa “ít work”

PASS130 đã coalesce branch và trì hoãn layer writer tốt hơn, nhưng nếu một work item:

```text
được queue
→ source decode
→ projection
→ completed
→ sau đó bị ownership reject
```

thì việc defer chỉ làm work xảy ra muộn hơn, không làm nó biến mất.

21.878 handoff reject là minh chứng.

---

## 6.2 Full Cave minimap allocation ~2 GiB/s là tín hiệu nguy hiểm

Full Cave GAME:

```text
allocation ≈ 2034 MiB/s
process CPU ≈ 44.3%
governor pressure ≈ 87%
```

Đây không phải profile của một renderer “chỉ draw cached tiles”.

Nó cho thấy trong thời gian Cave minimap active, pipeline vẫn đang tạo/đổi nhiều object/buffer/work state.

Mục tiêu Xaero-style nên là:

```text
steady-state minimap frame
→ chủ yếu lookup retained tile + draw
```

chứ không:

```text
steady-state minimap frame
→ source/projection/assembly/branch lifecycle vẫn hoạt động dày đặc
```

---

## 6.3 Governor đang có nguy cơ tạo feedback loop

Hiện source concurrency native importer:

```text
NORMAL_ACTIVE_SOURCES   = 32
PRESSURE_ACTIVE_SOURCES = 12
SOURCE_SLICE             = 8
```

Cave gây pressure → governor giảm source concurrency.

Nhưng khi source bị giảm:

```text
current page load chậm hơn
→ player dễ outrun hơn
→ hole tồn tại lâu hơn
→ layer/presentation churn kéo dài
→ branch/exact work tiếp tục
→ pressure còn cao
```

Đây là feedback loop sai thứ tự ưu tiên.

Khi pressure, thứ nên bị cắt đầu tiên phải là:

```text
offscreen branch
background branch
exact refinement ngoài center
```

không phải **current-center source ingestion**.

---

# 7. Root causes xếp theo độ tin cậy

## R1 — Foreground presentation ownership regression — **Rất cao**

Bằng chứng:

```text
21,878 rejects
100% LAYERED/MINIMAP
100% request=false/planner=false/projection=false
PASS129 trước đó ≈ 0
```

Ảnh hưởng:

- waste CPU/projection;
- allocation/queue churn;
- page đã xong không trở thành current visual;
- tạo cảm giác vùng Cave “không load”.

---

## R2 — `.mca` ingestion đang bị gắn quá chặt với current viewport — **Cao**

Bằng chứng source:

- importer chỉ đọc union visible 6×6 halos;
- không append toàn bộ ~1.100 cells của region;
- unneeded source leases bị đóng;
- movement nhanh thay viewport liên tục.

Ảnh hưởng:

- người chơi có thể outrun source ingestion;
- cùng region có raw data nhưng chưa có durable Cave archive cho đường vừa chạy qua;
- revisit có thể phải tiếp tục ingest.

---

## R3 — Current/previous fallback chưa phải hard rendering invariant — **Cao**

Bằng chứng:

- log vẫn có `RENDER_NO_CONTENT`;
- writer window có trường hợp expand full với `ready_core=4/5`;
- Xaero explicitly fallback per `MapTileChunk` sang previous layer.

Ảnh hưởng:

- một missing current tile lộ thành hole;
- source latency bị phơi trực tiếp ra UI thay vì được che bởi last-good.

---

## R4 — Transition working set vẫn quá rộng — **Cao**

Bằng chứng source:

`CaveModeTransitionPolicy` hiện chủ động không shrink viewport/admission, trái với Xaero:

```text
Xaero caveStart change → loadDistance <= 4 chunks
Simple Map transition → full visible working set enumerated immediately
```

Ảnh hưởng:

- nhiều obligation cùng lúc;
- queue/cancel/rebase nhiều hơn;
- center page không được ưu tiên đủ tuyệt đối.

---

## R5 — Branch pipeline bị starvation/lifecycle pollution — **Cao**

Bằng chứng:

```text
branch derive/upload ≈ vài chục microsecond trung bình
nhưng queue max = 87.4 s
gpu branch denied = 10,142
```

Ảnh hưởng:

- không trực tiếp giải thích mọi close-zoom hole;
- nhưng góp phần governor pressure, queue memory và frame pacing;
- coarse zoom có thể thấy stale/missing branch lâu bất thường.

---

## R6 — Raw `.mca` I/O tail — **Trung bình/thấp hơn**

Bằng chứng:

```text
Anvil max ≈307 ms
decode max ≈107 ms
```

Có ảnh hưởng, nhưng trung bình rất thấp và archive rehydrate chứng minh nhiều page phục hồi nhanh. Không đủ giải thích toàn bộ triệu chứng.

---

# 8. Route tiến tới — thứ tự khuyến nghị

## ROUTE A — Sửa correctness/lifecycle trước, chưa tối ưu throughput

**Độ ưu tiên: P0**  
**Rủi ro: thấp–trung bình**  
**Mục tiêu:** loại bỏ work đã hoàn thành nhưng bị presentation reject.

### A1. Tạo một `CavePresentationGeneration`/`CaveViewTicket` bất biến

Một ticket nên chứa tối thiểu:

```text
dimension
view FULL/LAYERED
normalized 16-block band
exact projectionTopY
lane MINIMAP/FULLSCREEN
generation id
writer viewport id
```

Tách rõ:

```text
displayedTicket
loadingTicket
previousTicket
```

### A2. Producer và consumer phải dùng cùng một loading ticket

Nếu native importer đang làm page cho `loadingTicket = G42`, page G42 phải được accept vào **loading cache** miễn G42 chưa bị supersede.

Nó **không cần displayed planner hiện tại sở hữu G42**.

Sai logic hiện tại gần như:

```text
loading page mới phải được displayed planner sở hữu
→ nếu chưa display layer mới thì reject
```

Logic cần:

```text
loading page mới
→ belongs to live loading generation?
    yes → retain/admit
    no  → reject

renderer:
current tile ready? → draw current
else previous?      → draw previous
```

### A3. Chỉ reject nếu generation thật sự bị supersede

Ví dụ:

```text
G42 writer Y=75
↓
player target đổi, writer commit G43 Y=58
↓
page G42 hoàn thành sau đó
→ reject/retain as historical only
```

Không reject G42 chỉ vì `displayed=G41`.

### A4. Acceptance target

```text
CAVE_REGION_FOREGROUND_HANDOFF_REJECTED
< 100 / 10 phút
mục tiêu lý tưởng = 0 cho current loading generation
```

Tỷ lệ reject/projected offers nên <0,1%.

---

# ROUTE B — Biến previous-layer fallback thành invariant “không hole”

**Độ ưu tiên: P0/P1**  
**Rủi ro: thấp**

### B1. Per-tile fallback, không phải whole-plan fallback

Rendering contract:

```text
current exact tile available
→ draw current

current unavailable
+ previous exact tile available
→ draw previous

current + previous unavailable
+ last-known same-layer branch available
→ draw last-known

không gì có
→ coherent placeholder/unknown
```

### B2. Không expand writer “full” nếu core thiếu mà fallback không đảm bảo

PASS130 có:

```text
writer_top_y=92
full expansion after 783 ms
ready_core=4/5
```

và:

```text
writer_top_y=75
full expansion after 149 ms
ready_core=4/5
```

Hard ceiling là tốt để không deadlock, nhưng khi 1/5 core tile chưa ready thì phải có:

```text
previous tile guaranteed
```

Nếu không, hard ceiling đang trực tiếp cho phép current view lộ hole.

### B3. Success criterion

```text
RENDER_NO_CONTENT trong Cave = 0
nếu previous/last-good data tồn tại
```

Người chơi chạy nhanh có thể nhìn thấy layer cũ trong vài frame, nhưng không được nhìn thấy khoảng trống.

---

# ROUTE C — Tách `.mca` ingestion lifetime khỏi viewport lifetime

**Độ ưu tiên: P1**  
**Rủi ro: trung bình**

Đây là route trực tiếp giải quyết câu hỏi “đã có `.mca` sao chạy qua vẫn bỏ lỗ?”.

## C1. Tạo source-only recent-path corridor

Khi player di chuyển:

```text
current viewport
+
2–4 giây path vừa đi qua
+
short corridor theo hướng velocity phía trước
```

được giữ source ingestion ở priority thấp.

Quan trọng:

```text
source-only
```

Nó chỉ làm:

```text
.mca → decode → vertical archive
```

Không:

```text
style
exact GPU
branch LOD
```

### C2. Khi `.mca` read đã bắt đầu, đừng cancel chỉ vì viewport vừa rời

Có thể vẫn cancel request chưa bắt đầu. Nhưng nếu NBT read/decode đã trả phần lớn cost rồi, nên cho nó commit vào durable vertical archive.

Nguyên tắc:

```text
presentation demand có thể chết nhanh
source knowledge không nên chết cùng nó
```

### C3. Movement-aware prefetch

Từ velocity vector:

```text
player đang đi đông nhanh
→ source-only prefetch 1 native region/page strip phía đông
```

Không pre-render Cave. Chỉ đảm bảo vertical archive đã có trước khi viewport tới.

### C4. Không đọc cả `.mca` 1024 chunks synchronously

Học Xaero không có nghĩa là:

```text
chạm region → decode 1024 chunks ngay
```

Cách an toàn hơn:

```text
center/route corridor first
→ continue bounded ingestion in background
→ once decoded, persist archive
```

Mục tiêu là **durability**, không phải flood throughput.

---

# ROUTE D — Khôi phục working-set contraction giống Xaero

**Độ ưu tiên: P1**  
**Rủi ro: thấp–trung bình**

Hiện PASS130 comment chủ động chọn “enumerate full visible working set immediately”. Đây là điểm nên thay.

## D1. Layer transition chỉ có center obligation

Khi writer Top-Y/band mới commit:

```text
Phase 0: 1 center page / 3×3 page neighborhood
Phase 1: near ring
Phase 2: rest of minimap
Phase 3: offscreen/archive background
```

Đừng queue Phase 2/3 trước khi Phase 0 đạt presentation-ready hoặc fallback-safe.

## D2. Motion nhanh: ưu tiên source, không ưu tiên style

Khi horizontal/vertical velocity cao:

```text
source prefetch ↑
exact restyle outside center ↓
branch work ≈ 0
```

Khi player đứng ổn 200–300 ms:

```text
commit exact Top-Y
refine near tiles
resume branch
```

Đây gần với `!playerMoving && caveStartOutdated(...)` của Xaero.

---

# ROUTE E — Dọn branch pipeline khỏi foreground critical path

**Độ ưu tiên: P1/P2**  
**Rủi ro: thấp–trung bình**

## E1. Branch queue phải thuộc projection generation hiện hành

Hiện queue item có thể mang `firstQueuedNanos` rất lâu. Khi layer inactive:

- giữ **CPU branch pixels/cache** nếu muốn;
- nhưng không giữ một **live pending update obligation** 87 giây.

Khi view re-activate:

```text
retained pixels → reusable
new current revision cần refine → enqueue một job mới
```

Không “resurrect” tuổi queue cũ.

## E2. Visible exact > source > branch

Thứ tự pressure:

1. current center source;
2. current exact tile;
3. previous fallback availability;
4. near exact refinement;
5. visible branch;
6. offscreen/background branch.

Nếu governor pressure:

```text
branch = thứ đầu tiên bị cắt
```

Không giảm source 32→12 trong khi vẫn để branch queue/GPU retry gây pressure.

## E3. GPU denied branch không được retry foreground mỗi frame

Nếu exact leaf đang che current minimap:

```text
branch GPU denied
→ backoff mạnh / demote
```

Không để 10.142 denial trở thành một nguồn scheduler noise.

### E4. Target

```text
branch_queue_max < 1,000 ms
mục tiêu tốt < 300–500 ms cho visible work

gpu_branch_reservation_denied
↓ ít nhất 80–90%
```

---

# ROUTE F — Sau khi lifecycle ổn: giảm atomicity 64×64 ở presentation

**Độ ưu tiên: P2/P3**  
**Rủi ro: cao hơn**

Simple Map thực ra đã có `CaveProjectionTile` 16×16. Không cần rewrite projection từ zero.

Khoảng cách còn lại là:

```text
16×16 projected child
→ 64×64 presentation/style page
→ branch tree
```

Một page source convention vẫn mang halo 6×6 chunks.

## F1. Central 4×4 chunks quyết định first-visible

Halo chỉ nên refine:

```text
slope / border / style continuity
```

Không nên là barrier để central data được vẽ.

## F2. 16×16 child độc lập về revision/publication

Một child mới:

```text
update child subrect
```

không nên bắt buộc restyle/replace cả 64×64 page nếu representation cho phép.

64×64 nên là **GPU packing unit**, không nhất thiết là **source transaction unit**.

---

# 9. Telemetry bắt buộc trước khi sửa lớn tiếp

Hiện event log rất tốt nhưng vẫn thiếu một thứ quan trọng: **một vùng đen trên màn hình chính xác đang thiếu ở tầng nào?**

Nên thêm event/counter sau trước khi tiếp tục tối ưu lớn.

## 9.1 `CAVE_PAGE_HOLE_REASON`

Mỗi current visible page không thể draw phải được phân loại đúng một reason:

```text
NO_MCA_RECORD
MCA_PRESENT_NOT_DECODED
SOURCE_DECODE_IN_FLIGHT
ARCHIVE_INDEXED_NOT_RESIDENT
ARCHIVE_MISSING
PROJECTION_NOT_READY
PROJECTION_STALE
LOADING_GENERATION_NOT_ADMITTED
HANDOFF_REJECTED
EXACT_GPU_NOT_READY
CURRENT_MISSING_PREVIOUS_AVAILABLE
CURRENT_AND_PREVIOUS_MISSING
GPU_BUDGET_DENIED
```

Quan trọng nhất là phân biệt:

```text
raw source thiếu
```

với:

```text
source có nhưng presentation lifecycle sai
```

---

## 9.2 Timestamp pipeline cho mỗi sampled page

Không cần log mọi page mọi frame. Chỉ sample center + một số hole page:

```text
mca_presence_known_ms
read_start_ms
read_end_ms
decode_end_ms
archive_ready_ms
projection_start_ms
projection_ready_ms
handoff_accept_ms
exact_gpu_ready_ms
first_visible_ms
```

Sau đó ta có thể trả lời bằng số:

```text
.mca → archive = X ms
archive → projection = Y ms
projection → visible = Z ms
```

Hiện tại nhiều pass đang tối ưu “đoán” dựa trên tổng counter.

---

## 9.3 Source cancellation reason

Cần counter riêng:

```text
CAVE_SOURCE_LEASE_CANCELLED_VIEWPORT_EXIT
CAVE_SOURCE_LEASE_CANCELLED_GENERATION_SUPERSEDED
CAVE_SOURCE_LEASE_COMPLETED_AFTER_VIEWPORT_EXIT
CAVE_SOURCE_ARCHIVE_COMMITTED_OFFSCREEN
```

Đây sẽ xác nhận chính xác mức độ người chơi outrun ingestion.

---

## 9.4 Foreground handoff ratio

Thay vì chỉ reject count:

```text
offered
accepted
rejected_superseded
rejected_planner_mismatch
retained_loading_generation
```

Target phải nhìn theo tỷ lệ, không chỉ absolute count.

---

## 9.5 Branch queue oldest-item classification

Khi max age tăng, log:

```text
page key
lane
view
band
exact topY
generation
visible?
active layer?
gpu denied count
age excluding inactive time
```

Nhờ đó biết 87 giây là:

- work hiện tại thật sự starvation;
- hay historical work sống sai lifetime.

---

# 10. Kiến trúc đích đề xuất

Một kiến trúc gần Xaero nhưng vẫn giữ điểm mạnh của Simple Map:

```text
                 ┌───────────────────────┐
                 │  Anvil .mca / live    │
                 └──────────┬────────────┘
                            │
                     SOURCE INGESTION
                  (viewport-independent)
                            │
              ┌─────────────▼─────────────┐
              │ Persistent Vertical Cave │
              │ Archive per dimension    │
              └─────────────┬─────────────┘
                            │
                     16×16 projection
                            │
              ┌─────────────▼─────────────┐
              │ Retained projected tiles │
              │ by band / exact Top-Y    │
              └─────────────┬─────────────┘
                            │
             ┌──────────────┼──────────────┐
             │              │              │
        displayed G       loading G      previous G
             │              │              │
             └──────────────┼──────────────┘
                            │
                 per-tile fallback select
                            │
                    shared exact atlas
                            │
              optional branch refinement
                            │
             ┌──────────────┴──────────────┐
             │                             │
          Minimap                      World Map
        same tile data                same tile data
```

### Invariant quan trọng

**Source knowledge lifetime > viewport lifetime.**  
**Loading generation lifetime > request pulse lifetime.**  
**Displayed tile luôn có previous/last-good fallback.**  
**Branch không bao giờ quan trọng hơn current exact/source.**

---

# 11. Roadmap thực tế theo pass

## Pass kế tiếp — chỉ correctness + telemetry

Không tối ưu thêm constants.

1. Thêm `CavePresentationGeneration`.
2. Loading writer page được retain theo loading generation, không phụ thuộc displayed planner.
3. Thêm `CAVE_PAGE_HOLE_REASON`.
4. Đảm bảo current-missing → previous fallback.
5. Không full-expand nếu core thiếu mà không có fallback.

**Expected:** 21.878 handoff rejects → gần 0. Hole giảm rõ ngay cả khi raw load speed chưa tăng.

---

## Pass sau — source durability / fast movement

1. Source-only recent path corridor.
2. Đừng cancel read/decode đã tiến xa chỉ vì viewport vừa thoát.
3. Commit vertical archive offscreen.
4. Velocity-based source-only prefetch.

**Expected:** chạy nhanh qua khu đã có `.mca` không để lại strip chưa ingest; revisit gần như tức thì.

---

## Pass sau nữa — Xaero-style transition working set

1. 200–300 ms stable motion/layer gate.
2. Layer switch chỉ center 1–3 page obligation.
3. Near ring sau center.
4. Full minimap sau đó.
5. Trong movement, source ahead vẫn chạy nhưng exact restyle/branch bị giảm.

**Expected:** Cave enter/change layer không tạo spike; perceived load nhanh hơn dù total processing có thể kéo dài nền.

---

## Pass branch cleanup

1. Pending branch jobs chỉ sống khi projection generation active.
2. Inactive retained branch giữ pixels nhưng bỏ live queue obligation.
3. Reset queue-age khi restage.
4. Branch first victim khi governor pressure.

**Expected:** branch queue max từ 87 s xuống <1 s; GPU denial giảm mạnh.

---

## Cuối cùng mới cân nhắc presentation granularity

1. Child 16×16 independent publication.
2. 64×64 chỉ packing/atlas unit.
3. Halo refinement riêng.

Đây là thay đổi lớn hơn; không nên làm trước khi ownership và source lifetime ổn.

---

# 12. Success criteria cụ thể

Một run 10–15 phút có đi nhanh, rơi/leo Y, vào/ra Cave và mở World Map nên đạt:

### Correctness / hole

```text
CAVE_REGION_FOREGROUND_HANDOFF_REJECTED
≈ 0 cho current loading generation

RENDER_NO_CONTENT
= 0 nếu previous/last-good tile tồn tại
```

### Frame pacing

```text
GAME Layered Cave avg
<= Surface + 0.5 ms

GAME Layered Cave p95
<= Surface p95 + 1.0 ms

GAME Full Cave avg
mục tiêu ~9 ms hoặc thấp hơn trên cùng workload

governor pressure Cave steady-state
< 10–15%
```

### Source

Với chunk đã có `.mca`:

```text
center source/archive ready
<150–250 ms trong trường hợp bình thường
```

Nhưng quan trọng hơn:

```text
first visible via previous fallback
≈ 1 frame
```

### Branch

```text
branch_queue_max < 1 s
mục tiêu <300–500 ms visible work

gpu_branch_reservation_denied
↓ 80–90%
```

### Fast traversal

Nếu người chơi đi nhanh qua một dải chunk generated:

```text
source archive corridor vẫn tiếp tục commit
```

và khi quay lại:

```text
không phải decode lại cùng source strips từ đầu
```

---

# 13. Những hướng KHÔNG nên làm lúc này

## Không tăng thread pool trước

Handoff reject 21.878 lần nghĩa là nhiều worker hơn có thể chỉ giúp tạo **stale work nhanh hơn**.

## Không tăng GPU budget trước

10.142 branch denials không chứng minh GPU budget quá nhỏ. Branch queue 87 giây trong khi derive/upload cực rẻ cho thấy scheduling/lifetime sai trước.

## Không đọc đồng bộ toàn bộ 1024 chunks của `.mca` khi player chạm region

Có thể làm hole source giảm nhưng sẽ đổi thành I/O/CPU spike nặng hơn. Xaero có persistent region cache và bounded region loading; nó không yêu cầu synchronous full-region decode trên render frame.

## Không buộc 36 source chunks phải xong mới cho page trung tâm hiện

6×6 window có halo. Central 4×4 phải có thể trở thành first-visible độc lập; halo là refinement.

## Không tiếp tục hạ TTL request ngắn hơn

`PageRequest` là scheduling pulse. Nó không nên là ownership lifetime của một loading generation.

## Không cố làm Cave “fresh tuyệt đối” từng tick khi player đang chạy

Xaero cố tình không làm điều này. Minimap cần stable, coherent, continuous trước; exact freshness đến sau.

---

# 14. Trả lời trực tiếp từng câu hỏi

## “Tại sao đi nhanh qua lại có khu vực Cave không load, không phải tất cả đọc từ `.mca` sao?”

**Có raw data trong `.mca` không đồng nghĩa data đã nằm trong Cave archive/projection/GPU.** Simple Map hiện chỉ demand-decode các source windows đang nhìn thấy và có thể đóng source lease khi viewport rời đi. Fast traversal có thể outrun pipeline.

Trong PASS130 còn nặng hơn: **21.878 Layered minimap pages đã projection xong nhưng bị foreground ownership reject**, nên có những vùng không hiện dù source work thực tế đã được thực hiện.

## “Xaero sao gần như không bao giờ bị?”

Vì Xaero thường không bắt current layer phải ready mới có gì để vẽ:

- nó có persistent map cache riêng;
- minimap có thể draw thẳng World Map `MapTileChunk` texture;
- current Cave tile thiếu thì dùng previous layer texture;
- player đang di chuyển thì không ép caveStart-outdated region refresh liên tục;
- layer switch có delay ~300 ms;
- transition working set bị thu nhỏ rất mạnh;
- mỗi pass chỉ request rất ít region và writer có hard time budget.

Nói cách khác, Xaero **che latency bằng retention/fallback** và **giảm obligation set**, chứ không cố thắng latency bằng brute force.

## “Vì sao Simple Map lại vừa load chậm hơn vừa lag hơn?”

Vì một phần lớn work hiện tại không trở thành visible output hoặc sống quá lâu:

- 21.878 projected page bị reject;
- branch queue có item 87 giây;
- 10.142 branch GPU denials;
- Full Cave minimap allocation ~2 GiB/s;
- transition vẫn enumerate full visible set thay vì center-first obligation thực sự nhỏ.

Tức pipeline đang tiêu tốn CPU/GC/GPU scheduling nhưng không chuyển tương ứng thành “map hiện nhanh hơn”.

---

# 15. Thứ tự hành động tôi khuyến nghị

Nếu chỉ chọn **5 việc** tiếp theo, thứ tự nên là:

1. **Sửa loading/displayed generation ownership** để loại 21.878 handoff reject.
2. **Biến previous-layer fallback thành renderer invariant**, không cho hole nếu có last-good.
3. **Tách source ingestion khỏi viewport** bằng recent-path/source-only corridor và commit archive cả khi page vừa rời màn hình.
4. **Khôi phục Xaero-style small transition working set**, thay vì enumerate full viewport ngay.
5. **Dọn branch queue lifetime**, để branch không sống 87 giây và không đẩy governor vào pressure.

Chỉ sau 5 bước đó mới nên cân nhắc rewrite sâu hơn 16×16 child publication/64×64 assembly.

---

# 16. File/class đã đối chiếu

## Simple Map PASS130

- `client/cave/AnvilPagePresenceIndex.java`
- `client/cave/DecodedWorldRegionCache.java`
- `client/cave/CaveWorldSaveReader.java`
- `client/cave/CaveNativeRegionImportService.java`
- `client/cave/WorldSaveProjectionPipeline.java`
- `client/cave/UnifiedCaveTextureManager.java`
- `client/cave/CaveLodTree.java`
- `client/cave/CaveScreenSpacePolicy.java`
- `client/cave/CaveModeTransitionPolicy.java`

## Xaero Minimap

- `xaero/common/minimap/write/MinimapWriter.java`
- `xaero/common/mods/SupportXaeroWorldmap.java`

Các điểm chính:

- `loadingCaving` vs `loadedCaving`;
- cave transition radius 1–2 map chunks;
- tile budget + hard time budget;
- direct World Map chunk rendering;
- previous rendered cave layer fallback.

## Xaero World Map

- `xaero/map/MapWriter.java`
- `xaero/map/minimap/MinimapRenderListener.java`
- `xaero/map/file/worldsave/WorldDataReader.java`
- `xaero/map/file/MapSaveLoad.java`
- `xaero/map/region/MapRegion.java`

Các điểm chính:

- writing layer switch gate ~300 ms;
- Cave load distance cap 16 chunks;
- caveStart change cap 4 chunks;
- `!playerMoving` gate cho outdated cave refresh;
- only 1 region load request / finalize pass;
- persistent MapRegion/MapTileChunk cache (`.xwmc`).

---

## Final diagnosis

**Hiện Simple Map không thiếu một “thuật toán đọc `.mca` nhanh hơn”. Nó thiếu một ranh giới lifecycle giống Xaero.**

Source phải có lifetime dài hơn viewport. Loading generation phải có lifetime dài hơn request pulse. Displayed layer phải luôn có last-good fallback. Branch phải là optional refinement. Một Cave page đã được build cho loading generation hiện hành không được phép bị displayed planner cũ từ chối.

Nếu giải quyết đúng bốn invariant này, mới có thể đạt trạng thái giống Xaero: **người chơi cảm giác Cave layer đổi gần như tức thì, trong khi work thật được dàn đều ở background mà không phá frame pacing.**
