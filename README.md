# BitTorrent-Lite — Nhóm 11

Hệ thống chia sẻ file P2P bằng Java 21, Spring Boot và React. Tracker quản lý tài khoản, metadata và danh sách peer. Nội dung file truyền trực tiếp giữa các peer qua TCP.

Tracker chỉ dùng PostgreSQL 16 + Flyway ở cả dev/prod, mặc định prod; tự đọc `.env` từ working directory. Xem [cấu hình, migration và kiểm thử PostgreSQL](docs/POSTGRESQL.md). Bộ test Java hiện cần Docker Engine 25+ để chạy Testcontainers.

## Cấu trúc

Backend được tổ chức theo MVC, tách `controller`, `service`, `model`, `repository`, `dto`, `config`, `exception`. React là View; lỗi REST trả thống nhất `{code, message, status, path}` qua `AppException`. Xem [kiến trúc và hợp đồng lỗi](docs/ARCHITECTURE.md).

- `common` chứa metadata, hashing và giao thức TCP.
- `tracker-server` cung cấp xác thực, directory và quản lý swarm.
- `peer-node` cung cấp TCP Dispatcher/Worker, tit-for-tat, tải chunk song song và WebSocket.
- `peer-node/frontend` chứa dashboard React với Chunk Matrix và thông tin nguồn/hash từng chunk.

## Build và kiểm thử

Cần JDK 21+, Maven 3.9+ và Node.js/npm tương thích với Vite.
Chạy tại thư mục gốc, build frontend trước khi đóng gói backend.

```powershell
npm --prefix peer-node/frontend ci
npm --prefix peer-node/frontend test
npm --prefix peer-node/frontend run build
mvn test-compile
mvn verify
```

Maven không tự chạy npm. Nếu dùng `mvn clean`, chạy lệnh này trước khi build frontend để tránh xóa dashboard đã build trong `target`.

## Chạy trong IntelliJ IDEA

Mở `pom.xml` ở thư mục gốc dưới dạng Maven project, chọn Project SDK và Maven runner JDK 21 trở lên, rồi reload Maven.
Sau khi build frontend, chạy `mvn -pl peer-node -am process-resources` để chép dashboard vào classpath.

Tạo các cấu hình Application, đặt working directory là thư mục gốc dự án.

Tracker dùng module `tracker-server`, main class `vn.nhom11.bittorrent.tracker.TrackerApplication`, program arguments

```text
--server.port=18080 --spring.profiles.active=prod
```

Ba peer dùng module `peer-node`, main class `vn.nhom11.bittorrent.peer.PeerApplication`.
Mỗi cấu hình dùng các arguments chung sau, cộng thêm arguments tương ứng trong bảng.

```text
--peer.tracker-url=http://127.0.0.1:18080 --peer.register-user=true --peer.password=demo-password-123
```

| Peer | Arguments bổ sung | Dashboard |
|---|---|---|
| Alice | `--peer.id=idea-alice --peer.username=alice --server.port=18081 --peer.tcp.port=16881` | http://localhost:18081 |
| Bob | `--peer.id=idea-bob --peer.username=bob --server.port=18082 --peer.tcp.port=16882` | http://localhost:18082 |
| Carol | `--peer.id=idea-carol --peer.username=carol --server.port=18083 --peer.tcp.port=16883` | http://localhost:18083 |

Chạy tracker trước rồi chạy ba peer. Đăng nhập từng dashboard bằng tài khoản tương ứng và mật khẩu demo ở trên. Tracker lưu dữ liệu trong PostgreSQL. Đặt VM options `-Duser.timezone=UTC` và cấu hình `.env` trước khi chạy.

Alice dùng Publish file để chia sẻ file. Bob chọn file từ directory để tải. Giữ Alice và Bob online, rồi cho Carol tải để quan sát nguồn từ nhiều peer. Bấm từng ô Chunk Matrix để xem peer, IP/port, byte range, SHA-256 và lịch sử thử lại. Phân bố nguồn phụ thuộc trạng thái swarm tại thời điểm tải.

Nội dung được lưu tại `peer-storage/<peerId>/<fileId>/content.bin`. Tracker chỉ lưu metadata và thông tin swarm. Nút Save file xuất file đã xác minh qua trình duyệt.

Các cấu hình trên dành cho chạy thử cục bộ. Giao thức JSON/TCP của dự án không tương thích BitTorrent chuẩn. Tham khảo thêm giao thức CLI cũ trong [PROTOCOL_V1](docs/PROTOCOL_V1.md).

Swarm dùng Redis ở prod và bộ nhớ ở dev/test, TTL 90 giây. Khởi động Redis bằng `docker compose up -d redis` trước khi chạy tracker prod. Xem [Swarm Redis và batch thống kê](docs/SWARM_REDIS.md).

Tracker chọn nguồn theo rarest-first, phân phối ngẫu nhiên có trọng số và lọc peer bị ban. Peer dùng uy tín để phá hòa tốc độ trong TitForTat. Xem [API chọn nguồn](docs/SOURCE_SELECTION.md) và [uy tín, đối chiếu lưu lượng, báo cáo chunk lỗi](docs/REPUTATION.md).
