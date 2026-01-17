# Logging Best Practices

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Request ID](#request-id)
3. [Log Formats](#log-formats)
4. [Naming Convention](#naming-convention)
5. [Môi trường và CloudWatch](#môi-trường-và-cloudwatch)
6. [Console Logs vs CloudWatch](#console-logs-vs-cloudwatch)
7. [Logging Levels](#logging-levels)
8. [Disk Space và Log Rotation](#disk-space-và-log-rotation)

---

## Tổng quan

Document này tổng hợp các best practices về logging trong production environments, bao gồm Request ID, naming conventions, khi nào bật CloudWatch, cách quản lý log levels và disk space.

---

## Request ID

### Tại sao cần Request ID?

```
┌────────┐         ┌─────────┐         ┌─────────┐         ┌────────┐
│ Client │────────►│ API GW  │────────►│ Service │────────►│   DB   │
└────────┘         └─────────┘         └─────────┘         └────────┘
     │                  │                   │                   │
     └──────────────────┴───────────────────┴───────────────────┘
                    X-Request-ID: abc-123
                    
Tất cả logs đều chứa: [requestId=abc-123]
→ Dễ dàng trace toàn bộ flow của 1 request
```

| Vấn đề | Không có Request ID | Có Request ID |
|--------|---------------------|---------------|
| Debug lỗi | Tìm kim trong đống rơm | Filter 1 dòng |
| Multi-service | Không biết request nào liên quan | Trace xuyên suốt |
| Concurrent requests | Logs lẫn lộn | Phân biệt rõ ràng |

### Request ID vs Trace ID

```
┌─────────────────────────────────────────────────────────────────┐
│  User click "Submit Order"                                      │
│                                                                 │
│  Request ID: ORD-abc123      ← Business ID (do bạn tạo)        │
│                                                                 │
│  Trace ID: xyz-789           ← Technical ID (thư viện tạo)     │
│    ├── Span 1: API Gateway        (2ms)                        │
│    ├── Span 2: Order Service      (50ms)                       │
│    └── Span 3: Payment Service    (100ms)                      │
└─────────────────────────────────────────────────────────────────┘
```

| | Request ID | Trace ID |
|--|-----------|----------|
| **Là gì** | Business identifier | Technical identifier |
| **Ai tạo** | FE hoặc BE | Tracing library (Micrometer/OTel) |
| **Mục đích** | Correlation cho support/user | Performance analysis |
| **Format** | Tùy ý: `ORD-123`, UUID | Chuẩn W3C: 32 hex chars |
| **Xem ở đâu** | Logs, error response | Jaeger, Zipkin, Tempo |

**Workflow:** Request ID → tìm logs → lấy Trace ID → xem chi tiết trong Jaeger.

### Ai nên tạo Request ID?

**FE tạo + BE fallback** (recommended):

```
┌─────────────────────────────────────────────────────────────────┐
│  FE tạo (Recommended cho full tracing)                         │
├─────────────────────────────────────────────────────────────────┤
│  Browser → API GW → Service A → Service B                      │
│     │                                                           │
│     └── X-Request-ID: fe-abc-123 (tạo từ đầu)                  │
│         → Trace được cả: user click → response                 │
└─────────────────────────────────────────────────────────────────┘
```

| Tiêu chí | FE tạo | BE tạo |
|----------|--------|--------|
| **Trace scope** | End-to-end (browser → DB) | Server-side only |
| **Debug FE errors** | ✅ Có thể match với BE logs | ❌ Không |
| **Retry handling** | Cùng ID cho retries | Mỗi retry = ID mới |

### Response: Header vs Body

```
┌─────────────────────────────────────────────────────────────────┐
│  Success Response                                               │
├─────────────────────────────────────────────────────────────────┤
│  HTTP/1.1 200 OK                                                │
│  X-Request-ID: abc-123        ← Header only                    │
│                                                                 │
│  { "data": { ... } }          ← Không cần requestId            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Error Response                                                 │
├─────────────────────────────────────────────────────────────────┤
│  HTTP/1.1 400 Bad Request                                       │
│  X-Request-ID: abc-123        ← Header (cho FE đọc)            │
│                                                                 │
│  {                                                              │
│    "status": 400,                                               │
│    "message": "Invalid email",                                  │
│    "requestId": "abc-123"     ← Body (cho user thấy)           │
│  }                                                              │
└─────────────────────────────────────────────────────────────────┘
```

**Lý do có requestId trong error body:**
- User dễ thấy và copy
- Gửi cho support → debug ngay

### Implementation

#### Backend Filter (Spring Boot)

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) {
        
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }
}
```

#### Error Response DTO

```java
public record ErrorResponse(
    int status,
    String message,
    String requestId,
    Instant timestamp
) {}
```

#### Global Exception Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        String requestId = MDC.get("requestId");
        ErrorResponse error = new ErrorResponse(500, "Internal error", requestId, Instant.now());
        return ResponseEntity.status(500).body(error);
    }
}
```

#### Frontend (TypeScript)

```typescript
const requestId = crypto.randomUUID();

const response = await fetch('/api/users', {
    headers: { 'X-Request-ID': requestId }
});

if (!response.ok) {
    const error = await response.json();
    console.error(`[${error.requestId}] Error: ${error.message}`);
    // Show to user: "Lỗi. Mã hỗ trợ: abc-123"
}
```

### Các headers khác nên có

| Header | Mục đích | Ví dụ |
|--------|----------|-------|
| X-Request-ID | Trace request | `abc-123` |
| X-User-ID | Biết ai gọi (sau auth) | `user-456` |
| X-Client-Version | Debug theo version | `2.1.0` |
| X-Device-ID | Debug specific device | `ios-789` |
| X-Tenant-ID | Multi-tenant routing | `company-xyz` |

---

## Log Formats

### Các format phổ biến

| Format | Ví dụ | CloudWatch parsing |
|--------|-------|-------------------|
| **Plain text** | `2026-01-17 INFO Order created` | Chỉ có `@message`, phải dùng `parse` |
| **JSON** | `{"level":"INFO","msg":"Order created"}` | ✅ Tự động tạo fields |
| **Logfmt** | `level=INFO msg="Order created"` | Phải dùng `parse` |
| **CSV** | `2026-01-17,INFO,Order created` | Phải dùng `parse` |

### Plain text (default)

```
2026-01-17 10:25:18.294 INFO  [abc-123] [main] com.app.Service - Order created
```

**Query trong CloudWatch:**
```sql
# Phải pattern match trong @message
filter @message like /ERROR/

# Hoặc parse để extract fields
parse @message "* * *  [*] [*] * - *" as date, time, level, requestId, thread, logger, msg
| filter level = "ERROR"
```

### JSON Structured Logging (recommended)

```json
{"@timestamp":"2026-01-17T10:25:18Z","level":"INFO","requestId":"abc-123","logger":"com.app.Service","message":"Order created","userId":"123"}
```

**Query trong CloudWatch:**
```sql
# CloudWatch tự động tạo fields → query trực tiếp
filter level = "ERROR" and requestId = "abc-123"
```

### So sánh

| | Plain Text | JSON |
|---|---|---|
| **Setup** | Mặc định, không cần config | Cần config encoder |
| **Đọc log thủ công** | ✅ Dễ đọc | ❌ Khó đọc |
| **Query CloudWatch** | Phải dùng `parse` | ✅ Query trực tiếp fields |
| **Thêm context** | Khó | ✅ Dễ (thêm fields) |
| **Tương thích tools** | Chỉ text search | ✅ ELK, Datadog, Splunk |

### Recommendation

| Môi trường | Format | Lý do |
|------------|--------|-------|
| **Local/Dev** | Plain text | Dễ đọc trong console |
| **Staging/Production** | JSON | Query dễ, thêm context, tương thích monitoring tools |

### Logback Configuration

```xml
<!-- Console plain text for dev -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{HH:mm:ss} %-5level [%X{requestId:-no-request}] %logger - %msg%n</pattern>
    </encoder>
</appender>

<!-- Console JSON for production -->
<appender name="CONSOLE_JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>requestId</includeMdcKeyName>
    </encoder>
</appender>

<!-- Profile-based selection -->
<springProfile name="local,dev">
    <root level="DEBUG">
        <appender-ref ref="CONSOLE"/>
    </root>
</springProfile>

<springProfile name="prod,staging">
    <root level="INFO">
        <appender-ref ref="CONSOLE_JSON"/>
    </root>
</springProfile>
```

---

## Naming Convention

### Log Group & Log Stream - Tự do đặt tên

Log Group và Log Stream name **hoàn toàn do bạn tự đặt**, chỉ cần tuân thủ:

| | Quy tắc |
|---|---|
| **Log Group** | 1-512 ký tự, cho phép: `a-zA-Z0-9`, `_`, `-`, `/`, `.`, `#` |
| **Log Stream** | 1-512 ký tự, cho phép: `a-zA-Z0-9`, `_`, `-`, `/`, `.` |

### Log Group Naming Convention

```
/aws/lambda/function-name        ← Lambda tự tạo
/aws/ecs/cluster-name            ← ECS logs  
/app/{service}/{environment}     ← Application logs (recommended)
```

### Log Stream Naming Patterns

Mỗi instance/container cần stream riêng. Pattern phổ biến trong production:

| Môi trường | Pattern | Ví dụ |
|------------|---------|-------|
| **ECS/Fargate** | `{container-name}/{container-id}` | `app/abc123def456` |
| **Kubernetes** | `{namespace}/{pod-name}` | `prod/api-7f8d9c-xyz` |
| **EC2** | `{instance-id}` | `i-0abc123def456` |
| **General** | `{hostname}/{date}` | `api-server-1/2026-01-17` |

**Lưu ý quan trọng:**
- Tránh dùng `timestamp (milliseconds)` → mỗi restart tạo stream mới
- Dùng `date` thay vì `timestamp` → 1 stream/instance/day, tự động rotation

---

## Môi trường và CloudWatch

### Khi nào bật CloudWatch?

| Môi trường | CloudWatch | Lý do |
|------------|------------|-------|
| **Local/Dev** | ❌ Không | Console đủ, không phụ thuộc infrastructure |
| **Staging/UAT** | ✅ Có | Test tương tự production |
| **Production** | ✅ Có | Centralized logs, monitoring, alerting |

### Configuration theo môi trường

```xml
<!-- logback-spring.xml -->

<!-- Production/Staging: Console JSON + CloudWatch -->
<springProfile name="prod,staging">
    <root level="INFO">
        <appender-ref ref="CONSOLE_JSON"/>
        <appender-ref ref="CLOUDWATCH"/>
    </root>
</springProfile>

<!-- Development: Console plain text only -->
<springProfile name="dev,local">
    <root level="DEBUG">
        <appender-ref ref="CONSOLE"/>
    </root>
</springProfile>
```

---

## Console Logs vs CloudWatch

### Tại sao giữ cả hai?

```
Application
    │
    ├──► Console (stdout) ──► Container runtime capture
    │                         kubectl logs, docker logs
    │
    └──► CloudWatch ────────► Centralized, query, alert
```

**Không nên bỏ console logs** ở UAT/Production vì:

| Console logs dùng để | |
|---------------------|---|
| `kubectl logs pod-name` | Debug ngay lập tức |
| `docker logs container` | Không cần vào CloudWatch |
| Container runtime capture | Fluent Bit/Fluentd có thể forward |
| Backup khi CloudWatch lỗi | Không mất logs |

**Kết luận:** Giữ cả 2, logs đi song song. Chỉ bỏ console nếu có lý do đặc biệt (logs quá nhiều, ảnh hưởng performance).

---

## Logging Levels

### Log Level Hierarchy

```
TRACE < DEBUG < INFO < WARN < ERROR
```

Khi set level, sẽ hiện **level đó và tất cả levels cao hơn**:

| Set level | Hiện | Ẩn |
|-----------|------|-----|
| `TRACE` | TRACE, DEBUG, INFO, WARN, ERROR | (không ẩn gì) |
| `DEBUG` | DEBUG, INFO, WARN, ERROR | TRACE |
| `INFO` | INFO, WARN, ERROR | DEBUG, TRACE |
| `WARN` | WARN, ERROR | INFO, DEBUG, TRACE |
| `ERROR` | ERROR | Tất cả còn lại |

### Level theo môi trường

| Môi trường | Level | Lý do |
|------------|-------|-------|
| **Local/Dev** | `DEBUG` | Debug chi tiết, xem SQL queries, request/response |
| **UAT/Staging** | `DEBUG` hoặc `INFO` | Test tương tự prod, nhưng cần debug khi có issue |
| **Production** | `INFO` | Cân bằng giữa visibility và performance/cost |

### Khi nào dùng level nào?

| Level | Khi nào dùng | Ví dụ |
|-------|--------------|-------|
| `ERROR` | Lỗi cần xử lý ngay | Payment failed, DB connection lost |
| `WARN` | Có vấn đề nhưng app vẫn chạy | Retry thành công, deprecated API |
| `INFO` | Business events quan trọng | Order created, User login |
| `DEBUG` | Chi tiết technical | SQL queries, method params |
| `TRACE` | Rất chi tiết | Loop iterations, byte-level data |

### Dynamic Log Level trong Production

Mặc định `INFO`, nhưng có thể **thay đổi runtime** khi cần debug:

```bash
# Spring Actuator - thay đổi level không cần restart
curl -X POST http://localhost:8080/actuator/loggers/com.myapp \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
```

---

## Disk Space và Log Rotation

### Console logs có làm đầy disk?

Phụ thuộc cách deploy:

| Môi trường | Console logs | Đầy disk? |
|------------|--------------|-----------|
| **Docker/K8s** | stdout → container runtime | ❌ Có log rotation tự động |
| **ECS/Fargate** | awslogs driver forward | ❌ Không lưu local |
| **VM + `java -jar > app.log`** | File không rotation | ⚠️ **Có thể đầy** |
| **VM + systemd** | journald capture | ❌ Có size limit |

### Docker Log Rotation (mặc định)

```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
```

### Kubernetes Log Rotation

Kubelet tự động rotate logs, cấu hình trong:
- `containerLogMaxSize`: 10Mi (mặc định)
- `containerLogMaxFiles`: 5 (mặc định)

### VM/EC2 - Cần cấu hình thủ công

Nếu chạy trực tiếp trên VM, dùng `logrotate`:

```bash
# /etc/logrotate.d/myapp
/var/log/myapp/*.log {
    daily
    rotate 7
    compress
    missingok
    notifempty
}
```

**Kết luận:** Container environments tự handle. Chỉ lo nếu chạy trực tiếp trên VM mà redirect stdout vào file không có rotation.

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Logback Configuration** | https://logback.qos.ch/manual/configuration.html |
| **Logstash Encoder** | https://github.com/logfellow/logstash-logback-encoder |
| **Spring Boot Logging** | https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging |
| **Docker Logging Drivers** | https://docs.docker.com/config/containers/logging/configure/ |
| **Kubernetes Logging** | https://kubernetes.io/docs/concepts/cluster-administration/logging/ |
| **OpenTelemetry** | https://opentelemetry.io/docs/ |
| **Micrometer Tracing** | https://micrometer.io/docs/tracing |

---

*Ngày tạo: 2026-01-17*  
*Cập nhật: 2026-01-17 - Thêm Request ID section*  
*Project: realworld-exam*
