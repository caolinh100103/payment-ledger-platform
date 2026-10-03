# Kế hoạch: Payment Processing & Ledger Platform

> Tài liệu kế hoạch tổng thể của dự án. Cập nhật checklist sau mỗi phase.
> Lần cập nhật cuối: 2026-10-03

## 1. Mục tiêu

Xây một platform mô phỏng hệ thống xử lý thanh toán theo hướng ngân hàng, dùng làm portfolio cho vị trí
IT Banking / Backend, và có thể phát triển tiếp thành sản phẩm.

Dự án phải thể hiện được:

- Database transaction & isolation level
- Optimistic / Pessimistic locking
- Idempotency
- Double-entry accounting
- Event-driven architecture với Kafka
- Security / RBAC
- Audit & compliance
- Retry & failure handling
- Observability
- CI/CD

---

## 2. Các quyết định kiến trúc đã chốt

| # | Quyết định | Lý do | ADR |
|---|---|---|---|
| 1 | Bắt đầu bằng **modular monolith**, chỉ tách Notification và Audit ra service riêng | Tập trung vào logic tiền trước, tránh sa lầy vào hạ tầng | [0001](adr/0001-modular-monolith-first.md) |
| 2 | Tiền lưu bằng **BIGINT theo đơn vị nhỏ nhất** (VND: đồng, USD: cent), không dùng float | Tính toán chính xác tuyệt đối | [0002](adr/0002-money-as-minor-units.md) |
| 3 | **Ghi ledger đồng bộ** trong cùng DB transaction với transfer | Ledger cập nhật bất đồng bộ qua Kafka thì không chặn được double spending | [0003](adr/0003-synchronous-ledger-with-outbox.md) |
| 4 | Dùng **Transactional Outbox** để publish event lên Kafka | Tránh dual-write: ghi DB thành công mà gửi Kafka thất bại thì mất event | [0003](adr/0003-synchronous-ledger-with-outbox.md) |
| 5 | Dùng **một repo chung** (monorepo) cho backend, frontend và infra | Recruiter chỉ cần mở một link; sửa API và UI trong cùng một commit | — |
| 6 | **Pessimistic lock** (`FOR NO KEY UPDATE`) theo thứ tự id tăng dần, `READ COMMITTED`, có `lock_timeout` | Benchmark: optimistic chỉ nhanh hơn ~14% khi ít tranh chấp, nhưng chậm hơn 37–54% và làm hỏng ~10% giao dịch khi có tài khoản "nóng" | [0004](adr/0004-pessimistic-row-locking-for-transfers.md) |
| 7 | **Idempotency key lưu trong PostgreSQL**, cùng transaction với nghiệp vụ (không dùng Redis) | Redis và Postgres không commit nguyên tử cùng nhau, crash giữa chừng sẽ mở lại khả năng trừ tiền 2 lần | [0005](adr/0005-idempotency-keys.md) |
| 8 | **Tài khoản SYSTEM** làm đối ứng (được phép âm); tổng số dư mỗi currency luôn bằng 0; ledger chỉ INSERT; giao dịch bị từ chối vẫn được lưu `FAILED` | Đối soát chỉ cần so tài khoản funding với sao kê ngân hàng; mọi số dư đều tính lại được từ ledger | [0006](adr/0006-double-entry-ledger-model.md) |
| 9 | **Outbox relay dạng polling** chạy trên mọi instance với `FOR UPDATE SKIP LOCKED`, mỗi transfer chỉ lấy event cũ nhất còn chờ | Chạy song song nhiều instance mà vẫn giữ thứ tự event của từng transfer; không cần thêm Kafka Connect hay replication slot như Debezium | [0007](adr/0007-polling-outbox-relay.md) |
| 10 | Event theo **CloudEvents 1.0** + extension `schemaversion`; consumer là tolerant reader và chuyển các version không hiểu vào DLT | Hợp đồng event rõ ràng ([events.md](events.md)), producer thêm field mà không làm hỏng consumer | [0008](adr/0008-idempotent-consumers-retry-topics-dlq.md) |
| 11 | **Consumer idempotent** (`processed_events` ghi cùng transaction với tác dụng phụ) + **retry topic không chặn** + **DLT**, tên topic riêng cho từng service | Kafka giao at-least-once; một event lỗi không được chặn cả partition; hai service cùng đọc một topic không được đọc nhầm retry của nhau | [0008](adr/0008-idempotent-consumers-retry-topics-dlq.md) |
| 12 | **Audit service riêng, database riêng**, ba lớp: phân quyền (`audit_app` chỉ có `SELECT, INSERT`), trigger chặn sửa/xóa, **hash chain** | Core bị chiếm quyền cũng không sửa được lịch sử; superuser sửa thì hash chain phát hiện | [0009](adr/0009-tamper-evident-audit-log.md) |
| 13 | Core **tự phát hành token**: access token **ES256** theo RFC 9068, sống 5 phút; public key công bố ở **JWKS**; audit/notification chỉ verify | Khóa bất đối xứng: service khác không thể tự tạo token. FAPI 2.0 chỉ cho PS256/ES256/EdDSA, không cho RS256. Muốn đổi sang Keycloak chỉ cần đổi URL JWKS và issuer | [0010](adr/0010-authentication-tokens.md) |
| 14 | **Refresh token xoay vòng** + **phát hiện dùng lại** (dùng lại token cũ thì thu hồi cả phiên) | RFC 9700 §4.14.2, giống Auth0/Okta; token lộ chỉ dùng được đến lần refresh tiếp theo của chủ | [0010](adr/0010-authentication-tokens.md) |
| 15 | Mật khẩu **Argon2id** (tham số OWASP), chính sách **NIST SP 800-63B-4** (15–64 ký tự, không bắt buộc ký tự đặc biệt), **khóa sau 5 lần sai** (15 phút hoặc đến khi operator mở) | Giống VCB Digibank; khóa dòng user khi kiểm tra mật khẩu để đoán song song không vượt được bộ đếm | [0010](adr/0010-authentication-tokens.md) |
| 16 | **RBAC** bằng `@PreAuthorize` trên mọi endpoint (test fail nếu thiếu) + **kiểm tra sở hữu** trong service; ADMIN kế thừa OPERATOR, AUDITOR nhưng **không** kế thừa CUSTOMER; tài khoản của người khác trả **404**; **nạp tiền chỉ qua API key của ngân hàng** | Tách bạch nhiệm vụ: nhân viên không chuyển được tiền của khách; không lộ id tài khoản (OWASP API1) | [0011](adr/0011-authorization-and-api-keys.md) |
| 17 | **API key** kiểu GitHub (`plk_` + 32 ký tự base62 + checksum CRC32), chỉ lưu SHA-256, có scope (`deposits:write`), hạn dùng, thu hồi | Secret scanning nhận ra key bị lộ; key gõ sai bị loại mà không cần query DB | [0011](adr/0011-authorization-and-api-keys.md) |
| 18 | **Rate limit** token bucket trên **Redis** (Bucket4j) theo user / API key / IP; **fail-open** khi Redis lỗi | Theo Stripe: bộ giới hạn hỏng không được làm sập API; brute force vẫn bị chặn bởi khóa tài khoản trong PostgreSQL | [0012](adr/0012-rate-limiting.md) |

