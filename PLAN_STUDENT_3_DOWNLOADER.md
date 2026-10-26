# Kế hoạch cá nhân Student 3 — Multithreaded Peer Client / Downloader

## 1. Mục tiêu, phạm vi và giả định

Bạn chịu trách nhiệm biến một manifest thành file hoàn chỉnh bằng cách tìm nguồn, tải chunk song song, xác minh dữ liệu và phục hồi khi nguồn gặp lỗi.

Kế hoạch dựa trên `PLAN_BITTORRENT_LITE.md` và phân vai trong ảnh đề. Giả định 4 tuần, 16 buổi, tổng khoảng 52 giờ. Đây là ước lượng cho người đã biết lập trình cơ bản; nếu chưa biết socket/thread, cần thêm thời gian cho bài tập nền tảng. Chưa có deadline cụ thể. Đã chốt Java + TCP Socket + ExecutorService + JSON + JUnit; milestone đầu dùng Java 21, Maven và Jackson. Cấu trúc dưới đây là đích thiết kế của cả lộ trình; README mô tả chính xác phần đã triển khai.

### Bắt buộc hoàn thành

1. Đọc metadata và truy vấn tracker để biết nguồn của từng chunk.
2. Mở nhiều kết nối đến nhiều peer, tải các chunk đồng thời.
3. Kiểm tra kích thước và hash từng chunk trước khi công nhận thành công.
4. Ghép file theo đúng thứ tự, kiểm tra kích thước và hash cuối.
5. Khi peer ngắt kết nối, tự đổi nguồn mà giữ lại các chunk đã tải đúng.
6. Tích hợp với uploader: chunk đã xác minh có thể được chia sẻ; đóng góp của peer được ghi nhận phục vụ tit-for-tat.

### Ưu tiên sau phần bắt buộc

- Resume sau khi thoát chương trình; CLI tiến độ; benchmark và log có cấu trúc.
- Rarest-first chỉ làm khi các yêu cầu bắt buộc đã ổn định. GUI và endgame tải trùng chưa cần trong phiên bản đầu.

### Định nghĩa hoàn thành

Từ bản mã nguồn sạch, chạy được một lệnh tải; lấy dữ liệu thực từ ít nhất hai peer có các lượt truyền chồng lấp; vượt qua test mất peer và chunk lỗi khi còn nguồn thay thế; file cuối đúng hash; có hướng dẫn, log và giải thích được thuật toán.

## 2. Chốt phụ thuộc ngay trong hai buổi đầu

Không cần đợi cả tracker và uploader hoàn tất. Cần thống nhất hợp đồng để viết adapter thật và bộ giả lập có cùng hành vi.

| Đối tác | Bạn cần nhận | Bạn cần cung cấp | Mốc |
|---|---|---|---|
| Student 1 | Schema manifest; GET_PEERS/HAVE; endpoint, peer_id, session_id; mã lỗi | API tracker mà downloader cần; ví dụ request; cách xử lý tracker unavailable | Chốt buổi 1–2; gọi tracker thật buổi 4 |
| Student 2 | GET_CHUNK; framing; mã BUSY/CHOKED/NOT_FOUND; API ChunkStore | Sự kiện chunk đã commit; credit byte hợp lệ theo peer; yêu cầu thread-safety | Chốt buổi 1–2; tải chunk từ uploader thật buổi 4 |
| Cả nhóm | Một codec, một manifest model, một peer identity | Danh sách test hợp đồng và log mẫu | Trước khi viết riêng giao thức |

Các quyết định cần ghi vào tài liệu giao thức:

