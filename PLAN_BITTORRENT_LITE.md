# Kế hoạch đồ án Track A — Hybrid P2P File-Sharing System

## 1. Cơ sở và giả định

- Nhóm 3 người theo phân vai trong ảnh đề; lịch đề xuất 4 tuần, khoảng 10–14 giờ/người/tuần. Chưa có deadline, tên thành viên, ngôn ngữ bắt buộc hoặc rubric riêng của Track A.
- Đây là kế hoạch đề xuất để đáp ứng yêu cầu và tạo bằng chứng đánh giá; không phải cam kết điểm số.
- Đã đối chiếu Syllabus.pdf; Lect01_Overview.pdf (đặc biệt trang 16: hybrid và BitTorrent); Lect02_Distributed_Communication_and_Naming.pdf (trang 12: marshaling, trang 19: sockets); Lect04_processes_and_coordination.pdf (trang 4–5: threads, dispatcher/worker; trang 8: soft state/TTL).
- Syllabus liệt kê Tanenbaum & van Steen, Distributed Systems: Principles and Paradigms, 2nd edition (2007). Thư mục hiện chưa có bản giáo trình đầy đủ để xác minh nội dung Ch.2 §2.2.3, Hình 2-14, bài 18 và Ch.3 §3.1.2, Hình 3-3 được dẫn trong đề.
- Rubric trong Lecture 04 thuộc lab election, không phải rubric đồ án Track A. Không tự gán trọng số điểm cho đồ án.
- B3_QuanlysThoigian.pdf không phải trọng tâm yêu cầu Track A. Không thêm Lamport/vector clock, election hoặc consensus chỉ để tăng độ phức tạp.

## 2. Phạm vi và kiến trúc

Tracker chỉ quản lý metadata và vị trí chunk; dữ liệu file truyền trực tiếp giữa các peer. Mỗi peer là một chương trình có cả uploader và downloader, có thể chia sẻ chunk đã xác minh ngay khi còn tải phần còn lại.

Luồng chuẩn: tạo manifest → khởi động uploader → đăng ký chunk với tracker → downloader hỏi nguồn → tải nhiều chunk đồng thời từ nhiều peer → kiểm tra hash → lưu chunk → thông báo HAVE → ghép file → xác minh file cuối.

### Bắt buộc theo đề

1. Chia file thành chunk cố định, xử lý đúng chunk cuối.
2. Metadata dạng .torrent chứa kích thước file và hash chunk.
3. Tracker xử lý đồng thời, ánh xạ file/chunk sang peer đang hoạt động.
4. Heartbeat và tự hết hạn peer mất liên lạc.
5. Uploader có dispatcher, hàng đợi và worker pool; đọc đúng block trên đĩa.
6. Chính sách upload tit-for-tat cơ bản.
7. Downloader tải song song nhiều nguồn, xác minh hash, ghép file.
8. Tự chuyển nguồn khi peer ngắt kết nối.

### Nên có sau khi hoàn thành yêu cầu bắt buộc

- Resume từ chunk đã xác minh; giới hạn số kết nối và bộ nhớ; log có request ID.
- Script chạy demo, kiểm thử lỗi tự động, biểu đồ benchmark.
- CLI hiển thị tiến độ, nguồn mỗi chunk, tốc độ và số lần retry.

### Chỉ làm khi còn thời gian

- Rarest-first, dashboard hoặc Docker đóng gói demo.
- Không ưu tiên DHT, NAT traversal, tracker replication hay giao diện đẹp trước các yêu cầu bắt buộc.

## 3. Quyết định chung phải chốt trong ngày 1–2

### Công nghệ

Nếu cả nhóm chưa có lựa chọn, đề xuất Java + TCP Socket + ExecutorService + thư viện JSON + JUnit. Đây là lựa chọn thiết kế, không phải yêu cầu giảng viên. Nếu nhóm thành thạo Python/C++ hơn, giữ nguyên hợp đồng và chuyển ngôn ngữ; không chia ba module sang ba ngôn ngữ để tăng rủi ro tích hợp.

### Metadata