---

## 3. Kiến trúc

```
React (TS) ─────────┐  Bearer access token (JWT ES256)
Ngân hàng đối tác ──┤  X-API-Key (chỉ nạp tiền)
                    ▼
            Core Service (Spring Boot, modular monolith)
            ├── security   đăng nhập, token, JWKS, RBAC, API key, rate limit ──► Redis
            ├── account
            ├── transfer   ─┐
            ├── ledger     ─┤ cùng 1 DB transaction
            └── outbox     ─┘
                    │
              PostgreSQL ◄── Outbox relay ──► Kafka: transfers / accounts / security
                                                ├── Notification Service ─┐ verify token
                                                └── Audit Service ────────┘ bằng JWKS của core
            Prometheus + Grafana: metrics
```

**Tech stack:** Java 21 · Spring Boot 4.1 · Spring Security 7 · PostgreSQL 17 · Flyway · Kafka 4 (KRaft) ·
Redis + Bucket4j ·
Testcontainers · Prometheus · Grafana · Docker Compose · GitHub Actions · React + TypeScript

---

## 4. Data model

```sql
accounts         (id, owner_id, currency, type CUSTOMER/SYSTEM, status, balance BIGINT, version,
                  created_at, updated_at)                                                            -- ✅ Phase 1 + 2
transfers        (id, type DEPOSIT/TRANSFER/REVERSAL, status, source_account_id, destination_account_id,
                  amount BIGINT, currency, description, failure_code, failure_reason, reversal_of,
                  initiated_by, version, created_at, updated_at)                                     -- ✅ Phase 2, initiated_by Phase 4
ledger_entries   (id BIGINT identity, transfer_id, account_id, direction DEBIT/CREDIT, amount BIGINT,
                  currency, balance_after, created_at)                                               -- ✅ Phase 2, chỉ INSERT
idempotency_keys ((scope, idempotency_key) PK, request_hash, status, response_status, response_body,
                  response_location, lock_token, locked_until, created_at, expires_at)               -- ✅ Phase 2
outbox           (id BIGINT identity, event_id UNIQUE, aggregate_type, aggregate_id, event_type, topic,
                  payload JSON, created_at, published_at, attempts, last_error)                       -- ✅ Phase 3
users            (id, username UNIQUE lower-case, password_hash Argon2id, role, failed_login_attempts,
                  locked_until, last_login_at, version, created_at, updated_at)                      -- ✅ Phase 4
auth_sessions    (id, user_id, created_at, expires_at, revoked_at, revoke_reason LOGOUT/REFRESH_TOKEN_REUSE) -- ✅ Phase 4
refresh_tokens   (token_hash SHA-256 PK, session_id, issued_at, expires_at, used_at)                 -- ✅ Phase 4
api_keys         (id, name, prefix, key_hash SHA-256 UNIQUE, scopes, created_by, created_at, expires_at,
                  revoked_at, last_used_at)                                                           -- ✅ Phase 4

-- notification-service (database riêng)
notifications    (id, event_id, recipient_id, channel SMS/EMAIL/PUSH, template, message, created_at)  -- ✅ Phase 3
processed_events ((consumer, event_id) PK, processed_at)                                              -- ✅ Phase 3

-- audit-service (database riêng)
audit_events     (seq BIGINT PK không có khoảng trống, event_id UNIQUE, actor, action, resource_id,
                  occurred_at, source, payload TEXT, recorded_at, prev_hash, hash)                     -- ✅ Phase 3
```