- `.torrent` là định dạng tùy biến hay cần tương thích chuẩn; hash và cách mã hóa hash.
- GET_PEERS trả nguồn theo chunk hay bitmap theo peer. Đề xuất trả danh sách peer có `available_chunks`, rồi adapter chuyển thành `chunk_index → sources`.
- `chunk_index` bắt đầu từ 0; byte order; giới hạn độ dài; đơn vị timeout và `retry_after`.
- Một request trên một TCP connection ở bản đầu để dễ kiểm soát. Reuse kết nối là cải tiến sau.
- Bản đầu giới hạn 4 lượt tải đồng thời, tối đa 2 lượt/peer; có ít nhất hai peer khả dụng thì scheduler phân phối lượt tải sang nhiều nguồn.
- Tracker server do Student 1 sở hữu. Student 3 viết/dùng tracker client adapter. Vòng đời peer chỉ có một REGISTER/HEARTBEAT loop, đề xuất đặt trong PeerRuntime do Student 2 tích hợp; downloader dùng chung identity và thông báo HAVE qua adapter. Đây là phân công cần thống nhất để tránh hai vòng heartbeat.
- Nếu uploader chưa chạy thì downloader chưa quảng bá mình là nguồn. Khi uploader sẵn sàng, đăng ký lại toàn bộ chunk đã verified.

Khi chưa có implementation thật:

- FakeTracker trả inventory cấu hình sẵn để kiểm thử scheduler.
- TestPeerServer là TCP server nhỏ trả chunk từ file fixture và gây lỗi theo kịch bản; đây là công cụ kiểm thử của bạn.
- Adapter, fake và module thật phải dùng cùng codec/schema. Hàng tuần chạy lại contract test với cả fake lẫn module thật để tránh fake che giấu lệch giao thức.

## 3. Kiến thức cần học theo đúng việc sắp làm

| Kiến thức | Bài tập ngắn | Bạn phải tự giải thích được |
|---|---|---|
| TCP là byte stream | Server chia header/body thành nhiều lần gửi, client đọc lại đầy đủ | Vì sao một lần read không chắc nhận đủ một message? |
| Chunk và hash | Chia file nhị phân, tính SHA-256, đổi một byte rồi kiểm tra | Vì sao TCP tin cậy vẫn cần xác minh ở tầng ứng dụng? |
| Thread pool/Future | Chạy 4 tác vụ chậm đồng thời, thu kết quả và lỗi | Số task khác số thread thế nào? Ai quyết định trạng thái? |
| Shared state và race | Cho hai tác vụ thử nhận cùng một chunk | Làm sao đảm bảo chỉ một attempt còn hiệu lực? |
| Timeout và retry | Server im lặng hoặc đóng giữa body | Khi nào đổi nguồn, khi nào chờ, khi nào kết thúc? |
| File I/O | Lưu file tạm rồi ghép theo index | Khi nào dữ liệu được phép coi là hoàn chỉnh? |

Nguồn học ưu tiên là slide môn học đã đọc ở kế hoạch chung: phần socket/marshaling, client multithreading và failure transparency. Các mục giáo trình trong ảnh đề cần đối chiếu khi nhóm có bản đầy đủ.

## 4. Thiết kế module của riêng bạn

Tên sau là cấu trúc đề xuất, chưa phải mã đã có trong repo:

```text
downloader/
  DownloadManager       điều phối phiên tải, kết thúc/hủy và kết quả chung
  ChunkScheduler        chọn chunk, chọn peer, quản lý attempts và cooldown
  ChunkDownloadTask     tải đúng một chunk trong một attempt
  PeerClient            TCP request/response và kiểm tra framing
  TrackerClient         query nguồn và gửi HAVE
  FileAssembler         ghép chunk và kiểm tra file cuối
  DownloadProgress      số liệu tiến độ và kết quả theo peer
shared/                 dùng chung với hai bạn
  Manifest, PeerEndpoint, ProtocolCodec, ChunkStore, PeerRuntime
tests/support/
  FakeTracker, TestPeerServer, FixtureFactory
```

Hợp đồng ở mức ý tưởng:

```text
TrackerClient.getPeers(fileId) -> PeerInventory
TrackerClient.announceHave(identity, fileId, chunkIndex)
PeerClient.fetchChunk(peer, manifest, chunkIndex, attemptId) -> TempChunk
ChunkStore.commitVerifiedChunk(fileId, chunkIndex, tempFile)
ContributionSink.recordVerifiedBytes(peerId, sessionId, byteCount, attemptId)
DownloadManager.download(manifest, outputPath, options) -> DownloadResult
```

