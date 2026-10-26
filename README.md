# Distributed Systems Final Project — Nhóm 11

Track A: **Hybrid P2P File-Sharing System (BitTorrent-Lite)**.

Nhánh `dev-Student3` triển khai milestone đầu của **Student 3 — Downloader** bằng Java 21, TCP Socket, `ExecutorService`, Jackson JSON và JUnit.

## Chạy nhanh

Yêu cầu: **JDK 21 trở lên, Maven 3.9 trở lên**. Kiểm tra `mvn -version` để chắc Maven đang dùng đúng JDK. Maven cần truy cập Maven Central ở lần tải dependency đầu.

```powershell
mvn clean verify
java -jar target/bittorrent-lite.jar demo demo-output/run-1
```

Lệnh demo tự tạo file nhị phân 1 MiB + 123 byte, metadata JSON, hai peer TCP loopback trên cổng trống và tải bằng 4 worker. Thư mục `demo-output/run-1` phải chưa tồn tại; dùng tên mới cho lần chạy tiếp theo.

Kết quả:

```text
demo-output/run-1/
  original.bin
  sample.torrent.json
  downloaded.bin
```

Console hiển thị nguồn từng chunk, request ID, thời gian sự kiện và số chunk đã xác minh. Thành công kết thúc bằng `COMPLETED ... (final SHA-256 verified)`; lỗi trả exit code 1. Demo dừng các peer khi tải xong. Peer demo là fixture phục vụ học/tự kiểm thử Student 3, không thay thế module uploader của Student 2.

Có thể đối chiếu thêm trên PowerShell:

```powershell
Get-FileHash demo-output/run-1/original.bin -Algorithm SHA256
Get-FileHash demo-output/run-1/downloaded.bin -Algorithm SHA256
```

## Tải từ uploader chạy riêng

Sau khi uploader của Student 2 tuân thủ [giao thức v1](docs/PROTOCOL_V1.md):

```powershell
java -jar target/bittorrent-lite.jar download sample.torrent.json downloaded.bin --peer peer-a@127.0.0.1:9001 --peer peer-b@127.0.0.1:9002 --workers 4
```

- `--peer`: lặp lại để khai báo nhiều nguồn; định dạng `id@host:port`, IPv6 dùng `id@[::1]:port`. `id` phải khớp `peer_id` mà server trả về.
- `--workers`: 1–16, mặc định 4; dùng 1 để học và kiểm thử tải tuần tự.
- Metadata đọc từ file; output là đường dẫn bạn truyền, không lấy đường dẫn từ metadata.
- Output đã tồn tại sẽ bị từ chối, kể cả khi được tạo trong lúc đang tải.
- Hiện nguồn được truyền qua CLI. `tracker_endpoint` được đọc/kiểm tra tồn tại trong metadata nhưng chưa được kết nối.

## Chức năng đã có

- Metadata JSON với snake_case; kiểm tra version, kích thước, số chunk, SHA-256 và chunk cuối. File rỗng được hỗ trợ.
- Framing TCP: length 4 byte big-endian + header JSON UTF-8 + body nhị phân. Đọc đủ header/body, giới hạn length trước khi cấp phát.
- Kiểm tra response khớp request, file, peer và chunk; kiểm tra hash chunk trước khi commit.
- `ExecutorService` với tối đa N task đang hoạt động; coordinator quản lý chunk đã commit. Không submit toàn bộ file vào hàng đợi.
- Chọn nguồn khởi đầu xoay vòng theo index. Khi nguồn lỗi, thử nguồn kế tiếp trong danh sách; mỗi nguồn được thử một lần cho mỗi chunk.
- Timeout kết nối, timeout đọc và deadline cho lượt tải để xử lý nguồn gửi nhỏ giọt. Timeout có thể cấu hình qua `DownloadOptions` trong Java; CLI hiện chỉ cho cấu hình workers. DNS lookup vẫn phụ thuộc resolver của hệ điều hành; dùng IP cho demo có thời gian dự đoán được.
- Lưu từng chunk vào workspace riêng, ghép theo index, kiểm tra hash file cuối rồi mới tạo output.
- Khi lỗi/hủy: đóng socket, dừng worker và dọn workspace riêng sau khi worker dừng.
- Demo độc lập và JUnit kiểm tra cả các trường hợp lỗi qua TCP thật trên loopback.

## Mốc tiếp theo của Student 3

- Adapter tracker qua `PeerSource`: `GET_PEERS`, refresh nguồn, chờ khi chưa có nguồn và retry có backoff.
- Tích hợp identity/session chung với `PeerRuntime` và `ChunkStore` của nhóm; gửi `HAVE` và credit phục vụ tit-for-tat sau khi commit.
- Phân biệt mã lỗi typed, chờ `retry_after` cho `BUSY/CHOKED`, giới hạn kết nối mỗi peer và retry có ngân sách.
- Resume, CLI timeout/cancel thân thiện và benchmark LAN.

Hiện `BUSY/CHOKED` khiến downloader thử nguồn khác; nếu hết nguồn thì thất bại rõ ràng. Chưa có vòng chờ và thử lại theo `retry_after`. Các chunk tạm được dọn sau phiên; chưa có resume hoặc upload chunk đang tải. `.torrent` ở đây là JSON tùy biến của nhóm, chưa tương thích giao thức BitTorrent chuẩn.

## Cấu trúc để học và tích hợp

```text
src/main/java/vn/nhom11/bittorrent/
  Main.java                   CLI
  common/                     JSON, SHA-256
  model/                      Manifest, PeerEndpoint
  protocol/FrameCodec.java    TCP framing
  downloader/PeerClient.java  tải và kiểm tra một chunk
  downloader/PeerSource.java  điểm ghép tracker tương lai
  downloader/DownloadManager.java
                              điều phối worker, fallback, ghép file, cleanup
  demo/LocalDemo.java          demo loopback tự chạy
src/test/java/vn/nhom11/bittorrent/
  TestFixtures.java            peer TCP thử nghiệm và dữ liệu mẫu
  ManifestTest.java
  FrameCodecTest.java
  DownloaderTest.java
  MainTest.java
```

Nên đọc theo thứ tự: `Manifest` → `FrameCodec` → `PeerClient` → `DownloadManager` → test mất kết nối/sai hash.

```powershell
mvn test
mvn "-Dtest=DownloaderTest" test
mvn "-Dtest=DownloaderTest#transfersFromTwoPeersConcurrentlyWithBoundedWork" test
```

Báo cáo JUnit nằm trong `target/surefire-reports`. Test song song dùng latch buộc hai nguồn nhận request trước khi trả dữ liệu, giúp kiểm chứng tải đồng thời thay vì chỉ đếm số thread.

## Tài liệu

- [Kế hoạch chung](PLAN_BITTORRENT_LITE.md)
- [Kế hoạch cá nhân Student 3](PLAN_STUDENT_3_DOWNLOADER.md)
- [Giao thức v1 và hợp đồng tích hợp](docs/PROTOCOL_V1.md)
- [Jackson 2.21 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21)
- [JUnit 5.14.3 user guide](https://docs.junit.org/5.14.3/)

Dependency và plugin được chốt phiên bản trong `pom.xml`. File PDF học tập ở local chưa được đưa vào Git; `target`, `tmp` và dữ liệu demo được bỏ qua bằng `.gitignore`.
