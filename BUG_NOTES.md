# BÁO CÁO & PHÂN TÍCH KỸ THUẬT CÁC LỖI TRÊN CÁC BẢN PORT
*(Tài liệu ghi chú kỹ thuật nội bộ dự án Simple-Map - Cập nhật ngày 26/10/2026)*

> **LƯU Ý:** Tài liệu này được tạo theo yêu cầu để **ghi nhận chi tiết nguyên nhân gốc rễ và hiện tượng**, phục vụ đối chiếu dữ liệu log trước khi tiến hành chỉnh sửa mã nguồn cho cả 3 bản port.

---

## 1. Lỗi Texture Icon Pin (Ghim Tọa Độ) Bị Lỗi Caro Tím/Đen (Missing Texture)

* **Nền tảng phát hiện**: **Fabric 1.20.1 & Forge 1.20.1**.
* **Đối chiếu bản 1.21.1**: Cả **NeoForge 1.21.1 và Fabric 1.21.1 đều hiển thị CHUẨN** (Red X), hoàn toàn không bị lỗi này.
* **Ảnh chụp bằng chứng**: `media_1792995738698_db3b0bc1.png` trên Fabric 1.20.1 (Icon pin ghim khoảng cách "201 blocks" bị biến thành khối caro đen - hồng tím đặc trưng của texture bị thiếu).
* **Vị trí mã nguồn**:
  - `fabric-1.20.1/src/main/java/com/velorise/simplemap/client/MapRenderer.java` (dòng 80-82 và dòng 813).
  - `forge-1.20.1/src/main/java/com/velorise/simplemap/client/MapRenderer.java`.
* **Nguyên nhân kỹ thuật**:
  - Trong `MapRenderer.java` có khai báo:
    ```java
    private static final ResourceLocation RED_X_TEXTURE =
            new ResourceLocation("minecraft", "textures/map/decorations/red_x.png");
    ```
  - **Sự khác biệt phiên bản Minecraft**:
    - Trong **Minecraft 1.21.1**: Mojang đã tách nhỏ atlas icon bản đồ thành từng file ảnh riêng lẻ trong thư mục `textures/map/decorations/` (bao gồm `red_x.png`). Do đó các bản 1.21.1 tìm thấy file và hiển thị bình thường.
    - Trong **Minecraft 1.20.1**: Vanilla Minecraft **hoàn toàn không có file** `textures/map/decorations/red_x.png`! Toàn bộ icon trang trí bản đồ của 1.20.1 nằm chung trong một atlas duy nhất là `textures/map/map_icons.png`.
    - Khi mod cố gắng bind đường dẫn không tồn tại trên 1.20.1, game sẽ fallback về texture lỗi màu tím/đen (`missingno`).
* **Giải pháp dự kiến khi fix**:
  - Đóng gói trực tiếp file icon `red_x.png` sạch đẹp vào tài nguyên của chính mod: `assets/simplemap/textures/gui/pin.png` hoặc `assets/simplemap/textures/gui/red_x.png`. Khi đó cả 4 bản (1.21.1 & 1.20.1) đều dùng chung một asset độc lập, không phụ thuộc vào sự thay đổi đường dẫn của Mojang.

---

## 2. Lỗi Không Lưu Cave Layer & Bị Bật Ngược Khi Kéo Về AUTO

* **Nền tảng phát hiện**: **Fabric 1.20.1 & Forge 1.20.1**.
* **Đối chiếu bản 1.21.1**:
  - **NeoForge 1.21.1 (bản gốc)**: Hoạt động hoàn hảo, kéo về AUTO giữ chuẩn AUTO.
  - **Fabric 1.21.1**: **Hoạt động tốt, kéo về AUTO ăn ngay**, không bị lỗi thanh trượt!
