# Giao thức v1 — Milestone đầu Student 3

Đây là hợp đồng đề xuất đã được downloader và peer demo triển khai. Cả nhóm cần thống nhất khi tích hợp tracker/uploader. Không phải giao thức BitTorrent chuẩn.

## 1. Kết nối và framing

Một request GET_CHUNK trên một TCP connection. Client đóng connection khi nhận đủ chunk hoặc gặp lỗi. Mỗi frame:

```text
[header_length: 4 bytes, big-endian]
[header: header_length bytes UTF-8 JSON object]
[body: body_length bytes binary]
```

- `header_length`: 1–65536 byte. Độ dài tính theo byte UTF-8, không phải số ký tự.
- Header JSON không chấp nhận key trùng. Các trường số trên wire phải là JSON integer.
- Với response OK, `body_length` phải bằng độ dài chunk suy ra từ manifest. Không dùng độ dài do peer khai báo để cấp phát tùy ý.
- Với error response, body_length phải là 0.
- TCP không giữ ranh giới message: cả header lẫn body có thể được chia thành nhiều lần read.
- Mỗi connection chỉ dùng một lần; downloader đọc đúng body_length và đóng connection, không chờ EOF để kết luận thành công.

## 2. GET_CHUNK

Ví dụ minh họa; thay `<file SHA-256>` bằng chuỗi hex lowercase 64 ký tự thực:

```json
{
  "version": 1,
  "request_id": "e9bc2f16-abc1-4c4f-92c7-c0d4d6403cbd",
  "message_type": "GET_CHUNK",
  "peer_id": "downloader-<session UUID>",
  "file_id": "<file SHA-256>",
  "chunk_index": 0,
  "body_length": 0
}
```

`peer_id` trong request là downloader. Milestone này tạo một ID cho mỗi phiên tải; khi tích hợp, đổi sang identity/session chung do PeerRuntime cung cấp. Không dùng ID tự khai báo làm cơ chế xác thực.

## 3. CHUNK_RESPONSE

```json
{
  "version": 1,
  "request_id": "e9bc2f16-abc1-4c4f-92c7-c0d4d6403cbd",
  "message_type": "CHUNK_RESPONSE",
  "peer_id": "peer-a",
  "file_id": "<file SHA-256>",
  "chunk_index": 0,
  "body_length": 262144,
  "status": "OK"
}
```

Ngay sau header là đúng 262144 byte nhị phân trong ví dụ. `peer_id` response phải là uploader, khớp endpoint client đã chọn. Các trường version, request_id, file_id, chunk_index và message_type đều được kiểm tra trước khi nhận body.

Mã lỗi dự kiến: NOT_FOUND, INVALID_REQUEST, BUSY, CHOKED, INTERNAL_ERROR. Có thể thêm `retry_after` (giây) với BUSY/CHOKED. Bản hiện tại chấp nhận header mở rộng, coi mọi status khác OK là nguồn không phục vụ được và thử nguồn khác một lần; chưa tự chờ hoặc retry theo retry_after. Metadata JSON thì kiểm tra schema nghiêm ngặt, bao gồm từ chối field lạ.

## 4. Manifest JSON

| Trường | Kiểu/giới hạn |
|---|---|
| version | Integer, giá trị 1 |
| filename | Tên file thuần, không có `/`, `\`, `:` hoặc tên `.`/`..`; output thực tế do CLI chỉ định |
| file_size | Integer >= 0, long |
| chunk_size | Integer từ 1 đến 16 MiB |
| chunk_count | Integer bằng ceil(file_size/chunk_size), tối đa 100000; file rỗng là 0 |
| hash_algorithm | Chuỗi `SHA-256` |
| chunk_hashes | Danh sách hash theo index từ 0, số phần tử bằng chunk_count |
| file_hash | SHA-256 toàn file, 64 ký tự hex lowercase |
| file_id | Bằng file_hash theo quy ước nhóm |
| tracker_endpoint | Chuỗi không rỗng; hiện chưa thực hiện kết nối |

File manifest khi đọc tối đa 4 MiB. Tất cả trường bắt buộc. Từ chối kích thước âm, số thực/chuỗi thay số nguyên, hash sai định dạng và metadata không nhất quán. Manifest mẫu hợp lệ được tạo tự động bởi lệnh `demo`; phần sinh này chỉ phục vụ demo, module tạo metadata sản phẩm thuộc Student 1.

```text
expected_length(i) = min(chunk_size, file_size - i * chunk_size)
```

File rỗng có 0 chunk và hash SHA-256 của dãy byte rỗng. Chunk hash xác minh dữ liệu theo manifest; không chứng minh manifest đến từ người phát hành đáng tin cậy.

## 5. Điều phối và điểm ghép tương lai

1. Coordinator gọi `PeerSource.sourcesFor(manifest, index)` trước khi submit task. CLI hiện trả cùng danh sách nguồn cho mọi chunk.
2. Có tối đa `workers` task đang chạy/được cấp việc. Mỗi index chỉ có một task; các nguồn fallback được thử tuần tự bên trong task đó.
3. Nguồn đầu được xoay vòng theo index để dùng nhiều peer; nguồn tiếp theo được thử khi lỗi. Chưa giới hạn riêng từng peer.
4. Worker ghi file tạm riêng và kiểm tra SHA-256. Coordinator commit bằng move vào đường dẫn chunk trong workspace riêng.
5. Chỉ chunk đã commit được dùng khi ghép file. Xác minh hash toàn file trước khi chuyển output ra đường dẫn cuối; không replace file có sẵn.
6. Kết thúc phiên dọn workspace; mất toàn bộ nguồn trả lỗi thay vì vòng lặp vô hạn.

Khi tích hợp Student 1: triển khai PeerSource bằng tracker adapter, thêm refresh/cooldown/waiting. Không gọi tracker trong vòng retry nóng.

Khi tích hợp Student 2: thay commit nội bộ bằng shared ChunkStore; sau commit mới gửi HAVE và credit. Dùng chung peer identity, một vòng heartbeat cho một peer và chỉ quảng bá chunk khi uploader phục vụ được. Tránh biến từng worker thành một peer độc lập.

## 6. Test hợp đồng

`FrameCodecTest`: phân mảnh header, length sai, header bị cắt, giữ nguyên binary body.

`DownloaderTest`: response sai request/peer/file/chunk/length, hash chunk sai, source disconnect, CHOKED, deadline, thiếu nguồn, hủy, giới hạn song song, chunk cuối, hash file cuối và bảo toàn output có sẵn.

Khi uploader thật sẵn sàng, chạy lại các tình huống trên với implementation đó; pass trên peer giả lập chưa chứng minh tương thích với module của Student 2.