- version, filename, file_size, chunk_size, chunk_count, hash_algorithm, chunk_hashes, file_hash, tracker_endpoint.
- Đề xuất chunk_size mặc định 1 MiB, cấu hình được; SHA-256 cho chunk và file.
- file_id = SHA-256 toàn bộ file; kiểm tra manifest nhất quán với file_id/file_hash và số chunk.
- file rỗng: 0 chunk, hash của nội dung rỗng; hoặc từ chối rõ ràng nếu nhóm thống nhất thu hẹp phạm vi và ghi vào báo cáo.
- .torrent JSON tùy biến chỉ là định dạng của BitTorrent-Lite. Không tuyên bố tương thích BitTorrent chuẩn. Xác nhận yêu cầu định dạng với giảng viên sớm; nếu cần bencoding thì chỉnh trước khi triển khai.

### Giao thức TCP v1

- 4 byte độ dài header, big-endian → JSON UTF-8 → body nhị phân dài đúng body_length.
- Header: version, request_id, message_type, peer_id, file_id, chunk_index (nếu có), body_length, status/error_code.
- Giới hạn header và body; đọc đủ byte bằng vòng lặp. TCP không bảo toàn ranh giới message.
- Tracker: REGISTER, HEARTBEAT, GET_PEERS, HAVE, LEAVE. REGISTER khai báo endpoint và tập chunk đã xác minh; đăng ký lại là idempotent.
- Uploader: GET_CHUNK; phản hồi OK + byte, NOT_FOUND, INVALID_REQUEST, BUSY hoặc CHOKED + retry_after.
- GET_PEERS trả nguồn theo chunk hoặc trả bitmap chunk của từng peer; chọn một cách duy nhất trước ngày 2.
- Không gửi đường dẫn tùy ý qua mạng. Server chỉ dùng file_id/chunk_index để truy cập dữ liệu đã đăng ký.
- peer_id ổn định trong phiên; session_id/generation phân biệt lần khởi động lại. HAVE/heartbeat cũ không được ghi đè phiên mới.

### Cấu hình ban đầu để thử nghiệm

- Heartbeat H = 2 giây; TTL = 8 giây; quét hết hạn S = 1 giây.
- Dùng đồng hồ monotonic tại tracker, không dựa vào đồng bộ thời gian các máy.
- Connect timeout 2 giây; read timeout 5 giây; đều cấu hình được.
- Worker upload = 4; download song song = 4; hàng đợi upload hữu hạn, ví dụ 32.
- Khi nghẽn trả BUSY; retry có backoff và giới hạn tổng thời gian. Khi không có nguồn, báo WAITING/UNAVAILABLE rõ ràng, không quay vòng vô hạn.

## 4. Thành viên 1 — Central Tracker & Directory Server

### Trách nhiệm

- Sở hữu metadata generator/parser, tracker, membership, heartbeat expiry và contract tracker.
- Chịu trách nhiệm điều phối hợp đồng giao tiếp; không gánh toàn bộ báo cáo hoặc kiểm thử của nhóm.

### Công việc theo thứ tự

1. Đọc Lecture 01 trang 15–16, Lecture 02 phần naming/marshaling, Lecture 04 trang 8; bổ sung đọc các phần giáo trình khi có file.
2. Tạo manifest từ file; tính hash dạng stream; parser kiểm tra kích thước và số hash.
3. Lưu peers[peer_id] = endpoint, session_id, last_seen và availability[file_id][chunk_index] = tập peer.
4. Xử lý REGISTER/GET_PEERS/HAVE/LEAVE; mọi GET_PEERS phải lọc peer đã hết hạn ngay cả khi thread dọn dẹp chưa chạy.
5. Chạy heartbeat và cleanup an toàn khi đồng thời REGISTER/HAVE/GET_PEERS. Khóa ngắn; không giữ khóa khi ghi socket.
6. Lưu manifest nếu cần; membership là soft state. Tracker khởi động lại thì peer tự đăng ký lại inventory.
7. Log register, heartbeat, expire, query, have, leave; xuất snapshot membership phục vụ demo.

### Kiểm thử và nghiệm thu