Student 1 sở hữu metadata model/parser; bạn dùng chung và kiểm tra dữ liệu đầu vào ở ranh giới downloader. Student 2 sở hữu ChunkStore; bạn không tạo hệ thống lưu chunk thứ hai khi tích hợp.

### Chọn mô hình đồng thời dễ kiểm soát

Đề xuất một coordinator thread sở hữu trạng thái scheduler; worker chỉ thực hiện network I/O, xác minh chunk, rồi trả kết quả. Coordinator xử lý kết quả, commit và cập nhật trạng thái. Worker không trực tiếp sửa map trạng thái chung. Pool và số task đang chạy đều có giới hạn; không submit toàn bộ hàng triệu chunk vào hàng đợi không giới hạn.

Mô hình này vẫn là client đa luồng: các worker có thể đang đọc từ nhiều socket cùng lúc. Coordinator không giữ khóa hoặc chờ một socket trong lúc điều phối các worker khác.

### Trạng thái và điều kiện không được vi phạm

```text
MISSING --cấp việc--> IN_FLIGHT --đủ byte + hash đúng + commit xong--> VERIFIED
                         |
                         +-- lỗi có thể thử lại --> MISSING

Phiên tải: RUNNING → WAITING_FOR_SOURCES → RUNNING
           RUNNING → COMPLETED / FAILED / CANCELLED
```

- Mỗi chunk có tối đa một attempt hợp lệ tại một thời điểm trong bản đầu.
- Mỗi attempt có ID; kết quả muộn của attempt đã hủy/hết hạn không được ghi đè hoặc phát credit lần nữa.
- VERIFIED nghĩa là dữ liệu đã kiểm tra và commit thành công vào kho chunk.
- HAVE chỉ được phát sau VERIFIED và khi uploader có thể phục vụ chunk đó.
- `progress` tính theo tổng byte của chunk VERIFIED, không cộng byte tải lại do lỗi.
- Chỉ báo COMPLETED sau khi ghép xong, kích thước đúng và hash file cuối đúng.
- Lỗi gửi HAVE không biến chunk đúng thành MISSING; giữ hàng đợi thông báo để retry/reconcile inventory.
- Lỗi ghi đĩa khác lỗi nguồn mạng: không tải lại vô hạn từ các peer khi ổ đĩa đã đầy.

## 5. Lịch 16 buổi — khoảng 52 giờ

### Tuần 1 — Tải đúng trước, tích hợp sớm: 12 giờ

| Buổi | Thời lượng | Việc làm | Điều kiện hoàn thành |
|---|---|---|---|
| 1 | 3h | Đọc yêu cầu; chốt trách nhiệm; vẽ luồng tải; thống nhất metadata/API với nhóm | Có bảng request/response, ownership, tiêu chí đúng và danh sách điều chưa chốt |
| 2 | 3h | Học framing; viết/dùng codec; dựng TestPeerServer; tạo file fixture/manifest cố định | Header/body bị gửi rời vẫn đọc đủ; length sai bị từ chối |
| 3 | 3h | Viết PeerClient + task tải một chunk; kiểm tra độ dài/hash; lưu tạm | Chunk đúng được nhận; thiếu byte/sai hash bị loại; socket được đóng |
| 4 | 3h | Tải tuần tự mọi chunk, ghép và xác minh file; kết nối tracker/uploader thật | Một file nhỏ chạy xuyên cả ba module; nếu đối tác trễ, ghi rõ test nào còn dùng fake và hẹn mốc tích hợp sớm nhất |

### Tuần 2 — Song song và failover: 14 giờ