* **Hiện tượng chi tiết trên 2 bản 1.20.1**:
  1. **Khi kéo slider về AUTO**: Người chơi kéo thanh trượt Cave Layer xuống kịch sàn bên trái (vùng `AUTO`), nhưng khi thả tay ra thì slider không giữ `AUTO` mà lập tức **bị nảy/reset ngược lại về giá trị số độ cao thủ công trước đó**!
  2. **Khi tắt/mở lại bản đồ**: Khi chỉnh sang một layer bất kỳ rồi đóng map (`M` hoặc `Esc`) và mở lại, layer đã chọn không được lưu lại mà bị nhảy về giá trị cũ.
* **Vị trí mã nguồn**:
  - `MapScreen.java` (`CaveLayerSlider.commitLayer()`, `applyValue()`, `onRelease()`, `syncFromMode()`).
  - `CaveMode.java` (`MANUAL_TOP_Y`, `setManualLayer()`, `setAutoTopY()`, `getLayerY()`).
  - `MapManager.MapConfig`.
* **Nguyên nhân kỹ thuật**:
  1. **Xung đột giữa `isAutoSelection()` và `syncFromMode()` trên 1.20.1**:
     - Trong `CaveLayerSlider`, vùng AUTO được định nghĩa bởi `AUTO_ZONE = 0.045`:
       ```java
       private boolean isAutoSelection() {
           return this.value <= AUTO_ZONE;
       }
       ```
     - Trên 1.20.1, chuỗi event chuột của `AbstractSliderButton` (thao tác click -> drag -> release) gọi `applyValue()` với giá trị làm tròn khác với 1.21.1, khiến `CaveMode.setAutoTopY(mc)` vừa xóa xong thì `syncFromMode()` ở khung hình tiếp theo lại đọc giá trị cũ và giật slider ngược lại số trước đó.
  2. **Thiếu cơ chế lưu cấu hình (Persistence Gap)**:
     - `MapConfig` chỉ lưu `caveDimensionModes` (`OFF`, `LAYERED`, `FULL`). Hoàn toàn không có trường lưu giá trị độ cao thủ công (`manualTopY`) theo dimension vào file `config.json`.
* **Giải pháp dự kiến khi fix**:
  - Đảm bảo `commitLayer()` cập nhật giá trị `value = 0.0` dứt khoát và khóa không cho `applyValue()` gọi lặp lại.
  - Thêm cờ chặn `syncFromMode()` đè lại giá trị vừa commit.
  - Bổ sung `caveDimensionManualY` vào `MapConfig` để lưu vĩnh viễn lựa chọn của người dùng.

---

## 3. Lỗi Full Cave Ở Xa Không Load / Không Đáp Ứng Trên Cả 3 Bản Port

* **Nền tảng phát hiện**: Cả 3 bản port (`fabric-1.21.1`, `forge-1.20.1`, `fabric-1.20.1`).
* **Đối chiếu bản gốc NeoForge 1.21.1**: **Bản gốc NeoForge 1.21.1 chạy tất cả đều ngon**, nạp Full Cave ở xa cực kỳ mượt mà, bao quát toàn bộ thế giới mà không bị rỗng/đen hay đứng hình!
* **Hiện tượng chi tiết**:
  - Khi xem Full Cave (`CaveType.FULL`), các khu vực bản đồ ở xa (ngoài render distance của Client, phải nạp từ file Anvil `.mca` trên đĩa) hoàn toàn không hoạt động, không nạp được và không hiển thị (bị rỗng đen hoặc đứt đoạn).
  - Ngược lại, nếu chuyển sang chế độ `Cave Layers` (xem từng tầng độ cao Y), các vùng hang động ở xa lại nạp và hiển thị bình thường!
* **Vị trí mã nguồn**:
  - `CaveTextureManager.java` (`CaveMapManager.sliceAnvilRegion()`, `getMcaDirectory()`, `hasRegionFile()`).
  - `CaveMode.java` (`branchOnly()`, `CavePageHoleReason`).
  - `MapViewLoadPlanner.java` & `MapPerformanceGovernor.java`.
