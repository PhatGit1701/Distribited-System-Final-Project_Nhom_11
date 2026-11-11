# Tracker PostgreSQL

## Profiles

- `dev` và `prod` đều dùng PostgreSQL với Flyway và Hibernate validate. Mặc định là prod; không còn dependency H2, kể cả test.
- Ứng dụng tự đọc `.env` trong working directory; biến môi trường có độ ưu tiên cao hơn. `prod` dùng PostgreSQL, Flyway chạy migration trước khi Hibernate `validate`. Bắt buộc đặt `TRACKER_DB_URL`, `TRACKER_DB_USER`, `TRACKER_DB_PASSWORD`. Không tự baseline hoặc xóa schema.

V1 khởi tạo **database trống**. Không tự chuyển dữ liệu từ H2 cũ (`user_accounts`, `torrents`) sang schema mới. Nếu cần giữ dữ liệu cũ, phải có bước xuất/chuyển dữ liệu riêng, bao gồm xác định owner cho metadata cũ.

## Chạy PostgreSQL 16 và tracker (PowerShell)

Cần JDK 21+, Maven và Docker Desktop đang chạy Linux containers. Chạy từ thư mục gốc repository.

```powershell
$env:JAVA_HOME = 'D:\Java21' # Thay bằng JDK của máy
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:TRACKER_DB_USER = 'bittorrent'
$env:TRACKER_DB_PASSWORD = Read-Host 'Mật khẩu PostgreSQL dùng cho local'
$env:TRACKER_DB_URL = 'jdbc:postgresql://localhost:5432/bittorrent'

docker compose up -d --wait postgres
mvn -pl tracker-server -am package -DskipTests
java -Duser.timezone=UTC -jar tracker-server/target/tracker-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

Tracker mặc định ở port 8080, có thể đặt `TRACKER_PORT`. Khi triển khai lâu dài, đặt `TRACKER_JWT_SECRET` bằng secret ít nhất 32 byte để token không mất hiệu lực sau mỗi lần khởi động. Dùng JVM timezone UTC để tránh JDBC gửi alias cũ như `Asia/Saigon` không được bản tzdata trong image PostgreSQL nhận diện. Trong IntelliJ thêm `-Duser.timezone=UTC` vào VM options của tracker. Timestamp trong database dùng timezone-aware `TIMESTAMP WITH TIME ZONE`.

Compose chỉ mở PostgreSQL trên `127.0.0.1` và lưu dữ liệu trong named volume `postgres_data`. `POSTGRES_DB` mặc định `bittorrent`, `POSTGRES_PORT` mặc định `5432`; nếu đổi chúng cần cập nhật JDBC URL tương ứng. User/password của image chỉ được tạo khi volume còn trống. Thay biến môi trường không đổi mật khẩu trong volume đã tồn tại.

```powershell
docker compose ps
docker compose down # Dừng container, giữ volume và dữ liệu
```

## Chạy dev / IntelliJ

```powershell
java -Duser.timezone=UTC -jar tracker-server/target/tracker-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

Cấu hình local hiện tại dùng PostgreSQL localhost:5433, database bittorrent qua `.env`. Không cần chạy thêm Compose nếu đã có PostgreSQL. Dev cũng yêu cầu PostgreSQL.

Trong IntelliJ chọn main class `vn.nhom11.bittorrent.tracker.TrackerApplication`, classpath module `tracker-server`, JDK 21 và working directory gốc repository. Program arguments là `--spring.profiles.active=prod`; đặt ba biến `TRACKER_DB_*` trong mục Environment variables của Run Configuration. Có thể dùng `.env` ở thư mục gốc thay cho Environment variables. Dev cũng dùng cùng cấu hình PostgreSQL. Peer và URL REST giữ nguyên.

## Kiểm thử

```powershell
mvn test-compile
mvn test
# Chỉ chạy bộ PostgreSQL cùng các module phụ thuộc
mvn -pl tracker-server -am "-Dtest=PostgresRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

`PostgresRepositoryTest` tự tạo PostgreSQL 16 với cổng ngẫu nhiên, không dùng database Compose hoặc biến `TRACKER_DB_*`. Test chạy Flyway V1, Hibernate validate, round-trip repository, BYTEA, CHECK/UNIQUE/FK, optimistic locking, quyền sở hữu file và các test REST kế thừa. Không bỏ qua test khi thiếu Docker. Cần Docker Engine 25+; `src/test/resources/docker-java.properties` đặt API 1.44 cho Testcontainers 1.21.3 đi kèm Spring Boot 3.5.7, tương thích Docker 29.

## Schema và REST

- `users` có username/passkey duy nhất, password hash, role, is_banned và version. Passkey ngẫu nhiên 32 byte được biểu diễn bằng 64 ký tự hex. Role mặc định USER và is_banned mặc định false; tác vụ quản trị/quy tắc ban không được thêm trong thay đổi database này.
- `shared_files.file_id` là khóa SHA-256 64 ký tự theo API hiện có, không đổi thành info_hash của BitTorrent chuẩn. `chunk_hashes` là BYTEA chứa nối tiếp các SHA-256 digest 32 byte theo index; độ dài phải bằng `chunk_count * 32`. `tracker_url` giữ nguyên để trả lại DTO cũ. Owner lấy từ subject JWT lúc đăng ký đầu tiên và không đổi khi peer khác publish lại cùng metadata.
- File phải lớn hơn 0 byte, chunk size/count hợp lệ và khớp kích thước. File rỗng nhận HTTP 400. Endpoint, tên trường JSON và response còn lại giữ nguyên.
- `user_file_stats` có một dòng cho mỗi cặp user/file, bộ đếm không âm và completed_at. `@Version` ngăn mất cập nhật khi cùng sửa một bản ghi; caller nhận lỗi optimistic locking và có thể đọc lại/retry.
- `peer_reports` lưu reporter, file, peer ID, lý do và thời điểm báo cáo. Stats được cập nhật qua delta heartbeat theo batch 45 giây và sự kiện completed/stopped. Peer reports vẫn chỉ có entity/repository, chưa có API báo cáo. Heartbeat/swarm dùng Redis ở prod và bộ nhớ ở dev/test; xem SWARM_REDIS.md.
- `@Version` cũng có trên users/shared_files; version nullable trong entity giúp Spring Data nhận biết file mới với ID được gán sẵn, tránh merge đè nội dung cùng ID.

Ánh xạ binary dùng `byte[]` + VARBINARY, không dùng `@Lob` để tránh PostgreSQL OID. Tham khảo [Hibernate PostgreSQL mapping](https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java) và [Testcontainers Docker API compatibility](https://github.com/testcontainers/testcontainers-java/issues/11210).