| Buổi | Thời lượng | Việc làm | Điều kiện hoàn thành |
|---|---|---|---|
| 5 | 3h | Implement coordinator, state machine, attempt ID, bounded task submission | Không có hai attempt hợp lệ cho cùng chunk; kết quả cũ bị bỏ |
| 6 | 4h | Worker pool; phân phối nhiều peer; giới hạn global/per-peer; tiến độ theo verified bytes | Hai nguồn có transfer chồng lấp trên timeline của downloader, file cuối đúng |
| 7 | 4h | Connect/read timeout, deadline attempt, retry và cooldown theo nguồn | Ngắt giữa chunk tự đổi nguồn; chunk đã verified không tải lại |
| 8 | 3h | Chạy 1 tracker + 3 peer thật; cập nhật source list, HAVE, credit và shared store | Chunk vừa tải có thể được peer khác lấy; credit chỉ tăng cho dữ liệu hợp lệ và không tăng trùng |

### Tuần 3 — Độ ổn định và tình huống lỗi: 14 giờ

| Buổi | Thời lượng | Việc làm | Điều kiện hoàn thành |
|---|---|---|---|
| 9 | 4h | Phân biệt hash mismatch, EOF, NOT_FOUND, BUSY, CHOKED; chạy fault injection | Xử lý đúng từng loại; không retry nóng hoặc coi mọi peer CHOKED là đã chết |
| 10 | 4h | WAITING_FOR_SOURCES, tracker refresh, tracker lỗi tạm thời, cancel và cleanup | Nguồn mới xuất hiện giúp tiếp tục; hết deadline kết thúc rõ ràng; cancel đóng socket/dừng worker |
| 11 | 3h | File biên; chunk cuối; output an toàn; thử lỗi ghi đĩa; sửa race tích hợp | Không success giả; không ghi đè file có sẵn ngoài ý muốn; lỗi local có thông báo đúng |
| 12 | 3h | Nếu core đã đạt: resume bằng chunk trên đĩa; nếu chưa: dùng toàn buổi sửa core | Resume xác minh lại chunk trước khi dùng; hoặc checklist core không còn lỗi nghiêm trọng |

### Tuần 4 — Đo, viết và bảo vệ: 12 giờ

| Buổi | Thời lượng | Việc làm | Điều kiện hoàn thành |
|---|---|---|---|
| 13 | 3h | Script benchmark 3 cấu hình; log/CSV và thông số môi trường | Có dữ liệu thô từ ít nhất 5 lần/cấu hình |
| 14 | 3h | Phân tích kết quả; hoàn tất README phần downloader; vẽ sequence/state machine | Có bảng/biểu đồ từ dữ liệu thật và giải thích giới hạn |
| 15 | 3h | Chạy LAN, demo kill peer/chunk lỗi; thử chạy từ checkout sạch | Kịch bản tái lập, không phụ thuộc đường dẫn máy bạn |
| 16 | 3h | Diễn tập hỏi đáp; chốt báo cáo/slide; chạy regression; chuẩn bị video dự phòng | Các test bắt buộc pass và bạn tự giải thích được code quan trọng |

Nếu chỉ có 2 tuần: giữ thứ tự các cổng nghiệm thu, tăng số buổi/tuần; bỏ resume/rarest-first/GUI. Không nén bằng cách bỏ kiểm tra hash hoặc failover.

## 6. Chi tiết thuật toán để bắt đầu viết

### Một chunk được tải như thế nào?

Với file_size = F, chunk_size = C và index = i:

```text
chunk_count = ceil(F / C)             với F > 0, C > 0
offset = i * C
expected_length = min(C, F - offset)
```

Ví dụ file 2 MiB + 123 byte, chunk 1 MiB: ba chunk có độ dài 1 MiB, 1 MiB và 123 byte. Dùng kiểu số đủ lớn cho file size/offset; kiểm tra tràn và giới hạn cấu hình khi đọc manifest. File rỗng xử lý theo hợp đồng chung.

Quy trình:

