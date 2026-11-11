# Kiến trúc MVC và xử lý lỗi

Project giữ ba Maven module `common`, `tracker-server`, `peer-node`. Backend dùng Spring MVC cho REST API; dashboard React trong `peer-node/frontend` là View. Không cần thêm template HTML ở server.

## Phân lớp

| Package | Trách nhiệm |
| --- | --- |
| `controller` | Route, nhận DTO, lấy thông tin xác thực/session, trả HTTP response |
| `service` | Nghiệp vụ xác thực, đăng ký torrent, swarm, reputation, publish/download |
| `model` | Entity và dữ liệu miền nghiệp vụ |
| `repository` | Truy cập PostgreSQL, Redis hoặc lưu file/chunk cục bộ |
| `dto` | Request/response của API, độc lập với controller |
| `config` | Bean, cấu hình Spring, HTTP, WebSocket, JWT |
| `exception` | Chuyển exception thành HTTP response thống nhất |
| `event` | Phát sự kiện swarm và tiến độ tải |
| `client` (peer) | Giao tiếp HTTP với tracker |
| `network` (peer) | TCP server và worker truyền chunk |
| `security` (peer) | Kiểm tra session, origin và CSRF token |

Luồng chính: React → Controller → Service → Repository/Client. Controller không tạo JWT, đọc repository hoặc điều phối đăng ký torrent. DTO không khai báo lồng trong controller để service có thể sử dụng mà không phụ thuộc tầng HTTP.

Entry point vẫn là `vn.nhom11.bittorrent.tracker.TrackerApplication` và `vn.nhom11.bittorrent.peer.PeerApplication`; cấu hình chạy IntelliJ hiện tại tiếp tục dùng được. Các package CLI cũ `downloader`, `protocol`, `demo` vẫn phục vụ giao thức CLI riêng.

## Hợp đồng lỗi

`common/.../shared/exception` chứa:

- `ErrorCode`: mã ổn định, HTTP status và thông báo mặc định.
- `AppException`: lỗi nghiệp vụ có thông báo an toàn để trả cho người dùng.
- `ErrorResponse`: cấu trúc JSON dùng chung.

Ví dụ đăng nhập sai:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid credentials",
  "status": 401,
  "path": "/api/node/session"
}
```

Service có thể dùng thông báo mặc định hoặc cung cấp chi tiết phù hợp với tình huống:

```java
throw new AppException(ErrorCode.TORRENT_NOT_FOUND);
throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid file name");
```

`ApiErrors` và `NodeApiErrors` là `@RestControllerAdvice`, xử lý AppException và chuyển lỗi framework (JSON không hợp lệ, thiếu tham số, kiểu dữ liệu sai, giới hạn upload...) về cùng cấu trúc. Các validator miền hiện có dùng `IllegalArgumentException` được chuyển thành `INVALID_REQUEST` và giữ thông báo cụ thể. Lỗi ngoài dự kiến được ghi log ở server, chỉ trả `INTERNAL_ERROR`; lỗi I/O cục bộ trả `IO_ERROR` mà không lộ đường dẫn nội bộ.

Lỗi xác thực/ủy quyền của tracker được chuyển thành JSON ngay trong Spring Security vì phát sinh trước controller. Peer dùng AppException trong interceptor cho lỗi session/origin/token.

`HttpTrackerClient` đọc `code` và `message` từ JSON của tracker, lấy status từ HTTP response và ném `TrackerException`. Đây là `IOException` có kiểu rõ ràng để giữ cơ chế retry/fallback của downloader. Không tìm mã HTTP trong chuỗi lỗi. Tracker mất kết nối trả `TRACKER_UNAVAILABLE` (502); response không phải JSON dùng thông báo dự phòng an toàn. React `ApiError` giữ `code`, `message`, `status` để giao diện có thể xử lý theo mã và hiển thị lỗi cụ thể.

Hợp đồng này áp dụng cho REST HTTP API. Frame TCP và sự kiện lỗi của các tác vụ download bất đồng bộ vẫn dùng giao thức hiện tại.

## Kiểm tra sau khi đổi package

Xóa class build cũ để Spring không quét nhầm các class ở package cũ:

```powershell
mvn clean
npm --prefix peer-node/frontend test
npm --prefix peer-node/frontend run build
mvn verify
```

Các test hồi quy bổ sung kiểm tra cấu trúc lỗi, lỗi xác thực peer, chuyển tiếp lỗi tracker và mã lỗi phía frontend. Test tích hợp PostgreSQL/Redis cần Docker.