- File nhỏ hơn chunk, đúng bội số chunk, dư chunk cuối; manifest sai hash_count bị từ chối.
- 20 client thử đăng ký/truy vấn đồng thời không làm sai ánh xạ hoặc gây exception cạnh tranh.
- Peer hết hạn không xuất hiện trong query sau TTL; cleanup xóa bản ghi trong khoảng TTL + S + sai số lập lịch đo được.
- HAVE lặp không tạo nguồn trùng; REGISTER lại không giữ endpoint cũ.
- Manifest và chunk listing khớp với uploader/downloader thật, không chỉ mock.

### Bàn giao

- Mã metadata/tracker, tài liệu API, test tracker, log heartbeat/expiry, sơ đồ control plane.
- Viết phần kiến trúc hybrid, directory/naming, soft-state, giới hạn tracker là single point of failure.
- Demo tạo manifest, đăng ký peer, tắt một peer và quan sát expiry.

## 5. Thành viên 2 — Multithreaded Peer Server / Uploader

### Trách nhiệm

- Sở hữu TCP server, dispatcher/worker, chunk store dùng chung và chính sách upload.
- Thành viên 3 tích hợp downloader với chunk store này; tránh hai cách đánh dấu trạng thái chunk khác nhau.

### Công việc theo thứ tự

1. Đọc Lecture 04 trang 4–5 và phần giáo trình dispatcher/worker khi có.
2. Dispatcher nhận kết nối, chuyển socket vào hàng đợi hữu hạn; worker đọc request, kiểm tra, đọc chunk và trả dữ liệu. Đặt timeout để client chậm không giữ worker mãi.
3. Cung cấp API hasVerifiedChunk, readChunk, commitVerifiedChunk. Dùng file tạm riêng từng chunk và atomic move/commit; chỉ xuất bản trạng thái verified sau khi commit thành công.
4. Không giữ global lock trong toàn bộ thời gian đọc đĩa hoặc truyền mạng.
5. Thống kê bytes_sent, bytes_received_verified theo peer; nhận sự kiện chunk tải thành công từ downloader.
6. Triển khai tit-for-tat cơ bản có cửa sổ đo, upload slots, optimistic unchoke và xử lý bootstrap.
7. Thêm cấu hình test trả chunk sai, trì hoãn phản hồi, ngắt giữa chunk; tắt mặc định khi chạy bình thường.

### Tit-for-tat đề xuất cụ thể

- Mỗi 10 giây xếp hạng peer đang muốn tải theo lượng byte hợp lệ đã nhận từ họ trong cửa sổ gần nhất.
- Cho 2 slot ưu tiên và 1 optimistic slot luân phiên cho peer mới/ít dữ liệu. Request peer đang bị choked nhận CHOKED + retry_after.
- Nếu cần throttle tốc độ, dùng cơ chế giới hạn tốc độ riêng; chia băng thông đều không tự động trở thành tit-for-tat.
- Seeder đã có đủ file không có nhu cầu nhận lại: dùng chính sách round-robin/fair upload cho seeder và ghi rõ đây là ngoại lệ.
- Không tin số byte do remote peer tự khai báo. Credit do downloader cục bộ xác nhận sau khi kiểm tra hash.
- Demo phải có peer vừa download vừa upload. Chỉ một seeder và một downloader không đủ chứng minh trao đổi có đi có lại.
- Đây là phiên bản đơn giản, chưa chống giả mạo peer_id/Sybil; không tuyên bố triển khai hoàn chỉnh giao thức BitTorrent.

### Kiểm thử và nghiệm thu

- Nhiều yêu cầu đồng thời trả đúng chunk và hash; chunk cuối không bị padding hoặc cắt sai.
- Chunk chưa verified không bao giờ được phục vụ; chunk tạm không xuất hiện trong HAVE.
- Log thể hiện dispatcher và ít nhất 2 worker xử lý chồng lấp thời gian; một client chậm không chặn toàn server khi còn worker.
- Quá tải trả BUSY hoặc từ chối có kiểm soát; thread, queue và file descriptor không tăng không giới hạn.
- Demo cho thấy peer đóng góp được ưu tiên và peer mới vẫn có cơ hội tải; không deadlock bootstrap.

### Bàn giao