1. Mở socket với connect timeout; gửi GET_CHUNK có request_id/attempt_id và index.
2. Đọc đủ 4 byte length và header; từ chối header vượt giới hạn trước khi cấp phát.
3. Kiểm tra version, request_id, file_id, chunk_index, status và body_length bằng expected_length.
4. Đọc body đến đủ số byte; tính SHA-256 tăng dần và ghi vào file tạm riêng của attempt.
5. EOF sớm hoặc quá deadline thì thất bại; đóng stream/socket và bỏ file tạm của attempt.
6. Hash đúng thì trả kết quả cho coordinator; coordinator kiểm tra attempt còn hiệu lực, commit qua ChunkStore rồi mới đánh dấu VERIFIED.
7. Gửi sự kiện đóng góp và HAVE. Nếu HAVE lỗi mạng, retry thông báo mà không tải lại chunk.

Read timeout chỉ giới hạn lần chờ đọc; một peer gửi nhỏ giọt có thể tránh timeout đó. Vì vậy thêm deadline tổng cho attempt, điều chỉnh theo kích thước chunk và băng thông dự kiến. Giá trị thử ban đầu: connect 2s, read 5s, attempt 30s; cần cấu hình được và điều chỉnh khi benchmark/throttle.

### Scheduler vòng đầu

```text
while chưa kết thúc:
    nhận và xử lý các kết quả worker đã hoàn tất
    áp dụng commit/failure chỉ cho attempt còn hiệu lực
    refresh nguồn nếu đến hạn hoặc nguồn hiện có không dùng được
    khi còn slot global/per-peer:
        chọn chunk MISSING có nguồn chưa cooldown
        chọn nguồn ít tải đang chạy, xoay vòng khi ngang nhau
        tạo attempt, đánh dấu IN_FLIGHT, submit worker
    nếu hết chunk: ghép và kiểm tra file
    nếu không có việc chạy và chưa có nguồn: WAITING_FOR_SOURCES
    chờ event hoặc thời điểm retry/refresh tiếp theo; không busy-loop
```

Chọn chunk theo index trước để dễ kiểm thử. Sau khi core ổn định có thể chọn chunk hiếm nhất, nhưng cần đo để chứng minh tác dụng.

### Chính sách lỗi đề xuất

| Tình huống | Hành động |
|---|---|
| Connection refused, timeout, EOF giữa body | Hủy attempt, cooldown nguồn, đưa chunk về MISSING, ưu tiên nguồn khác |
| Hash mismatch | Bỏ dữ liệu; đánh dấu nguồn đáng nghi cho chunk; chọn nguồn khác và log chi tiết |
| NOT_FOUND | Loại nguồn đó khỏi candidate của chunk hiện tại, refresh tracker khi cần |
| BUSY/CHOKED | Tôn trọng retry_after trong giới hạn cấu hình, thử nguồn khác; không báo peer chết |
| Header/index/file_id/length sai | Hủy connection, ghi lỗi giao thức; không commit hoặc cấp phát bộ nhớ theo length không kiểm soát |
| Tracker tạm lỗi | Tiếp tục nguồn đã biết còn hoạt động; retry query/HAVE với backoff |
| Không nguồn khả dụng | Chờ có giới hạn, refresh nguồn; hết deadline trả UNAVAILABLE kèm danh sách chunk thiếu |
| Hết dung lượng/permission ghi file | Dừng phiên hoặc báo lỗi local để người dùng xử lý; giữ chunk tốt đã commit |
| Người dùng hủy | Dừng cấp việc, đóng socket đang hoạt động, thu hồi worker/file tạm; giữ chunk verified nếu hỗ trợ resume |

Thử cấu hình backoff 0.5s → 1s → 2s → tối đa 5s, thêm jitter nhỏ. Giới hạn retry theo nguồn và tổng thời gian phiên tải; đề xuất chờ nguồn tối đa 60s để demo, tùy chỉnh cho file thật. Khi thử nghiệm cần nguồn thay thế, fixture phải thực sự có bản sao chunk đó.

## 7. Test để biết mình làm đúng

