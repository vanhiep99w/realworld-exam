# Structured Logging - JSON Logs

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [So sánh Log Formats](#so-sánh-log-formats)
3. [Tại sao dùng JSON Logs](#tại-sao-dùng-json-logs)
4. [Structured Logging với CloudWatch](#structured-logging-với-cloudwatch)
5. [MDC (Mapped Diagnostic Context)](#mdc-mapped-diagnostic-context)
6. [Best Practices](#best-practices)
7. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

**Structured Logging** = ghi logs theo format có cấu trúc (thường là JSON) thay vì plain text.

```
┌─────────────────────────────────────────────────────────────────────┐
│  Plain Text (Unstructured)                                          │
│  ─────────────────────────────────────────────────────────────────  │
│  2026-01-18 10:05:32 ERROR PaymentService - Payment failed for      │
│  user 123, order 456, amount 99.99 USD                              │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              │  Khó parse, khó query
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  JSON (Structured)                                                   │
│  ─────────────────────────────────────────────────────────────────  │
│  {                                                                   │
│    "timestamp": "2026-01-18T10:05:32Z",                             │
│    "level": "ERROR",                                                 │
│    "logger": "PaymentService",                                       │
│    "message": "Payment failed",                                      │
│    "userId": "123",                                                  │
│    "orderId": "456",                                                 │
│    "amount": 99.99,                                                  │
│    "currency": "USD"                                                 │
│  }                                                                   │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              │  Dễ parse, dễ query
                              ▼
                    filter userId = "123"
                    stats avg(amount) by currency
```

---

## So sánh Log Formats

### 3 formats phổ biến

| Format | Ví dụ | Ưu điểm | Nhược điểm |
|--------|-------|---------|------------|
| **Plain Text** | `INFO Order created for user 123` | Dễ đọc | Khó parse, khó query |
| **Logfmt** | `level=INFO user=123 action=order` | Dễ đọc + parse | Ít phổ biến |
| **JSON** | `{"level":"INFO","user":"123"}` | Dễ parse, query mạnh | Khó đọc raw |

### So sánh chi tiết

```
┌────────────────────────────────────────────────────────────────────────┐
│                           Plain Text                                    │
├────────────────────────────────────────────────────────────────────────┤
│  2026-01-18 10:05:32.123 INFO  c.s.l.PaymentService - Payment          │
│  processed for user=123 order=456 amount=99.99                         │
├────────────────────────────────────────────────────────────────────────┤
│  ✅ Dễ đọc trong terminal                                              │
│  ❌ Phải dùng regex để extract fields                                  │
│  ❌ Format không nhất quán giữa các service                            │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│                             Logfmt                                      │
├────────────────────────────────────────────────────────────────────────┤
│  ts=2026-01-18T10:05:32Z level=INFO logger=PaymentService              │
│  msg="Payment processed" user=123 order=456 amount=99.99               │
├────────────────────────────────────────────────────────────────────────┤
│  ✅ Dễ đọc + dễ parse                                                  │
│  ⚠️ Ít tool hỗ trợ native                                              │
│  ⚠️ CloudWatch không auto-parse                                        │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│                              JSON                                       │
├────────────────────────────────────────────────────────────────────────┤
│  {"ts":"2026-01-18T10:05:32Z","level":"INFO","logger":"PaymentService",│
│   "msg":"Payment processed","user":"123","order":"456","amount":99.99} │
├────────────────────────────────────────────────────────────────────────┤
│  ✅ CloudWatch tự động parse fields                                    │
│  ✅ Query trực tiếp: filter user = "123"                               │
│  ✅ Chuẩn industry, mọi tool hỗ trợ                                    │
│  ❌ Khó đọc raw trong terminal                                         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Tại sao dùng JSON Logs

### 1. CloudWatch tự động parse

```
┌─────────────────────────────────────────────────────────────────────┐
│  JSON Log vào CloudWatch                                             │
│  {"level":"ERROR","userId":"123","orderId":"456","amount":99.99}    │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                ▼  CloudWatch tự động tạo fields
┌─────────────────────────────────────────────────────────────────────┐
│  Discovered Fields:                                                  │
│  ├── level     (string)                                             │
│  ├── userId    (string)                                             │
│  ├── orderId   (string)                                             │
│  └── amount    (number)                                             │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼  Query trực tiếp
┌─────────────────────────────────────────────────────────────────────┐
│  filter level = "ERROR" and userId = "123"                          │
│  stats avg(amount) by orderId                                        │
└─────────────────────────────────────────────────────────────────────┘
```

### 2. So sánh query

| Task | Plain Text | JSON |
|------|------------|------|
| Tìm errors của user 123 | `filter @message like /ERROR/ and @message like /user=123/` | `filter level = "ERROR" and userId = "123"` |
| Đếm errors theo user | Không thể (hoặc rất phức tạp) | `stats count(*) by userId` |
| Tính trung bình amount | Phải parse bằng regex | `stats avg(amount)` |
| Filter amount > 100 | Không thể | `filter amount > 100` |

### 3. Metric Filters dễ hơn

**Plain Text:**
```
# Phải dùng pattern phức tạp
filter-pattern: "[timestamp, level=ERROR, ...]"
```

**JSON:**
```
# Filter đơn giản
filter-pattern: "{ $.level = \"ERROR\" }"

# Filter phức tạp cũng dễ
filter-pattern: "{ $.level = \"ERROR\" && $.amount > 100 }"
```

---

## Structured Logging với CloudWatch

### Flow tổng quan

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────────────┐
│  Application    │     │  CloudWatch     │     │  Query/Filter           │
│                 │     │  Logs           │     │                         │
│  log.info(...)  │────►│  Auto-parse     │────►│  filter level="ERROR"   │
│  → JSON output  │     │  JSON fields    │     │  stats count(*) by user │
└─────────────────┘     └─────────────────┘     └─────────────────────────┘
```

### Logs Insights với JSON

```sql
# Với JSON logs - query trực tiếp fields
fields @timestamp, level, userId, message
| filter level = "ERROR"
| filter userId = "123"
| stats count(*) by message
| sort @timestamp desc
```

```sql
# Với Plain Text - phải parse trước
fields @timestamp, @message
| parse @message "* * * user=* order=* amount=*" as ts, level, msg, userId, orderId, amount
| filter level = "ERROR"
| filter userId = "123"
| stats count(*) by msg
```

### Metric Filters với JSON

```
┌─────────────────────────────────────────────────────────────────────┐
│  Filter Pattern cho JSON Logs                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Đếm tất cả errors:                                                  │
│  { $.level = "ERROR" }                                               │
│                                                                      │
│  Đếm payment errors > $100:                                          │
│  { $.level = "ERROR" && $.service = "payment" && $.amount > 100 }   │
│                                                                      │
│  Đếm theo HTTP status:                                               │
│  { $.statusCode >= 500 }                                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## MDC (Mapped Diagnostic Context)

### MDC là gì?

MDC là feature của **Java logging** (SLF4J/Logback), không phải của AWS. Cho phép **thêm context** vào tất cả logs trong 1 request mà không cần truyền qua parameters.

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Tech Stack                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────┐                                                │
│  │  SLF4J / Logback│  ◄── MDC thuộc về đây (Java logging)          │
│  │  (Java)         │                                                │
│  └────────┬────────┘                                                │
│           │ ghi log                                                  │
│           ▼                                                          │
│  ┌─────────────────┐                                                │
│  │  CloudWatch     │  ◄── Chỉ nhận logs, không biết MDC là gì      │
│  │  (AWS)          │                                                │
│  └─────────────────┘                                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### MDC hoạt động như thế nào?

MDC dùng **ThreadLocal** của Java - mỗi thread có "túi riêng" để chứa data:

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   Thread 1   │  │   Thread 2   │  │   Thread 3   │
│  ┌────────┐  │  │  ┌────────┐  │  │  ┌────────┐  │
│  │userId=A│  │  │  │userId=B│  │  │  │userId=C│  │
│  │reqId=01│  │  │  │reqId=02│  │  │  │reqId=03│  │
│  └────────┘  │  │  └────────┘  │  │  └────────┘  │
└──────────────┘  └──────────────┘  └──────────────┘

→ Mỗi thread gọi log.info() → lấy MDC của thread đó
→ Các request chạy song song không đụng nhau
```

### Set MDC ở đâu? (Filter/Interceptor)

**Set 1 lần trong Filter**, không cần set trong mỗi handler:

```
┌─────────────────────────────────────────────────────────────────────┐
│  Request Flow                                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Request vào                                                         │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────┐                        │
│  │  Filter / Interceptor                    │  ◄── Set MDC ở đây    │
│  │                                          │                        │
│  │  MDC.put("requestId", UUID.randomUUID()) │                        │
│  │  MDC.put("userId", getUserFromToken())   │                        │
│  └─────────────────────────────────────────┘                        │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────┐                        │
│  │  Controller                              │                        │
│  │  log.info("Order created")  ◄── tự có userId, requestId         │
│  └─────────────────────────────────────────┘                        │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────┐                        │
│  │  Service                                 │                        │
│  │  log.info("Payment processed")  ◄── tự có userId, requestId     │
│  └─────────────────────────────────────────┘                        │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────┐                        │
│  │  Filter (finally)                        │  ◄── Clear MDC        │
│  │  MDC.clear()                             │                        │
│  └─────────────────────────────────────────┘                        │
│       │                                                              │
│       ▼                                                              │
│  Response ra                                                         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Spring Boot: MDC Filter Example

```java
@Component
public class MDCFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                     HttpServletResponse response, 
                                     FilterChain chain) {
        try {
            // Set MDC 1 lần ở đầu request
            MDC.put("requestId", UUID.randomUUID().toString());
            MDC.put("clientIp", request.getRemoteAddr());
            
            // Nếu đã authenticate, lấy userId
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                MDC.put("userId", auth.getName());
            }
            
            // Tiếp tục request
            chain.doFilter(request, response);
            
        } finally {
            // QUAN TRỌNG: Clear MDC khi request kết thúc
            MDC.clear();
        }
    }
}
```

### Kết quả: Logs tự động có context

```java
// Controller - KHÔNG cần set MDC
@PostMapping("/orders")
public Order createOrder(@RequestBody OrderRequest req) {
    log.info("Creating order");  // Tự có requestId, userId
    return orderService.create(req);
}

// Service - KHÔNG cần set MDC  
public Order create(OrderRequest req) {
    log.info("Processing payment");  // Tự có requestId, userId
    return order;
}
```

**Output logs:**
```json
{"msg":"Creating order", "requestId":"abc-123", "userId":"user_456"}
{"msg":"Processing payment", "requestId":"abc-123", "userId":"user_456"}
```

### Tại sao cần MDC?

**Không có MDC:**
```
INFO  OrderService - Order created for user 123
INFO  PaymentService - Payment processed          ← User nào? Request nào?
INFO  EmailService - Email sent                   ← Không biết liên quan log nào
```

**Có MDC:**
```
INFO  OrderService - {"msg":"Order created","userId":"123","requestId":"abc"}
INFO  PaymentService - {"msg":"Payment processed","userId":"123","requestId":"abc"}
INFO  EmailService - {"msg":"Email sent","userId":"123","requestId":"abc"}
```

→ Dễ dàng trace toàn bộ flow của 1 request.

### Common MDC Fields

| Field | Mô tả | Ví dụ |
|-------|-------|-------|
| `requestId` / `traceId` | ID unique cho mỗi request | `abc-123-def-456` |
| `userId` | User đang thực hiện request | `user_123` |
| `sessionId` | Session của user | `sess_789` |
| `clientIp` | IP của client | `192.168.1.1` |
| `userAgent` | Browser/client info | `Mozilla/5.0...` |

### Các framework khác cũng có tương tự

| Framework/Language | Tên gọi |
|-------------------|---------|
| **Java (SLF4J/Logback)** | MDC (Mapped Diagnostic Context) |
| **Java (Log4j2)** | ThreadContext |
| **Python** | `logging.LoggerAdapter` hoặc `contextvars` |
| **Node.js** | `cls-hooked`, `AsyncLocalStorage` |
| **.NET** | `LogContext` (Serilog) |

### MDC + Logs Insights

```sql
# Tìm tất cả logs của 1 request
filter requestId = "abc-123-def-456"
| sort @timestamp asc

# Xem flow của user 123
filter userId = "123"
| fields @timestamp, logger, message
| sort @timestamp asc
| limit 100
```

---

## Best Practices

### 1. Chọn fields chuẩn

```json
{
  "timestamp": "2026-01-18T10:05:32.123Z",    // ISO 8601
  "level": "ERROR",                            // DEBUG, INFO, WARN, ERROR
  "logger": "com.example.PaymentService",      // Class name
  "message": "Payment failed",                 // Human-readable message
  "requestId": "abc-123",                      // Trace context
  "userId": "user_456",                        // Business context
  
  // Error details (nếu có)
  "error": {
    "type": "PaymentException",
    "message": "Card declined",
    "stackTrace": "..."
  },
  
  // Business data
  "orderId": "order_789",
  "amount": 99.99,
  "currency": "USD"
}
```

### 2. Đặt tên fields nhất quán

```
┌─────────────────────────────────────────────────────────────────────┐
│  ❌ Không nhất quán                                                  │
│  ─────────────────────────────────────────────────────────────────  │
│  Service A: {"user_id": "123", "orderId": "456"}                    │
│  Service B: {"userId": "123", "order-id": "456"}                    │
│  Service C: {"uid": "123", "oid": "456"}                            │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ✅ Nhất quán (camelCase)                                           │
│  ─────────────────────────────────────────────────────────────────  │
│  Service A: {"userId": "123", "orderId": "456"}                     │
│  Service B: {"userId": "123", "orderId": "456"}                     │
│  Service C: {"userId": "123", "orderId": "456"}                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3. Log levels đúng mục đích

| Level | Khi nào dùng | Ví dụ |
|-------|--------------|-------|
| **ERROR** | Lỗi cần xử lý ngay | Payment failed, Database connection lost |
| **WARN** | Có vấn đề nhưng không critical | Retry succeeded, Config deprecated |
| **INFO** | Business events quan trọng | Order created, User logged in |
| **DEBUG** | Chi tiết cho debugging | SQL query, HTTP request/response |

### 4. Không log sensitive data

```json
// ❌ KHÔNG BAO GIỜ log những thứ này
{
  "password": "secret123",
  "creditCard": "4111-1111-1111-1111",
  "ssn": "123-45-6789",
  "apiKey": "sk_live_xxx"
}

// ✅ Mask hoặc không log
{
  "creditCard": "****-****-****-1111",
  "hasPassword": true
}
```

### 5. Structured exceptions

```json
// ❌ Bad: Stack trace trong message
{
  "message": "Error: NullPointerException at com.example.Service.method(Service.java:42)\n  at com.example..."
}

// ✅ Good: Structured error object
{
  "message": "Payment processing failed",
  "error": {
    "type": "NullPointerException",
    "message": "userId is null",
    "stackTrace": "at com.example.Service.method(Service.java:42)...",
    "cause": "Missing required field"
  }
}
```

---

## So sánh: Khi nào dùng format nào?

| Scenario | Recommended Format |
|----------|-------------------|
| **Production + CloudWatch** | JSON |
| **Local development** | Plain text (dễ đọc) |
| **Legacy system** | Plain text + parse |
| **High-performance, low-latency** | Logfmt (nhẹ hơn JSON) |

### Hybrid approach

```
┌─────────────────────────────────────────────────────────────────────┐
│  Local Development: Plain Text                                       │
│  ─────────────────────────────────────────────────────────────────  │
│  2026-01-18 10:05:32 INFO  PaymentService - Payment processed       │
│                                                                      │
│  Dễ đọc trong terminal, IntelliJ console                            │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  Production: JSON                                                    │
│  ─────────────────────────────────────────────────────────────────  │
│  {"timestamp":"2026-01-18T10:05:32Z","level":"INFO",...}            │
│                                                                      │
│  CloudWatch tự động parse, query mạnh                                │
└─────────────────────────────────────────────────────────────────────┘
```

Cấu hình bằng Spring profiles:
- `application-local.yml` → Plain text
- `application-prod.yml` → JSON

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **CloudWatch JSON Logs** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html |
| **Logs Insights Query Syntax** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_QuerySyntax.html |
| **SLF4J MDC** | https://www.slf4j.org/manual.html#mdc |
| **Logback JSON Encoder** | https://github.com/logfellow/logstash-logback-encoder |

---

*Ngày tạo: 2026-01-18*
*Project: realworld-exam*