- Mã uploader/chunk store, test đồng thời và policy, log worker/slot, biểu đồ băng thông theo peer.
- Viết phần concurrency, đồng bộ bộ nhớ, đọc đĩa, tit-for-tat và giới hạn chính sách.
- Demo server phục vụ nhiều peer và thay đổi upload slot theo đóng góp.

## 6. Thành viên 3 — Multithreaded Peer Client / Downloader

### Trách nhiệm

- Sở hữu scheduler tải, connection/retry, kiểm tra hash, ghép file, CLI và kịch bản end-to-end.
- Không làm lại uploader thành một chương trình seeder khác; cùng thành viên 2 ghép thành một peer hai vai trò.

### Công việc theo thứ tự

1. Đọc Lecture 02 phần sockets/marshaling, Lecture 04 trang 5 và yêu cầu tải song song trong đề.
2. Parser manifest dùng thư viện chung; hỏi tracker để lập bảng chunk → các nguồn.
3. Quản lý trạng thái MISSING → IN_FLIGHT → VERIFIED; lỗi trả về MISSING. Scheduler cấp phát chunk bằng thao tác nguyên tử để tránh tải trùng ngoài ý muốn.
4. Tạo bounded pool tải đồng thời từ nhiều peer; ưu tiên phân phối đều trước khi làm rarest-first.
5. Đọc đủ body, xác minh hash, commit chunk qua API chung; sau đó gửi HAVE và credit event cho uploader.
6. Timeout, EOF, hash mismatch: bỏ dữ liệu tạm, ghi nguyên nhân, chuyển sang nguồn khác; không tải lại chunk đã VERIFIED.
7. Khi nguồn trong cache không dùng được, query lại tracker; một peer mới tham gia có thể giúp hoàn thành tải đang chờ.
8. Ghép theo chunk_index, kiểm tra file_size và file_hash; đổi tên output tạm sang file hoàn chỉnh chỉ sau thành công.
9. Nếu còn thời gian: resume qua bitmap và kiểm tra lại chunk trên đĩa trước khi tái sử dụng.

### Kiểm thử và nghiệm thu

- Một lượt tải có ít nhất 2 peer cung cấp chunk và ít nhất 2 transfer chồng lấp thời gian, chứng minh bằng log.
- Tắt peer khi đang gửi chunk: downloader tự đổi nguồn nếu chunk đó có bản sao khả dụng.
- Nhận chunk sai: hash mismatch được phát hiện, chunk sai không được commit/HAVE, tải lại từ nguồn khác.
- Thiếu nguồn cho một chunk: trạng thái thiếu nguồn rõ ràng; không báo hoàn thành giả.
- File cuối trùng kích thước và SHA-256 file gốc, kể cả file nhị phân và chunk cuối lẻ.

### Bàn giao

- Mã downloader/CLI, integration tests, script demo/benchmark, log failover và integrity.
- Viết phần scheduler, retry, tính đúng đắn, kết quả hiệu năng và đánh đổi.
- Demo tải đa nguồn, kill peer, chunk lỗi, file cuối đúng hash.

## 7. Kế hoạch 4 tuần và cổng nghiệm thu

| Mốc | Thành viên 1 | Thành viên 2 | Thành viên 3 | Cổng nghiệm thu chung |
|---|---|---|---|---|
| Ngày 1–2 | Schema metadata, API tracker | Framing, API chunk store | State machine, API downloader | Chốt protocol v1, cấu hình và test data; gửi giảng viên câu hỏi rubric/định dạng nếu cần |
| Tuần 1 | Manifest + register/query tối thiểu | Dispatcher + đọc một chunk | Tải một chunk + kiểm tra hash | Một luồng thật chạy xuyên 3 module, ghép được file nhỏ |
| Tuần 2 | Heartbeat, expiry, xử lý đồng thời | Worker pool, queue hữu hạn, upload đồng thời | Tải đa nguồn, retry, HAVE | 1 tracker + 3 peer; tắt một nguồn vẫn hoàn thành nếu còn bản sao |
| Tuần 3 | Membership race/rejoin, log expiry | Tit-for-tat, optimistic slot, fault injection | Hash mismatch, thiếu nguồn, resume nếu đủ thời gian | Tất cả yêu cầu bắt buộc có test và bằng chứng; chạy LAN trên các máy thật |
| Tuần 4 | Hoàn thiện phần báo cáo + kiến trúc | Benchmark server/policy + hình minh họa | Benchmark download + script demo | Đóng băng tính năng; chạy từ checkout sạch, rehearsal, hoàn tất báo cáo và backup |

