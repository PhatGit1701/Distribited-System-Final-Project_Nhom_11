# Nguồn tải theo chunk

API mới: POST /api/tracker/sources/{fileId}, Authorization Bearer JWT. GET /api/tracker/swarm/{fileId} giữ định dạng cũ cho dashboard; downloader dùng API mới.

Request ví dụ:

```json
{"peerId":"bob-local","availableChunks":[0,2],"maxSources":5}
```

- availableChunks là các chunk đã có; response chỉ liệt kê chunk còn thiếu.
- maxSources bắt buộc trong khoảng 1..16. Peer-node hiện yêu cầu 5 nguồn mỗi chunk.
- Nguồn bị loại nếu là requester, đã hết TTL, tài khoản/peer bị ban hoặc bad_chunk_count > ngưỡng (mặc định 3).
- Ngưỡng chỉnh bằng tracker.sources.bad-chunk-threshold hoặc TRACKER_SOURCES_BAD_CHUNK_THRESHOLD.
- Peer ID đã đăng ký phải thuộc tài khoản JWT của requester; không được mạo danh peer khác.

Response:

```json
{
  "fileId":"<64-character hash>",
  "sources":[
    {"chunkIndex":1,"availability":1,"peers":["PeerNodeInfo objects"]},
    {"chunkIndex":3,"availability":4,"peers":["up to maxSources objects"]},
    {"chunkIndex":4,"availability":0,"peers":[]}
  ],
  "seeders":2,
  "leechers":3,
  "heartbeatIntervalSeconds":30
}
```

availability đếm tất cả nguồn hợp lệ trước khi giới hạn N, không dùng số nguồn sau cắt để tính độ hiếm. Chunk có nguồn hiếm nhất đứng trước, bằng nhau theo chunk index. Chunk không có nguồn xếp cuối để không chặn việc tải phần đang có. Seeders/leechers đếm peer hợp lệ đang sống, loại requester. PeerNodeInfo trong từng chunk chỉ quảng bá availableChunks chứa đúng chunk đó để tránh payload tăng bậc hai với file lớn.

Nguồn được lấy mẫu ngẫu nhiên có trọng số, không lặp lại trong cùng chunk. Ratio = tổng verified_uploaded / max(1, tổng verified_downloaded) của tài khoản trong user_file_stats. Trọng số = 1 + log1p(ratio). Ratio cao có xác suất cao hơn nhưng không cố định nguồn đầu tiên; peer chưa có đóng góp vẫn có cơ hội. Số liệu ratio phản ánh lần flush thống kê gần nhất, có thể trễ khoảng 45 giây. Chỉ số byte đã đối chiếu hai phía mới được tính vào ratio; thông đồng giữa các tài khoản vẫn là giới hạn của mô hình báo cáo.

Migration V3 tạo peer_reputation, khóa (user_id, peer_id), gồm is_banned và bad_chunk_count. Đây là dữ liệu moderation do phía quản trị cập nhật sau khi xác minh, không có endpoint cho peer tự sửa hoặc ban peer khác. users.is_banned áp dụng cho tất cả peer của tài khoản. Báo cáo hợp lệ qua POST /api/reports có cơ chế quarantine riêng: 4 người báo cáo khác nhau trong 10 phút gây ban tạm 10 phút, không sửa bộ đếm moderation thủ công.

Peer-node dùng thứ tự chunk do tracker gợi ý và chỉ chọn trong danh sách ứng viên của chunk đó. Khi tải đồng thời, ưu tiên nguồn ít kết nối đang chạy; nếu bằng nhau giữ thứ tự ngẫu nhiên do tracker trả về. Tải thất bại tiếp tục thử nguồn khác và refresh kế hoạch theo chu kỳ hiện tại. Interval 30 giây là gợi ý; heartbeat peer hiện tại 5 giây vẫn hợp lệ với TTL 90 giây.

Chạy Redis/PostgreSQL như docs/SWARM_REDIS.md; restart tracker để Flyway áp dụng V3, rồi restart peer-node với bản build mới.

```powershell
mvn test-compile
mvn verify
```
