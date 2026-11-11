# Uy tín và đối chiếu lưu lượng

## API

- `GET /api/users/{id}/reputation` cần JWT. Trả số byte verified upload/download, ratio và score. Ratio = verified_uploaded / max(1,verified_downloaded); score = 100 * ratio / (1 + ratio).
- `POST /api/reports` cần JWT. Reporter lấy từ JWT; body gồm fileId, peerId, chunkIndex, expectedHash, actualHash. targetUserId có thể bỏ qua để tracker xác định từ swarm; nếu truyền phải khớp chủ peer.
- SourcePlan trả reputation (score 0..100) của từng nguồn. TitForTatManager nhóm tốc độ trong phạm vi 10% so với peer nhanh nhất của nhóm, rồi dùng reputation phá hòa; tốc độ khác biệt lớn vẫn ưu tiên tốc độ.

Báo cáo sai hash phải chỉ đúng metadata, chunk hợp lệ, expected hash chính xác, actual hash khác expected, peer còn quảng bá chunk và không tự báo cáo mình. Một user chỉ được một phiếu cho cùng target peer trong cửa sổ 10 phút, không phụ thuộc số chunk. Bốn user khác nhau kích hoạt ban 10 phút. Phiếu tiếp theo không kéo dài ban đang có. Ban hết hạn tự mất hiệu lực khi chọn nguồn, không cần xóa bằng tay. Đây là tín hiệu nghi ngờ theo quorum, không phải bằng chứng mật mã về hành vi của peer; tạo nhiều tài khoản/thông đồng chưa được giải quyết.

## Heartbeat tích lũy

Peer gửi `uploaded`, `downloaded`, `sessionId`, `sequence`, và `transfers` gồm `{peerId, uploadedTo, downloadedFrom}`. Tổng theo counterpart không được vượt tổng của file. Session là UUID mới mỗi lần tiến trình peer khởi động, sequence tăng; báo cáo trùng hoặc cũ không được cộng lại. Nếu bộ đếm giảm sau restart, delta là giá trị mới, không âm. Các client cũ không gửi session không phân biệt được báo cáo đến sai thứ tự với reset; cần nâng cấp peer cùng tracker.

`total_uploaded/total_downloaded` lưu lượng tự khai báo; `verified_uploaded/verified_downloaded` chỉ tăng khi hai phía đối chiếu. Khi A khai tải từ B, phải có báo cáo B gửi cho A; không coi dữ liệu một phía là uy tín. Chênh lệch vượt ngưỡng đánh dấu nghi ngờ cả hai (chưa biết bên nào sai), không cộng phần chưa khớp vào ratio. Khi hai bên bắt kịp nhau, chỉ cộng phần đã khớp mới; không cộng lại lượng đã xác nhận.

Mặc định ngưỡng chênh lệch là max(65536 byte, 5% giá trị lớn hơn), cấu hình `tracker.accounting.tolerance-bytes` và `tracker.accounting.tolerance-fraction`. Đối chiếu có thể trễ do heartbeat không đồng thời. Trong ngưỡng vẫn chỉ công nhận min(sent,received).

## Kiểm thử và chạy

Ở prod, `accounting:{fileId}` trong Redis giữ baseline, sequence và số byte đã đối chiếu, không dùng TTL swarm 90 giây. Compare-and-set tránh cộng trùng khi nhiều tracker nhận cùng báo cáo. Giữ volume Redis và AOF; xóa checkpoint sẽ mất khả năng nhận diện số liệu đã xử lý. Hiện chưa có chính sách dọn lịch sử session. Dev/test dùng bộ nhớ nên không giữ baseline qua restart.

PostgreSQL được ghi theo batch 45 giây hoặc ngay khi completed/stopped. Checkpoint Redis và batch PostgreSQL chưa thuộc một giao dịch chung: crash sau checkpoint, trước flush có thể làm mất phần thống kê chưa ghi. Đây là thống kê vận hành, không dùng để thanh toán. Đối chiếu hai phía không ngăn được hai tài khoản thông đồng khai cùng số liệu giả.

Khởi động Redis và PostgreSQL, build rồi restart tracker và peers. Flyway tự chạy V3–V5.

```powershell
docker compose up -d redis
mvn test-compile
mvn verify
```

Test gồm reset/delta không âm, báo cáo lặp/sai thứ tự, đối chiếu hai phía, không tính mismatch vào verified, phân phối nguồn, ban theo 4 user khác nhau và hết hạn ban, khôi phục checkpoint sau restart và heartbeat đồng thời từ hai tracker; Testcontainers không dùng dữ liệu PostgreSQL local.
