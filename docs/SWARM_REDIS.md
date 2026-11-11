# Swarm Redis và thống kê

Tracker dùng PostgreSQL cho tài khoản, metadata và thống kê. Swarm tạm thời dùng Redis ở prod; profile dev/test dùng ConcurrentHashMap, không dùng H2.

## Chạy

Giữ PostgreSQL hiện có trên localhost:5433 và `.env` đang dùng. Chỉ khởi động Redis từ Compose:

```powershell
docker compose up -d redis
mvn verify
```

Chạy TrackerApplication trong IntelliJ với working directory gốc, VM options `-Duser.timezone=UTC`, arguments `--spring.profiles.active=prod`. Redis mặc định localhost:6379. Dev dùng `--spring.profiles.active=dev` và không cần Redis, nhưng vẫn cần PostgreSQL cho dữ liệu lâu dài.

## Lưu trữ và thời hạn

- `peers:{fileId}` là Redis Hash, field peerId, JSON chứa userId, username, ip, port, lastSeen và định danh file/peer. Không lưu danh sách chunk trong JSON này.
- `chunks:{fileId}:{peerId}` là Redis String nhị phân bitfield. Các cập nhật chunk là phép hợp (OR); peer còn sống chỉ thêm chunk. Muốn bỏ trạng thái chia sẻ phải gửi stopped rồi đăng ký lại.
- TTL 90 giây áp dụng theo peer cho chunks/lease. Heartbeat gia hạn. Hash chung không đặt TTL vì không thể để heartbeat của một peer giữ peer khác sống mãi hoặc xóa các peer khác.
- Sorted set quản lý hạn cuối; job định kỳ xóa peer hết hạn nguyên tử, giữ metadata đủ lâu để phát peer_left. Các thao tác Lua tránh race giữa cleanup và heartbeat.
- Redis triển khai hiện tại dành cho standalone/single shard, không dùng Redis Cluster vì các Lua script truy cập nhiều key theo layout yêu cầu.
- Metadata số chunk được nạp khi tracker khởi động và khi đăng ký file. Heartbeat không truy vấn JPA.

## Heartbeat và thống kê

Heartbeat gửi `event` (completed/stopped/started), bộ đếm tích lũy `uploaded`, `downloaded`, `sessionId`, `sequence` và `transfers` theo counterpart. userId luôn lấy từ JWT. Cần nâng cấp peer cùng tracker; các trường `uploadedDelta`/`downloadedDelta` cũ không còn là giao thức thống kê hiện tại.

Peer tính byte upload sau khi gửi, byte download sau khi xác minh và ghi chunk; không tính import file local là download. Peer gửi completed một lần khi đã đủ chunk, stopped khi đóng bình thường. Kill process hoặc mất mạng được xử lý bằng hết hạn lease.

Tracker tính delta từ bộ đếm tích lũy, gom trong bộ nhớ và ghi PostgreSQL mỗi 45 giây; completed/stopped ghi ngay cùng delta đang chờ. Session/sequence loại heartbeat trùng hoặc cũ; checkpoint Redis ở prod giữ baseline qua lần khởi động lại và dùng compare-and-set để tránh cộng trùng giữa các tracker. Mỗi batch có receipt để retry giao dịch PostgreSQL không cộng trùng khi kết quả commit không rõ. Bộ đệm chưa flush có thể mất khi tracker crash, kể cả sau khi checkpoint đã lưu; đây chưa phải giao dịch nguyên tử Redis–PostgreSQL. Xem [giao thức uy tín và giới hạn](REPUTATION.md).

## WebSocket

Tracker có STOMP endpoint `/ws/tracker`. CONNECT gửi header `Authorization: Bearer <tracker JWT>`, sau đó subscribe `/topic/swarm/<fileId>`. Topic phát event `peer_left` khi peer hết hạn hoặc stopped. Client không được SEND dữ liệu vào topic. Endpoint này riêng với WebSocket `/ws` của dashboard peer.

## Kiểm thử

```powershell
mvn test-compile
mvn verify
```

Bộ test dùng PostgreSQL và Redis container riêng, không ghi vào database local. Kiểm tra expiry 90 giây, gia hạn, cập nhật chunk đồng thời, ownership, peer_left, lifecycle và batch accounting.

Migration V2 thêm started_at/stopped_at và stats_flush_receipts; Flyway tự áp dụng khi tracker khởi động trên database đã có V1. Không sửa hoặc xóa dữ liệu V1. Redis có thể cấu hình qua SPRING_DATA_REDIS_HOST / SPRING_DATA_REDIS_PORT / SPRING_DATA_REDIS_PASSWORD. Các receipt chống ghi trùng hiện được giữ lại; chưa có job retention tự động.

Event WebSocket có dạng `{"type":"peer_left","fileId":"...","peerId":"...","timestamp":"..."}`. Simple broker đang chạy trong từng tracker; triển khai nhiều tracker cần broker relay/pub-sub chung nếu muốn mọi client nhận event do instance khác phát.