- Cuối mỗi buổi cập nhật: đã xong gì, bằng chứng, blocker, việc tiếp theo.
- Mỗi người review một phần của người khác: TV1 review downloader; TV2 review tracker; TV3 review uploader.
- Không đổi protocol riêng lẻ. Thay đổi cần cập nhật tài liệu và contract test trong cùng lần sửa.
- Mỗi task có một owner, tiêu chí xong và PR/commit có thể truy vết. Báo cáo phản ánh đóng góp thực tế.
- Nếu chỉ còn 2 tuần: giữ toàn bộ yêu cầu bắt buộc, gộp tuần 1–2 và 3–4; bỏ UI, rarest-first, Docker và resume nếu cần. Không bỏ tit-for-tat, failover hoặc kiểm tra hash.

## 8. Ma trận kiểm thử phải có

| ID | Tình huống | Kết quả mong đợi | Owner chính |
|---|---|---|---|
| T01 | File < chunk, đúng bội số, chunk cuối lẻ | Byte và hash file cuối đúng | TV1 + TV3 |
| T02 | Tracker nhận nhiều REGISTER/HAVE/query | Không race, duplicate hoặc sai inventory | TV1 |
| T03 | Mất heartbeat | Query bỏ peer quá TTL; cleanup có log | TV1 |
| T04 | Nhiều request upload, một client chậm | Các worker khác vẫn phục vụ | TV2 |
| T05 | Tải từ 2–3 nguồn | Có transfer chồng lấp và nguồn khác nhau | TV3 |
| T06 | Kill peer giữa chunk | Đổi nguồn, không mất chunk đã xác minh | TV3 |
| T07 | Peer trả chunk sai | Phát hiện hash mismatch; retry từ nguồn tốt | TV2 + TV3 |
| T08 | Không còn nguồn cho chunk | WAITING/UNAVAILABLE, không success giả | TV3 |
| T09 | Peer đóng góp khác nhau và peer mới | Slot ưu tiên và optimistic hoạt động | TV2 |
| T10 | Restart peer/tracker | Peer đăng ký lại, không dùng phiên cũ | TV1 |
| T11 | Sai length/chunk_index/file_id, queue đầy | Từ chối có kiểm soát; không crash | TV2 |
| T12 | Cài và chạy từ checkout sạch | README đủ, không phụ thuộc đường dẫn cá nhân | Cả nhóm |

## 9. Đo hiệu năng có giá trị

- So sánh: 1 nguồn/1 luồng; nhiều nguồn/1 luồng tổng; nhiều nguồn/4 luồng tổng. Tách được tác động của đa nguồn và song song.
- Cùng file (ví dụ 100 MiB và 500 MiB nếu phù hợp máy), chunk size, máy và giới hạn băng thông. Chạy ít nhất 5 lần mỗi cấu hình; báo median và min–max.
- Đo thời gian hoàn thành, throughput = file_size/thời gian, số byte/chunk từng peer, retry, phát hiện lỗi và thời gian phục hồi.
- Có thí nghiệm failover riêng, ghi thời điểm kill và thời điểm bắt đầu transfer thành công từ nguồn mới.
- Đo policy trên swarm có trao đổi hai chiều; tách kết quả seeder fairness khỏi tit-for-tat.
- Lưu dữ liệu thô CSV và thông số máy/mạng. Nêu ảnh hưởng disk cache và tài nguyên dùng chung khi chạy localhost.
- Không hứa tải 3 nguồn nhanh gấp 3. Nếu giới hạn là đường truyền của downloader hoặc đĩa, giải thích bằng số liệu.
- Nếu dùng giới hạn tốc độ trong ứng dụng để mô phỏng mạng, ghi rõ điều kiện thí nghiệm đó.

