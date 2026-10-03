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

---

## 3. Kiến trúc

```
React (TS) ──► API Gateway (JWT, rate limit, API key)
                    │
                    ▼
            Core Service (Spring Boot, modular monolith)
            ├── account
            ├── transfer   ─┐
            ├── ledger     ─┤ cùng 1 DB transaction
            └── outbox     ─┘
                    │
              PostgreSQL ◄── Outbox relay ──► Kafka
                                                ├── Notification Service
                                                └── Audit Service (append-only)
            Redis: rate limit
            Prometheus + Grafana: metrics
```

**Tech stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 17 · Flyway · Kafka 4 (KRaft) · Redis ·
Testcontainers · Prometheus · Grafana · Docker Compose · GitHub Actions · React + TypeScript

---

## 4. Data model

```sql
accounts         (id, owner_id, currency, type CUSTOMER/SYSTEM, status, balance BIGINT, version,
                  created_at, updated_at)                                                            -- ✅ Phase 1 + 2
transfers        (id, type DEPOSIT/TRANSFER/REVERSAL, status, source_account_id, destination_account_id,
                  amount BIGINT, currency, description, failure_code, failure_reason, reversal_of,
                  version, created_at, updated_at)                                                   -- ✅ Phase 2
ledger_entries   (id BIGINT identity, transfer_id, account_id, direction DEBIT/CREDIT, amount BIGINT,
                  currency, balance_after, created_at)                                               -- ✅ Phase 2, chỉ INSERT
idempotency_keys ((scope, idempotency_key) PK, request_hash, status, response_status, response_body,
                  response_location, lock_token, locked_until, created_at, expires_at)               -- ✅ Phase 2
outbox           (id, aggregate_id, event_type, payload JSONB, created_at, published_at)              -- Phase 3
processed_events (event_id PK, consumer, processed_at)                                                -- Phase 3
audit_events     (id, actor, action, resource_id, payload, ts, prev_hash, hash)                       -- Phase 3
```

**Quy tắc bất biến:**

- Với mỗi transfer: tổng DEBIT = tổng CREDIT. Database kiểm tra lúc commit bằng deferred constraint trigger.
- Tổng số dư của mọi tài khoản cùng một currency luôn bằng 0 (tài khoản SYSTEM là phía đối ứng).
- Số dư tài khoản khách hàng không bao giờ âm. Điều này được chặn bằng `CHECK` ngay trong database.
- `ledger_entries` không được sửa hay xóa: trigger chặn `UPDATE`, `DELETE`, `TRUNCATE`.
- Reverse một giao dịch **không sửa và không xóa dữ liệu cũ**, mà tạo bút toán bù (compensating entries) ngược chiều.
- Audit event không được sửa hay xóa. Thực thi bằng `REVOKE UPDATE, DELETE`, trigger chặn sửa, và hash chain để phát hiện can thiệp.

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

### ⬜ Phase 3: Event-driven

- [ ] Bảng outbox + relay publish lên Kafka
- [ ] Events có version schema: `TransferCreated`, `TransferCompleted`, `TransferFailed`, `TransferReversed`
- [ ] Audit Service: append-only, hash chain
- [ ] Notification Service (giả lập bằng log/email)
- [ ] Consumer idempotent (bảng `processed_events`)
- [ ] Retry topic + Dead Letter Queue (DLQ)
- [ ] Demo: tắt Kafka giữa chừng, bật lại thì không mất event

### ⬜ Phase 4: Security

- [ ] Spring Security + JWT (access token + refresh token)
- [ ] RBAC: `CUSTOMER`, `OPERATOR`, `AUDITOR`, `ADMIN`
- [ ] Lấy `ownerId` từ JWT thay vì từ request
- [ ] API key cho client machine-to-machine (lưu dạng hash)
- [ ] Rate limiting bằng Redis (Bucket4j) theo user hoặc API key

| Role | Quyền |
|---|---|
| CUSTOMER | Xem tài khoản của mình, chuyển tiền từ tài khoản của mình |
| OPERATOR | Freeze/unfreeze tài khoản, reverse giao dịch |
| AUDITOR | Chỉ đọc audit log |
| ADMIN | Toàn quyền |

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
├── services/       # Phase 3: notification, audit
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
4. **Không mất event:** tắt Kafka, thực hiện giao dịch, bật lại Kafka; event vẫn được gửi đi đầy đủ.
5. **Audit:** thử sửa một audit event; database từ chối và hash chain phát hiện được.
6. **Observability:** chạy load test bằng k6 và theo dõi latency, error rate trên Grafana.

---

## 8. Câu hỏi phỏng vấn cần chuẩn bị

- Vì sao chọn pessimistic lock thay vì optimistic lock cho transfer? Khi nào thì nên chọn ngược lại?
- Vì sao dùng `READ COMMITTED` + row lock mà không dùng `SERIALIZABLE`?
- Làm thế nào để tránh deadlock khi 2 transfer A→B và B→A chạy cùng lúc?
- Idempotency key nên được lưu bao lâu? Nếu request đầu tiên vẫn đang xử lý thì request thứ hai xử lý thế nào?
- Outbox pattern giải quyết vấn đề gì? Vì sao không dùng Kafka transaction thay thế?
- At-least-once delivery thì consumer phải làm gì?
- Vì sao reverse giao dịch mà không xóa hay sửa dữ liệu cũ?
- Làm sao chứng minh audit log không bị sửa?