**Quy tắc bất biến:**

- Với mỗi transfer: tổng DEBIT = tổng CREDIT. Database kiểm tra lúc commit bằng deferred constraint trigger.
- Tổng số dư của mọi tài khoản cùng một currency luôn bằng 0 (tài khoản SYSTEM là phía đối ứng).
- Số dư tài khoản khách hàng không bao giờ âm. Điều này được chặn bằng `CHECK` ngay trong database.
- `ledger_entries` không được sửa hay xóa: trigger chặn `UPDATE`, `DELETE`, `TRUNCATE`.
- Reverse một giao dịch **không sửa và không xóa dữ liệu cũ**, mà tạo bút toán bù (compensating entries) ngược chiều.
- Audit event không được sửa hay xóa. Thực thi bằng phân quyền (app chỉ có `SELECT, INSERT`), trigger chặn sửa, và hash chain để phát hiện can thiệp.
- Event có trong outbox khi và chỉ khi thay đổi của nó đã commit. Các event của cùng một transfer tới Kafka đúng thứ tự.
- Mật khẩu, refresh token và API key không bao giờ được lưu ở dạng gốc (Argon2id / SHA-256), không xuất hiện trong
  event hay log.
- Chỉ chủ tài khoản mới chuyển được tiền ra khỏi tài khoản đó; chỉ API key của ngân hàng mới nạp được tiền.

---

## 5. Lộ trình

### ✅ Phase 1: Nền tảng (hoàn thành 2026-10-02)

