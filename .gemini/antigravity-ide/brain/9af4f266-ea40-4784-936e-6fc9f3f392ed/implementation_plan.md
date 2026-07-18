# Implementation Plan - Dimension Switcher & Waypoint Manager List

Add a **Dimension Switcher** button above the Cave control button on the Fullscreen Map (allowing players to view Nether, End, or Overworld maps from anywhere) and a complete **Waypoint List Manager Screen** with an icon button on the toolbar.

## Proposed Features & UI Layout

### 1. Dimension Switcher (Chuyển đổi Chiều không gian trên Map)
- **Vị trí UI:** Đặt nằm ngay **phía trên** nút Cave Mode ở góc dưới-bên trái màn hình Fullscreen Map.
- **Icon / Nút:** Nút biểu tượng quả địa cầu / Cổng không gian (Dimension Portal Icon).
- **Hành động khi click:**
  - Click chuột trái: Xoay vòng nhanh giữa các Dimension đã lưu (`Current Live Level` ➔ `Overworld` ➔ `The Nether` ➔ `The End`).
  - Click chuột phải (hoặc Hover Tooltip): Hiển thị menu/tooltip tên Dimension đang chọn (ví dụ: `Dimension: Nether`).
- **Logic Renderer:** Khi chọn một Dimension khác vị trí hiện tại của player, map sẽ hiển thị dữ liệu tĩnh đã lưu của Dimension đó. Khi chuyển về "Current", map lập tức trở lại vị trí và chế độ live của player.

### 2. Waypoint List Manager Screen (Giao diện Quản lý Danh sách Waypoint)
- **Vị trí Nút mở:** Nút Icon **Lá cờ / Danh sách (Waypoint List Icon)** trên thanh Toolbar chính góc trên-bên trái, đồng thời đăng ký phím tắt mặc định **`U`** (có thể đổi phím trong Minecraft Controls).
- **Giao diện `WaypointListScreen`:**
  - **Tabs Chiều không gian:** Lọc hiển thị theo Overworld, Nether, End hoặc All.
  - **Bảng danh sách Waypoint:**
    - Tên, Tọa độ (X, Y, Z), Màu sắc điểm, Khoảng cách thực tế.
    - Công tắc **Toggle On/Off** để ẩn/hiện điểm đó trên Minimap và Fullscreen map.
    - Nút **[Edit]**: Mở màn hình chỉnh sửa Tên, Tọa độ, Màu sắc.
    - Nút **[Delete]**: Xóa điểm khỏi hệ thống.
    - Nút **[Teleport]**: Dịch chuyển tới điểm đó (nếu có quyền OP/Creative).
    - Nút **[Track / Pin]**: Bật con trỏ chỉ đường định vị tới điểm đó.
  - **Nút [Add New Waypoint]**: Thêm điểm mới theo tọa độ tùy chọn.

---

## User Review Required

> [!NOTE]
> 1. Lệnh `.\gradlew build` đã biên dịch thành công 100%, mọi tối ưu hiệu năng và sửa lỗi hiển thị sóng lượn đều đã có sẵn trong codebase.
> 2. Nút Dimension Switcher sẽ tự động phát hiện tất cả các Dimension mà người chơi đã từng đi qua và lưu trữ map.

---

## Proposed Changes

### [MODIFY] [MapUiIcons.java](file:///c:/Users/hunga/Documents/Coder/Minecraft/myMod/Simple%20Map/src/main/java/com/velorise/simplemap/client/MapUiIcons.java)
- Thêm icon `DIMENSION_GLOBE` và `WAYPOINT_LIST` vào atlas icon UI.

### [MODIFY] [MapScreen.java](file:///c:/Users/hunga/Documents/Coder/Minecraft/myMod/Simple%20Map/src/main/java/com/velorise/simplemap/client/MapScreen.java)
- Thêm nút `dimensionSwitchButton` nằm trên `caveLayerModeButton`.
- Thêm nút `waypointListButton` vào thanh Toolbar góc trên-bên trái.
- Hỗ trợ xem map theo `selectedDimension` được chọn từ nút chuyển Dimension.

### [NEW] [WaypointListScreen.java](file:///c:/Users/hunga/Documents/Coder/Minecraft/myMod/Simple%20Map/src/main/java/com/velorise/simplemap/client/WaypointListScreen.java)
- Cửa sổ danh sách Waypoint đầy đủ tính năng Lọc, Thêm, Sửa, Xóa, Teleport và Track.

### [MODIFY] [SimpleMapClientEvents.java](file:///c:/Users/hunga/Documents/Coder/Minecraft/myMod/Simple%20Map/src/main/java/com/velorise/simplemap/client/SimpleMapClientEvents.java)
- Đăng ký Keybinding `WAYPOINT_LIST_KEY` (Phím mặc định `U`) để mở danh sách Waypoint từ trong game.

---

## Verification Plan

### Automated Tests
- Lệnh biên dịch Gradle: `.\gradlew compileJava`

### Manual Verification
- Mở Fullscreen Map, nhấn nút Dimension Switcher phía trên nút Cave để soi bản đồ Nether/End khi đang ở Overworld.
- Nhấn phím `U` hoặc bấm icon Waypoint List trên Toolbar để mở màn hình Quản lý Waypoint, thử nghiệm Thêm, Sửa, Xóa, Bật/Tắt và Teleport.