Chia test thành unit (logic thuần), protocol/TCP (server giả lập thật trên loopback) và integration (tracker/uploader của nhóm). Chạy test nhẹ mỗi lần sửa; test LAN và benchmark ở mốc tương ứng.

| ID | Test | Kết quả bắt buộc |
|---|---|---|
| D01 | File 1 byte, C−1, C, C+1, 2C+123 | Kích thước/hash chunk cuối và file cuối đúng |
| D02 | Header/body gửi thành nhiều phần nhỏ | Đọc đúng toàn bộ message |
| D03 | Body bị cắt hoặc header length quá lớn | Fail có kiểm soát; không VERIFIED/HAVE |
| D04 | Hash sai, index sai, request_id sai | Từ chối đúng response; không ghi nhầm chunk |
| D05 | Hai peer có nguồn phù hợp, concurrency > 1 | Hai transfer từ hai peer chồng lấp thời gian |
| D06 | Kill peer đang truyền chunk, có bản sao ở peer khác | Retry tự động sang nguồn khác và hoàn thành |
| D07 | Kill peer giữ bản duy nhất | WAITING/UNAVAILABLE đúng; không success giả |
| D08 | Nhiều completion/failure gần nhau; kết quả attempt cũ | Không double-commit, double-credit hoặc vượt giới hạn concurrency |
| D09 | BUSY/CHOKED rồi nguồn cho tải lại | Có backoff, không vòng lặp tiêu tốn CPU; tiến độ tiếp tục |
| D10 | Tracker lỗi sau khi đã lấy source list | Nguồn đã biết vẫn được dùng; HAVE lỗi không mất chunk tốt |
| D11 | Peer mới xuất hiện khi đang thiếu nguồn | Query mới cập nhật candidate và tiếp tục tải |
| D12 | Hủy giữa tải hoặc hết deadline | Worker/socket kết thúc; file cuối không được công bố |
| D13 | Chunk verified rồi HAVE thất bại | Không tải lại chunk; thông báo lại khi tracker phục hồi |
| D14 | ChunkStore commit lỗi | Không đánh dấu VERIFIED/HAVE; báo lỗi local |
| D15 | Resume có một chunk lưu bị sửa | Chỉ tái sử dụng chunk kiểm tra lại đúng hash |
| D16 | Ghép xong nhưng hash file sai | Báo thất bại; không đổi output thành file hoàn chỉnh |

D15 chỉ bắt buộc nếu triển khai resume. Test fake phải chạy được lặp lại với seed/config cố định. Để tránh test thời gian chập chờn, dùng barrier/latch hoặc event để biết worker đã bắt đầu rồi mới gây lỗi.

## 8. Log và benchmark để bảo vệ phần của bạn

Mỗi event nên có: download_id, request_id/attempt_id, chunk_index, peer_id, event, byte_count, elapsed_ms, error_code. Event chính: TASK_STARTED, TRANSFER_STARTED, HASH_OK, HASH_FAILED, CHUNK_COMMITTED, RETRY_SCHEDULED, WAITING_FOR_SOURCES, DOWNLOAD_COMPLETED.

Ví dụ minh họa sau không phải log đo thực tế:

```text
chunk=7 peer=A attempt=1 event=TRANSFER_STARTED
chunk=8 peer=B attempt=1 event=TRANSFER_STARTED
chunk=7 peer=A attempt=1 event=FAILED reason=EARLY_EOF
chunk=7 peer=C attempt=2 event=TRANSFER_STARTED
chunk=7 peer=C attempt=2 event=CHUNK_COMMITTED
```

Đo duration/timeline bằng monotonic clock trên downloader. Không trừ timestamp tuyệt đối giữa hai máy chưa đồng bộ để suy ra độ trễ.

Benchmark ba cấu hình: 1 peer/1 worker; 3 peer/1 worker tổng; 3 peer/4 worker tổng. Cùng file, chunk size, giới hạn băng thông và môi trường; ít nhất 5 lượt mỗi cấu hình. Ghi median, min–max, tổng thời gian, throughput, byte từng nguồn và retry. Tách benchmark có lỗi khỏi benchmark bình thường.