* **Nguyên nhân kỹ thuật**:
  1. **Khác biệt môi trường Server/WorldPath giữa NeoForge và Fabric/Forge 1.20.1**:
     - `getMcaDirectory(targetDimId)` phụ thuộc vào `Minecraft.getInstance().getSingleplayerServer().getWorldPath(LevelResource.ROOT)`.
     - Cần kiểm tra xem đường dẫn thư mục `region/` có bị sai lệch hoặc quyền truy cập file trên các bản port có bị block hay không.
  2. **Phân bổ hạn ngạch (Quota/Budget) & Thread Pool**:
     - Full Cave có khối lượng quét block cực lớn (384 blocks độ cao).
     - Trên NeoForge, scheduler cấp hạn ngạch nạp ổn định. Trong khi đó, trên Fabric và Forge 1.20.1, các thread nạp nền (`SimpleMap-CaveIO`, `SimpleMap-Worker`) có thể bị điều phối ưu tiên thấp hơn render thread hoặc bị `MapPerformanceGovernor` coi là quá tải và tự động hủy bỏ yêu cầu (`exactBuildDiscarded`).
  3. **Cơ chế LOD Branch đối với Full Cave ở khoảng cách xa**:
     - Ở zoom xa (`scale <= 0.25f`), `CaveMode.branchOnly(...)` kích hoạt chế độ nạp nhanh cây LOD. Nếu bản port chưa tạo file `.scdat` cho Full Cave thì các branch node sẽ trả về rỗng, trong khi Cave Layer thì đã có sẵn cache.
* **Giải pháp dự kiến khi fix**:
  - Sử dụng công cụ đo đạc telemetry từ `run-debug.bat` (nhấn `F10` khi lia đến vùng Full Cave ở xa) để xem các mã sự kiện: `CavePageHoleReason`, số lượng `exactBuildDiscarded` và thời gian I/O của thread `SimpleMap-CaveIO`.
  - Tối ưu hóa thuật toán slice Anvil của Full Cave và đồng bộ cơ chế điều phối thread giống bản chuẩn NeoForge 1.21.1.

---

## 4. Bảng Tổng Hợp So Sánh Trạng Thái Giữa 4 Phiên Bản

| Hạng mục chức năng | NeoForge 1.21.1 *(Bản gốc chuẩn)* | Fabric 1.21.1 *(Port)* | Forge 1.20.1 *(Port)* | Fabric 1.20.1 *(Port)* |
| :--- | :---: | :---: | :---: | :---: |
| **Bản đồ bề mặt Overworld** | Hoạt động tốt | Hoạt động tốt (lag nhẹ lúc đầu pan) | Hoạt động tốt | Hoạt động tốt |
| **Cave Layers (xem từng tầng Y)** | Hoạt động tốt | Hoạt động tốt | Hoạt động tốt | Hoạt động tốt |
| **Full Cave ở xa (Anvil .mca)** | **Hoạt động tốt** | **Lỗi (Không load / bị đen)** | **Lỗi (Không load / bị đen)** | **Lỗi (Không load / bị đen)** |
| **Thanh trượt kéo về AUTO** | **Hoạt động tốt** | **Hoạt động tốt (Ăn ngay AUTO)** | **Lỗi (Bị reset về số cũ)** | **Lỗi (Bị reset về số cũ)** |
| **Lưu Cave Layer khi tắt/mở** | Tạm nhớ trong phiên | Tạm nhớ trong phiên | Bị reset | Bị reset |
| **Texture Pin Icon (Ghim)** | Chuẩn (Red X) | **Chuẩn (Red X)** | **Lỗi (Caro tím/đen)** | **Lỗi (Caro tím/đen)** |

*(Toàn bộ mã nguồn hiện tại được bảo toàn 100%, sẵn sàng cho quá trình test lấy log qua các script debug trước khi tiến hành fix đồng loạt).*

*(Toàn bộ mã nguồn hiện tại được giữ nguyên, sẵn sàng cho việc kiểm thử qua script debug).*