- [x] Docker Compose: Postgres, Redis, Kafka + Kafka UI, Prometheus, Grafana
- [x] Spring Boot 4.1 + Flyway + validate schema bằng Hibernate
- [x] Account API: tạo, xem, liệt kê theo owner, freeze / unfreeze / close
- [x] Quy tắc chuyển trạng thái nằm trong domain entity
- [x] Lỗi theo chuẩn RFC 9457 với `code` cố định; optimistic lock conflict trả về 409
- [x] Unit test + integration test với Testcontainers (14 test)
- [x] GitHub Actions CI
- [x] README + 3 ADR
- [x] Tạo repo GitHub [`caolinh100103/payment-ledger-platform`](https://github.com/caolinh100103/payment-ledger-platform) và push lên

### ✅ Phase 2: Core tiền ⭐ quan trọng nhất (hoàn thành 2026-10-03)

- [x] Tài khoản hệ thống (SYSTEM) để nạp tiền vào tài khoản khách hàng (deposit) theo double-entry
- [x] Transfer API, state machine `PENDING → COMPLETED / FAILED`
- [x] Ghi ledger entries + cập nhật balance trong cùng 1 transaction
- [x] `SELECT ... FOR NO KEY UPDATE`, lock theo thứ tự account_id tăng dần để tránh deadlock, có `lock_timeout` (quá hạn trả 503 + `Retry-After`)
- [x] Kiểm tra: tài khoản phải ACTIVE, hai tài khoản cùng currency, đủ số dư; khách hàng không được chuyển vào/ra tài khoản SYSTEM
- [x] Idempotency key: cùng key + cùng body thì trả response cũ; cùng key + khác body thì trả 422; đang xử lý thì trả 409
- [x] Reversal bằng compensating entries (`COMPLETED → REVERSED`); cho phép thu hồi tiền từ tài khoản FROZEN
- [x] **Test đồng thời:** 100 client HTTP chuyển tiền cùng lúc; số dư không âm, tổng tiền không đổi, không có deadlock
- [x] Benchmark so sánh pessimistic và optimistic locking, ghi kết quả vào [docs/benchmarks/locking.md](benchmarks/locking.md)
- [x] ADR: chiến lược locking (0004), chiến lược idempotency (0005), mô hình ledger (0006)

**Kết quả:** 84 test (unit + integration với PostgreSQL thật). Đã kiểm chứng bằng mutation test: nếu lock theo
thứ tự request thì PostgreSQL báo deadlock; nếu bỏ row lock thì `@Version` bắt được lost update. Cả hai trường hợp
đều làm test đồng thời fail.

**Để dành cho sau:** sao kê tài khoản (`GET /accounts/{id}/ledger-entries`), rút tiền ra ngân hàng (cần trạng thái
`PENDING`), phí giao dịch, hạn mức theo quy định của Ngân hàng Nhà nước, giảm tải cho tài khoản "nóng" (xem ADR 0004).

### ✅ Phase 3: Event-driven (hoàn thành 2026-10-03)

- [x] Bảng outbox ghi cùng transaction với transfer + relay polling `FOR UPDATE SKIP LOCKED` publish lên Kafka
      (key = transfer id, `acks=all`, idempotent producer)
- [x] Events theo CloudEvents 1.0 + `schemaversion`: `com.payledger.transfer.created` / `.completed` / `.failed` /
      `.reversed`, tài liệu ở [events.md](events.md)
- [x] Audit Service (`services/audit-service`, DB riêng): append-only, hash chain, phân quyền + trigger, API kiểm tra chuỗi
- [x] Notification Service (`services/notification-service`, DB riêng): SMS biến động số dư kiểu ngân hàng Việt Nam, giả lập bằng log
- [x] Consumer idempotent (bảng `processed_events` ghi cùng transaction; audit dùng `event_id` unique)
- [x] Retry topic không chặn (`@RetryableTopic`) + Dead Letter Topic, tên topic riêng cho từng service
- [x] Demo: tắt Kafka giữa chừng, bật lại thì không mất event (test tự động + [scripts/demo-kafka-outage.sh](../scripts/demo-kafka-outage.sh))
- [x] CI build cả 3 service song song (matrix)
- [x] ADR: outbox relay (0007), consumer idempotent + retry/DLQ (0008), audit log (0009)

**Kết quả:** 138 test (core 99, audit-service 21, notification-service 18), chạy với PostgreSQL và Kafka thật.
Đã kiểm chứng bằng mutation test:
- Bỏ điều kiện "chỉ lấy event cũ nhất của mỗi transfer" trong relay: event bị publish sai thứ tự.
- Bỏ advisory lock của audit: hai consumer nối vào cùng một mắt xích, gây trùng `seq`.
- Bỏ kiểm tra `processed_events`: cả 4 test idempotent fail.

Chạy demo trên stack thật (`docker compose stop kafka`): 5 giao dịch vẫn COMPLETED trong khoảng 200 ms. 10 event nằm
chờ trong outbox. Bật Kafka lại thì outbox về 0, khách nhận đủ SMS, audit có đủ event và chuỗi hash hợp lệ.

**Bài học / phát hiện khi làm:**
- `@KafkaListener(id = ...)` âm thầm ghi đè consumer group. Phải đặt `idIsGroup = false`.
- Hai service cùng đọc một topic phải dùng suffix retry/DLT riêng, nếu không sẽ đọc nhầm retry của nhau.
- Consumer phải kiểm tra `schemaversion` trên envelope trước khi bind `data`, nếu không bản v2 bị báo là
  "malformed" thay vì "unsupported".
- Lần gửi bị timeout vẫn có thể đã tới broker (ví dụ broker bị treo rồi chạy lại), nên Kafka có thể giữ một event
  hai lần. Idempotent producer không chặn được trường hợp này sau `delivery.timeout.ms`. Đó là lý do consumer bắt
  buộc phải khử trùng theo event id.

**Để dành cho sau:** neo (anchor) head hash của audit ra hệ thống bên ngoài để phát hiện việc cắt đuôi chuỗi;
crypto-shredding cho dữ liệu cá nhân trong audit log; công cụ replay từ DLT; metrics cho outbox (số event chờ,
`attempts`) và cho DLT (Phase 5); `LISTEN/NOTIFY` để giảm độ trễ relay; chuyển sang Debezium khi lưu lượng lớn.

### ✅ Phase 4: Security (hoàn thành 2026-10-03)

- [x] Spring Security + JWT: access token ES256 theo RFC 9068 (5 phút), public key ở `/.well-known/jwks.json`,
      refresh token xoay vòng + phát hiện dùng lại (15 phút idle, phiên tối đa 8 giờ), logout
- [x] Đăng ký / đăng nhập: Argon2id, chính sách NIST SP 800-63B-4, khóa sau 5 lần sai, không lộ username có tồn tại
      hay không, ADMIN đầu tiên tạo từ biến môi trường (như Keycloak)
- [x] RBAC: `CUSTOMER`, `OPERATOR`, `AUDITOR`, `ADMIN` bằng `@PreAuthorize` trên mọi endpoint, có test fail build nếu
      endpoint nào thiếu quy tắc
- [x] Lấy `ownerId` từ JWT thay vì từ request; kiểm tra quyền sở hữu tài khoản / giao dịch (tài khoản người khác trả 404)
- [x] API key cho client machine-to-machine (ngân hàng đối tác gọi nạp tiền): định dạng có prefix + checksum, lưu SHA-256,
      có scope, hạn dùng, thu hồi
- [x] Rate limiting bằng Redis (Bucket4j) theo user, API key và IP (cho đăng nhập); fail-open khi Redis lỗi
- [x] Ghi người khởi tạo giao dịch (`initiated_by`) và đưa vào `actor` của event; idempotency key tách theo từng client
- [x] Audit service và notification service thành OAuth 2.0 resource server (verify token bằng JWKS của core)
- [x] Đưa sự kiện bảo mật và thay đổi tài khoản vào audit trail (PCI DSS 10.2.1): đăng nhập thành công / thất bại (kèm IP),
      khóa / mở khóa, thu hồi phiên, tạo user, tạo / thu hồi API key, mở / đóng băng / đóng tài khoản
- [x] Demo: [scripts/demo-security.sh](../scripts/demo-security.sh); cập nhật demo Kafka cho luồng có đăng nhập
- [x] ADR: xác thực (0010), phân quyền + API key (0011), rate limiting (0012)

| Role | Quyền |
|---|---|
| CUSTOMER | Mở tài khoản cho mình, xem / đóng tài khoản của mình, chuyển tiền từ tài khoản của mình tới bất kỳ tài khoản khách nào, xem giao dịch mình là một bên |
| OPERATOR | Tra cứu tài khoản / giao dịch / user của mọi khách, freeze/unfreeze tài khoản, reverse giao dịch, mở khóa user |
| AUDITOR | Chỉ đọc audit log |
| ADMIN | Quyền của OPERATOR và AUDITOR, quản lý user và API key. **Không** chuyển được tiền của khách (tách bạch nhiệm vụ) |
| API key `deposits:write` | Chỉ báo nạp tiền |

**Kết quả:** 243 test (core 194, audit-service 26, notification-service 23), chạy với PostgreSQL, Kafka và Redis thật,
request đi qua bộ lọc bảo mật thật với token ký thật. Đã kiểm chứng bằng mutation test: bỏ khóa dòng user khi đăng nhập
thì 30 lần đoán song song đều được kiểm tra mật khẩu (27 lỗi optimistic lock + 3 bị từ chối) và tài khoản không bao giờ
bị khóa.

Chạy trên stack thật (3 service + docker compose): audit và notification tải JWKS từ core thành công; demo bảo mật cho
kết quả đúng ở mọi bước; bắn 150 request cùng lúc thì 126 qua, 24 bị 429; demo Kafka vẫn chạy (5 giao dịch ~200 ms khi
Kafka tắt) và audit ghi đúng người thực hiện.

**Bài học / phát hiện khi làm:**
- Decoder mặc định của Spring Security chỉ nhận `typ: JWT`. Token chuẩn RFC 9068 (`typ: at+jwt`) phải dùng
  `JwtValidators.createAtJwtValidator()`, và validator này bắt buộc có claim `client_id`.
- Spring Security 7 tự thêm `resource_metadata` (RFC 9728) vào header `WWW-Authenticate` và phục vụ
  `/.well-known/oauth-protected-resource`. Cần cấu hình để endpoint này chỉ đúng issuer.
- Không khóa dòng user khi kiểm tra mật khẩu là một lỗ hổng race condition: mọi request song song đều đọc "0 lần sai".
- Kiểm tra quyền sở hữu trước khi lock tài khoản phải đọc `owner_id` bằng projection, không load entity, nếu không
  `AccountLocker` sẽ nhận lại bản cũ trong persistence context thay vì dòng vừa khóa.
- Filter khai báo là Spring bean sẽ bị Spring Boot đăng ký thêm một lần ngoài security chain. Filter API key và rate
  limit được tạo bằng `new` trong `SecurityConfig`.
- Token bucket cho phép burst rồi cắt theo tốc độ nạp lại. 125 request gửi tuần tự không bị chặn (bucket nạp 2 token/giây
  trong lúc gửi); phải bắn song song mới thấy 429.
- Rate limiter phải tự quản lý timeout của Redis (200 ms) và có thời gian "nghỉ" sau lỗi, nếu không mỗi request đều phải
  chờ timeout khi Redis treo. Test bằng `docker pause` Redis.
- Nạp tiền tạo ra tiền của khách (đối ứng với tài khoản SYSTEM), nên không giao cho người nào, kể cả ADMIN: chỉ ngân hàng
  biết tiền đã thật sự về.

**Để dành cho sau:** xác thực sinh trắc học / step-up cho giao dịch trên 10 triệu đồng theo Quyết định 2345/QĐ-NHNN (OTP
cho phần còn lại); MFA, quên / đổi mật khẩu, kiểm tra mật khẩu đã lộ; maker-checker cho reversal lớn; hạn mức giao dịch
theo quy định Ngân hàng Nhà nước; công cụ xoay vòng khóa ký (JWKS đã hỗ trợ nhiều khóa); vô hiệu hóa user khi nhân viên
nghỉ việc; BFF + cookie HttpOnly cho SPA (Phase 6); mTLS cho kết nối ngân hàng; concurrent request limiter và load
shedding; thư viện bảo mật dùng chung cho các service.

### ⬜ Phase 5: Observability

- [ ] Metrics nghiệp vụ: `transfers_total{status}`, latency p50/p95/p99, lock timeout, số request trùng idempotency key
- [ ] Kafka consumer lag
- [ ] Grafana dashboard (lưu file JSON trong repo)
- [ ] Structured logging + correlation ID xuyên suốt HTTP → Kafka

### ⬜ Phase 6: Frontend & hoàn thiện

- [ ] React + TS (Vite, TanStack Query)
- [ ] Màn hình: đăng nhập, danh sách tài khoản, chuyển tiền, lịch sử giao dịch, audit (AUDITOR), admin
- [ ] Sinh TypeScript types từ OpenAPI spec của backend
- [ ] Tách CI thành `backend.yml` và `frontend.yml`, mỗi workflow có bộ lọc `paths:`
- [ ] Load test bằng k6, đưa kết quả TPS và latency vào README
- [ ] Deploy bản demo
- [ ] Quay video demo 2–3 phút + chụp màn hình Grafana

### Mở rộng (nếu còn thời gian)

Đa tiền tệ + FX · Scheduled transfers · Reconciliation job hằng ngày · Webhook cho merchant ·
Saga pattern khi tách ledger thành service riêng · Kubernetes

---

## 6. Quy ước làm việc

**Repo:** `payment-ledger-platform` (public)

**Cấu trúc thư mục:**

```
payment-ledger-platform/
├── backend/        # Core service (Spring Boot)
├── services/       # audit-service, notification-service (mỗi service một DB, một Maven project)
├── scripts/        # demo scripts
├── frontend/       # Phase 6: React + TS
├── infra/          # docker-compose, Prometheus, Grafana
├── docs/           # PLAN.md, ADR
└── .github/workflows/
```

**Branch:** mỗi phase hoặc tính năng một branch, xong thì mở PR vào `main`.

```
feature/phase-2-transfers
feature/idempotency-key
fix/transfer-deadlock
```

**Commit message** theo Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`

**Một phase chỉ được coi là xong khi:**

1. CI xanh
2. Có test cho các trường hợp lỗi và trường hợp đồng thời, không chỉ happy path
3. README và checklist trong file này đã được cập nhật
4. Các quyết định thiết kế quan trọng đã có ADR

---

## 7. Kịch bản demo khi phỏng vấn

1. **Idempotency:** gửi 2 request chuyển tiền cùng `Idempotency-Key`; tiền chỉ bị trừ 1 lần.
2. **Double spending:** 100 request đồng thời rút từ cùng một tài khoản; số dư không bao giờ âm.
3. **Cân bằng ledger:** query chứng minh tổng DEBIT = tổng CREDIT trên toàn hệ thống.
4. **Không mất event:** tắt Kafka, thực hiện giao dịch, bật lại Kafka; event vẫn được gửi đi đầy đủ
   (`scripts/demo-kafka-outage.sh`).
5. **Audit:** thử sửa một audit event; database từ chối (`permission denied`, trigger). Superuser tắt trigger để sửa
   thì `GET /api/v1/audit-events/verification` chỉ ra đúng bản ghi bị sửa.
6. **Bảo mật** (`scripts/demo-security.sh`): khách hàng khác không đọc / không chuyển được tiền từ tài khoản của Alice
   (404, không để lại dấu vết); đoán sai mật khẩu 5 lần thì bị khóa, operator mở khóa; refresh token bị đánh cắp thì cả
   phiên bị thu hồi; bắn nhiều request thì nhận 429; audit trail cho thấy ai làm gì, từ IP nào.
7. **Observability:** chạy load test bằng k6 và theo dõi latency, error rate trên Grafana.

---

## 8. Câu hỏi phỏng vấn cần chuẩn bị

- Vì sao chọn pessimistic lock thay vì optimistic lock cho transfer? Khi nào thì nên chọn ngược lại?
- Vì sao dùng `READ COMMITTED` + row lock mà không dùng `SERIALIZABLE`?
- Làm thế nào để tránh deadlock khi 2 transfer A→B và B→A chạy cùng lúc?
- Idempotency key nên được lưu bao lâu? Nếu request đầu tiên vẫn đang xử lý thì request thứ hai xử lý thế nào?
- Outbox pattern giải quyết vấn đề gì? Vì sao không dùng Kafka transaction thay thế?
- Nhiều instance cùng chạy relay với `SKIP LOCKED` thì làm sao giữ được thứ tự event? Khi nào nên chuyển sang Debezium?
- At-least-once delivery thì consumer phải làm gì? `processed_events` cần giữ bao lâu?
- Retry topic khác blocking retry ở điểm nào? Đánh đổi gì về thứ tự?
- Hash chain phát hiện được gì và không phát hiện được gì (cắt đuôi chuỗi)?
- Vì sao reverse giao dịch mà không xóa hay sửa dữ liệu cũ?
- Làm sao chứng minh audit log không bị sửa?
- Vì sao access token JWT sống ngắn mà không thu hồi được? Vì sao vẫn cần refresh token, và vì sao phải xoay vòng?
- Vì sao chọn ES256 thay vì HS256 hay RS256? JWKS giúp xoay vòng khóa thế nào?
- Phát hiện refresh token bị dùng lại hoạt động ra sao? Hai tab refresh cùng lúc thì chuyện gì xảy ra?
- Vì sao Argon2id mà không phải bcrypt? Vì sao refresh token / API key chỉ cần SHA-256?
- Khóa tài khoản sau 5 lần sai có thể bị vượt qua bằng request song song không? Chặn thế nào?
- Vì sao trả 404 thay vì 403 cho tài khoản của người khác?
- Vì sao ADMIN không được chuyển tiền của khách, và không được nạp tiền?
- Rate limiter nên fail-open hay fail-closed? Token bucket khác fixed window thế nào?