Đo failover từ lúc gây lỗi đến lúc lượt tải thay thế bắt đầu/thành công; ghi rõ định nghĩa. Nếu chạy cùng máy, nêu giới hạn disk/CPU/cache dùng chung. Tốc độ không tăng tuyến tính vẫn là kết quả hợp lệ nếu có giải thích dựa trên số liệu.

## 9. Sản phẩm cá nhân phải bàn giao

- [ ] Downloader có unit/protocol/integration tests và cấu hình giới hạn rõ ràng.
- [ ] FakeTracker/TestPeerServer để tái lập các lỗi quan trọng.
- [ ] Hướng dẫn chạy, tham số timeout/concurrency/output và cách đọc trạng thái lỗi.
- [ ] Sequence tải thường + failover; state machine chunk và phiên tải.
- [ ] Dữ liệu benchmark thô, bảng/biểu đồ và mô tả môi trường.
- [ ] Log bằng chứng tải đa nguồn, retry, chunk lỗi, file hash cuối.
- [ ] Phần báo cáo cá nhân và ghi nhận đóng góp qua commit/PR thực tế.

Mỗi buổi nên kết thúc với một thay đổi nhỏ có thể kiểm chứng; commit ghi hành vi đã làm được, không gộp toàn bộ vào cuối đồ án. Review với Student 1 ở mốc tracker và Student 2 ở mốc shared store/HAVE/credit.

## 10. Phần demo và câu hỏi bảo vệ của bạn

Trong 3–4 phút dành cho downloader:

1. Chỉ ra inventory của các nguồn, số worker và file cần tải.
2. Bắt đầu tải, chỉ ra hai peer đang truyền đồng thời.
3. Tắt một nguồn giữa chunk; chỉ ra attempt lỗi, retry từ nguồn mới và tiến độ giữ nguyên phần verified.
4. Đối chiếu hash file cuối. Dùng một lượt test riêng cho chunk hỏng để người xem dễ theo dõi.

Bạn cần tự giải thích được:

- Vì sao nhiều thread mà vẫn có thể vô tình chỉ tải từ một peer?
- Ai sở hữu trạng thái chunk, và kết quả worker muộn bị xử lý thế nào?
- Connect timeout, read timeout và attempt deadline khác nhau ra sao?
- Vì sao không báo peer chết ngay khi timeout? Vì sao CHOKED không phải mất kết nối?
- Vì sao chunk cuối có độ dài khác? Ghép theo thứ tự nào?
- Khi hash mismatch, vì sao không công bố HAVE hoặc tính tiến độ?
- Khi tracker lỗi, phần tải nào còn tiếp tục được?
- Khi thiếu mọi bản sao của một chunk, vì sao hệ thống không thể tự phục hồi dữ liệu?
- Vì sao file hash đúng chứng minh tính toàn vẹn theo manifest, nhưng không tự xác thực người phát hành manifest?
- Kết quả benchmark bị giới hạn bởi mạng, đĩa hay lịch điều phối?

## 11. Bắt đầu ngay trong buổi đầu tiên

1. Đọc lại phần Student 3 và đánh dấu các đầu việc bắt buộc.
2. Chốt ngôn ngữ và 3 hợp đồng với nhóm: metadata, GET_PEERS/HAVE, GET_CHUNK/ChunkStore.
3. Vẽ luồng tải một chunk bằng giấy hoặc Mermaid; chỉ ra chỗ kiểm tra hash, commit và HAVE.
4. Chuẩn bị ba fixture: nhỏ hơn một chunk, đúng bội số chunk và chunk cuối lẻ.
5. Đặt mục tiêu kỹ thuật đầu tiên: tải đúng một chunk qua TCP và phát hiện một byte bị sửa. Khi đạt, chuyển sang tải tuần tự toàn file rồi mới song song.