## 10. Kịch bản demo 8–10 phút

1. 0:00–1:00: trình bày kiến trúc, ba module và đường đi control/data.
2. 1:00–2:00: tạo manifest, chạy tracker và 3 peer; hiển thị chunk inventory.
3. 2:00–4:00: tải từ ít nhất 2 peer, chỉ ra chunk và transfer chồng lấp; peer đang tải cũng upload chunk đã xác minh.
4. 4:00–5:00: kill một nguồn; chỉ ra timeout/retry, nguồn thay thế và tracker expiry. Chuẩn bị bản sao chunk cần thiết để demo có khả năng hoàn thành.
5. 5:00–6:00: bật một nguồn trả chunk sai ở lượt test riêng; chỉ ra từ chối, tải lại và hash cuối đúng.
6. 6:00–7:30: trình diễn tit-for-tat bằng log slot/credit, optimistic slot và ngoại lệ seeder.
7. 7:30–9:00: trình bày benchmark, so sánh hash và giới hạn tracker tập trung.
8. 9:00–10:00: phân công đóng góp, kết luận và hỏi đáp.

Chuẩn bị script reset dữ liệu, cấu hình cổng/IP, file test, log mẫu và video dự phòng. Chạy thử đúng mạng/phòng demo nếu có thể; kiểm tra firewall trước ngày bảo vệ. Một laptop chạy nhiều process hữu ích để tái lập, nhưng nên có ít nhất một lần chạy qua nhiều máy để chứng minh truyền mạng thật.

## 11. Báo cáo và bảo vệ

Đề xuất báo cáo 12–18 trang nếu giảng viên chưa quy định: yêu cầu; kiến trúc và liên hệ bài học; protocol/metadata; tracker; uploader và concurrency; downloader; kiểm thử lỗi; benchmark; giới hạn; đóng góp và hướng dẫn chạy. Mỗi người viết và chịu trách nhiệm phần của mình; cả nhóm thống nhất thuật ngữ và rà soát số liệu.

Hình cần có: kiến trúc tracker–peer; sequence tải bình thường; sequence failover; dispatcher/worker; state machine chunk; biểu đồ benchmark. Mỗi khẳng định quan trọng nên có test/log hoặc số liệu đi kèm.

Cả ba cần trả lời được:

- Vì sao là hybrid P2P? Tracker có chuyển byte file không?
- File name, file_id và endpoint khác nhau thế nào?
- TCP tin cậy rồi vì sao còn hash ứng dụng?
- Vì sao một recv/read không tương đương một message?
- Tracker timeout có chứng minh peer chết không? Chọn TTL có đánh đổi gì?
- Trạng thái shared nào cần khóa? Vì sao không khóa trong khi gửi mạng?
- Downloader tránh cấp cùng chunk cho hai worker thế nào?
- Vì sao peer mới không bị starvation trong tit-for-tat? Seeder được xử lý ra sao?
- Nếu tracker chết, peer với nguồn đã biết còn tải được không? Khám phá nguồn mới bị ảnh hưởng thế nào?
- Nếu mọi bản sao của một chunk đều mất thì hệ thống làm gì?
- Vì sao benchmark không tăng tuyến tính theo số peer?

## 12. Checklist trước nộp

- [ ] Mọi yêu cầu trong ảnh có module, test và minh chứng tương ứng.
- [ ] Mỗi peer có cả upload/download, hỗ trợ chia sẻ chunk đã xác minh.
- [ ] Demo đa nguồn, worker pool, heartbeat expiry, failover, hash mismatch và tit-for-tat chạy ổn định.
- [ ] Không báo thành công nếu thiếu chunk hoặc hash file sai.
- [ ] README ghi phiên bản runtime, lệnh build/run, IP/port và cấu hình.
- [ ] Không cần đường dẫn tuyệt đối của máy tác giả để chạy.
- [ ] Có dữ liệu benchmark thô; biểu đồ không dùng số liệu giả.
- [ ] Có báo cáo, slide, log, kịch bản và video dự phòng nếu được phép.
- [ ] Cả ba đã diễn tập và giải thích được phần giao tiếp với module còn lại.
